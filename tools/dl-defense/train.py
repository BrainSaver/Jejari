#!/usr/bin/env python3
"""가상 데이터로 봇 탐지 모델을 학습하고 out/model.pt 에 저장한다.

    python train.py                       # 기본 (CPU로 1~2분)
    python train.py --epochs 40 --max-fpr 0.01
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from jejari_dl import metrics, sim, training  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="out/model.pt")
    ap.add_argument("--n-human", type=int, default=4000)
    ap.add_argument("--n-bot", type=int, default=3000)
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--max-fpr", type=float, default=0.02, help="검증용 사람 기준 허용 오탐률 (문턱을 이걸로 고름)")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args(argv)

    cfg = training.TrainConfig(epochs=a.epochs, seed=a.seed)
    train = sim.make_dataset(a.n_human, a.n_bot, seed=a.seed * 10 + 1)
    val = sim.make_dataset(a.n_human // 4, a.n_bot // 4, seed=a.seed * 10 + 2)
    test = sim.make_dataset(a.n_human // 4, a.n_bot // 4, seed=a.seed * 10 + 3)
    print(f"학습 {len(train)} · 검증 {len(val)} · 시험 {len(test)} (모두 가상 데이터)")

    model = training.fit(train, val, cfg)
    pv = training.predict(model, val)
    thr = training.pick_threshold(pv[[s.label == 0 for s in val]], a.max_fpr)
    training.save(model, Path(a.out), thr, cfg, {"data": "synthetic", "max_fpr": a.max_fpr})

    rep = metrics.report(test, training.predict(model, test), thr)
    print(metrics.format_report(rep, "시험 데이터 (학습에 안 쓴 가상 데이터)"))
    print(f"저장: {a.out}")
    return rep


if __name__ == "__main__":
    main()
