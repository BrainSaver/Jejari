#!/usr/bin/env python3
"""위험 점수 모델 학습 — 그래디언트 부스팅(본체) + Isolation Forest(안전망).

    python3 tools/ml/train.py data/ml/model data/ml/gen-s7/features.csv [data/ml/gen-s8/features.csv …]
    → data/ml/model/model.pkl · data/ml/model/report.txt

왜 이 조합인가 (compare.py, 2026-11-18, "봇 계열 하나 빼기" = 처음 보는 수법)
  - 그래디언트 부스팅 혼자 74% · 신경망 40% · Isolation Forest 29%
  - 부스팅 + Isolation Forest 87% (속도형 29 → 89%, 재시도형 73 → 98%)
    부스팅은 "본 적 있는 봇과 닮았나", Isolation Forest는 "사람과 얼마나 다른가"를 본다 → 약점이 서로 다르다

점수: 두 모델의 점수를 각각 "사람 검증 세트에서 몇 백분위인가"로 바꾸고 큰 쪽을 쓴다 (0~100).
문턱: 사람 검증 세트의 95백분위 (오탐률 5% 목표). 학습 데이터는 생성 데이터만 쓴다 — 대회 데이터로 학습하지 않는다.

pickle 파일은 코드를 실행할 수 있으니 직접 만든 model.pkl만 연다.
"""
from __future__ import annotations

import os
import pickle
import random
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
from sklearn.ensemble import HistGradientBoostingClassifier, IsolationForest  # noqa: E402
from sklearn.impute import SimpleImputer  # noqa: E402
from sklearn.pipeline import make_pipeline  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

from compare import load  # noqa: E402
from features import FEATURES as ALL_FEATURES  # noqa: E402

# 모델에 넣지 않는 특징과 이유
#   account_age_days_log: 대회(로컬)에서는 사람·봇 모두 그날 가입한다. 생성 데이터에서 "새 계정 = 봇 쪽"으로 배우면
#   대회 날 사람 참가자를 대거 오탐한다. 실제 운영에서 쓰려면 실제 가입 분포로 다시 학습한 뒤에
#   accounts_on_ip · distinct_ips · min_sync_gap_ip_s_log: 로컬 대회는 봇·사람이 한 PC(한 IP)에서 들어와
#   "한 IP에 계정이 몰림"이 사람에게도 생긴다 (리허설에서 botc와 같이 매기자 사람 3명이 100점으로 오탐).
#   실제 운영처럼 사람마다 IP가 다를 때는 ML_USE_IP=1로 다시 넣는다
EXCLUDE = ["account_age_days_log"] + ([] if os.environ.get("ML_USE_IP") == "1"
                                      else ["accounts_on_ip", "distinct_ips", "min_sync_gap_ip_s_log"])
FEATURES = [f for f in ALL_FEATURES if f not in EXCLUDE]
COLS = [ALL_FEATURES.index(f) for f in FEATURES]

SEED = 7
FP_TARGET = 0.05


def main():
    if len(sys.argv) < 3:
        sys.exit("사용법: train.py <출력 폴더> <생성 데이터 features.csv> [...]")
    out, paths = sys.argv[1], sys.argv[2:]
    rows, X = [], []
    for p in paths:
        r, x = load(p)
        bad = {row.get("source", "") for row in r} - {"generator"}
        if bad:
            sys.exit(f"{p}: 생성 데이터만 학습에 쓴다 (source={bad}). 대회 데이터는 score.py로 적용만 하세요")
        for row in r:  # 파일이 여러 개면 묶음 번호가 겹치지 않게
            row["group"] = f"{p}:{row['group']}"
        rows += r
        X.append(x)
    X = np.vstack(X)[:, COLS]
    y = np.array([r["label"] != "NORMAL" for r in rows])

    # 사람은 묶음 단위로 학습 80 · 검증 20 (검증 = 문턱과 백분위 기준)
    rng = random.Random(SEED)
    groups = sorted({r["group"] for r, b in zip(rows, y) if not b})
    rng.shuffle(groups)
    val_groups = set(groups[: int(0.2 * len(groups))])
    val = np.array([(not b) and r["group"] in val_groups for r, b in zip(rows, y)])
    tr = ~val

    gbm = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(X[tr], y[tr].astype(int))
    iso = make_pipeline(SimpleImputer(strategy="median"), StandardScaler(),
                        IsolationForest(n_estimators=300, random_state=SEED)).fit(X[tr & ~y])

    # 검증 사람을 둘로: 하나는 백분위 기준(ref), 하나는 문턱. 두 점수의 큰 값을 쓰므로 문턱은 합친 점수로 잡아야
    # 오탐이 5%가 된다 (각각 95백분위로 자르면 둘 중 하나라도 넘는 사람이 늘어 8~9%가 됐다)
    vidx = np.flatnonzero(val)
    rng2 = np.random.default_rng(SEED)
    rng2.shuffle(vidx)
    ref_i, thr_i = vidx[: len(vidx) // 2], vidx[len(vidx) // 2:]
    ref_gbm = np.sort(gbm.predict_proba(X[ref_i])[:, 1])
    ref_iso = np.sort(-iso.score_samples(X[ref_i]))
    human = X[~y]
    model = {
        "gbm": gbm, "iso": iso, "ref_gbm": ref_gbm, "ref_iso": ref_iso,
        "features": FEATURES, "all_features": ALL_FEATURES, "cols": COLS, "excluded": EXCLUDE,
        # 근거 표시용: 사람(생성 데이터)의 특징별 5·50·95 백분위
        "human_q": {f: [float(np.nanquantile(human[:, i], q)) for q in (0.05, 0.5, 0.95)] for i, f in enumerate(FEATURES)},
        "trained": {"at": datetime.now().isoformat(timespec="seconds"), "files": paths,
                    "accounts": len(rows), "bots": int(y.sum()), "humans": int((~y).sum())},
    }
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "model.pkl"), "wb") as f:
        pickle.dump(model, f)

    # 학습 데이터 안에서의 확인 (낙관적인 값. 실제 실력은 compare.py의 "계열 하나 빼기"와 대회 데이터로 본다)
    from score import score_matrix
    s = score_matrix(model, X)
    model["threshold"] = float(np.quantile(s[thr_i], 1 - FP_TARGET))
    with open(os.path.join(out, "model.pkl"), "wb") as f:
        pickle.dump(model, f)
    flag = s >= model["threshold"]
    lines = [
        f"학습 {model['trained']['at']} · 계정 {len(rows)} (봇 {int(y.sum())} · 사람 {int((~y).sum())}) · 파일 {len(paths)}개",
        f"문턱 {model['threshold']:.1f}점 (사람 검증 세트 오탐 {FP_TARGET:.0%})",
        f"학습 데이터 안: 봇 탐지 {flag[y].mean():.1%} · 사람(검증) 오탐 {flag[val].mean():.1%}  ← 낙관적인 값",
        f"뺀 특징: {', '.join(EXCLUDE)}",
    ]
    with open(os.path.join(out, "report.txt"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"→ {os.path.join(out, 'model.pkl')}")


if __name__ == "__main__":
    main()
