#!/usr/bin/env python3
"""실제 데이터 둘레로 대량 생성해 학습한 모델(real) — 실제 사람 오탐을 줄이고 실제 봇은 그대로 잡는지 본다.
    python3 tools/ml/train_real.py                      # → data/ml/model-real-local/ (GRU 포함 30분~1시간)
    python3 tools/ml/train_real.py --quick              # GRU 없이 몇 분 (흐름 확인용)
    python3 tools/ml/train_real.py --calib-from data/ml/contest-c1124d --holdout data/ml/contest-c1124c data/ml/contest-c1119 data/ml/contest-c1124w

하는 일
  1. calibrate_real.py: --calib-from 폴더(기본 c1124d)의 실제 계정에서 행동 프로필만 뽑는다
     (기기·카드·연결 계정·판매 오픈 시각은 대회에서만 생긴 모양이라 뺌) → <data>/real_calibration.json
  2. 생성: 시드 7·8·9 (각 --members명) + 시험 시드 99. 원래 수법은 그대로 두고, 실제 프로필 둘레의
     사람(h_focused)·봇(b_real_mimic·b_real_macro)을 더한다 (ML_REAL_CALIB). 사람 고르기 하한·단계 특징은 next와 같이 켬
  3. 학습: train.py (부스팅 + IF + GRU)
  4. 시험: 생성 시드 99 · 실제 대회 데이터 — 지금 모델·next·새 모델을 같은 데이터로
     --holdout 폴더는 1의 프로필에 안 쓴 실제 데이터(정직한 시험). --calib-from 폴더도 같이 보여 주되 "학습 둘레에 씀"으로 표시
     실제 사람 오탐은 계정이 적어(수십) 한두 명에 크게 흔들린다. 그대로 바꾸지 말고 실제 데이터를 더 쌓은 뒤 다시 본다
학습 데이터는 저장소에 올리지 않는다 (data/ml/ 아래, .gitignore). 모델 폴더는 기본으로 올리지 않는다(-local).
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
import time
from collections import defaultdict

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PY = sys.executable
TRAIN_SEEDS, TEST_SEED = (7, 8, 9), 99
SHOW = ["h_focused", "h_quick", "h_open_rusher", "b_real_mimic", "b_real_macro", "b_human_mimic", "b_fast"]


def step(msg):
    print(f"\n[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def run(args, env_extra=None, quiet=True):
    env = {**os.environ, "PYTHONUTF8": "1", **(env_extra or {})}
    r = subprocess.run([PY, *args], cwd=ROOT, env=env, capture_output=quiet, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        sys.exit(f"실패: python {' '.join(args)}\n{(r.stderr or '')[-3000:]}")
    return r.stdout if quiet else ""


# 특징 계산에 늘 켜 두는 것. 운행별 속도(ML_RUNS)는 열을 끝에 더할 뿐이고 모델은 쓰는 열만 이름으로 꺼내므로
# 옛 모델(지금 모델·next) 점수에는 영향이 없다
FEAT_ENV = {"ML_STAGE": "1", "ML_RUNS": "1", "ML_HOLD": "1"}


def make_data(folder, seed, members, calib, boost=None, strat_boost=None, shift_bot=False):
    if not os.path.exists(os.path.join(folder, "events.jsonl")):
        run(["-I", "tools/ml/generate.py", "--members", str(members), "--seed", str(seed), "--out", folder],
            {"ML_PICK_FLOOR": "1", "ML_REAL_CALIB": calib, **({"ML_TRAP_BOOST": str(boost)} if boost else {}),
             **({"ML_STRAT_BOOST": strat_boost} if strat_boost else {}),
             **({"ML_SHIFT_BOT": "1"} if shift_bot else {})})
    feats = os.path.join(folder, "features.csv")
    if not os.path.exists(feats) or "seats_per_resv" not in open(feats, encoding="utf-8").readline():
        run(["-I", "tools/ml/features.py", folder], FEAT_ENV)
    with open(feats, encoding="utf-8") as f:
        print(f"  {sum(1 for _ in f) - 1}계정 · {os.path.relpath(folder, ROOT)}", flush=True)


def copy_real(src, dst):
    """실제 대회 폴더 → 작업 폴더 (단계 특징까지 다시 계산)"""
    os.makedirs(dst, exist_ok=True)
    for x in ("events.jsonl", "members.jsonl", "runs.jsonl"):
        if os.path.exists(os.path.join(src, x)):
            shutil.copyfile(os.path.join(src, x), os.path.join(dst, x))
    run(["-I", "tools/ml/features.py", dst], FEAT_ENV)


# 대회에서만 생긴 모양 (사람 5명이 계정 27개를 같은 기기로, 흔한 시험 카드 공유, 몇 시간 전에 연 운행 재사용).
# 실제 서비스의 사람에게는 없으므로, 사람 계정에서만 이 칸을 "계정 하나·기기 하나·카드 하나"의 보통값으로 바꿔 한 번 더 본다
ARTIFACT_FIXED = {"accounts_on_device": "1", "accounts_on_card": "1", "distinct_devices": "1",
                  "handoff_in": "0", "handoff_out": "0", "device_missing": "0"}
ARTIFACT_MEDIAN = ["min_sync_gap_s_log", "first_hold_after_open_log", "open_to_queue_log"]


def neutralize(src, dst, ref_features):
    """사람 계정의 대회 흔적만 지운 복사본. 중앙값 칸은 생성 시험 데이터의 사람 중앙값"""
    with open(ref_features, encoding="utf-8") as f:
        ref = [r for r in csv.DictReader(f) if r["label"] == "NORMAL"]
    vals = dict(ARTIFACT_FIXED)
    for k in ARTIFACT_MEDIAN:
        xs = sorted(float(r[k]) for r in ref if r.get(k) not in (None, "", "nan"))
        if xs:
            vals[k] = f"{xs[len(xs) // 2]:.4f}"
    os.makedirs(dst, exist_ok=True)
    for x in ("events.jsonl", "members.jsonl", "runs.jsonl"):
        if os.path.exists(os.path.join(src, x)):
            shutil.copyfile(os.path.join(src, x), os.path.join(dst, x))
    with open(os.path.join(src, "features.csv"), encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        if r["label"] == "NORMAL":
            r.update({k: v for k, v in vals.items() if k in r})
    with open(os.path.join(dst, "features.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


def evaluate(model_pkl, folder, work):
    os.makedirs(work, exist_ok=True)
    for x in ("features.csv", "events.jsonl", "members.jsonl", "runs.jsonl"):
        src, dst = os.path.join(folder, x), os.path.join(work, x)
        if os.path.exists(src):
            shutil.copyfile(src, dst)
    run(["tools/ml/score.py", model_pkl, os.path.join(work, "features.csv")])
    with open(os.path.join(work, "scores.csv"), encoding="utf-8") as f:
        flag = {int(r["member_id"]): r["flag"] == "1" for r in csv.DictReader(f)}
    with open(os.path.join(folder, "members.jsonl"), encoding="utf-8") as f:
        mem = [json.loads(l) for l in f if l.strip()]
    bots = [flag.get(m["member_id"], False) for m in mem if m["label"] != "NORMAL"]
    humans = [flag.get(m["member_id"], False) for m in mem if m["label"] == "NORMAL"]
    by = defaultdict(list)
    for m in mem:
        by[m["strategy"]].append(flag.get(m["member_id"], False))
    pc = lambda xs: f"{100 * sum(xs) / len(xs):.1f}%" if xs else "-"
    kinds = [k for k in SHOW if by.get(k)] or sorted(k for k in by if k.startswith("bot"))
    extra = " · ".join(f"{k} {pc(by[k])}" for k in kinds)
    return (f"봇 탐지 {sum(bots)}/{len(bots)} ({pc(bots)}) · 사람 오탐 {sum(humans)}/{len(humans)} ({pc(humans)})"
            + (f"\n      {extra}" if extra else ""))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default="data/ml/model-real-local")
    ap.add_argument("--data", default="data/ml/real")
    ap.add_argument("--members", type=int, default=6000)
    ap.add_argument("--quick", action="store_true", help="GRU 없이 (흐름 확인용, 비교에는 쓰지 말 것)")
    ap.add_argument("--runs", action="store_true",
                    help="운행마다 따로 본 속도 특징(open_to_hold_med_log·fast_open_run_ratio·browse_per_run)을 학습에 넣는다")
    ap.add_argument("--if-no-volume", action="store_true",
                    help="Isolation Forest에서 양 특징(운행 수·결제 수 등)을 뺀다 (단체 총무 오탐)")
    ap.add_argument("--if-ratio", action="store_true",
                    help="Isolation Forest에서 양 특징 대신 운행 하나당 양(결제 수/운행 수 등)을 넣는다 (4차)")
    ap.add_argument("--exon", action="store_true",
                    help="해명 모델(5차): 약한 행동 그룹(취소함·한 운행 대량)마다 사람 vs 봇 모델을 두고 사람이면 풀어 줌")
    ap.add_argument("--exon-leak", type=float, default=None, help="해명 모델이 잘못 풀어 줄 그룹 봇 비율 (기본 0.02)")
    ap.add_argument("--exon-shared", action="store_true",
                    help="6차: 해명 그룹 shared_device (기기 공유·카드 단독·30분 넘게 떨어짐, 기기 공유 강한 증거만 예외)")
    ap.add_argument("--shift-bot", action="store_true",
                    help="6차: 생성에 b_shift_device 봇 추가 (학습·시험 모두. 옛 --data 폴더와 섞지 말 것)")
    ap.add_argument("--hold", action="store_true", help="6차: 한 번에 잡은 좌석 특징(ML_HOLD) 사용")
    ap.add_argument("--also-eval", nargs="*", default=[],
                    help="같은 시험 데이터로 함께 매길 모델 폴더 (예: data/ml/model-real-r8)")
    ap.add_argument("--strat-boost", default=None,
                    help='전략마다 따로 배수, 예: "h_open_rusher=6,h_quick_regret=5" (학습 시드만, --trap-boost 뒤에 곱함)')
    ap.add_argument("--trap-boost", type=float, default=None,
                    help="오탐 함정 사람(모임 주선자·오픈 직후·바로 후회·가족 몫·빠른 사람)을 학습 데이터에서 이 배수만큼 (시험 시드는 그대로)")
    ap.add_argument("--calib-from", nargs="+", default=["data/ml/contest-c1124d"])
    ap.add_argument("--holdout", nargs="+", default=["data/ml/contest-c1124c", "data/ml/contest-c1119", "data/ml/contest-c1124w"])
    a = ap.parse_args()
    out, data = os.path.join(ROOT, a.out), os.path.join(ROOT, a.data)
    if os.path.abspath(out) in {os.path.join(ROOT, "data", "ml", "model"), os.path.join(ROOT, "data", "ml", "model-next")}:
        sys.exit("지금 모델·next는 덮어쓰지 않습니다. --out을 다른 폴더로 주세요")
    os.makedirs(data, exist_ok=True)
    t0 = time.time()

    step(f"1. 실제 프로필 ({', '.join(os.path.basename(p) for p in a.calib_from)})")
    calib = os.path.join(data, "real_calibration.json")
    print(run(["-I", "tools/ml/calibrate_real.py", *a.calib_from, "--out", calib]).strip())

    step(f"2. 생성 (시드 {', '.join(map(str, TRAIN_SEEDS))} × {a.members} + 시험 {TEST_SEED})")
    for s in TRAIN_SEEDS:
        make_data(os.path.join(data, f"g{s}"), s, a.members, calib, a.trap_boost, a.strat_boost, a.shift_bot)
    make_data(os.path.join(data, f"g{TEST_SEED}"), TEST_SEED, a.members // 2, calib, shift_bot=a.shift_bot)

    step("3. 학습" + (" (GRU 없이)" if a.quick else " (GRU 포함 — 한동안 멈춘 것처럼 보여도 정상)"))
    os.makedirs(out, exist_ok=True)
    env = {"ML_STAGE": "1", **({"ML_RUNS": "1"} if a.runs else {}), **({"ML_IF_NO_VOLUME": "1"} if a.if_no_volume else {}),
           **({"ML_IF_VOLUME": "ratio"} if a.if_ratio else {}),
           **({"ML_EXON": "1"} if a.exon else {}),
           **({"ML_EXON_SHARED": "1"} if a.exon_shared else {}),
           **({"ML_HOLD": "1"} if a.hold else {}),
           **({"ML_EXON_LEAK": str(a.exon_leak)} if a.exon_leak is not None else {}),
           **({"ML_GRU": "0"} if a.quick else {})}
    run(["-u", "tools/ml/train.py", out, *[os.path.join(data, f"g{s}", "features.csv") for s in TRAIN_SEEDS]], env, quiet=False)
    shutil.copyfile(calib, os.path.join(out, "real_calibration.json"))

    step("4. 시험")
    models = [("지금 모델", os.path.join(ROOT, "data", "ml", "model", "model.pkl")),
              ("next     ", os.path.join(ROOT, "data", "ml", "model-next", "model.pkl")),
              *((f"{os.path.basename(os.path.normpath(x))}", os.path.join(ROOT, x, "model.pkl")) for x in a.also_eval),
              ("새 모델  ", os.path.join(out, "model.pkl"))]
    tests = [(f"생성 시드 {TEST_SEED} (실제 둘레 포함)", os.path.join(data, f"g{TEST_SEED}"))]
    ref = os.path.join(data, f"g{TEST_SEED}", "features.csv")
    for src, tag in [*((x, "프로필에 안 씀") for x in a.holdout), *((x, "프로필에 씀 — 참고") for x in a.calib_from)]:
        name = os.path.basename(os.path.normpath(src))
        dst = os.path.join(data, "real-" + name)
        copy_real(os.path.join(ROOT, src), dst)
        tests.append((f"실제 {name} ({tag})", dst))
        with open(os.path.join(dst, "members.jsonl"), encoding="utf-8") as f:
            has_human = any(json.loads(l)["label"] == "NORMAL" for l in f if l.strip())
        if has_human:
            neutral = os.path.join(data, "neutral-" + name)
            neutralize(dst, neutral, ref)
            tests.append((f"실제 {name} ({tag}) · 사람의 대회 흔적 지움", neutral))
    lines = [f"학습 {time.strftime('%Y-%m-%d %H:%M')} · {'GRU 없이' if a.quick else 'GRU 포함'} · {round((time.time() - t0) / 60)}분",
             f"모델: {a.out}/model.pkl · 프로필: {', '.join(os.path.basename(p) for p in a.calib_from)}", ""]
    work = os.path.join(data, ".eval", os.path.basename(os.path.normpath(out)))   # 같은 --data로 두 모델을 돌려도 안 덮어쓰게
    for name, folder in tests:
        lines.append(f"[{name}]")
        for label, pkl in models:
            if os.path.exists(pkl):
                lines.append(f"  {label} " + evaluate(pkl, folder, os.path.join(work, os.path.basename(folder), label.strip())))
        lines.append("")
    report = "\n".join(lines)
    with open(os.path.join(out, "compare.txt"), "w", encoding="utf-8") as f:
        f.write(report)
    print("\n" + report)


if __name__ == "__main__":
    main()
