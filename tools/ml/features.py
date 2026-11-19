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

# Windows(Git Bash) 기본 인코딩이 CP949라 한글·기호 출력에서 멈추지 않게 UTF-8로 내보낸다
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
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
    "min_sync_gap_s_log",        # 같은 기기·카드를 쓰는 다른 계정과 첫 선점 시각 차이(초)의 최솟값, log1p
    "min_sync_gap_ip_s_log",     # 같은 IP만 공유하는 계정까지 넣은 시각 차이 (로컬 대회처럼 모두 한 IP면 쓰면 안 됨)
    "device_missing",            # 기기 ID 헤더가 없음
    "first_hold_after_open_log", # 그 운행의 첫 이벤트(≈ 판매 오픈) 뒤 첫 선점까지 초, log1p
    "account_age_days_log",
    "n_runs",                    # 몇 개 운행에 손댔나
    "distinct_devices", "distinct_ips",  # 한 계정이 쓴 기기·IP 수 (회전·저장소 막힘)
    "hold_unpaid_ratio",         # 잡고 결제하지 않은 선점 비율 (좌석 묶어 두기)
    "handoff_in",                # 남이 취소한 좌석을 60초 안에 잡은 횟수 (넘겨받기)
    "handoff_out",               # 내가 취소한 좌석을 남이 60초 안에 잡은 횟수 (넘겨주기)
]
# 단계별 속도 (2026-11-19 실험, docs/강동균/모델-비교-김성원-20261119.md). ML_STAGE=1일 때만 넣는다.
# 기본값은 꺼짐 — 대회 전 모델(data/ml/model)은 이 특징 없이 학습했다. 끝에 붙이므로 기존 모델의 열 번호는 그대로
STAGE = [
    "open_to_queue_log",         # 판매 오픈(운행 첫 이벤트) → 첫 대기열 진입, 초 log1p
    "queue_to_seatview_log",     # 첫 대기열 진입 → 첫 좌석표 조회 (대기 시간 포함)
    "view_to_hold_min_log",      # 좌석표 조회 → 선점, 가장 빠른 한 번 (사람: 좌석표를 보고 고르기)
    "view_to_hold_med_log",      # 같은 값의 중앙값
    "hold_without_view",         # 직전에 좌석표 조회가 없던 선점 수 (API 직접 호출)
    "hold_to_pay_med_log",       # 선점 → 결제 중앙값 (카드 입력)
    "crit_vs_pace",              # 가장 빠른 고르기 − 평소 간격 (log). 중요한 단계만 유난히 빠르면 크게 음수
]
STAGE_ON = os.environ.get("ML_STAGE") == "1"
if STAGE_ON:
    FEATURES = FEATURES + STAGE
HANDOFF_SEC = 60


def ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).replace(tzinfo=None)


def load(folder: str):
    events = defaultdict(list)
    with open(os.path.join(folder, "events.jsonl"), encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            # 첫 화면 자동 검색(meta.auto)은 사람이 한 행동이 아니라 화면이 스스로 부른 것이라 뺀다
            # (안 빼면 화면만 열어도 "검색한 사람"이 되어 n_view·browse_before_hold·간격이 흐려짐)
            if (e.get("meta") or {}).get("auto"):
                continue
            if e.get("member_id") is not None:
                events[e["member_id"]].append(e)
    members = {}
    with open(os.path.join(folder, "members.jsonl"), encoding="utf-8") as f:
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


def stage_speed(evs, holds, pays, ints, run_open) -> dict:
    """단계별 속도 (STAGE). 평균 하나로 뭉개지 않고 "어느 단계에서 얼마나 빨랐나"를 따로 둔다"""
    L = math.log1p
    qe = [e for e in evs if e["event_type"] == "QUEUE_ENTER"]
    o2q = q2v = math.nan
    if qe and qe[0].get("run_id") in run_open:
        o2q = L(max(0.0, (ts(qe[0]["ts"]) - run_open[qe[0]["run_id"]]).total_seconds()))
    if qe:
        t0 = ts(qe[0]["ts"])
        v = next((e for e in evs if e["event_type"] == "VIEW_SEAT" and ts(e["ts"]) >= t0), None)
        q2v = L((ts(v["ts"]) - t0).total_seconds()) if v else math.nan
    v2h, no_view, last_view = [], 0, None
    for e in evs:
        if e["event_type"] == "VIEW_SEAT":
            last_view = ts(e["ts"])
        elif e["event_type"] == "SEAT_HOLD":
            if last_view is None:
                no_view += 1
            else:
                v2h.append((ts(e["ts"]) - last_view).total_seconds())
            last_view = None if e["response_status"] < 300 else last_view
    hold_at = {(e.get("meta") or {}).get("reservationId"): ts(e["ts"]) for e in holds if e["response_status"] < 300}
    h2p = [(ts(e["ts"]) - hold_at[r]).total_seconds() for e in pays
           if (r := (e.get("meta") or {}).get("reservationId")) in hold_at]
    v2h_min = L(max(0.0, min(v2h))) if v2h else math.nan
    return {
        "open_to_queue_log": o2q, "queue_to_seatview_log": q2v, "view_to_hold_min_log": v2h_min,
        "view_to_hold_med_log": L(max(0.0, statistics.median(v2h))) if v2h else math.nan,
        "hold_without_view": no_view,
        "hold_to_pay_med_log": L(max(0.0, statistics.median(h2p))) if h2p else math.nan,
        "crit_vs_pace": (v2h_min - L(statistics.median(ints))) if v2h and ints else math.nan,
    }


def build(folder: str) -> list[dict]:
    events, members = load(folder)
    # 판매 오픈 시각: runs.jsonl(export_live.sh가 DB에서 꺼냄)이 있으면 그것, 없으면 운행마다 첫 이벤트 시각.
    # 첫 이벤트 기준은 "같이 넣은 데이터"에 따라 바뀐다 (리허설에서 사람만 매기면 0초, 봇과 같이 매기면 40분)
    run_open = {}
    rp = os.path.join(folder, "runs.jsonl")
    if os.path.exists(rp):
        with open(rp, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    r = json.loads(line)
                    run_open[r["run_id"]] = ts(r["open_ts"])
    for evs in events.values():
        for e in evs:
            t = ts(e["ts"])
            if e.get("run_id") is not None and not os.path.exists(rp) and (e["run_id"] not in run_open or t < run_open[e["run_id"]]):
                run_open[e["run_id"]] = t

    # 좌석 넘겨주기: 취소된 예약의 좌석이 풀린 시각·계정, 성공한 선점의 좌석·시각·계정
    resv_owner, resv_seats_all, released, grabbed = {}, {}, [], []
    for m, evs in events.items():
        for e in evs:
            meta = e.get("meta") or {}
            if e["event_type"] == "SEAT_HOLD" and e["response_status"] < 300 and meta.get("reservationId"):
                resv_owner[meta["reservationId"]] = m
                resv_seats_all[meta["reservationId"]] = meta.get("seatIds") or []
                grabbed.append((ts(e["ts"]), m, set(meta.get("seatIds") or [])))
    for m, evs in events.items():
        for e in evs:
            r = (e.get("meta") or {}).get("reservationId")
            if e["event_type"] == "CANCEL" and e["response_status"] < 300 and r in resv_seats_all:
                released.append((ts(e["ts"]), m, set(resv_seats_all[r])))
    handoff_in, handoff_out = defaultdict(int), defaultdict(int)
    by_seat = defaultdict(list)
    for t, m, seats in released:
        for st in seats:
            by_seat[st].append((t, m))
    for t, m, seats in grabbed:
        for st in seats:
            for rt, rm in by_seat.get(st, ()):
                if rm != m and 0 <= (t - rt).total_seconds() <= HANDOFF_SEC:
                    handoff_in[m] += 1
                    handoff_out[rm] += 1

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

    def first_hold_log(evs):
        for e in evs:
            if e["event_type"] == "SEAT_HOLD" and e.get("run_id") in run_open:
                return math.log1p(max(0.0, (ts(e["ts"]) - run_open[e["run_id"]]).total_seconds()))
        return math.nan

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
        # 0 아래로 자르기: 앱 2대의 시계가 몇 ms 어긋나면 취소가 결제보다 앞서 찍힐 수 있다 (log1p 음수 → 오류)
        after = [max(0.0, (ts(c["ts"]) - paid_at[r]).total_seconds() / 60)
                 for c in cancels if (r := (c.get("meta") or {}).get("reservationId")) in paid_at]
        seats_paid = sum(seats_of.get((e.get("meta") or {}).get("reservationId"), 0) for e in pays)
        seats_cancel = sum(seats_of.get((e.get("meta") or {}).get("reservationId"), 0) for e in cancels)

        first_view_hold = typ.index("SEAT_HOLD") if "SEAT_HOLD" in typ else len(typ)
        linked = (links(m, by_dev, dev_of) | links(m, by_card, card_of)) - {m}
        linked_ip = linked | (links(m, by_ip, ip_of) - {m})
        gap = lambda others: [abs((first_hold[o] - first_hold[m]).total_seconds())
                              for o in others if o in first_hold and m in first_hold]
        gaps, gaps_ip = gap(linked), gap(linked_ip)
        mean = statistics.fmean(ints) if ints else math.nan
        short = [i for i in ints if i < 10]
        smean = statistics.fmean(short) if short else math.nan

        rows.append({**(stage_speed(evs, holds, pays, ints, run_open) if STAGE_ON else {}),
            "member_id": m, "label": info.get("label"), "strategy": info.get("strategy"),
            "group": info.get("group"), "source": info.get("source"), "family": info.get("family"),
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
            "min_sync_gap_ip_s_log": math.log1p(min(gaps_ip)) if gaps_ip else math.log1p(86400),
            "device_missing": int(not dev_of(m)),
            "first_hold_after_open_log": first_hold_log(evs),
            "account_age_days_log": math.log1p(info.get("created_days_ago") or 0),
            "n_runs": len({e.get("run_id") for e in evs if e.get("run_id") is not None}),
            "distinct_devices": len(dev_of(m)),
            "distinct_ips": len(ip_of(m)),
            "hold_unpaid_ratio": (1 - len(pays) / len(seats_of)) if seats_of else 0.0,
            "handoff_in": handoff_in[m],
            "handoff_out": handoff_out[m],
        })
    return rows


def main():
    if len(sys.argv) != 2:
        print("사용법: features.py <입력 폴더>")
        sys.exit(1)
    folder = sys.argv[1]
    rows = build(folder)
    out = os.path.join(folder, "features.csv")
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["member_id", "label", "strategy", "family", "group", "source"] + FEATURES)
        w.writeheader()
        for r in rows:
            w.writerow({k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()})
    labels = defaultdict(int)
    for r in rows:
        labels[r["label"]] += 1
    print(f"계정 {len(rows)} → {out}  ({', '.join(f'{k} {v}' for k, v in sorted(labels.items()))})")


if __name__ == "__main__":
    main()
