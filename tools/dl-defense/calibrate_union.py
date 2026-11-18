#!/usr/bin/env python3
"""모델 여러 개를 OR로 묶고 문턱을 보정한다 (어느 하나라도 문턱 이상이면 봇).

전용 모델(예: 속도 전용)은 전체 오탐 목표 중 일부만 쓰게 한다. 보정 전용 사람(학습·시험에 안 쓴 시드)에서
  1) 전용 모델 문턱을 사람 --spec-fpr 가 넘는 값으로 정하고
  2) 합쳐서 사람 --target-fpr 가 되도록 주 모델 문턱을 정한다.

    python calibrate_union.py --main out/a.pt --spec out/s.pt --gen gen_bot_data_v3.py --gen-per-flag per-variant \\
        --gen-per 30 --gen-humans 400 --device-context --dup-context --calib-seeds 401,...,408 --out out/union.json
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import train_csv as T  # noqa: E402
from jejari_dl import training  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--main", required=True)
    ap.add_argument("--spec", required=True, nargs="+", help="전용 모델들")
    ap.add_argument("--gen", required=True)
    ap.add_argument("--gen-per-flag", default="per-type")
    ap.add_argument("--gen-per", type=int, default=40)
    ap.add_argument("--gen-humans", type=int, default=400)
    ap.add_argument("--device-context", action="store_true")
    ap.add_argument("--dup-context", action="store_true")
    ap.add_argument("--calib-seeds", required=True)
    ap.add_argument("--target-fpr", type=float, default=0.019, help="합친 허용 오탐률 (보정 사람 기준)")
    ap.add_argument("--spec-fpr", type=float, default=0.005, help="전용 모델 하나가 쓸 오탐률")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    T.PER_FLAG, T.DEVICE_CTX, T.DUP_CTX = a.gen_per_flag, a.device_context, a.dup_context

    cal = [s for sd in a.calib_seeds.split(",")
           for s in T.fresh_attack(a.gen, int(sd), per_type=a.gen_per, humans=a.gen_humans) if s.label == 0]
    paths = [a.main] + list(a.spec)
    ps = [training.predict(training.load(Path(m))[0], cal) for m in paths]
    thrs = [None] + [float(np.quantile(p, 1 - a.spec_fpr, method="higher")) for p in ps[1:]]
    spec_hit = np.zeros(len(cal), dtype=bool)
    for p, t in zip(ps[1:], thrs[1:]):
        spec_hit |= p >= t
    # 주 모델 문턱: 합친 오탐이 target 이하가 되는 가장 낮은 값
    cand = np.sort(np.unique(ps[0]))
    t_main = float(cand[-1]) + 1e-6
    for t in cand:
        if ((ps[0] >= t) | spec_hit).mean() <= a.target_fpr:
            t_main = float(t)
            break
    thrs[0] = t_main
    union = ((ps[0] >= t_main) | spec_hit).mean()
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps({"models": [{"path": m, "thr": t} for m, t in zip(paths, thrs)],
                                       "calib_humans": len(cal), "calib_union_fpr": float(union)},
                                      ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"보정 사람 {len(cal)}명 · 합친 오탐 {union:.2%}")
    for m, t, p in zip(paths, thrs, ps):
        print(f"  {m}: 문턱 {t:.3f} (이 모델만의 보정 오탐 {(p >= t).mean():.2%})")


if __name__ == "__main__":
    main()
