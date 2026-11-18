#!/usr/bin/env python3
"""모델 비교 — 생성 데이터로 학습하고 "처음 보는 수법"에 얼마나 버티는지 잰다.

    pip install scikit-learn
    python3 -I tools/ml/compare.py data/ml/gen-s7/features.csv [--live data/ml/contest-<태그>/features.csv]

모델 세 가지
  - Isolation Forest   라벨 없이 "평소와 다른 계정"을 찾는다 (사람만으로 학습)
  - 그래디언트 부스팅  라벨로 학습하는 표 데이터 모델 (결정 나무 묶음)
  - 신경망 (MLP)       작은 딥러닝. 은닉층 2개

평가 두 가지
  1. 섞어서 나누기      같은 수법이 학습·시험 양쪽에 있다 → 점수가 좋게 나온다 (낙관적인 값)
  2. 계열 하나 빼기     봇 계열 하나를 학습에서 통째로 빼고 그 계열로만 시험 → "처음 보는 수법" (발표에 쓸 쪽)

문턱: 사람 검증 세트의 오탐률이 5%가 되는 점수 (기능설계서 목표 "오탐률 5% 이하")
사람은 묶음(group) 단위로 학습 60 · 검증 20 · 시험 20으로 나눈다 (같은 가족·사무실이 양쪽에 들어가지 않게)

--live: 대회(실제 서버) 데이터에 학습한 모델을 그대로 적용해 본다. 시험용이라 여기서 학습하지 않는다.
"""
from __future__ import annotations

import argparse
import csv
import math
import random
import sys

try:
    import numpy as np
    from sklearn.ensemble import HistGradientBoostingClassifier, IsolationForest
    from sklearn.impute import SimpleImputer
    from sklearn.neural_network import MLPClassifier
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
except ImportError:
    sys.exit("scikit-learn이 필요합니다: pip install scikit-learn")

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from features import FEATURES  # noqa: E402

FP_TARGET = 0.05
SEED = 7


def load(path):
    with open(path) as f:
        rows = list(csv.DictReader(f))
    X = np.array([[float(r[c]) if r[c] not in ("", "nan") else np.nan for c in FEATURES] for r in rows])
    return rows, X


def split_humans(rows, rng):
    groups = sorted({(r["strategy"], r["group"]) for r in rows if r["label"] == "NORMAL"})
    rng.shuffle(groups)
    n = len(groups)
    part = {g: ("train" if i < 0.6 * n else "val" if i < 0.8 * n else "test") for i, g in enumerate(groups)}
    return [part.get((r["strategy"], r["group"])) if r["label"] == "NORMAL" else None for r in rows]


def models():
    return {
        "Isolation Forest": ("unsup", lambda: make_pipeline(
            SimpleImputer(strategy="median"), StandardScaler(),
            IsolationForest(n_estimators=300, contamination="auto", random_state=SEED))),
        "그래디언트 부스팅": ("sup", lambda: HistGradientBoostingClassifier(max_iter=300, random_state=SEED)),
        "신경망 (MLP)": ("sup", lambda: make_pipeline(
            SimpleImputer(strategy="median"), StandardScaler(),
            MLPClassifier(hidden_layer_sizes=(64, 32), max_iter=600, early_stopping=True, random_state=SEED))),
    }


def score(kind, model, X):
    if kind == "unsup":
        return -model.score_samples(X)          # 클수록 이상
    return model.predict_proba(X)[:, 1]          # 봇일 확률


def fit(kind, make, X, y):
    m = make()
    if kind == "unsup":
        m.fit(X[y == 0])                         # 사람만으로 학습
    else:
        m.fit(X, y)
    return m


def threshold(scores_val_human):
    return float(np.quantile(scores_val_human, 1 - FP_TARGET))


def pct(a, b):
    return f"{100 * a / b:5.1f}%" if b else "   - "


def run(rows, X, live=None):
    rng = random.Random(SEED)
    hsplit = split_humans(rows, rng)
    is_bot = np.array([r["label"] != "NORMAL" for r in rows])
    fam = np.array([r.get("family") or "" for r in rows])
    hs = np.array([s or "" for s in hsplit])
    families = sorted({f for f, b in zip(fam, is_bot) if b})

    # 봇도 묶음 단위로 학습 70 · 시험 30 (평가 1용)
    bgroups = sorted({(r["strategy"], r["group"]) for r, b in zip(rows, is_bot) if b})
    rng.shuffle(bgroups)
    btest = set(bgroups[: int(0.3 * len(bgroups))])
    bsplit = np.array([b and (r["strategy"], r["group"]) in btest for r, b in zip(rows, is_bot)])

    print(f"계정 {len(rows)} (사람 {int((~is_bot).sum())} · 봇 {int(is_bot.sum())}) · 특징 {X.shape[1]}개 · 문턱 = 사람 오탐 {FP_TARGET:.0%}\n")

    results = {}
    for name, (kind, make) in models().items():
        # ---- 평가 1: 섞어서 나누기
        tr = (hs == "train") | (is_bot & ~bsplit)
        m = fit(kind, make, X[tr], is_bot[tr].astype(int))
        thr = threshold(score(kind, m, X[hs == "val"]))
        s = score(kind, m, X)
        te_h = hs == "test"
        te_b = is_bot & bsplit
        mixed = ((s[te_b] >= thr).mean(), (s[te_h] >= thr).mean())

        # ---- 평가 2: 계열 하나 빼기
        per = {}
        for f in families:
            tr = (hs == "train") | (is_bot & (fam != f))
            m2 = fit(kind, make, X[tr], is_bot[tr].astype(int))
            thr2 = threshold(score(kind, m2, X[hs == "val"]))
            s2 = score(kind, m2, X)
            hit = int((s2[is_bot & (fam == f)] >= thr2).sum())
            per[f] = (hit, int((is_bot & (fam == f)).sum()), float((s2[te_h] >= thr2).mean()))

        # ---- 실제 데이터: 생성 데이터 전부로 학습한 모델을 그대로 적용
        live_res = None
        if live is not None:
            lrows, LX = live
            tr = (hs != "") | is_bot
            m3 = fit(kind, make, X[tr & (hs != "test")], is_bot[tr & (hs != "test")].astype(int))
            thr3 = threshold(score(kind, m3, X[hs == "val"]))
            ls = score(kind, m3, LX)
            live_res = [(r["strategy"], r["label"], bool(v >= thr3)) for r, v in zip(lrows, ls)]
        results[name] = (mixed, per, live_res)

    # ---- 출력
    print("1) 섞어서 나누기 — 같은 수법이 학습에도 있음 (낙관적)")
    print(f"   {'모델':<16} {'봇 탐지율':>9} {'사람 오탐률':>10}")
    for name, (mixed, _, _) in results.items():
        print(f"   {name:<16} {100 * mixed[0]:8.1f}% {100 * mixed[1]:9.1f}%")

    print("\n2) 계열 하나 빼기 — 학습 때 못 본 계열을 잡은 비율 (처음 보는 수법)")
    head = "   " + f"{'계열':<14}" + "".join(f"{n:>18}" for n in results)
    print(head)
    for f in families:
        cells = []
        for name in results:
            hit, n, _ = results[name][1][f]
            cells.append(f"{hit:>4}/{n:<4} {pct(hit, n)}")
        print("   " + f"{f:<14}" + "".join(f"{c:>18}" for c in cells))
    avg = []
    for name in results:
        per = results[name][1]
        rec = sum(h for h, _, _ in per.values()) / max(1, sum(n for _, n, _ in per.values()))
        fp = sum(fp for _, _, fp in per.values()) / max(1, len(per))
        avg.append(f"{100 * rec:6.1f}% / {100 * fp:4.1f}%")
    print("   " + f"{'전체(탐지/오탐)':<14}" + "".join(f"{c:>18}" for c in avg))

    if live is not None:
        print("\n3) 실제 서버 데이터에 적용 (학습에 쓰지 않음)")
        lrows = live[0]
        kinds = sorted({(r["strategy"], r["label"]) for r in lrows})
        print("   " + f"{'종류':<10}{'라벨':<10}" + "".join(f"{n:>18}" for n in results))
        for k, lab in kinds:
            cells = []
            for name in results:
                lr = [x for x in results[name][2] if x[0] == k]
                hit = sum(1 for x in lr if x[2])
                cells.append(f"{hit}/{len(lr)} 탐지")
            print("   " + f"{k:<10}{lab:<10}" + "".join(f"{c:>18}" for c in cells))


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("features", help="생성 데이터 features.csv (학습)")
    p.add_argument("--live", help="실제 서버 데이터 features.csv (적용만)")
    a = p.parse_args()
    rows, X = load(a.features)
    live = load(a.live) if a.live else None
    run(rows, X, live)


if __name__ == "__main__":
    main()
