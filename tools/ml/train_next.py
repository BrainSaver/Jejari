#!/usr/bin/env python3
"""새 위험 점수 모델(next)을 처음부터 학습한다 — 어느 PC에서나 같은 명령 하나로.

    pip install torch scikit-learn numpy          # 처음 한 번 (Windows·Mac은 이대로 CPU판)
    python tools/ml/train_next.py                  # → data/ml/model-next-local/ (30분~1시간, CPU 4코어 기준)
    python tools/ml/train_next.py --out data/ml/model-next-dg --quick   # GRU 없이 1분 (흐름 확인용)

하는 일 (docs/강동균/모델-비교-김성원-20261119.md "단계별 속도 실험")
  1. 생성 데이터 시드 7·8·9 (각 6,000명) — 사람 고르기 하한을 켠 생성기 (ML_PICK_FLOOR=1)
  2. 특징 — 단계별 속도 특징 포함 (ML_STAGE=1)
  3. 학습 — 부스팅 + Isolation Forest + GRU 5개 (--quick이면 GRU 없이)
  4. 시험 — 학습에 안 쓴 시드 99, 있으면 리허설 로그(data/ml/contest-r1118·r1118b)에 지금 모델(data/ml/model)과 같이 점수를 매겨
     <out>/compare.txt에 적는다

같은 시드라 생성 데이터·특징·부스팅은 어느 PC에서나 같다. GRU는 PC(CPU·torch 버전)에 따라 조금 달라질 수 있다.
그래서 두 PC에서 학습한 모델을 compare_dl.py에 같이 넣어 보면 "학습할 때마다 얼마나 흔들리나"도 볼 수 있다.
학습 데이터는 저장소에 올리지 않는다 (data/ml/ 아래, .gitignore). 모델 폴더(model-next*)만 올린다.
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

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PY = sys.executable
TRAIN_SEEDS, TEST_SEED = (7, 8, 9), 99
REHEARSAL = ("contest-r1118", "contest-r1118b")
SHOW = ["h_open_rusher", "h_quick", "h_organizer", "b_distributed", "b_human_mimic", "b_fast", "b_sniper"]


def step(msg):
    print(f"\n[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def run(args, env_extra=None, quiet=True):
    env = {**os.environ, **(env_extra or {})}
    r = subprocess.run([PY, *args], cwd=ROOT, env=env, capture_output=quiet, text=True)
    if r.returncode != 0:
        sys.exit(f"실패: python {' '.join(args)}\n{(r.stderr or '')[-3000:]}")
    return r.stdout if quiet else ""


def make_data(folder, seed, members):
    """생성기(하한 켬) → 특징(단계 포함). 이미 있으면 그대로 쓴다"""
    if not os.path.exists(os.path.join(folder, "events.jsonl")):
        run(["-I", "tools/ml/generate.py", "--members", str(members), "--seed", str(seed), "--out", folder],
            {"ML_PICK_FLOOR": "1"})
    feats = os.path.join(folder, "features.csv")
    if not os.path.exists(feats) or "view_to_hold_min_log" not in open(feats, encoding="utf-8").readline():
        run(["-I", "tools/ml/features.py", folder], {"ML_STAGE": "1"})
    with open(feats, encoding="utf-8") as f:
        n = sum(1 for _ in f) - 1
    print(f"  {n}계정 · {os.path.relpath(folder, ROOT)}", flush=True)


def evaluate(model_pkl, folder, work):
    """폴더의 정답(members.jsonl)으로 봇 탐지 / 사람 오탐과 주요 수법을 센다"""
    os.makedirs(work, exist_ok=True)
    for x in ("features.csv", "events.jsonl", "members.jsonl", "runs.jsonl"):
        src, dst = os.path.join(folder, x), os.path.join(work, x)
        if os.path.exists(src) and not os.path.exists(dst):
            shutil.copyfile(src, dst)      # 심볼릭 링크 대신 복사 (Windows는 링크에 관리자 권한이 필요)
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
    extra = " · ".join(f"{k} {pc(by[k])}" for k in SHOW if by.get(k))
    return f"봇 탐지 {sum(bots)}/{len(bots)} ({pc(bots)}) · 사람 오탐 {sum(humans)}/{len(humans)} ({pc(humans)})" + (f"\n      {extra}" if extra else "")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default="data/ml/model-next-local", help="모델을 둘 폴더 (기본 data/ml/model-next-local)")
    ap.add_argument("--data", default="data/ml/next", help="생성 데이터를 둘 폴더 (저장소에 안 올라감)")
    ap.add_argument("--members", type=int, default=6000)
    ap.add_argument("--quick", action="store_true", help="GRU 없이 (1분). 흐름 확인용, 비교에는 쓰지 말 것")
    a = ap.parse_args()
    out = os.path.join(ROOT, a.out)
    data = os.path.join(ROOT, a.data)
    if os.path.abspath(out) == os.path.join(ROOT, "data", "ml", "model"):
        sys.exit("지금 모델(data/ml/model)은 덮어쓰지 않습니다. --out을 다른 폴더로 주세요")

    try:
        import numpy, sklearn  # noqa: F401
    except ImportError:
        sys.exit("pip install torch scikit-learn numpy 를 먼저 하세요")
    if not a.quick:
        try:
            import torch  # noqa: F401
            print(f"torch {torch.__version__} · CPU {os.cpu_count()}개", flush=True)
        except ImportError:
            sys.exit("GRU에 torch가 필요합니다 (pip install torch). GRU 없이 해 보려면 --quick")

    t0 = time.time()
    step(f"1. 학습 데이터 (시드 {', '.join(map(str, TRAIN_SEEDS))}, 사람 고르기 하한 켬)")
    for s in TRAIN_SEEDS:
        make_data(os.path.join(data, f"g{s}"), s, a.members)
    step(f"   시험 데이터 (시드 {TEST_SEED}, 학습에 안 씀)")
    make_data(os.path.join(data, f"g{TEST_SEED}"), TEST_SEED, a.members // 2)

    step("2. 학습" + (" (GRU 없이)" if a.quick else " (GRU 5개 포함, 30분~1시간 — 화면이 한동안 멈춘 것처럼 보여도 정상)"))
    env = {"ML_STAGE": "1", **({"ML_GRU": "0"} if a.quick else {})}
    os.makedirs(out, exist_ok=True)
    run(["-u", "tools/ml/train.py", out, *[os.path.join(data, f"g{s}", "features.csv") for s in TRAIN_SEEDS]],
        env, quiet=False)

    step("3. 시험 — 지금 모델(data/ml/model)과 비교")
    current = os.path.join(ROOT, "data", "ml", "model", "model.pkl")
    new = os.path.join(out, "model.pkl")
    tests = [(f"생성 데이터 시드 {TEST_SEED} (하한 켠 생성기)", os.path.join(data, f"g{TEST_SEED}"))]
    for d in REHEARSAL:
        src = os.path.join(ROOT, "data", "ml", d)
        if os.path.exists(os.path.join(src, "events.jsonl")):
            dst = os.path.join(data, d)
            os.makedirs(dst, exist_ok=True)
            for x in ("events.jsonl", "members.jsonl", "runs.jsonl"):
                if os.path.exists(os.path.join(src, x)):
                    shutil.copyfile(os.path.join(src, x), os.path.join(dst, x))
            run(["-I", "tools/ml/features.py", dst], {"ML_STAGE": "1"})
            tests.append((f"리허설 {d}", dst))
    lines = [f"학습 {time.strftime('%Y-%m-%d %H:%M')} · {'GRU 없이' if a.quick else 'GRU 포함'} · {round((time.time() - t0) / 60)}분",
             f"모델: {a.out}/model.pkl", ""]
    work = os.path.join(data, ".eval")
    for name, folder in tests:
        lines.append(f"[{name}]")
        if os.path.exists(current):
            lines.append("  지금 모델 " + evaluate(current, folder, os.path.join(work, os.path.basename(folder), "current")))
        lines.append("  새 모델   " + evaluate(new, folder, os.path.join(work, os.path.basename(folder), "new")))
        lines.append("")
    if not any(n.startswith("리허설") for n, _ in tests):
        lines.append("(리허설 로그가 이 PC에 없어 생성 데이터로만 시험했습니다)")
    report = "\n".join(lines) + "\n"
    with open(os.path.join(out, "compare.txt"), "w", encoding="utf-8") as f:
        f.write(report)
    print("\n" + report)
    print(f"→ {a.out}/ 에 model.pkl · gru.pt · report.txt · compare.txt")
    print(f"  올리기: git add {a.out} && git commit -m \"chore(ml): {os.path.basename(a.out)} 학습\"")
    print(f"  대회 날 비교: python tools/ml/compare_dl.py data/ml/contest-<태그> --next {a.out}/model.pkl")


if __name__ == "__main__":
    main()
