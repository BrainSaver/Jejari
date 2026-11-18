#!/usr/bin/env python3
"""저장된 모델을 새 시드로 다시 만든 공격 + 사람으로 시험해, 사람 오탐률별 봇 탐지율 곡선을 그린다.

    python curve.py --model out/model.pt --gen gen_bot_data_v3.py --gen-per-flag per-variant --gen-per 30 \\
        --gen-humans 400 --device-context --seeds 31,32,33,34,35,36,37,38,39,40

오탐률마다 문턱을 시험 사람의 분위수로 다시 잡는다. 곡선 비교용이라 배포 문턱을 고르는 데 쓰면 안 된다
(문턱은 train_csv.py가 보정 전용 사람으로 따로 고른다).
"""
import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import train_csv as T  # noqa: E402
from jejari_dl import training  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, nargs="+")
    ap.add_argument("--gen", required=True)
    ap.add_argument("--gen-per-flag", default="per-type")
    ap.add_argument("--gen-per", type=int, default=40)
    ap.add_argument("--gen-humans", type=int, default=400)
    ap.add_argument("--device-context", action="store_true")
    ap.add_argument("--dup-context", action="store_true")
    ap.add_argument("--seeds", default="31,32,33,34,35,36,37,38,39,40")
    ap.add_argument("--fprs", default="0.01,0.02,0.03,0.05,0.10")
    a = ap.parse_args(argv)
    T.PER_FLAG, T.DEVICE_CTX, T.DUP_CTX = a.gen_per_flag, a.device_context, a.dup_context

    data = [T.fresh_attack(a.gen, int(s), per_type=a.gen_per, humans=a.gen_humans) for s in a.seeds.split(",")]
    allsub = [s for d in data for s in d]
    y = np.array([s.label for s in allsub])
    fam = np.array([s.family for s in allsub])
    fprs = [float(x) for x in a.fprs.split(",")]
    for path in a.model:
        model, thr = training.load(Path(path))
        p = training.predict(model, allsub)
        h = p[y == 0]
        print(f"== {path} · 사람 {len(h)}명 · 봇 {int((y == 1).sum())}세션 · 저장된 문턱 {thr:.3f} → "
              f"오탐 {(h >= thr).mean():.2%} / 탐지 {(p[y == 1] >= thr).mean():.1%}")
        for f in fprs:
            t = np.quantile(h, 1 - f, method="higher")
            ev = {v: (p[fam == v] >= t).mean() for v in sorted(set(fam)) if v.startswith("evasive")}
            if abs(f - 0.02) < 1e-9:
                low = {v: (p[fam == v] >= t).mean() for v in sorted(set(fam)) if v.startswith(("multi", "retry", "cancel", "hoard", "speed"))}
                low = {v: r for v, r in low.items() if r < 0.995}
                if low:
                    print("      오탐 2%에서 100% 미만인 변형: " + " · ".join(f"{v} {r:.0%}" for v, r in low.items()))
            print(f"  오탐 {f:>4.0%} → 탐지율 {(p[y == 1] >= t).mean():.1%}   "
                  + " · ".join(f"{v.split('/')[1]} {r:.0%}" for v, r in ev.items()))


if __name__ == "__main__":
    main()
