#!/usr/bin/env python3
"""모델 여러 개를 OR로 묶고 단계별 문턱을 보정한다 (어느 하나라도 문턱 이상이면 해당 단계).

단계: 2 = 강한 불이익 후보(hard, 사람 오탐 --target-fpr), 1 = 약한 불이익 후보(soft, 사람 오탐 --soft-target-fpr).
이 도구는 점수를 매기고 단계를 나눌 뿐이다. 순번을 얼마나 미룰지는 서버(risk 패키지)가 정한다.
보정 전용 사람(학습·시험에 안 쓴 시드)에서:
  1) 전용 모델 문턱을 사람 --spec-fpr(soft는 --soft-spec-fpr)가 넘는 값으로 정하고
  2) 합쳐서 사람 --target-fpr(soft는 --soft-target-fpr)가 되도록 주 모델 문턱을 정한다.

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
    ap.add_argument("--target-fpr", type=float, default=0.015, help="강한 단계(2) 합친 허용 오탐률 (보정 사람 기준)")
    ap.add_argument("--spec-fpr", type=float, default=0.005, help="강한 단계에서 전용 모델 하나가 쓸 오탐률")
    ap.add_argument("--soft-target-fpr", type=float, default=0.05, help="약한 단계(1) 합친 허용 오탐률. 0이면 단계를 나누지 않음")
    ap.add_argument("--soft-spec-fpr", type=float, default=0.04, help="약한 단계에서 전용 모델 하나가 쓸 오탐률")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    if 0 < a.soft_target_fpr < a.target_fpr:
        sys.exit("--soft-target-fpr 는 --target-fpr 이상이어야 합니다 (약한 단계가 강한 단계를 포함)")
    T.PER_FLAG, T.DEVICE_CTX, T.DUP_CTX = a.gen_per_flag, a.device_context, a.dup_context

    cal = [s for sd in a.calib_seeds.split(",")
           for s in T.fresh_attack(a.gen, int(sd), per_type=a.gen_per, humans=a.gen_humans) if s.label == 0]
    paths = [a.main] + list(a.spec)
    ps = [training.predict(training.load(Path(m))[0], cal) for m in paths]
    hard = training.fit_union_thresholds(ps, a.target_fpr, a.spec_fpr)
    soft = (training.fit_union_thresholds(ps, a.soft_target_fpr, a.soft_spec_fpr) if a.soft_target_fpr > 0 else list(hard))
    soft = [min(s, h) for s, h in zip(soft, hard)]                       # 약한 단계 ⊇ 강한 단계
    tier = training.assign_tier(ps, hard, soft)
    names = ["main"] + [f"spec{i}" for i in range(1, len(paths))]
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps({
        "models": [{"name": n, "path": m, "thr": h, "soft_thr": s} for n, m, h, s in zip(names, paths, hard, soft)],
        "calib_humans": len(cal), "calib_hard_fpr": float((tier == 2).mean()), "calib_soft_fpr": float((tier >= 1).mean())},
        ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"보정 사람 {len(cal)}명 · 강한 단계(2) 오탐 {(tier == 2).mean():.2%} · 약한 단계(1 이상) 오탐 {(tier >= 1).mean():.2%}")
    for n, m, h, s in zip(names, paths, hard, soft):
        print(f"  {n} {m}: 강한 문턱 {h:.3f} · 약한 문턱 {s:.3f}")


if __name__ == "__main__":
    main()
