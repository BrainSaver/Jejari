#!/usr/bin/env python3
"""학습한 모델로 계정마다 위험 점수(0~100)와 근거를 낸다. 대회·실제 데이터에 **적용만** 한다.

    python3 tools/ml/score.py data/ml/model/model.pkl data/ml/contest-<태그>/features.csv
    → 같은 폴더의 scores.csv + 종류별 요약

점수 = 부스팅·Isolation Forest 점수를 사람 검증 세트 기준 백분위로 바꾼 것 중 큰 값.
근거 = 사람(생성 데이터) 5~95백분위 밖으로 가장 멀리 나간 특징 3개. 관리자가 "왜 이 점수인가"를 보게 한다
(설계 원칙: 점수 근거 기록, risk_score.reasons). 모델 점수는 관리자에게 보여 주는 보조 의견이다.
"""
from __future__ import annotations

import csv
import math
import os
import pickle
import sys

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


def score_matrix(model, X):
    g = pct(model["ref_gbm"], model["gbm"].predict_proba(X)[:, 1])
    i = pct(model["ref_iso"], -model["iso"].score_samples(X))
    return np.maximum(g, i)


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


def main():
    if len(sys.argv) != 3:
        sys.exit("사용법: score.py <model.pkl> <features.csv>")
    from compare import load
    with open(sys.argv[1], "rb") as f:
        model = pickle.load(f)
    rows, X = load(sys.argv[2])
    X = X[:, model["cols"]]   # 학습 때 쓴 특징만 (features.py 순서 기준)
    s = score_matrix(model, X)
    thr = model["threshold"]
    out = os.path.join(os.path.dirname(sys.argv[2]), "scores.csv")
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["member_id", "label", "strategy", "score", "flag", "reasons"])
        for r, v, x in zip(rows, s, X):
            w.writerow([r["member_id"], r["label"], r["strategy"], round(float(v), 1), int(v >= thr), " · ".join(reasons(model, x))])

    print(f"모델 {model['trained']['at']} (계정 {model['trained']['accounts']}) · 문턱 {thr:.1f}점 · 뺀 특징 {model['excluded']} → {out}\n")
    kinds = sorted({(r["strategy"], r["label"]) for r in rows})
    print(f"{'종류':<12}{'라벨':<15}{'계정':>5}{'표시':>6}{'점수 중앙값':>12}")
    for k, lab in kinds:
        idx = [j for j, r in enumerate(rows) if r["strategy"] == k]
        print(f"{k:<12}{lab:<15}{len(idx):>5}{int((s[idx] >= thr).sum()):>6}{float(np.median(s[idx])):>12.1f}")
    flagged = [j for j in range(len(rows)) if s[j] >= thr][:5]
    if flagged:
        print("\n근거 예시 (표시된 계정 앞 5개)")
        for j in flagged:
            print(f"  {rows[j]['member_id']} ({rows[j]['strategy']}) {s[j]:.0f}점: " + " · ".join(reasons(model, X[j])))


if __name__ == "__main__":
    main()
