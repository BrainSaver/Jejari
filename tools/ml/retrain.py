#!/usr/bin/env python3
"""③ 주간 재학습 + 자동 시험 — 사용자가 많아질수록 기준이 실제 사람에 가까워지고, 관리자 판정이 쌓일수록 새 수법을 배운다.

    python3 tools/ml/retrain.py --base data/ml/model --real build/ml/weekly/real --labels build/ml/weekly/labels.jsonl \\
        --gen build/ml/weekly/g7 build/ml/weekly/g8 build/ml/weekly/g9 --gen-test build/ml/weekly/g99 --out build/ml/models/ens-w261122

순서
  1. 관리자 판정(--labels, tools/ml/export_labels.sh)을 학습 70 · 시험 30으로 나눈다 (계정 단위, 시험 몫은 학습에 안 씀)
  2. 학습 몫 판정이 MIN_LABELS 이상이면 다시 학습: 생성 데이터 + 판정 붙은 실제 계정 (train.py, ML_ALLOW_REAL=1) + 유형 모델
     모자라면 다시 학습하지 않고 기준 모델을 그대로 쓴다 ("기준만 갱신")
  3. ① 사람 기준 갱신 (recalibrate.py): 실데이터로 백분위 기준·문턱·근거 범위
  4. 자동 시험(관문): 기준 모델과 같은 데이터로 비교. 통과해야 후보가 된다 (gate.json)
       - 시험 몫 판정 사람이 GATE_MIN명 이상: 후보 오탐 ≤ 5%, 기준보다 1%p 넘게 나빠지지 않음
       - 시험 몫 판정 봇이 GATE_MIN명 이상: 후보 탐지율이 기준 이상
       - 생성 시험 데이터: 탐지율이 기준보다 10%p 넘게 떨어지지 않음 (처음 보는 세계로 갈 때 무너지지 않게)
       - 실데이터에서 봇 쪽으로 보는 비율 ≤ 10% 또는 기준 이하 (상식선)
     판정이 모자라 위 앞의 둘을 못 재면 통과해도 "판정 부족 — 관리자 확인"을 붙인다
  5. 후보는 꺼진 채(is_active = N) 등록하고, 켜는 것은 관리자가 A-09에서 비교해 보고 한다 (deploy/model-retrain.sh)

학습할 때는 서버 CPU를 많이 쓰므로 한가한 시간(일요일 새벽 타이머)에만 돌린다.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import pickle
import random
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from features import STAGE  # noqa: E402
from recalibrate import recalibrate  # noqa: E402

MIN_LABELS = 20      # 학습 몫 판정이 이만큼은 있어야 다시 학습 (그보다 적으면 기준만 갱신)
GATE_MIN = 10        # 시험 몫에서 사람·봇이 이만큼은 있어야 그 항목을 잰다
HOLDOUT = 0.3
SEED = 7


def run(args, env_extra=None):
    env = {**os.environ, "PYTHONUTF8": "1", **(env_extra or {})}
    r = subprocess.run([sys.executable, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
    if r.returncode != 0:
        sys.exit(f"실패: {' '.join(args)}\n{r.stderr[-3000:]}")
    return r.stdout


def flags(model_dir, folder):
    """폴더의 계정마다 봇 쪽으로 봤나 (score.py를 임시 폴더에서). {member_id: bool}"""
    with tempfile.TemporaryDirectory() as tmp:
        for x in ("features.csv", "events.jsonl", "members.jsonl", "runs.jsonl"):
            if os.path.exists(os.path.join(folder, x)):
                shutil.copyfile(os.path.join(folder, x), os.path.join(tmp, x))
        run([os.path.join(HERE, "score.py"), os.path.join(model_dir, "model.pkl"), os.path.join(tmp, "features.csv")])
        with open(os.path.join(tmp, "scores.csv"), encoding="utf-8") as f:
            return {int(r["member_id"]): r["flag"] == "1" for r in csv.DictReader(f)}


def rate(fl, ids):
    ids = [i for i in ids if i in fl]
    return (sum(fl[i] for i in ids) / len(ids), len(ids)) if ids else (None, 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", required=True, help="지금 사용 중인 모델 폴더 (비교 기준, 판정이 모자라면 이걸 갱신)")
    ap.add_argument("--real", required=True, help="실데이터 폴더 (export_live.sh --recent + features.py ML_STAGE=1)")
    ap.add_argument("--labels", required=True, help="export_labels.sh 결과")
    ap.add_argument("--gen", nargs="+", required=True, help="생성 데이터 폴더들 (features.csv, ML_STAGE=1)")
    ap.add_argument("--gen-test", required=True, help="학습에 안 쓴 생성 시드 폴더 (정답 있음)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--fp", type=float, default=0.02)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    with open(os.path.join(a.base, "model.pkl"), "rb") as f:
        base_model = pickle.load(f)
    stage = any(c in STAGE for c in base_model["features"])     # 기준 모델이 단계 특징을 썼으면 같이
    with open(a.labels, encoding="utf-8") as f:
        labels = [json.loads(l) for l in f if l.strip()]
    real_ids = set()
    with open(os.path.join(a.real, "features.csv"), encoding="utf-8") as f:
        real_rows = list(csv.DictReader(f))
    real_ids = {int(r["member_id"]) for r in real_rows}
    labels = [d for d in labels if d["member_id"] in real_ids]   # 이번 기간에 행동 로그가 있는 판정 계정만
    rnd = random.Random(SEED)
    rnd.shuffle(labels)
    k = int(len(labels) * HOLDOUT)
    test_l, train_l = labels[:k], labels[k:]
    train_labels = os.path.join(a.out, "labels_train.jsonl")
    with open(train_labels, "w", encoding="utf-8") as f:
        f.writelines(json.dumps(d, ensure_ascii=False) + "\n" for d in train_l)

    # 2) 다시 학습 (판정이 충분할 때만)
    mode = "기준만 갱신"
    calib_base = a.base
    if len(train_l) >= MIN_LABELS:
        mode = "다시 학습 + 기준 갱신"
        rd = os.path.join(a.out, "_real_train")
        os.makedirs(rd, exist_ok=True)
        lab = {d["member_id"]: d["label"] for d in train_l}
        with open(os.path.join(rd, "features.csv"), "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(real_rows[0].keys()))
            w.writeheader()
            for r in real_rows:
                m = int(r["member_id"])
                if m in lab:
                    w.writerow({**r, "label": lab[m], "source": "real:weekly", "group": f"real:{m}", "family": ""})
        for x in ("events.jsonl", "members.jsonl", "runs.jsonl"):
            if os.path.exists(os.path.join(a.real, x)):
                shutil.copyfile(os.path.join(a.real, x), os.path.join(rd, x))
        calib_base = os.path.join(a.out, "_trained")
        env = {"ML_ALLOW_REAL": "1", "ML_STAGE": "1" if stage else "0"}
        print(f"다시 학습: 생성 {len(a.gen)}세트 + 판정 실제 계정 {len(train_l)}명 (단계 특징 {'씀' if stage else '안 씀'}) …", flush=True)
        run([os.path.join(HERE, "train.py"), calib_base, *[os.path.join(g, "features.csv") for g in a.gen],
             os.path.join(rd, "features.csv")], env)
        run([os.path.join(HERE, "train_type.py"), calib_base, *[os.path.join(g, "features.csv") for g in a.gen]])
    else:
        print(f"판정 {len(train_l)}명 (< {MIN_LABELS}) — 다시 학습하지 않고 기준만 갱신", flush=True)

    # 3) ① 사람 기준 갱신
    recalibrate(calib_base, a.real, a.out, train_labels, a.fp, quiet=True)
    with open(os.path.join(a.out, "recalibrate.json"), encoding="utf-8") as f:
        calib = json.load(f)

    # 4) 자동 시험 — 기준 모델과 같은 데이터로
    bot_t = [d["member_id"] for d in test_l if d["is_bot"]]
    hum_t = [d["member_id"] for d in test_l if not d["is_bot"]]
    fb, fc = flags(a.base, a.real), flags(a.out, a.real)
    gb, gc = flags(a.base, a.gen_test), flags(a.out, a.gen_test)
    with open(os.path.join(a.gen_test, "members.jsonl"), encoding="utf-8") as f:
        gm = {d["member_id"]: d["label"] != "NORMAL" for d in (json.loads(l) for l in f if l.strip())}
    g_bots = [m for m, b in gm.items() if b]
    checks = []

    def check(name, base, cand, ok, rule):
        checks.append({"name": name, "base": None if base is None else round(base, 4),
                       "cand": None if cand is None else round(cand, 4), "ok": bool(ok), "rule": rule})

    hb, nh = rate(fb, hum_t)
    hc, _ = rate(fc, hum_t)
    if nh >= GATE_MIN:
        check("판정 사람 오탐", hb, hc, hc <= 0.05 and hc <= hb + 0.01, "≤ 5%, 기준보다 1%p 넘게 나빠지지 않음")
    bb, nb = rate(fb, bot_t)
    bc, _ = rate(fc, bot_t)
    if nb >= GATE_MIN:
        check("판정 봇 탐지", bb, bc, bc >= bb, "기준 이상")
    grb, _ = rate(gb, g_bots)
    grc, _ = rate(gc, g_bots)
    check("생성 시험 봇 탐지", grb, grc, grc >= grb - 0.10, "기준보다 10%p 넘게 떨어지지 않음")
    real_flag = sum(fc.values()) / max(1, len(fc))
    base_flag = sum(fb.values()) / max(1, len(fb))
    check("실데이터 봇 쪽 비율", base_flag, real_flag, real_flag <= max(0.10, base_flag), "≤ 10% 또는 기준 이하")
    passed = all(c["ok"] for c in checks)
    short = nh < GATE_MIN or nb < GATE_MIN

    # 실데이터 시험 몫으로 낸 정밀도·재현율 (model_run 칸)
    tp = sum(fc.get(m, False) for m in bot_t)
    fpos = sum(fc.get(m, False) for m in hum_t)
    prec = tp / (tp + fpos) if tp + fpos else None
    rec = tp / len([m for m in bot_t if m in fc]) if nb else None
    f1 = 2 * prec * rec / (prec + rec) if prec and rec else None
    gate = {
        "at": datetime.now().isoformat(timespec="seconds"), "mode": mode, "passed": passed, "short_of_labels": short,
        "labels": {"train": len(train_l), "test_humans": nh, "test_bots": nb}, "checks": checks,
        "calibration": {k2: calib[k2] for k2 in ("human_reference", "threshold", "flagged_real")},
        "metrics": {"precision": prec, "recall": rec, "f1": f1}, "stage": stage,
        "summary": (("통과" if passed else "탈락") + f" · {mode} · 판정 학습 {len(train_l)} / 시험 사람 {nh}·봇 {nb}"
                    + (" · 판정 부족 — 관리자 확인" if short and passed else "")),
    }
    with open(os.path.join(a.out, "gate.json"), "w", encoding="utf-8") as f:
        json.dump(gate, f, ensure_ascii=False, indent=1)
    for d in ("_real_train", "_trained"):
        shutil.rmtree(os.path.join(a.out, d), ignore_errors=True)

    print(gate["summary"])
    for c in checks:
        fmt = lambda v: "—" if v is None else f"{v:.1%}"
        print(f"  {'✓' if c['ok'] else '✗'} {c['name']}: 기준 {fmt(c['base'])} → 후보 {fmt(c['cand'])}  ({c['rule']})")
    print(f"→ {a.out}/gate.json")


if __name__ == "__main__":
    main()
