#!/usr/bin/env python3
"""저장된 모델(들)을 CSV 하나로 시험한다. 문턱은 인자로 준다 (보정 전용 사람으로 미리 고른 값).

    python eval_csv.py --model out/a.pt out/b.pt --thr 0.612 --csv test_attack.csv --device-context --dup-context
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from jejari_dl import botcsv, metrics, training  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", nargs="+", help="여러 개면 점수를 평균낸다 (앙상블)")
    ap.add_argument("--union", default=None, help="calibrate_union.py 가 만든 JSON. 어느 한 모델이라도 문턱 이상이면 봇")
    ap.add_argument("--thr", type=float, default=None, help="없으면 모델에 저장된 문턱의 평균")
    ap.add_argument("--csv", required=True)
    ap.add_argument("--device-context", action="store_true")
    ap.add_argument("--dup-context", action="store_true")
    a = ap.parse_args(argv)
    subs = botcsv.read_csv(Path(a.csv), a.device_context, a.dup_context)
    tier = None
    if a.union:
        spec = json.loads(Path(a.union).read_text(encoding="utf-8"))["models"]
        ps = [training.predict(training.load(Path(m["path"]))[0], subs) for m in spec]
        hard = [m["thr"] for m in spec]
        soft = [m.get("soft_thr", m["thr"]) for m in spec]
        tier = training.assign_tier(ps, hard, soft)
        p = tier.astype(float)                                       # 단계(0·1·2). AUC는 단계 순위로 계산
        a.thr = 2.0
    else:
        loaded = [training.load(Path(m)) for m in a.model]
        if a.thr is None:
            a.thr = float(np.mean([t for _, t in loaded]))
        p = np.mean([training.predict(m, subs) for m, _ in loaded], axis=0)
    y = np.array([s.label for s in subs])
    fam = np.array([s.family for s in subs])
    flag = p >= a.thr
    if tier is not None:
        soft_flag = tier >= 1
        print(f"단계형 · 세션 {len(subs)} (사람 {int((y == 0).sum())} · 봇 {int((y == 1).sum())})")
        print(f"  사람: 강한 단계(2) {flag[y == 0].mean():.2%} · 약한 단계 이상(1+) {soft_flag[y == 0].mean():.2%}")
        for f in sorted(set(fam[y == 1])):
            m = fam == f
            print(f"  {f:<20} 강한(2) {flag[m].mean():>6.1%} · 약한 이상(1+) {soft_flag[m].mean():>6.1%}   (n={int(m.sum())})")
        print(f"  봇 전체: 강한(2) {flag[y == 1].mean():.1%} · 약한 이상(1+) {soft_flag[y == 1].mean():.1%}")
        return
    print(f"문턱 {a.thr:.3f} · 세션 {len(subs)} (사람 {int((y == 0).sum())} · 봇 {int((y == 1).sum())}) · AUC {metrics.auc(p, y):.3f}")
    print(f"  사람 오탐률 {flag[y == 0].mean():.2%} ({int(flag[y == 0].sum())}/{int((y == 0).sum())})")
    for f in sorted(set(fam[y == 1])):
        m = fam == f
        print(f"  {f:<20} 탐지율 {flag[m].mean():>6.1%} ({int(flag[m].sum())}/{int(m.sum())})   점수 중앙값 {np.median(p[m]):.2f}")
    print(f"  봇 전체 탐지율 {flag[y == 1].mean():.1%}")


if __name__ == "__main__":
    main()
