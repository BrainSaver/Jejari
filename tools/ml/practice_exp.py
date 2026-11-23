#!/usr/bin/env python3
"""연습 봇 데이터를 학습에 더하면 나아지는가 — 실험 (배포용 모델을 만들지 않음).

    python3 -I tools/ml/practice_exp.py \
        --gen data/ml/g7 data/ml/g8 data/ml/g9 \
        --train data/ml/practice-pc1123 data/ml/practice-pd1123 data/ml/practice-pe1123 \
        --test  data/ml/practice-pf1123 data/ml/practice-ph1123

  A = 생성 데이터만으로 학습 (지금 방식)
  B = 생성 데이터 + 연습 데이터(--train)로 학습
  둘 다 --test에만 적용한다. 시험 쪽 봇(3단계 녹화 꾸미기)과 사람 변형(human…)은 학습에 한 번도 안 나온 종류다.
  기본 사람(human)은 같은 스크립트가 학습에도 있어서 따로 표시한다 (같은 스크립트라 잘 맞는 게 당연함).

모델은 train.py와 같은 짝(그래디언트 부스팅 + Isolation Forest, GRU는 뺌 — 비교만 하려고). 점수 = 검증 사람 대비 백분위의 큰 값,
문턱 = 검증 사람의 95백분위 (오탐 5% 목표). 연습 데이터의 사람은 내가 만든 스크립트라 **이 결과로 모델을 바꾸지 않는다**.
"""
from __future__ import annotations

import argparse
import collections
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
from sklearn.ensemble import HistGradientBoostingClassifier, IsolationForest  # noqa: E402
from sklearn.impute import SimpleImputer  # noqa: E402
from sklearn.metrics import roc_auc_score  # noqa: E402
from sklearn.pipeline import make_pipeline  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

from compare import load  # noqa: E402
from train import COLS, SEED  # noqa: E402


def read(folders):
    rows, X = [], []
    for f in folders:
        r, x = load(os.path.join(f, "features.csv"))
        for row in r:
            row["group"] = f"{f}:{row['group']}"
            row["folder"] = os.path.basename(f)
        rows += r
        X.append(x)
    return rows, np.vstack(X)[:, COLS]


def fit(rows, X):
    y = np.array([r["label"] != "NORMAL" for r in rows])
    rng = random.Random(SEED)
    groups = sorted({r["group"] for r, b in zip(rows, y) if not b})
    rng.shuffle(groups)
    val_g = set(groups[: max(1, int(0.2 * len(groups)))])
    val = np.array([(not b) and r["group"] in val_g for r, b in zip(rows, y)])
    tr = ~val
    gbm = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(X[tr], y[tr].astype(int))
    iso = make_pipeline(SimpleImputer(strategy="median"), StandardScaler(),
                        IsolationForest(n_estimators=300, random_state=SEED)).fit(X[tr & ~y])
    vidx = np.flatnonzero(val)
    np.random.default_rng(SEED).shuffle(vidx)
    ref, cut = vidx[: len(vidx) // 2], vidx[len(vidx) // 2:]
    raw = lambda Z: (gbm.predict_proba(Z)[:, 1], -iso.score_samples(Z))  # noqa: E731
    rg, ri = raw(X[ref])

    def score(Z):
        g, i = raw(Z)
        pg = np.searchsorted(np.sort(rg), g, side="right") / len(rg)
        pi = np.searchsorted(np.sort(ri), i, side="right") / len(ri)
        return 100 * np.maximum(pg, pi), g
    cut_s, _ = score(X[cut])
    return score, float(np.quantile(cut_s, 0.95)), int(val.sum())


def report(name, score, thr, rows, X):
    s, g = score(X)
    y = np.array([r["label"] != "NORMAL" for r in rows])
    out = [f"### {name} (문턱 {thr:.1f}점)"]
    try:
        out.append(f"- AUC 점수 {roc_auc_score(y, s):.3f} · 부스팅 확률만 {roc_auc_score(y, g):.3f}")
    except ValueError:
        pass
    per = collections.defaultdict(lambda: [0, 0])
    for r, sc in zip(rows, s):
        per[r["strategy"]][0] += 1
        per[r["strategy"]][1] += sc >= thr
    bots = [k for k in per if not k.startswith("human")]
    hum = [k for k in per if k.startswith("human")]
    bt = sum(per[k][0] for k in bots)
    bd = sum(per[k][1] for k in bots)
    hv = [k for k in hum if k != "human"]
    out.append(f"- 봇 탐지 {bd}/{bt} ({100 * bd / max(1, bt):.0f}%) · 기본 사람(학습에 같은 스크립트) 오탐 {per['human'][1]}/{per['human'][0]}"
               f" · **사람 변형(처음 봄) 오탐 {sum(per[k][1] for k in hv)}/{sum(per[k][0] for k in hv)}**")
    out.append("- 종류별 (탐지 / 계정): " + " · ".join(f"{k} {per[k][1]}/{per[k][0]}" for k in sorted(per)))
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gen", nargs="+", required=True)
    ap.add_argument("--train", nargs="+", required=True)
    ap.add_argument("--test", nargs="+", required=True)
    a = ap.parse_args()
    grows, gX = read(a.gen)
    prows, pX = read(a.train)
    trows, tX = read(a.test)
    print(f"생성 {len(grows)} · 연습 학습 {len(prows)} · 시험 {len(trows)}\n")
    sa, ta, va = fit(grows, gX)
    print(report(f"A 생성 데이터만 (검증 사람 {va})", sa, ta, trows, tX) + "\n")
    sb, tb, vb = fit(grows + prows, np.vstack([gX, pX]))
    print(report(f"B 생성 + 연습 (검증 사람 {vb})", sb, tb, trows, tX))


if __name__ == "__main__":
    main()
