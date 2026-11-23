#!/usr/bin/env python3
"""녹화 모델 — 화면 녹화의 "손이 움직인 모양"만 보는 새 모델 (지금 모델 옆의 보조 의견 하나 더).

    # 시험만 (학습 데이터와 시험 데이터를 나눠서)
    python3 -I tools/ml/train_replay.py --train data/ml/<폴더>… --test data/ml/<폴더>…
    # 모델 파일까지 저장
    python3 -I tools/ml/train_replay.py --train … --test … --out data/ml/model-replay-local

입력: 폴더마다 replay_features.csv (export_live.sh → export_replay.sh → replay_features.py).
마우스 이동이 없는 계정(녹화 없음·휴대폰·키보드·클릭만)은 학습·시험에서 뺀다 — 그쪽은 규칙이 맡고, 이 모델은 "마우스가 움직였는데
사람다운가"만 본다. 그런 계정에는 점수를 내지 않는다(의견 없음).

모델은 train.py와 같은 짝: 그래디언트 부스팅(본 적 있는 봇과 닮았나) + Isolation Forest(사람과 얼마나 다른가).
점수 = 검증 사람 대비 백분위의 큰 값(0~100), 문턱 = 검증 사람 95백분위(오탐 5% 목표). 사람은 묶음(group) 단위로 학습 80 · 검증 20.

**사람 데이터가 진짜여야 의미가 있다.** 2026-11-23 지금은 사람이 모두 내 스크립트(합성)라 흐름 확인용이다.
2차 대회(11-26) 사람 참가자의 녹화가 생기면 --train에 그 절반, --test에 나머지 절반을 넣는다.
pickle 파일은 코드를 실행할 수 있으니 직접 만든 replay.pkl만 연다.
"""
from __future__ import annotations

import argparse
import collections
import csv
import os
import pickle
import random
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
from sklearn.ensemble import HistGradientBoostingClassifier, IsolationForest  # noqa: E402
from sklearn.impute import SimpleImputer  # noqa: E402
from sklearn.inspection import permutation_importance  # noqa: E402
from sklearn.metrics import roc_auc_score  # noqa: E402
from sklearn.pipeline import make_pipeline  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

from replay_features import FEATURES  # noqa: E402

SEED = 7


def read(folders):
    rows, X, skipped = [], [], 0
    for f in folders:
        with open(os.path.join(f, "replay_features.csv"), encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                if r["label"] == "UNKNOWN":
                    continue
                # 마우스 이동이 하나도 없는 계정(녹화 없음·휴대폰·키보드·클릭만·순간이동만)은 이 모델이 의견을 내지 않는다.
                # 궤적이 없으면 모양 특징이 모두 비어 "비어 있음 = 봇"을 배워 휴대폰·키보드 사람을 잡았다 (2026-11-23).
                # 그쪽은 규칙 몫: HEADLESS_HOLD(녹화 없음) · 클릭 없는 선점 · 궤적 없는 마우스 클릭 · 순간이동
                if not r["straight_med"]:
                    skipped += 1
                    continue
                r["group"] = f"{f}:{r['strategy']}:{r['group']}"
                rows.append(r)
                X.append([float(r[c]) if r[c] not in ("", "nan") else np.nan for c in FEATURES])
    return rows, np.array(X), skipped


class ReplayModel:
    def __init__(self, rows, X):
        y = np.array([r["label"] != "NORMAL" for r in rows])
        groups = sorted({r["group"] for r, b in zip(rows, y) if not b})
        random.Random(SEED).shuffle(groups)
        val_g = set(groups[: max(2, int(0.2 * len(groups)))])
        val = np.array([(not b) and r["group"] in val_g for r, b in zip(rows, y)])
        tr = ~val
        self.gbm = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(X[tr], y[tr].astype(int))
        self.iso = make_pipeline(SimpleImputer(strategy="median", keep_empty_features=True), StandardScaler(),
                                 IsolationForest(n_estimators=300, random_state=SEED)).fit(X[tr & ~y])
        vidx = np.flatnonzero(val)
        np.random.default_rng(SEED).shuffle(vidx)
        ref, cut = vidx[: max(1, len(vidx) // 2)], vidx[len(vidx) // 2:]
        g, i = self._raw(X[ref])
        self.ref_g, self.ref_i = np.sort(g), np.sort(i)
        self.threshold = float(np.quantile(self.score(X[cut]), 0.95)) if len(cut) else 95.0
        self.n_val_humans = int(val.sum())
        self.features = FEATURES

    def _raw(self, Z):
        return self.gbm.predict_proba(Z)[:, 1], -self.iso.score_samples(Z)

    def score(self, Z):
        g, i = self._raw(Z)
        pg = np.searchsorted(self.ref_g, g, side="right") / len(self.ref_g)
        pi = np.searchsorted(self.ref_i, i, side="right") / len(self.ref_i)
        return 100 * np.maximum(pg, pi)


def report(model, rows, X, title):
    s = model.score(X)
    y = np.array([r["label"] != "NORMAL" for r in rows])
    print(f"### {title} (문턱 {model.threshold:.1f}점, 검증 사람 {model.n_val_humans}명)")
    if len(set(y)) == 2:
        print(f"- AUC {roc_auc_score(y, s):.3f}")
    per = collections.defaultdict(lambda: [0, 0])
    for r, sc in zip(rows, s):
        per[r["strategy"]][0] += 1
        per[r["strategy"]][1] += sc >= model.threshold
    hum = [k for k in per if k.startswith("human") or k == "normal"]
    bots = [k for k in per if k not in hum]
    bt, bd = sum(per[k][0] for k in bots), sum(per[k][1] for k in bots)
    ht, hd = sum(per[k][0] for k in hum), sum(per[k][1] for k in hum)
    print(f"- 봇 탐지 {bd}/{bt} ({100 * bd / max(1, bt):.0f}%) · 사람 오탐 {hd}/{ht} ({100 * hd / max(1, ht):.0f}%)")
    print("- 종류별 (점수가 문턱 이상 / 계정): " + " · ".join(f"{k} {per[k][1]}/{per[k][0]}" for k in sorted(per)))
    if len(set(y)) == 2:
        pi = permutation_importance(model.gbm, X, y, scoring="roc_auc", n_repeats=10, random_state=SEED)
        top = np.argsort(-pi.importances_mean)[:5]
        print("- 부스팅이 기댄 특징 (시험 데이터, 순열 중요도): " + " · ".join(f"{FEATURES[i]} {pi.importances_mean[i]:.3f}" for i in top))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", nargs="+", required=True)
    ap.add_argument("--test", nargs="+", required=True)
    ap.add_argument("--out")
    a = ap.parse_args()
    rows, X, sk1 = read(a.train)
    trows, tX, sk2 = read(a.test)
    print(f"학습 {len(rows)}계정 · 시험 {len(trows)}계정 (마우스 이동이 없어 뺌 = 의견 없음: 학습 {sk1} · 시험 {sk2})\n")
    model = ReplayModel(rows, X)
    report(model, trows, tX, "녹화 모델")
    if a.out:
        os.makedirs(a.out, exist_ok=True)
        with open(os.path.join(a.out, "replay.pkl"), "wb") as f:
            pickle.dump({"model": model, "trained_at": datetime.now().isoformat(timespec="seconds"),
                         "train": a.train}, f)
        print(f"\n→ {a.out}/replay.pkl")


if __name__ == "__main__":
    main()
