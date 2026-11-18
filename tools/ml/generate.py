#!/usr/bin/env python3
"""학습용 행동 데이터 생성기 (봇 대회 제안서 "학습용 데이터는 만들어 쓰고, 시험용 데이터는 손대지 않습니다").

실제 행동 로그(MongoDB behavior_log)와 같은 모양의 이벤트를 만든다. 그래서 대회 데이터와 **같은 특징 추출기**
(features.py)를 거쳐 학습·시험이 같은 기준으로 비교된다.

    python3 -I tools/ml/generate.py --members 5000 --seed 7 --out data/ml/gen-s7
    → events.jsonl (행동 로그 1줄 = 1문서) · members.jsonl (정답 라벨·수법·연결 정보)

주의 (제안서 규칙)
  - 이 파일의 수법 목록은 대회가 끝날 때까지 공격자에게 보여 주지 않는다 (loadtest/contest/README.md "보지 않기")
  - 생성 데이터로는 **학습만** 한다. 시험은 대회 데이터(블라인드 봇 + 사람)로만
  - 사람 쪽도 지어낸 것이다. 실제 사람 데이터가 모이면 사람 수법의 숫자를 그 분포로 다시 맞춘다

외부 라이브러리 없음 (파이썬 기본 모듈만).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta

# 판매 오픈 = T0. 계정 수천 명이 한 운행(1,050석)에 몰리면 대부분 좌석이 없어 행동이 단조로워진다.
# 그래서 좌석은 여러 운행에 걸친 큰 풀로 둔다 (좌석 번호 자체는 특징에 쓰지 않는다)
SEAT_POOL = list(range(100_001, 160_001))
LIMIT_PER_RUN = 4  # 대회 기본 매수 제한 (특징은 이 값에 대한 비율로 쓴다)


def h(*parts) -> str:
    """가짜 해시 (실제 로그의 HMAC-SHA256 자리). 같은 입력이면 같은 값"""
    return hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()


@dataclass
class Account:
    member_id: int
    label: str          # NORMAL / MACRO / RESELLER / CANCEL_ABUSER (synthetic_label.user_type)
    strategy: str       # 수법 이름 (학습 분석용, 서버로는 안 감)
    group: int          # 같은 사람·조직이 쓰는 계정끼리 같은 값
    ci: str
    device: str | None  # None = 기기 ID 헤더를 뺀 봇
    ip: str
    cards: list[str] = field(default_factory=list)
    created_days_ago: int = 0


class World:
    def __init__(self, seed: int, t0: datetime):
        self.r = random.Random(seed)
        self.t0 = t0
        self.events: list[dict] = []
        self.accounts: list[Account] = []
        self.next_member = 900000
        self.next_resv = 5_000_000
        self.taken: set[int] = set()   # 이미 팔린 좌석 (409를 만들기 위해)

    # ------------------------------------------------------------ 계정

    def account(self, label, strategy, group, *, device="own", ip="own", card="own", age=None) -> Account:
        self.next_member += 1
        m = self.next_member
        r = self.r
        acc = Account(
            member_id=m, label=label, strategy=strategy, group=group,
            ci=h("ci", m),
            device=None if device is None else h("dev", m if device == "own" else device),
            ip=h("ip", m if ip == "own" else ip),
            cards=[h("card", m if card == "own" else card)],
            created_days_ago=age if age is not None else int(r.expovariate(1 / 300)),
        )
        self.accounts.append(acc)
        return acc

    # ------------------------------------------------------------ 이벤트

    def emit(self, acc: Account, t: datetime, etype: str, status: int = 200, **meta):
        self.events.append({
            "member_id": acc.member_id, "ci_hash": acc.ci, "device_hash": acc.device, "ip_hash": acc.ip,
            "event_type": etype, "run_id": 1, "ts": t.isoformat(timespec="milliseconds"),
            "response_status": status, "meta": meta,
        })

    def free_seats(self, n: int, *, together=False) -> list[int]:
        if len(self.taken) + n > len(SEAT_POOL) * 0.9:
            return []
        for _ in range(1000):
            if together:
                start = self.r.choice(SEAT_POOL)
                seats = list(range(start, start + n))
            else:
                seats = [self.r.choice(SEAT_POOL) for _ in range(n)]
            if len(set(seats)) == n and not self.taken.intersection(seats):
                return seats
        return []

    def buy(self, acc: Account, t: datetime, n: int, gap, *, browse=0, retry_same=0, retry_other=0,
            pay=True, together=False) -> tuple[datetime, int | None]:
        """조회 → 대기열 → 좌석 → 선점(실패 포함) → 결제. gap() = 다음 행동까지 초. (끝난 시각, 예약 ID)"""
        for _ in range(browse):
            self.emit(acc, t, "VIEW_TRAIN")
            t += timedelta(seconds=gap())
        self.emit(acc, t, "QUEUE_ENTER", ticketNo=self.r.randint(1, 50000), rejected=False)
        t += timedelta(seconds=self.r.uniform(2, 90))       # 대기열에서 기다린 시간 (로그 없음)
        self.emit(acc, t, "VIEW_SEAT")
        t += timedelta(seconds=gap())
        # 남이 먼저 잡은 좌석을 노렸다가 실패 (같은 좌석 반복 / 다른 좌석)
        if retry_same:
            target = self.r.choice(SEAT_POOL)
            for _ in range(retry_same):
                self.emit(acc, t, "SEAT_HOLD", 409, seatIds=[target], seatCount=1, errorCode="SEAT_ALREADY_HELD")
                t += timedelta(seconds=gap())
        for _ in range(retry_other):
            s = self.r.choice(SEAT_POOL)
            self.emit(acc, t, "SEAT_HOLD", 409, seatIds=[s], seatCount=1, errorCode="SEAT_ALREADY_HELD")
            t += timedelta(seconds=gap())
            self.emit(acc, t, "VIEW_SEAT")
            t += timedelta(seconds=gap())
        seats = self.free_seats(n, together=together)
        if not seats:
            return t, None
        if n > LIMIT_PER_RUN:
            self.emit(acc, t, "SEAT_HOLD", 422, seatIds=seats, seatCount=n, errorCode="LIMIT_PER_RUN")
            return t + timedelta(seconds=gap()), None
        self.next_resv += 1
        resv = self.next_resv
        self.taken.update(seats)
        self.emit(acc, t, "SEAT_HOLD", 201, seatIds=seats, seatCount=n, reservationId=resv)
        if not pay:
            return t, resv
        t += timedelta(seconds=gap() + self.r.uniform(3, 20))  # 결제 화면에서 카드 넣는 시간
        # 실제 로그와 같은 모양: 예약 번호·결제 상태만 (매수·카드는 없음. 카드 연결은 members.jsonl의 cards로)
        self.emit(acc, t, "PAY", 201, reservationId=resv, paymentStatus="APPROVED")
        return t, resv

    def cancel(self, acc: Account, t: datetime, resv: int, seats: int):
        self.emit(acc, t, "CANCEL", 200, reservationId=resv)

    # ------------------------------------------------------------ 시간 모양

    def human_gap(self, lo=1.5, med=5.0, hi=60.0):
        """사람의 쉬는 시간: 로그 정규 (중앙값 med초, 가끔 길게)"""
        r = self.r
        return lambda: min(hi, max(lo, r.lognormvariate(math.log(med), 0.7)))

    def fixed_gap(self, sec, cv):
        r = self.r
        return lambda: max(0.01, r.gauss(sec, sec * cv))

    def uniform_gap(self, a, b):
        r = self.r
        return lambda: r.uniform(a, b)

    def arrival(self, spread_min=30.0, rush=0.6):
        """판매 오픈 뒤 들어오는 시각. rush 비율은 처음 몇 분에 몰린다"""
        r = self.r
        if r.random() < rush:
            return self.t0 + timedelta(seconds=r.expovariate(1 / 90))
        return self.t0 + timedelta(minutes=r.uniform(0, spread_min))


# ====================================================================== 수법
# 각 함수 = 한 사람(또는 한 조직). group 번호를 받아 계정을 만들고 행동한다.

def h_casual(w: World, g):
    a = w.account("NORMAL", "h_casual", g)
    w.buy(a, w.arrival(), w.r.choice([1, 1, 2, 2, 3, 4]), w.human_gap(), browse=w.r.randint(1, 5),
          retry_other=w.r.choice([0, 0, 0, 1]))


def h_quick(w: World, g):  # 손이 빠른 익숙한 사람
    a = w.account("NORMAL", "h_quick", g)
    w.buy(a, w.arrival(rush=0.9), w.r.choice([1, 2, 4]), w.human_gap(lo=0.4, med=1.4, hi=8),
          browse=w.r.randint(0, 2), retry_other=w.r.choice([0, 1, 2]))


def h_slow(w: World, g):  # 천천히 고르는 사람 (어르신·모바일)
    a = w.account("NORMAL", "h_slow", g)
    w.buy(a, w.arrival(rush=0.2, spread_min=120), w.r.choice([1, 2]), w.human_gap(lo=4, med=14, hi=120),
          browse=w.r.randint(2, 8))


def h_family_device(w: World, g):  # 가족이 기기 하나를 몇 시간 차로 같이 씀
    shared = f"fam{g}"
    t = w.arrival()
    for k in range(w.r.randint(2, 3)):
        a = w.account("NORMAL", "h_family_device", g, device=shared, ip=shared)
        w.buy(a, t, w.r.choice([1, 2, 3]), w.human_gap(), browse=w.r.randint(1, 4))
        t += timedelta(hours=w.r.uniform(1, 6))


def h_family_bulk(w: World, g):  # 가족 몫까지 한도 가득 (정상인데 많이 삼)
    a = w.account("NORMAL", "h_family_bulk", g)
    w.buy(a, w.arrival(), LIMIT_PER_RUN, w.human_gap(), browse=w.r.randint(1, 4), together=True)


def h_changed_mind(w: World, g):  # 사고 나서 한참 뒤 한 번 취소
    a = w.account("NORMAL", "h_changed_mind", g)
    n = w.r.choice([1, 2])
    t, resv = w.buy(a, w.arrival(), n, w.human_gap(), browse=w.r.randint(1, 4))
    if resv:
        w.cancel(a, t + timedelta(hours=w.r.uniform(2, 72)), resv, n)


def h_quick_regret(w: World, g):  # 결제 직후 실수를 알아채고 취소 (사람도 한다)
    a = w.account("NORMAL", "h_quick_regret", g)
    t, resv = w.buy(a, w.arrival(), 1, w.human_gap(), browse=w.r.randint(1, 3))
    if resv:
        w.cancel(a, t + timedelta(minutes=w.r.uniform(1, 15)), resv, 1)
        w.buy(a, t + timedelta(minutes=w.r.uniform(2, 20)), 1, w.human_gap(), browse=1)


def h_unlucky(w: World, g):  # 인기 좌석이 계속 먼저 팔려 여러 번 실패 (같은 좌석도 두어 번)
    a = w.account("NORMAL", "h_unlucky", g)
    w.buy(a, w.arrival(rush=0.95), w.r.choice([1, 2]), w.human_gap(lo=0.8, med=2.5),
          browse=w.r.randint(0, 2), retry_same=w.r.choice([0, 2, 3]), retry_other=w.r.randint(1, 4))


def h_new_member(w: World, g):  # 명절이라 오늘 가입한 사람
    a = w.account("NORMAL", "h_new_member", g, age=0)
    w.buy(a, w.arrival(), w.r.choice([1, 2]), w.human_gap(), browse=w.r.randint(1, 5))


def b_fast(w: World, g):  # 조회 연타
    a = w.account("MACRO", "b_fast", g)
    gap = w.uniform_gap(0.02, 0.15)
    w.buy(a, w.t0 + timedelta(seconds=w.r.uniform(0, 3)), w.r.choice([2, 4]), gap, browse=w.r.randint(20, 80))


def b_steady(w: World, g):  # 고정 간격 (sleep 하나)
    a = w.account("MACRO", "b_steady", g)
    sec = w.r.choice([0.3, 0.5, 0.8, 1.0, 1.5, 2.0])
    w.buy(a, w.t0 + timedelta(seconds=w.r.uniform(0, 5)), 4, w.fixed_gap(sec, w.r.uniform(0.001, 0.03)),
          browse=w.r.randint(10, 40), retry_other=w.r.randint(0, 5))


def b_jitter(w: World, g):  # 무작위 간격을 섞은 매크로 (고정 간격 탐지를 피하려고)
    a = w.account("MACRO", "b_jitter", g)
    lo = w.r.uniform(0.2, 1.0)
    w.buy(a, w.t0 + timedelta(seconds=w.r.uniform(0, 10)), 4, w.uniform_gap(lo, lo * w.r.uniform(1.5, 4)),
          browse=w.r.randint(5, 30), retry_other=w.r.randint(0, 6))


def b_sniper(w: World, g):  # 원하는 좌석 하나를 계속 두드림
    a = w.account("MACRO", "b_sniper", g)
    w.buy(a, w.t0 + timedelta(seconds=w.r.uniform(0, 2)), 1, w.uniform_gap(0.1, 0.8),
          browse=w.r.randint(0, 3), retry_same=w.r.randint(4, 30))


def b_multi_device(w: World, g):  # 한 기기에서 여러 계정이 몇 초 안에
    t = w.t0 + timedelta(seconds=w.r.uniform(0, 30))
    for _ in range(w.r.randint(2, 6)):
        a = w.account("RESELLER", "b_multi_device", g, device=f"md{g}", ip=f"md{g}")
        w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.3, 2.0), browse=w.r.randint(0, 2))
        t += timedelta(seconds=w.r.uniform(0.5, 20))


def b_multi_card(w: World, g):  # 기기는 다르게, 카드 하나로 결제
    t = w.t0 + timedelta(seconds=w.r.uniform(0, 30))
    for _ in range(w.r.randint(2, 6)):
        a = w.account("RESELLER", "b_multi_card", g, card=f"mc{g}", ip=f"mc{g}")
        w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.3, 2.0), browse=w.r.randint(0, 2))
        t += timedelta(seconds=w.r.uniform(1, 40))


def b_distributed(w: World, g):  # 명의·기기·카드를 다 나누고 사람처럼 천천히, 시간도 흩음 (가장 어려움)
    t = w.t0 + timedelta(seconds=w.r.uniform(0, 60))
    for _ in range(w.r.randint(3, 6)):
        a = w.account("RESELLER", "b_distributed", g, ip=f"ds{g}" if w.r.random() < 0.7 else "own",
                      age=w.r.randint(0, 3))
        w.buy(a, t, LIMIT_PER_RUN, w.human_gap(lo=0.3, med=1.5, hi=8), browse=w.r.choice([0, 0, 1]))
        t += timedelta(seconds=w.r.uniform(75, 200))


def b_noheader(w: World, g):  # 기기 ID 헤더를 빼고 오는 다계정
    t = w.t0 + timedelta(seconds=w.r.uniform(0, 30))
    for _ in range(w.r.randint(2, 5)):
        a = w.account("RESELLER", "b_noheader", g, device=None, ip=f"nh{g}")
        w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.5, 3.0), browse=0)
        t += timedelta(seconds=w.r.uniform(5, 90))


def b_limit_prober(w: World, g):  # 한도를 모르고 많이 시도 → 422 뒤 낮춤
    a = w.account("RESELLER", "b_limit_prober", g)
    t = w.t0 + timedelta(seconds=w.r.uniform(0, 10))
    gap = w.uniform_gap(0.2, 1.0)
    for n in (9, 8, 6):
        t, _ = w.buy(a, t, n, gap, browse=0)
    w.buy(a, t, LIMIT_PER_RUN, gap, browse=0)


def b_cancel_rebuy(w: World, g):  # 사고 바로 취소하고 다시 사기 (좌석 돌리기)
    a = w.account("CANCEL_ABUSER", "b_cancel_rebuy", g)
    t = w.t0 + timedelta(seconds=w.r.uniform(0, 60))
    for _ in range(w.r.randint(2, 6)):
        t, resv = w.buy(a, t, 2, w.uniform_gap(0.3, 2.0), browse=0)
        if not resv:
            break
        t += timedelta(minutes=w.r.uniform(0.2, 8))
        w.cancel(a, t, resv, 2)
        t += timedelta(seconds=w.r.uniform(5, 60))


# (수법, 라벨 쪽, 비중). 비중은 상대값
STRATEGIES = [
    (h_casual, "human", 40), (h_quick, "human", 10), (h_slow, "human", 10), (h_family_device, "human", 4),
    (h_family_bulk, "human", 6), (h_changed_mind, "human", 6), (h_quick_regret, "human", 2),
    (h_unlucky, "human", 6), (h_new_member, "human", 6),
    (b_fast, "bot", 2), (b_steady, "bot", 2), (b_jitter, "bot", 2), (b_sniper, "bot", 2),
    (b_multi_device, "bot", 1), (b_multi_card, "bot", 1), (b_distributed, "bot", 1),
    (b_noheader, "bot", 1), (b_limit_prober, "bot", 1), (b_cancel_rebuy, "bot", 2),
]


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--members", type=int, default=5000, help="대략적인 계정 수 (다계정 수법 때문에 조금 넘을 수 있음)")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--bot-share", type=float, default=0.15, help="봇 쪽 비중 (사람 대비)")
    p.add_argument("--out", required=True, help="출력 폴더 (events.jsonl · members.jsonl)")
    a = p.parse_args()

    w = World(a.seed, datetime(2026, 11, 23, 10, 0, 0))
    human = [(f, wt) for f, side, wt in STRATEGIES if side == "human"]
    bot = [(f, wt) for f, side, wt in STRATEGIES if side == "bot"]
    g = 0
    while len(w.accounts) < a.members:
        g += 1
        pool = bot if w.r.random() < a.bot_share else human
        fn = w.r.choices([f for f, _ in pool], weights=[x for _, x in pool])[0]
        fn(w, g)

    os.makedirs(a.out, exist_ok=True)
    w.events.sort(key=lambda e: (e["member_id"], e["ts"]))
    last: dict[int, str] = {}
    with open(os.path.join(a.out, "events.jsonl"), "w") as f:
        for e in w.events:   # interval_ms = 같은 회원의 앞 이벤트와의 간격 (실제 로그와 같은 정의)
            prev = last.get(e["member_id"])
            e["interval_ms"] = None if prev is None else int(
                (datetime.fromisoformat(e["ts"]) - datetime.fromisoformat(prev)).total_seconds() * 1000)
            last[e["member_id"]] = e["ts"]
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    with open(os.path.join(a.out, "members.jsonl"), "w") as f:
        for acc in w.accounts:
            f.write(json.dumps(acc.__dict__ | {"source": "generator", "seed": a.seed}, ensure_ascii=False) + "\n")

    by: dict[str, int] = {}
    for acc in w.accounts:
        by[acc.strategy] = by.get(acc.strategy, 0) + 1
    print(f"계정 {len(w.accounts)} · 이벤트 {len(w.events)} → {a.out}")
    for k in sorted(by, key=lambda s: (s[0], -by[s])):
        print(f"  {k:18s} {by[k]}")


if __name__ == "__main__":
    main()
