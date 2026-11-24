#!/usr/bin/env python3
"""실제 대회 데이터에서 "행동 모양"만 뽑아 생성기가 쓰게 한다 → tools/ml/real_calibration.json
    python3 tools/ml/calibrate_real.py data/ml/contest-c1124d [더 많은 폴더 …] [--out tools/ml/real_calibration.json]

왜 (2026-11-24, 2차 대회 c1124d 분석):
  생성기의 "사람"은 실제 사람보다 느리고 많이 둘러본다 (간격 중앙값 7.8초 vs 실제 3.6초, 둘러보기 2회 vs 1회,
  p90 10회 vs 2회). 그래서 실제 사람이 생성 데이터 기준으로는 봇처럼 보여 오탐이 났다.
  생성기에 실제 사람·실제 봇 계정 한 명 한 명의 행동 모양(프로필)을 넣어, 그 둘레로 대량 생성한다.

뽑는 것 (계정마다 한 줄, 행동만)
  int_median_s · int_p10_s · int_cv  행동 사이 간격
  browse_before_hold                 처음 좌석을 잡기 전 운행 조회 수
  n_hold · n_view · seats            선점 시도 · 조회 · 산 좌석 수
  limit_rejects (봇)                 매수 제한 거절
  view_to_hold · hold_to_pay · queue_to_seatview  단계 시간 (실제 사람은 카드 입력이 생성기보다 훨씬 빨랐다)

뽑지 않는 것 — 대회에서만 생긴 모양이라 "사람이면 보통 이렇다"로 배우면 안 된다
  accounts_on_device · accounts_on_card · min_sync_gap · distinct_devices
    (사람 5명이 계정 27개를 같은 기기로 돌림, 몇 명이 흔한 시험 카드 4111…1111을 쳐서 봇과 카드가 묶임)
  first_hold_after_open · open_to_queue (c1124d는 몇 시간 전에 판매를 연 운행을 다시 써서 모두 늦게 잡은 것처럼 보임)
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
KEEP = ["int_median_s", "int_p10_s", "int_cv", "browse_before_hold", "n_hold", "n_view", "seats_paid_ratio", "limit_rejects",
        # 단계 시간 (log1p 초, ML_STAGE=1 특징): 좌석표 → 선점, 선점 → 결제(카드 입력), 대기열 → 좌석표
        "view_to_hold_med_log", "hold_to_pay_med_log", "queue_to_seatview_log"]


def num(v):
    try:
        x = float(v)
        return x if math.isfinite(x) else None
    except (TypeError, ValueError):
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("folders", nargs="+", help="features.csv·members.jsonl이 있는 실제 데이터 폴더")
    ap.add_argument("--out", default=os.path.join(HERE, "real_calibration.json"))
    a = ap.parse_args()

    human, bots = [], {}
    for d in a.folders:
        strat = {}
        with open(os.path.join(d, "members.jsonl"), encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    m = json.loads(line)
                    strat[m["member_id"]] = m.get("strategy") or m.get("label")
        with open(os.path.join(d, "features.csv"), encoding="utf-8") as f:
            for r in csv.DictReader(f):
                p = {k: num(r.get(k)) for k in KEEP}
                if p["int_median_s"] is None or p["n_hold"] in (None, 0):
                    continue   # 선점까지 안 간 계정은 모양이 없다
                p["seats"] = max(1, min(4, round((p.pop("seats_paid_ratio") or 0.25) * 4)))
                p["src"] = os.path.basename(os.path.normpath(d))
                s = strat.get(int(r["member_id"]), "")
                if r.get("label") == "NORMAL":
                    human.append(p)
                else:
                    bots.setdefault(s, []).append(p)

    out = {
        "source": [os.path.basename(os.path.normpath(d)) for d in a.folders],
        "note": "실제 대회 계정의 행동 프로필 (calibrate_real.py). 기기·카드·연결 계정·판매 오픈 시각은 대회에서만 생긴 모양이라 뺐다",
        "human": human,
        "bot": bots,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    med = lambda xs: sorted(xs)[len(xs) // 2] if xs else float("nan")
    print(f"사람 {len(human)} · 봇 {', '.join(f'{k} {len(v)}' for k, v in bots.items())} → {os.path.relpath(a.out)}")
    if human:
        print(f"  사람 간격 중앙값 {med([p['int_median_s'] for p in human]):.2f}초 · 둘러보기 중앙값 "
              f"{med([p['browse_before_hold'] for p in human]):g} · 좌석 중앙값 {med([p['seats'] for p in human])}")


if __name__ == "__main__":
    main()
