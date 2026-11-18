#!/usr/bin/env python3
"""행동 로그 → 계정별 특징 표 (CSV). 생성 데이터와 대회 데이터에 **똑같이** 쓴다.

    python3 -I tools/ml/features.py data/ml/gen-s7          → data/ml/gen-s7/features.csv
    python3 -I tools/ml/features.py data/ml/contest-<태그>   (export_live.sh가 만든 폴더)

입력 폴더: events.jsonl (behavior_log 문서 모양) · members.jsonl (member_id, label, strategy, group, ci, cards 등)

특징을 고른 원칙 (좌석 구조·한도가 바뀌어도 다시 학습할 일이 적게)
  - 좌석 번호·열차 ID 같은 날값은 쓰지 않는다. 간격·비율·횟수만
  - 산 매수는 한도에 대한 비율로 (한도가 4 → 2로 바뀌어도 "한도를 채웠다"는 뜻이 같다)
  - 규칙 엔진의 문턱 값을 특징에 넣지 않는다. 모델이 규칙을 다시 외우지 않게 연속값 그대로

외부 라이브러리 없음.
"""
from __future__ import annotations

import csv
import json
import math
import os
import statistics
import sys
from collections import defaultdict
from datetime import datetime

LIMIT_PER_RUN = 4
FEATURES = [
    "n_events", "n_view", "n_hold", "n_pay", "n_cancel",
    "browse_before_hold",        # 처음 좌석을 잡기 전에 운행을 몇 번 봤나 (봇은 바로 들어온다)
    "int_median_s", "int_p10_s", "int_cv", "frac_int_lt_200ms", "frac_int_lt_1s",
    "int_cv_short",              # 10초 미만 간격만의 변동계수. 대기열 대기·결제 입력 같은 긴 쉼에 묻히지 않게 (고정 간격 매크로)
    "hold_fail_ratio",           # 선점 중 409 비율
    "max_same_seat_fail",        # 같은 좌석 실패 최대 횟수
    "limit_rejects",             # 422 매수 제한 거절 수
    "seats_paid_ratio",          # 결제한 좌석 / 한도
    "cancel_ratio", "min_cancel_after_pay_min_log",
    "accounts_on_device", "accounts_on_card", "accounts_on_ip",
    "min_sync_gap_s_log",        # 같은 기기·카드·IP를 쓰는 다른 계정과 첫 선점 시각 차이(초)의 최솟값, log1p
    "device_missing",            # 기기 ID 헤더가 없음
    "first_hold_after_open_log", # 판매 오픈(첫 이벤트 기준) 뒤 첫 선점까지 초, log1p
    "account_age_days_log",
]


def ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).replace(tzinfo=None)


def load(folder: str):
    events = defaultdict(list)
    with open(os.path.join(folder, "events.jsonl")) as f:
        for line in f:
            e = json.loads(line)
            if e.get("member_id") is not None:
                events[e["member_id"]].append(e)
    members = {}
    with open(os.path.join(folder, "members.jsonl")) as f:
        for line in f:
            m = json.loads(line)
            members[m["member_id"]] = m
    for evs in events.values():
        evs.sort(key=lambda e: e["ts"])
    return events, members


def pct(xs, q):
    if not xs:
        return math.nan
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))]


def build(folder: str) -> list[dict]:
    events, members = load(folder)
    t_open = min((ts(e["ts"]) for evs in events.values() for e in evs), default=None)

    # 연결: 기기·카드·IP → 계정들, 계정 → 첫 선점 시각
    by_dev, by_card, by_ip = defaultdict(set), defaultdict(set), defaultdict(set)
    first_hold = {}
    for m, evs in events.items():
        for e in evs:
            if e.get("device_hash"):
                by_dev[e["device_hash"]].add(m)
            if e.get("ip_hash"):
                by_ip[e["ip_hash"]].add(m)
            card = (e.get("meta") or {}).get("cardHash")
            if card:
                by_card[card].add(m)
            if e["event_type"] == "SEAT_HOLD" and m not in first_hold:
                first_hold[m] = ts(e["ts"])
    for m, info in members.items():
        for c in info.get("cards") or []:
            by_card[c].add(m)

    def links(m, table, key_of):
        keys = key_of(m)
        others = set()
        for k in keys:
            others |= table.get(k, set())
        return others

    dev_of = lambda m: {e["device_hash"] for e in events[m] if e.get("device_hash")}
    ip_of = lambda m: {e["ip_hash"] for e in events[m] if e.get("ip_hash")}
    card_of = lambda m: set(members.get(m, {}).get("cards") or []) | {
        (e.get("meta") or {}).get("cardHash") for e in events[m] if (e.get("meta") or {}).get("cardHash")}

    rows = []
    for m, info in members.items():
        evs = events.get(m, [])
        if not evs:
            continue
        typ = [e["event_type"] for e in evs]
        ints = [e["interval_ms"] / 1000 for e in evs if e.get("interval_ms") is not None and e["interval_ms"] < 600_000]
        holds = [e for e in evs if e["event_type"] == "SEAT_HOLD"]
        fails = [e for e in holds if e["response_status"] == 409]
        seat_fail = defaultdict(int)
        for e in fails:
            seat_fail[tuple((e.get("meta") or {}).get("seatIds") or [])] += 1
        # 실제 로그의 PAY·CANCEL에는 매수가 없다 (예약 번호뿐) → 매수는 성공한 선점 로그에서 예약 번호로 찾는다
        pays = [e for e in evs if e["event_type"] == "PAY" and e["response_status"] < 300
                and (e.get("meta") or {}).get("paymentStatus", "APPROVED") == "APPROVED"]
        cancels = [e for e in evs if e["event_type"] == "CANCEL" and e["response_status"] < 300]
        seats_of = {(e.get("meta") or {}).get("reservationId"): (e.get("meta") or {}).get("seatCount") or 0
                    for e in holds if e["response_status"] < 300}
        paid_at = {(e.get("meta") or {}).get("reservationId"): ts(e["ts"]) for e in pays}
        after = [(ts(c["ts"]) - paid_at[r]).total_seconds() / 60
                 for c in cancels if (r := (c.get("meta") or {}).get("reservationId")) in paid_at]
        seats_paid = sum(seats_of.get((e.get("meta") or {}).get("reservationId"), 0) for e in pays)
        seats_cancel = sum(seats_of.get((e.get("meta") or {}).get("reservationId"), 0) for e in cancels)

        first_view_hold = typ.index("SEAT_HOLD") if "SEAT_HOLD" in typ else len(typ)
        linked = (links(m, by_dev, dev_of) | links(m, by_card, card_of) | links(m, by_ip, ip_of)) - {m}
        gaps = [abs((first_hold[o] - first_hold[m]).total_seconds()) for o in linked if o in first_hold and m in first_hold]
        mean = statistics.fmean(ints) if ints else math.nan
        short = [i for i in ints if i < 10]
        smean = statistics.fmean(short) if short else math.nan

        rows.append({
            "member_id": m, "label": info.get("label"), "strategy": info.get("strategy"),
            "group": info.get("group"), "source": info.get("source"),
            "n_events": len(evs), "n_view": typ.count("VIEW_TRAIN") + typ.count("VIEW_SEAT"),
            "n_hold": len(holds), "n_pay": len(pays), "n_cancel": len(cancels),
            "browse_before_hold": typ[:first_view_hold].count("VIEW_TRAIN"),
            "int_median_s": statistics.median(ints) if ints else math.nan,
            "int_p10_s": pct(ints, 0.1),
            "int_cv": (statistics.pstdev(ints) / mean) if len(ints) >= 3 and mean > 0 else math.nan,
            "frac_int_lt_200ms": sum(i < 0.2 for i in ints) / len(ints) if ints else 0.0,
            "frac_int_lt_1s": sum(i < 1.0 for i in ints) / len(ints) if ints else 0.0,
            "int_cv_short": (statistics.pstdev(short) / smean) if len(short) >= 5 and smean > 0 else math.nan,
            "hold_fail_ratio": len(fails) / len(holds) if holds else 0.0,
            "max_same_seat_fail": max(seat_fail.values(), default=0),
            "limit_rejects": sum(1 for e in holds if e["response_status"] == 422),
            "seats_paid_ratio": seats_paid / LIMIT_PER_RUN,
            "cancel_ratio": seats_cancel / seats_paid if seats_paid else 0.0,
            "min_cancel_after_pay_min_log": math.log1p(min(after)) if after else math.log1p(60 * 24 * 30),
            "accounts_on_device": max((len(by_dev[d]) for d in dev_of(m)), default=0),
            "accounts_on_card": max((len(by_card[c]) for c in card_of(m)), default=0),
            "accounts_on_ip": max((len(by_ip[i]) for i in ip_of(m)), default=0),
            "min_sync_gap_s_log": math.log1p(min(gaps)) if gaps else math.log1p(86400),
            "device_missing": int(not dev_of(m)),
            "first_hold_after_open_log": math.log1p((first_hold[m] - t_open).total_seconds()) if m in first_hold else math.nan,
            "account_age_days_log": math.log1p(info.get("created_days_ago") or 0),
        })
    return rows


def main():
    if len(sys.argv) != 2:
        print("사용법: features.py <입력 폴더>")
        sys.exit(1)
    folder = sys.argv[1]
    rows = build(folder)
    out = os.path.join(folder, "features.csv")
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["member_id", "label", "strategy", "group", "source"] + FEATURES)
        w.writeheader()
        for r in rows:
            w.writerow({k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()})
    labels = defaultdict(int)
    for r in rows:
        labels[r["label"]] += 1
    print(f"계정 {len(rows)} → {out}  ({', '.join(f'{k} {v}' for k, v in sorted(labels.items()))})")


if __name__ == "__main__":
    main()
