#!/usr/bin/env python3
"""위험 점수 모델 학습 — 그래디언트 부스팅(본체) + Isolation Forest(안전망).

    python3 tools/ml/train.py data/ml/model data/ml/gen-s7/features.csv [data/ml/gen-s8/features.csv …]
    → data/ml/model/model.pkl · data/ml/model/report.txt

왜 이 조합인가 (compare.py, 2026-11-18, "봇 계열 하나 빼기" = 처음 보는 수법)
  - 그래디언트 부스팅 혼자 74% · 신경망 40% · Isolation Forest 29%
  - 부스팅 + Isolation Forest 87% (속도형 29 → 89%, 재시도형 73 → 98%)
    부스팅은 "본 적 있는 봇과 닮았나", Isolation Forest는 "사람과 얼마나 다른가"를 본다 → 약점이 서로 다르다

GRU (deep.py, 2026-11-18 추가): 이벤트 순서를 읽는 딥러닝. **큰 GRU(연결 10만7천) 5개를 시드만 바꿔 평균**.
  4세트 비교(데이터·시드 모두 다르게)에서 부스팅+IF에 더하면 탐지 61.6 → 69.7%, 사람 오탐 7.0 → 6.0% (네 번 모두 오탐이 낮음),
  좌석 묶어 두기(hoard) 46~63 → 100%. GRU 1개는 운에 따라 크게 흔들려(다계정 53~99%) 평균을 낸다.
  torch가 없으면 GRU 없이 학습한다 (ML_GRU=0으로 끌 수도 있음). 학습은 CPU 4코어에서 15분 남짓 (18,000계정).
  가중치는 같은 폴더의 gru.pt, 점수를 매길 때 features.csv 옆의 events.jsonl을 읽는다.

점수: 모델마다 점수를 "사람 검증 세트에서 몇 백분위인가"로 바꾸고 가장 큰 값을 쓴다 (0~100).
문턱: 사람 검증 세트의 95백분위 (오탐률 5% 목표). 학습 데이터는 생성 데이터만 쓴다 — 대회 데이터로 학습하지 않는다.

pickle 파일은 코드를 실행할 수 있으니 직접 만든 model.pkl만 연다.
"""
from __future__ import annotations

import os
import pickle
import random
import sys

# Windows(Git Bash) 기본 인코딩이 CP949라 한글·기호 출력에서 멈추지 않게 UTF-8로 내보낸다
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
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
# Isolation Forest(사람만 보고 "드문 것"을 잡는 모델)에서 양(量) 특징을 뺀다 (ML_IF_NO_VOLUME=1).
# 사람 중에도 드물게 많이 사는 사람(단체 총무)이 있어, 양을 넣으면 IF가 그 사람을 통째로 이상하다고 본다
# (2026-11-25: 총무 오탐 이유가 "손댄 운행 수·결제 수·한도 대비 결제"). 양으로 봇을 가르는 건 부스팅이 맡는다
IF_VOLUME = ["n_events", "n_view", "n_hold", "n_pay", "n_runs", "seats_paid_ratio"]
# ML_IF_VOLUME=ratio (4차, 2026-11-25): 양을 통째로 빼면(3차 r4) 총무 오탐은 3%로 줄었지만 IF가 속도만 보게 돼
#   오픈 직후 빠르게 사는 사람 오탐이 10%→31%로 늘었다. 그래서 양 대신 "운행 하나당" 양을 넣는다.
#   총무는 운행이 많아도 운행당 결제는 보통 사람과 같고, 한 운행을 긁는 봇은 운행당 값이 크다
IF_MODE = "drop" if os.environ.get("ML_IF_NO_VOLUME") == "1" else os.environ.get("ML_IF_VOLUME", "")
IF_COLS = [i for i, f in enumerate(FEATURES) if f not in IF_VOLUME] if IF_MODE in ("drop", "ratio") else None
IF_RATIO = ([(FEATURES.index(a), FEATURES.index("n_runs")) for a in ("n_events", "n_view", "n_hold", "n_pay", "seats_paid_ratio")]
            if IF_MODE == "ratio" else None)


def iso_input(X, cols=IF_COLS, ratio=IF_RATIO):
    """IF에 넣는 행렬. score.py의 iso_x와 같은 규칙"""
    Xi = X if cols is None else X[:, cols]
    if ratio:
        Xi = np.column_stack([Xi] + [X[:, a] / np.maximum(X[:, b], 1) for a, b in ratio])
    return Xi

SEED = 7
FP_TARGET = 0.05


gru_scores_train = None
GRU_N, GRU_SIZE = 5, "large"   # deep.py 4세트 비교로 고름


def train_gru(rows, folders, X, y, tr, out):
    """이벤트 순서를 읽는 GRU를 같은 학습 세트로 학습해 out/gru.pt에 저장. torch가 없으면 None"""
    global gru_scores_train
    if os.environ.get("ML_GRU", "1") == "0":
        return None
    try:
        import torch
        from deep import Ensemble, pad
        from seq import load_sequences
    except ImportError:
        print("torch가 없어 GRU 없이 학습합니다 (pip install torch)")
        return None
    seqs = {}
    for fo in sorted(set(folders)):
        seqs[fo] = load_sequences(fo)
    S = pad([seqs[fo].get(int(r["member_id"]), []) for r, fo in zip(rows, folders)])
    d = Ensemble(n=GRU_N, base=SEED, size=GRU_SIZE).fit((S[0][tr], S[1][tr]), y[tr].astype(int))
    os.makedirs(out, exist_ok=True)
    d.save(os.path.join(out, "gru.pt"))
    gru_scores_train = d.predict(S)
    return {"file": "gru.pt", "scores": gru_scores_train}


# ── 해명 모델 (ML_EXON=1) ──────────────────────────────────────────────
# 모델 하나가 모든 함정을 한 기준으로 가르면 한쪽을 고칠 때 다른 쪽이 나빠진다 (4차 r6: 오픈 직후 31→5%를 얻고
# 바로 취소 7→24%를 잃음). 그래서 약한 행동 그룹마다 "그 그룹 안의 사람 vs 봇"만 보는 작은 모델을 두고,
# 주 판정이 봇이어도 그 모델이 사람이라고 강하게 보면 풀어 준다 (점수를 EXON_CAP으로 내림).
#   - 그룹은 생성기 전략 이름이 아니라 관찰되는 특징으로 정한다 (실제 데이터에는 전략 이름이 없다)
#   - 풀어 주는 문턱은 그 그룹 봇의 교차 검증 확률로 정해, 그룹 봇 중 EXON_LEAK(기본 2%)만 잘못 풀리게 한다
#   - 강한 증거(한 기기·한 카드를 여러 계정이 씀)가 있으면 풀지 않는다 — 학습 데이터 밖의 진짜 봇을 놓치지 않게
EXON_SEGMENTS = {
    # 산 뒤 취소한 적 있음 (바로 후회하는 사람 vs 취소·재구매·좌석 묶기 봇)
    "cancel": [("n_cancel", ">=", 1)],
    # 한 운행에서 한도 가까이 삼 (가족 몫 대량 구매 vs 쓸어 담기 봇). seats_paid_ratio = 결제 좌석 / 운행당 한도 4
    "bulk": [("seats_paid_ratio", ">=", 0.75)],
}
# 6차 (ML_EXON_SHARED=1, 2026-11-25): 기기를 같이 쓰는 가족 (5차 진단: 가족 기기 공유 오탐 28/114, 강한 증거에 막히거나 그룹 밖).
# 기기는 같이 쓰지만 카드는 각자이고, 같은 기기 계정끼리 첫 선점이 30분 넘게 떨어져 있을 때만 (몇 초 차로 도는 봇 농장은 제외).
# 이 그룹만 "기기 공유" 강한 증거를 예외로 둔다 (카드 공유는 그대로 막음). 이 조건을 노리는 봇은 generate.py b_shift_device
EXON_SHARED_GAP_LOG = 7.5   # log1p(초) ≈ 30분
if os.environ.get("ML_EXON_SHARED") == "1":
    EXON_SEGMENTS["shared_device"] = [("accounts_on_device", ">=", 2), ("accounts_on_card", "<=", 1),
                                      ("min_sync_gap_s_log", ">=", EXON_SHARED_GAP_LOG)]
EXON_ALLOW_HARD = {"shared_device": ["accounts_on_device"]}   # 그룹 → 이 그룹에서만 풀어 주는 강한 증거
EXON_HARD = [("accounts_on_device", ">=", 2), ("accounts_on_card", ">=", 2)]
EXON_CAP = 50.0


def segment_mask(features, X, conds):
    m = np.ones(len(X), dtype=bool)
    for f, op, v in conds:
        if f not in features:
            return np.zeros(len(X), dtype=bool)
        col = X[:, features.index(f)]
        m &= (col >= v) if op == ">=" else (col <= v)
    return m & ~np.isnan(X[:, features.index(conds[0][0])])


def train_exon(X, y, tr):
    from sklearn.model_selection import cross_val_predict
    leak = float(os.environ.get("ML_EXON_LEAK", "0.02"))
    out = []
    for name, conds in EXON_SEGMENTS.items():
        seg = segment_mask(FEATURES, X, conds) & tr
        n_h, n_b = int((seg & ~y).sum()), int((seg & y).sum())
        if n_h < 30 or n_b < 30:
            print(f"해명 모델 {name}: 사람 {n_h} · 봇 {n_b} — 너무 적어 건너뜀")
            continue
        Xs, ys = X[seg], (~y[seg]).astype(int)   # 1 = 사람
        clf = HistGradientBoostingClassifier(max_iter=200, random_state=SEED)
        p_cv = cross_val_predict(clf, Xs, ys, cv=3, method="predict_proba")[:, 1]
        bots_p = p_cv[ys == 0]
        q = float(np.quantile(bots_p, 1 - leak))      # 이 위로 가는 봇이 leak 비율뿐
        q = max(q, 0.5)
        clf.fit(Xs, ys)
        cleared = float((p_cv[ys == 1] >= q).mean())
        print(f"해명 모델 {name}: 사람 {n_h} · 봇 {n_b} · 문턱 P(사람) ≥ {q:.3f} → 그룹 사람 {cleared:.0%} 풀림, 그룹 봇 ≤ {leak:.0%}")
        out.append({"name": name, "conds": conds, "clf": clf, "q": q, "allow_hard": EXON_ALLOW_HARD.get(name, [])})
    return {"segments": out, "hard": EXON_HARD, "cap": EXON_CAP}


def main():
    if len(sys.argv) < 3:
        sys.exit("사용법: train.py <출력 폴더> <생성 데이터 features.csv> [...]")
    out, paths = sys.argv[1], sys.argv[2:]
    rows, X, folders = [], [], []
    for p in paths:
        r, x = load(p)
        folders += [os.path.dirname(p)] * len(r)
        # 기본은 생성 데이터만. 주간 재학습(retrain.py)은 관리자 판정이 붙은 실제 계정(source = real:…)을 더한다 (ML_ALLOW_REAL=1)
        allowed = {"generator"}
        bad = {row.get("source", "") for row in r} - allowed
        if os.environ.get("ML_ALLOW_REAL") == "1":
            bad = {b for b in bad if not b.startswith("real:")}
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
    Xi = iso_input(X)
    iso = make_pipeline(SimpleImputer(strategy="median"), StandardScaler(),
                        IsolationForest(n_estimators=300, random_state=SEED)).fit(Xi[tr & ~y])

    # 검증 사람을 둘로: 하나는 백분위 기준(ref), 하나는 문턱. 두 점수의 큰 값을 쓰므로 문턱은 합친 점수로 잡아야
    # 오탐이 5%가 된다 (각각 95백분위로 자르면 둘 중 하나라도 넘는 사람이 늘어 8~9%가 됐다)
    gru = train_gru(rows, folders, X, y, tr, out)

    vidx = np.flatnonzero(val)
    rng2 = np.random.default_rng(SEED)
    rng2.shuffle(vidx)
    ref_i, thr_i = vidx[: len(vidx) // 2], vidx[len(vidx) // 2:]
    ref_gbm = np.sort(gbm.predict_proba(X[ref_i])[:, 1])
    ref_iso = np.sort(-iso.score_samples(Xi[ref_i]))
    if gru is not None:
        gru["ref"] = np.sort(gru.pop("scores")[ref_i])
    human = X[~y]
    model = {
        "gbm": gbm, "iso": iso, "ref_gbm": ref_gbm, "ref_iso": ref_iso, "gru": gru,
        "features": FEATURES, "all_features": ALL_FEATURES, "cols": COLS, "excluded": EXCLUDE, "iso_cols": IF_COLS, "iso_ratio": IF_RATIO,
        # 근거 표시용: 사람(생성 데이터)의 특징별 5·50·95 백분위
        "human_q": {f: [float(np.nanquantile(human[:, i], q)) for q in (0.05, 0.5, 0.95)] for i, f in enumerate(FEATURES)},
        "trained": {"at": datetime.now().isoformat(timespec="seconds"), "files": paths,
                    "accounts": len(rows), "bots": int(y.sum()), "humans": int((~y).sum())},
    }
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "model.pkl"), "wb") as f:
        pickle.dump(model, f)

    # 해명 모델 (ML_EXON=1, 5차 2026-11-25): 주 판정이 봇이라 해도 "이 행동 그룹의 정상 모양"이면 풀어 준다
    if os.environ.get("ML_EXON") == "1":
        model["exon"] = train_exon(X, y, tr)

    # 학습 데이터 안에서의 확인 (낙관적인 값. 실제 실력은 compare.py의 "계열 하나 빼기"와 대회 데이터로 본다)
    from score import score_matrix
    seq_scores = gru_scores_train if gru is not None else None
    s = score_matrix(model, X, seq_scores)
    model["threshold"] = float(np.quantile(s[thr_i], 1 - FP_TARGET))
    # GRU를 못 쓰는 PC(torch 없음 · events.jsonl 없음)에서 부스팅+IF만으로 매길 때의 문턱
    model["threshold_base"] = float(np.quantile(score_matrix(model, X, None)[thr_i], 1 - FP_TARGET))
    with open(os.path.join(out, "model.pkl"), "wb") as f:
        pickle.dump(model, f)
    flag = s >= model["threshold"]
    lines = [
        f"학습 {model['trained']['at']} · 계정 {len(rows)} (봇 {int(y.sum())} · 사람 {int((~y).sum())}) · 파일 {len(paths)}개",
        f"문턱 {model['threshold']:.1f}점 (사람 검증 세트 오탐 {FP_TARGET:.0%})",
        f"학습 데이터 안: 봇 탐지 {flag[y].mean():.1%} · 사람(검증) 오탐 {flag[val].mean():.1%}  ← 낙관적인 값",
        f"뺀 특징: {', '.join(EXCLUDE)}",
        f"GRU: {f'큰 GRU {GRU_N}개 평균' + ' (gru.pt) · 부스팅+IF만일 때 문턱 ' + format(model['threshold_base'], '.1f') + '점' if gru else '없음 (torch 없음 또는 ML_GRU=0)'}",
    ]
    with open(os.path.join(out, "report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"→ {os.path.join(out, 'model.pkl')}")


if __name__ == "__main__":
    main()
