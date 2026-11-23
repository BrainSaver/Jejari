#!/usr/bin/env python3
"""봇 유형 모델 — "봇 쪽으로 본 계정이 어떤 수법과 가장 닮았나"를 고른다 (관리자 화면의 "닮은 유형(추정)").

    python3 tools/ml/train_type.py data/ml/model data/ml/g7/features.csv data/ml/g8/features.csv data/ml/g9/features.csv
    → data/ml/model/type.pkl · type_report.txt

- 점수 모델(model.pkl)은 건드리지 않는다. 봇이냐 아니냐는 지금처럼 model.pkl이 정하고, 이 모델은 유형만 붙인다
- 생성 데이터의 봇만으로 학습 (정답 = 생성기의 계열 family). 특징은 model.pkl과 같은 것을 쓴다
- 결과는 "추정"이다. 생성기가 정한 수법 여섯 가지 중 가장 닮은 것일 뿐, 처음 보는 수법이면 그중 가까운 것을 고른다
pickle 파일은 코드를 실행할 수 있으니 직접 만든 type.pkl만 연다.
"""
from __future__ import annotations

import os
import pickle
import random
import sys

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
from sklearn.ensemble import HistGradientBoostingClassifier  # noqa: E402

from compare import load  # noqa: E402

# 계열 코드 → 화면 이름 (frontend/src/admin/ModelOpinion.jsx와 같게, ERD model_score.type)
TYPES = {
    "speed": "SPEED",                  # 빠른 반복 매크로 (연타·일정 간격·오픈 순간)
    "retry": "RETRY",                  # 같은 좌석 반복 시도
    "multi_account": "MULTI_ACCOUNT",  # 다계정 (명의·기기·카드 여러 개)
    "evasive": "EVASIVE",              # 사람 흉내 매크로 (느리게·분산)
    "cancel": "CANCEL",                # 취소·되팔기 반복 (결제 직후 취소, 좌석 넘겨주기)
    "hoard": "HOARD",                  # 사재기 (한도까지 채우기, 여러 운행 독점)
}
SEED = 7


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out, paths = sys.argv[1], sys.argv[2:]
    with open(os.path.join(out, "model.pkl"), "rb") as f:
        model = pickle.load(f)
    feats = model["features"]

    rows, X = [], []
    for p in paths:
        r, _ = load(p)
        bots = [x for x in r if x["label"] != "NORMAL" and x.get("family") in TYPES]
        if any(x.get("source", "") != "generator" for x in bots):
            sys.exit(f"{p}: 생성 데이터만 학습에 쓴다")
        for x in bots:
            x["group"] = f"{p}:{x['group']}"
        rows += bots
    X = np.array([[float(x[c]) if x[c] not in ("", "nan") else np.nan for c in feats] for x in rows])
    y = np.array([TYPES[x["family"]] for x in rows])

    # 확인: 묶음(group) 단위로 80 · 20 나눠 맞힌 비율 (같은 조직의 봇이 양쪽에 들어가지 않게)
    groups = sorted({x["group"] for x in rows})
    random.Random(SEED).shuffle(groups)
    test_g = set(groups[: max(1, len(groups) // 5)])
    te = np.array([x["group"] in test_g for x in rows])
    clf = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(X[~te], y[~te])
    pred = clf.predict(X[te])
    lines = [f"유형 모델 · 봇 {len(rows)}개 (학습 {int((~te).sum())} · 확인 {int(te.sum())}) · 특징 {len(feats)}개",
             f"확인 세트 정답률 {100 * float((pred == y[te]).mean()):.1f}% (생성 데이터 안이라 낙관적인 값)"]
    for t in sorted(set(y)):
        m = y[te] == t
        if m.any():
            lines.append(f"  {t:14s} {int(m.sum()):4d}개  맞힘 {100 * float((pred[m] == t).mean()):5.1f}%")

    clf = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(X, y)   # 실제로 쓰는 건 전부로
    with open(os.path.join(out, "type.pkl"), "wb") as f:
        pickle.dump({"clf": clf, "features": feats, "types": list(clf.classes_)}, f)
    with open(os.path.join(out, "type_report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"→ {os.path.join(out, 'type.pkl')}")


if __name__ == "__main__":
    main()
