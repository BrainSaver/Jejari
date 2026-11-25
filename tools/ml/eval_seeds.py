#!/usr/bin/env python3
"""여러 시험 시드로 모델들을 같은 데이터에서 다시 잰다. 학습하지 않고 이미 학습한 모델로 점수만 매긴다.

    python3 tools/ml/eval_seeds.py --models data/ml/model-real-r8 data/ml/model-real-r12 \\
        --seeds 101 102 103 104 105 --data data/ml/eval-seeds --out docs/.../결과.md

왜: 시험 데이터 한 세트(시드 99)에서 사람 종류 하나는 30~110명뿐이라 1명 = 1~3%p다. 6차에서 같은 r8이
시험 데이터만 바뀌어도 가족 기기 공유 33.0% ↔ 28.4%, 오픈 직후 5.1% ↔ 7.0%로 움직였다. 그래서 모델끼리
비교는 같은 여러 시드에서 평균·범위로 한다.

시험 데이터는 train_real.py의 시험 시드와 같은 방식(사람 고르기 하한, 실제 프로필 둘레, 부스트 없음)에
6차 적대 봇(ML_SHIFT_BOT=1, 몰려 사는 rejoin 변형 포함)을 넣어 만든다. b_shift_device는
선점이 2개 이상 운행에 있으면 rejoin, 아니면 staggered로 나눠 따로 센다.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import statistics
import subprocess
import sys
from collections import defaultdict

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PY = sys.executable
FEAT_ENV = {"ML_STAGE": "1", "ML_RUNS": "1", "ML_HOLD": "1", "ML_LINK": "1"}
SHOW_HUMAN = ["h_family_device", "h_family_bulk", "h_pc_bang", "h_organizer", "h_open_rusher", "h_quick_regret",
              "h_quick", "h_slow", "h_casual"]
SHOW_BOT = ["b_shift_device", "b_shift_device:staggered", "b_shift_device:rejoin", "b_multi_device",
            "b_human_mimic", "b_distributed", "b_slow_drip", "b_cancel_rebuy", "b_multi_run_hoarder"]


def run(args, env_extra=None):
    env = {**os.environ, "PYTHONUTF8": "1", **(env_extra or {})}
    r = subprocess.run([PY, *args], cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    if r.returncode != 0:
        sys.exit(f"실패: python {' '.join(args)}\n{(r.stderr or '')[-3000:]}")
    return r.stdout


def make_test(folder, seed, members, calib):
    if not os.path.exists(os.path.join(folder, "events.jsonl")):
        run(["-I", "tools/ml/generate.py", "--members", str(members), "--seed", str(seed), "--out", folder],
            {"ML_PICK_FLOOR": "1", "ML_REAL_CALIB": calib, "ML_SHIFT_BOT": "1"})
    feats = os.path.join(folder, "features.csv")
    if not os.path.exists(feats) or "min_dev_hold_gap_s_log" not in open(feats, encoding="utf-8").readline():
        run(["-I", "tools/ml/features.py", folder], FEAT_ENV)


def kinds(folder):
    """member_id → 종류 (b_shift_device는 rejoin/staggered를 따로 하나 더)"""
    with open(os.path.join(folder, "members.jsonl"), encoding="utf-8") as f:
        mem = {m["member_id"]: m for m in (json.loads(l) for l in f if l.strip())}
    runs = defaultdict(set)
    with open(os.path.join(folder, "events.jsonl"), encoding="utf-8") as f:
        for l in f:
            if '"SEAT_HOLD"' in l:
                e = json.loads(l)
                runs[e["member_id"]].add(e.get("run_id"))
    out = {}
    for mid, m in mem.items():
        ks = [m["strategy"], "사람 전체" if m["label"] == "NORMAL" else "봇 전체"]
        if m["strategy"] == "b_shift_device":
            ks.append("b_shift_device:" + ("rejoin" if len(runs[mid]) >= 2 else "staggered"))
        out[mid] = ks
    return out


def score(model_dir, folder, work):
    os.makedirs(work, exist_ok=True)
    for x in ("features.csv", "events.jsonl", "members.jsonl", "runs.jsonl"):
        if os.path.exists(os.path.join(folder, x)):
            shutil.copyfile(os.path.join(folder, x), os.path.join(work, x))
    out = run(["tools/ml/score.py", os.path.join(model_dir, "model.pkl"), os.path.join(work, "features.csv")])
    gru = "GRU 빠짐" not in out
    with open(os.path.join(work, "scores.csv"), encoding="utf-8") as f:
        return {int(r["member_id"]): r["flag"] == "1" for r in csv.DictReader(f)}, gru


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--models", nargs="+", required=True)
    ap.add_argument("--seeds", nargs="+", type=int, default=[101, 102, 103, 104, 105])
    ap.add_argument("--members", type=int, default=3000)
    ap.add_argument("--calib", default="data/ml/model-real-r12/real_calibration.json")
    ap.add_argument("--data", default="data/ml/eval-seeds")
    ap.add_argument("--out", default=None, help="결과 markdown (없으면 화면만)")
    a = ap.parse_args()

    # rate[model][kind] = [seed별 비율], cnt[model][kind] = [(표시, 전체)]
    rate = defaultdict(lambda: defaultdict(list))
    tot = defaultdict(lambda: defaultdict(lambda: [0, 0]))
    nogru = set()
    for seed in a.seeds:
        folder = os.path.join(ROOT, a.data, f"g{seed}")
        make_test(folder, seed, a.members, os.path.join(ROOT, a.calib))
        kk = kinds(folder)
        print(f"시드 {seed}: {len(kk)}계정", flush=True)
        for md in a.models:
            name = os.path.basename(os.path.normpath(md))
            flag, gru = score(os.path.join(ROOT, md), folder, os.path.join(folder, ".eval", name))
            if not gru:
                nogru.add(name)
            per = defaultdict(lambda: [0, 0])
            for mid, ks in kk.items():
                for k in ks:
                    per[k][0] += flag.get(mid, False)
                    per[k][1] += 1
            for k, (f, n) in per.items():
                rate[name][k].append(100 * f / n)
                tot[name][k][0] += f
                tot[name][k][1] += n
            print(f"  {name}: 사람 오탐 {100 * per['사람 전체'][0] / per['사람 전체'][1]:.1f}% · "
                  f"봇 탐지 {100 * per['봇 전체'][0] / per['봇 전체'][1]:.1f}%", flush=True)

    names = [os.path.basename(os.path.normpath(m)) for m in a.models]

    def cell(name, k):
        xs = rate[name].get(k)
        if not xs:
            return "-"
        f, n = tot[name][k]
        return f"{100 * f / n:.1f}% ({min(xs):.0f}~{max(xs):.0f})"

    lines = [f"시드 {', '.join(map(str, a.seeds))} × {a.members}명 · 칸 = 전체 합산 비율 (시드별 최소~최대)",
             *( [f"GRU 없이 매김(문턱 threshold_base): {', '.join(sorted(nogru))}"] if nogru else []), "",
             "| 항목 | 계정(합) | " + " | ".join(names) + " |", "|---|---|" + "---|" * len(names)]
    for title, ks in (("사람 — 오탐 (낮을수록 좋음)", ["사람 전체", *SHOW_HUMAN]),
                      ("봇 — 탐지 (높을수록 좋음)", ["봇 전체", *SHOW_BOT])):
        lines.append(f"| **{title}** | | " + " | ".join("" for _ in names) + " |")
        for k in ks:
            n = tot[names[0]][k][1] if names else 0
            lines.append(f"| {k} | {n} | " + " | ".join(cell(nm, k) for nm in names) + " |")
    # 나머지 전략 전부 (부록)
    rest = sorted({k for nm in names for k in rate[nm]} - set(SHOW_HUMAN) - set(SHOW_BOT) - {"사람 전체", "봇 전체"})
    lines += ["", "### 나머지 전략", "", "| 전략 | 계정(합) | " + " | ".join(names) + " |", "|---|---|" + "---|" * len(names)]
    for k in rest:
        lines.append(f"| {k} | {tot[names[0]][k][1]} | " + " | ".join(cell(nm, k) for nm in names) + " |")
    report = "\n".join(lines)
    print("\n" + report)
    if a.out:
        os.makedirs(os.path.dirname(os.path.join(ROOT, a.out)) or ".", exist_ok=True)
        with open(os.path.join(ROOT, a.out), "w", encoding="utf-8", newline="\n") as f:
            f.write(report + "\n")


if __name__ == "__main__":
    main()
