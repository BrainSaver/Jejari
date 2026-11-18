#!/usr/bin/env python3
"""처음 보는 봇 유형에도 통하는지 확인한다 (leave-one-family-out).

봇 유형 하나를 학습에서 빼고 학습한 뒤, 그 유형과 새 사람 데이터로 시험한다.
대회 봇은 우리가 모르는 수법일 수 있으므로 "본 적 있는 수법의 탐지율"보다 이 값이 중요하다.

    python evaluate.py --loo
    python evaluate.py --model out/model.pt      # 저장된 모델을 새 가상 데이터로 시험
"""
import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from jejari_dl import metrics, sim, training  # noqa: E402


def loo(a):
    cfg = training.TrainConfig(epochs=a.epochs, seed=a.seed)
    rows = []
    for held in sim.BOT_FAMILIES:
        seen = [f for f in sim.BOT_FAMILIES if f != held]
        train = sim.make_dataset(a.n_human, a.n_bot, seed=101, families=seen)
        val = sim.make_dataset(a.n_human // 4, a.n_bot // 4, seed=102, families=seen)
        model = training.fit(train, val, cfg, log=None)
        thr = training.pick_threshold(training.predict(model, val)[[s.label == 0 for s in val]], a.max_fpr)
        test = sim.make_dataset(a.n_human // 4, a.n_bot // 4, seed=103, families=[held])
        rep = metrics.report(test, training.predict(model, test), thr)
        rows.append((held, rep))
        print(f"  [{held}] 빼고 학습 → 처음 본 {held} 탐지율 {rep['bot_detect_dl']:.1%} "
              f"(규칙 {rep['bot_detect_rules']:.1%}), 사람 오탐 {rep['human_fpr_dl']:.1%}", flush=True)
    print("\n== leave-one-family-out 요약 ==")
    print(f"{'처음 본 유형':<18}{'딥러닝':>9}{'규칙 기준선':>12}{'사람 오탐(DL)':>14}")
    for held, r in rows:
        print(f"{held:<18}{r['bot_detect_dl']:>9.1%}{r['bot_detect_rules']:>12.1%}{r['human_fpr_dl']:>14.1%}")
    print(f"{'평균':<18}{np.mean([r['bot_detect_dl'] for _, r in rows]):>9.1%}"
          f"{np.mean([r['bot_detect_rules'] for _, r in rows]):>12.1%}"
          f"{np.mean([r['human_fpr_dl'] for _, r in rows]):>14.1%}")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--loo", action="store_true")
    ap.add_argument("--model", default=None)
    ap.add_argument("--n-human", type=int, default=3000)
    ap.add_argument("--n-bot", type=int, default=2400)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--max-fpr", type=float, default=0.02)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args(argv)
    if a.loo:
        return loo(a)
    if not a.model:
        ap.error("--loo 또는 --model 이 필요합니다")
    model, thr = training.load(Path(a.model))
    test = sim.make_dataset(1000, 800, seed=999)
    print(metrics.format_report(metrics.report(test, training.predict(model, test), thr), "새 가상 데이터"))


if __name__ == "__main__":
    main()
