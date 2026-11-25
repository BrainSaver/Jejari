#!/usr/bin/env python3
"""학습한 모델로 계정마다 위험 점수(0~100)와 근거를 낸다. 대회·실제 데이터에 **적용만** 한다.

    python3 tools/ml/score.py data/ml/model/model.pkl data/ml/contest-<태그>/features.csv
    → 같은 폴더의 scores.csv + 종류별 요약

점수 = 부스팅·Isolation Forest·GRU 점수를 사람 검증 세트 기준 백분위로 바꾼 것 중 큰 값.
  GRU는 features.csv 옆의 events.jsonl을 읽는다. torch나 events.jsonl이 없으면 GRU 없이 매기고 그때의 문턱(threshold_base)을 쓴다.
근거 = 사람(생성 데이터) 5~95백분위 밖으로 가장 멀리 나간 특징 3개. 관리자가 "왜 이 점수인가"를 보게 한다
(설계 원칙: 점수 근거 기록, risk_score.reasons). 모델 점수는 관리자에게 보여 주는 보조 의견이다.
"""
from __future__ import annotations

import csv
import math
import os
import pickle
import sys

# Windows(Git Bash) 기본 인코딩이 CP949라 한글·기호 출력에서 멈추지 않게 UTF-8로 내보낸다
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

FEATURE_KO = {
    "n_events": "이벤트 수", "n_view": "조회 수", "n_hold": "선점 시도", "n_pay": "결제 수", "n_cancel": "취소 수",
    "browse_before_hold": "선점 전 둘러보기", "int_median_s": "간격 중앙값(초)", "int_p10_s": "짧은 간격(초)",
    "int_cv": "간격 들쭉날쭉함", "int_cv_short": "짧은 간격 들쭉날쭉함", "frac_int_lt_200ms": "0.2초 미만 비율",
    "frac_int_lt_1s": "1초 미만 비율", "hold_fail_ratio": "선점 실패 비율", "max_same_seat_fail": "같은 좌석 실패",
    "limit_rejects": "매수 제한 거절", "seats_paid_ratio": "한도 대비 결제", "cancel_ratio": "취소 비율",
    "min_cancel_after_pay_min_log": "결제 뒤 취소까지(로그)", "accounts_on_device": "같은 기기 계정",
    "accounts_on_card": "같은 카드 계정", "accounts_on_ip": "같은 IP 계정", "min_sync_gap_s_log": "연결 계정과 시간차(로그)",
    "device_missing": "기기 정보 없음", "first_hold_after_open_log": "오픈 뒤 첫 선점(로그)",
    "account_age_days_log": "가입 기간(로그)", "n_runs": "손댄 운행 수", "distinct_devices": "쓴 기기 수",
    "distinct_ips": "쓴 IP 수", "hold_unpaid_ratio": "결제 안 한 선점 비율", "handoff_in": "넘겨받은 좌석",
    "handoff_out": "넘겨준 좌석",
}


def pct(ref, v):
    return 100 * np.searchsorted(ref, v, side="right") / len(ref)


def iso_x(model, X):
    """IF에 넣는 열 (ML_IF_NO_VOLUME으로 학습한 모델은 양 특징을 뺀 열만, ML_IF_VOLUME=ratio는 거기에 운행당 양을 붙여서).
    옛 모델은 전부. train.py의 iso_input과 같은 규칙"""
    cols, ratio = model.get("iso_cols"), model.get("iso_ratio")
    Xi = X if cols is None else X[:, cols]
    if ratio:
        Xi = np.column_stack([Xi] + [X[:, a] / np.maximum(X[:, b], 1) for a, b in ratio])
    return Xi


def parts(model, X, seq_scores=None):
    """모델별 백분위 점수. seq_scores = GRU가 낸 봇 확률 (없으면 GRU 칸은 빠짐)"""
    out = {"부스팅": pct(model["ref_gbm"], model["gbm"].predict_proba(X)[:, 1]),
           "IF": pct(model["ref_iso"], -model["iso"].score_samples(iso_x(model, X)))}
    if seq_scores is not None and model.get("gru"):
        out["GRU"] = pct(model["gru"]["ref"], seq_scores)
    return out


def score_matrix(model, X, seq_scores=None):
    return np.maximum.reduce(list(parts(model, X, seq_scores).values()))


def gru_scores(model, model_path, rows, folder):
    """GRU를 쓸 수 있으면 봇 확률, 아니면 (None, 이유)"""
    if not model.get("gru"):
        return None, "이 모델에는 GRU가 없음"
    if not os.path.exists(os.path.join(folder, "events.jsonl")):
        return None, "events.jsonl 없음"
    try:
        from deep import Ensemble, pad
        from seq import load_sequences
    except ImportError:
        return None, "torch 없음 (pip install torch)"
    d = Ensemble.load(os.path.join(os.path.dirname(model_path), model["gru"]["file"]))
    seqs = load_sequences(folder)
    return d.predict(pad([seqs.get(int(r["member_id"]), []) for r in rows])), None


def reasons(model, x, k=3):
    out = []
    for f, v in zip(model["features"], x):
        if v is None or math.isnan(v):
            continue
        lo, mid, hi = model["human_q"][f]
        width = max(hi - lo, 1e-9)
        if v > hi:
            out.append(((v - hi) / width, f"{FEATURE_KO.get(f, f)} {v:g} (사람 {lo:g}~{hi:g})"))
        elif v < lo:
            out.append(((lo - v) / width, f"{FEATURE_KO.get(f, f)} {v:g} (사람 {lo:g}~{hi:g})"))
    return [t for _, t in sorted(out, reverse=True)[:k]]


def row_reasons(model, x, by):
    """GRU가 가장 높게 본 계정은 특징 근거만으로는 이유가 안 보여서, 그 사실을 맨 앞에 적는다"""
    r = reasons(model, x)
    return (["행동 순서가 학습한 봇과 닮음(GRU)"] + r[:2]) if by == "GRU" else r


def bot_types(model_path, rows):
    """model.pkl 옆에 type.pkl(train_type.py)이 있으면 계정마다 가장 닮은 봇 유형과 확률(0~1). 없으면 빈 칸.
    봇이냐 아니냐와는 별개로 모든 계정에 매긴다 — 화면은 "봇 쪽으로 봄"인 계정에만 보여 준다"""
    p = os.path.join(os.path.dirname(model_path), "type.pkl")
    if not os.path.exists(p) or not rows:
        return [""] * len(rows), [""] * len(rows)
    with open(p, "rb") as f:
        t = pickle.load(f)
    if any(c not in rows[0] for c in t["features"]):
        return [""] * len(rows), [""] * len(rows)
    X = np.array([[float(r[c]) if r[c] not in ("", "nan") else np.nan for c in t["features"]] for r in rows])
    pr = t["clf"].predict_proba(X)
    best = pr.argmax(axis=1)
    return [t["types"][i] for i in best], [round(float(pr[j, i]), 3) for j, i in enumerate(best)]


def main():
    if len(sys.argv) != 3:
        sys.exit("사용법: score.py <model.pkl> <features.csv>")
    from compare import load
    with open(sys.argv[1], "rb") as f:
        model = pickle.load(f)
    rows, _ = load(sys.argv[2])
    # 학습 때 쓴 특징을 이름으로 꺼낸다 (ML_STAGE 같은 환경 변수가 학습 때와 달라도 열이 어긋나지 않게)
    missing = [c for c in model["features"] if c not in rows[0]] if rows else []
    if missing:
        sys.exit(f"features.csv에 모델이 쓰는 특징이 없음: {', '.join(missing)} (학습 때와 같은 ML_STAGE로 features.py를 다시 돌리세요)")
    X = np.array([[float(r[c]) if r[c] not in ("", "nan") else np.nan for c in model["features"]] for r in rows])
    seq, why = gru_scores(model, sys.argv[1], rows, os.path.dirname(sys.argv[2]))
    P = parts(model, X, seq)
    s = np.maximum.reduce(list(P.values()))
    by = [max(P, key=lambda k: P[k][j]) for j in range(len(rows))]   # 어느 모델이 가장 높게 봤나
    thr = model["threshold"] if seq is not None else model.get("threshold_base", model["threshold"])
    types, type_p = bot_types(sys.argv[1], rows)
    out = os.path.join(os.path.dirname(sys.argv[2]), "scores.csv")
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["member_id", "label", "strategy", "score", "flag", "by", *[f"score_{k}" for k in P], "reasons",
                    "type", "type_prob"])
        for j, (r, v, x) in enumerate(zip(rows, s, X)):
            w.writerow([r["member_id"], r["label"], r["strategy"], round(float(v), 1), int(v >= thr), by[j],
                        *[round(float(P[k][j]), 1) for k in P], " · ".join(row_reasons(model, x, by[j])),
                        types[j], type_p[j]])

    print(f"모델 {model['trained']['at']} (계정 {model['trained']['accounts']}) · 문턱 {thr:.1f}점 · 뺀 특징 {model['excluded']} → {out}")
    print(f"쓴 모델: {' + '.join(P)}" + (f"  (GRU 빠짐: {why})" if why and model.get("gru") else "") + "\n")
    kinds = sorted({(r["strategy"], r["label"]) for r in rows})
    print(f"{'종류':<12}{'라벨':<15}{'계정':>5}{'표시':>6}{'점수 중앙값':>12}")
    for k, lab in kinds:
        idx = [j for j, r in enumerate(rows) if r["strategy"] == k]
        print(f"{k:<12}{lab:<15}{len(idx):>5}{int((s[idx] >= thr).sum()):>6}{float(np.median(s[idx])):>12.1f}")
    flagged = [j for j in range(len(rows)) if s[j] >= thr][:5]
    if flagged:
        print("\n근거 예시 (표시된 계정 앞 5개)")
        for j in flagged:
            print(f"  {rows[j]['member_id']} ({rows[j]['strategy']}) {s[j]:.0f}점 [{by[j]}]: " + " · ".join(row_reasons(model, X[j], by[j])))


if __name__ == "__main__":
    main()
