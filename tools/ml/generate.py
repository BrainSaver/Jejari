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
import sys

# Windows(Git Bash) 기본 인코딩이 CP949라 한글·기호 출력에서 멈추지 않게 UTF-8로 내보낸다
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
from dataclasses import dataclass, field
from datetime import datetime, timedelta

# 판매 오픈 = T0. 계정 수천 명이 한 운행(1,050석)에 몰리면 대부분 좌석이 없어 행동이 단조로워진다.
# 그래서 좌석은 여러 운행에 걸친 큰 풀로 둔다 (좌석 번호 자체는 특징에 쓰지 않는다)
SEAT_POOL = list(range(100_001, 160_001))
LIMIT_PER_RUN = 4  # 대회 기본 매수 제한 (특징은 이 값에 대한 비율로 쓴다)

# 실제 사람 데이터로 맞춘 "퍼짐" (tools/ml/human_calibration.json, UCI Online Shoppers Purchasing Intention,
# 구매로 끝난 실제 세션 1,908개). 쇼핑몰과 예매는 도메인이 달라 절대값은 쓰지 않고 사람 사이의 퍼짐만 가져온다
#   - 사람마다 속도가 다르다: 페이지당 머문 시간의 로그 표준편차 0.53 (약 ±70%)
#   - 사람마다 둘러보는 양이 크게 다르다: 페이지 수의 로그 표준편차 0.99 (하위 5%는 중앙값의 약 1/5)
HUMAN_SPEED_SIGMA = 0.53
# 실제 대회 계정의 행동 프로필 (tools/ml/calibrate_real.py → real_calibration.json). ML_REAL_CALIB=<경로>일 때만 쓴다
# (기본 꺼짐 = 지금까지의 학습 데이터와 같게). 켜면 그 프로필 둘레로 사람(h_focused)·봇(b_real_mimic·b_real_macro)을 더 만든다
REAL = None
if os.environ.get("ML_REAL_CALIB"):
    with open(os.environ["ML_REAL_CALIB"], encoding="utf-8") as _f:
        REAL = json.load(_f)
HUMAN_BROWSE_SIGMA = 0.99


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
    family: str = ""    # 수법 계열 (생성 데이터 안에서 "한 계열을 통째로 빼고 학습 → 그 계열로 시험"용)
    device_pool: list | None = None  # 요청마다 이 중 하나 ("rotate" = 매번 새 기기: 저장소가 막힌 브라우저 등)
    ip_pool: list | None = None      # 요청마다 이 중 하나 ("rotate" = 매번 새 IP: 프록시 회전)


class World:
    def __init__(self, seed: int, t0: datetime):
        self.r = random.Random(seed)
        self.t0 = t0
        self.events: list[dict] = []
        self.accounts: list[Account] = []
        self.next_member = 900000
        self.next_resv = 5_000_000
        self.taken: set[int] = set()   # 이미 팔린 좌석 (409를 만들기 위해)
        self.resv_seats: dict[int, list[int]] = {}
        # 운행 = (run_id, 판매 오픈 시각). 사흘 동안 하루 4편, 판매는 날마다 같은 시각에 열린다
        self.runs = [(d * 4 + k + 1, t0 + timedelta(days=d, minutes=k)) for d in range(3) for k in range(4)]
        self.cur_run = 1   # 지금 행동하는 수법이 노리는 운행 (main이 수법마다 고른다)

    @property
    def T(self) -> datetime:
        """지금 운행의 판매 오픈 시각"""
        return self.open_of(self.cur_run)

    # ------------------------------------------------------------ 계정

    def account(self, label, strategy, group, *, device="own", ip="own", card="own", age=None,
                device_pool=None, ip_pool=None) -> Account:
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
            family=FAMILY.get(strategy, "human" if label == "NORMAL" else "other"),
            device_pool=device_pool if device_pool in (None, "rotate") else [h("dev", d) for d in device_pool],
            ip_pool=ip_pool if ip_pool in (None, "rotate") else [h("ip", i) for i in ip_pool],
        )
        self.accounts.append(acc)
        return acc

    # ------------------------------------------------------------ 이벤트

    def pick(self, pool, default):
        if pool == "rotate":
            return h("rot", self.r.random())
        return self.r.choice(pool) if pool else default

    def emit(self, acc: Account, t: datetime, etype: str, status: int = 200, run: int = 1, **meta):
        self.events.append({
            "member_id": acc.member_id, "ci_hash": acc.ci,
            "device_hash": self.pick(acc.device_pool, acc.device) if acc.device is not None else None,
            "ip_hash": self.pick(acc.ip_pool, acc.ip),
            "event_type": etype, "run_id": run, "ts": t.isoformat(timespec="milliseconds"),
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

    # 사람이 좌석표를 보고(또는 실패를 보고) 고르고 누르기까지. [가정] 대회 사람 데이터로 다시 맞춘다
    # ML_PICK_FLOOR=1일 때만 쓴다 (기본 꺼짐 = 대회 전 모델의 학습 데이터와 같게). 꺼 두면 사람도 0.25초 만에 고를 수 있다
    PICK_FLOOR_S, PICK_MED_S = 1.0, 3.0

    def buy(self, acc: Account, t: datetime, n: int, gap, *, browse=0, retry_same=0, retry_other=0,
            pay=True, together=False, run=None, refresh=0, want=None, hold_gap=None, queue=(2, 90),
            pick_gap=None, pay_gap=None) -> tuple[datetime, int | None]:
        """조회 → 대기열 → 좌석 → 선점(실패 포함) → 결제. gap() = 다음 행동까지 초. (끝난 시각, 예약 ID)
        refresh = 좌석 배치도 새로 고침 횟수, want = 꼭 이 좌석 (넘겨받기), hold_gap = 선점 시도 사이만 다른 속도
        pick_gap = 좌석표 → 선점, pay_gap = 선점 → 결제를 따로 (실제 데이터 둘레 수법이 실제 단계 시간을 넣을 때)"""
        run = run or self.cur_run
        E = lambda *a, **k: self.emit(*a, run=run, **k)
        hg = hold_gap or gap
        human = acc.label == "NORMAL" and os.environ.get("ML_PICK_FLOOR") == "1"
        if human:
            pick_med = self.PICK_MED_S * self.r.lognormvariate(0, HUMAN_SPEED_SIGMA)
            pick = lambda: max(self.PICK_FLOOR_S, self.r.lognormvariate(math.log(pick_med), 0.5))
            hgp = lambda: max(pick(), hg())
        else:
            pick, hgp = gap, hg
        for _ in range(browse):
            E(acc, t, "VIEW_TRAIN")
            t += timedelta(seconds=gap())
        E(acc, t, "QUEUE_ENTER", ticketNo=self.r.randint(1, 50000), rejected=False)
        t += timedelta(seconds=self.r.uniform(*queue))      # 대기열에서 기다린 시간 (로그 없음)
        E(acc, t, "VIEW_SEAT", availableCount=self.r.randint(0, 900))
        t += timedelta(seconds=(pick_gap() if pick_gap and not refresh else pick() if human and not refresh else gap()))
        for i in range(refresh):
            E(acc, t, "VIEW_SEAT", availableCount=self.r.randint(0, 900))
            t += timedelta(seconds=(pick() if human and i == refresh - 1 else gap()))
        # 남이 먼저 잡은 좌석을 노렸다가 실패 (같은 좌석 반복 / 다른 좌석)
        if retry_same:
            target = self.r.choice(SEAT_POOL)
            for _ in range(retry_same):
                E(acc, t, "SEAT_HOLD", 409, seatIds=[target], seatCount=1, errorCode="SEAT_ALREADY_HELD")
                t += timedelta(seconds=hgp())
        for _ in range(retry_other):
            s = self.r.choice(SEAT_POOL)
            E(acc, t, "SEAT_HOLD", 409, seatIds=[s], seatCount=1, errorCode="SEAT_ALREADY_HELD")
            t += timedelta(seconds=hg())
            E(acc, t, "VIEW_SEAT", availableCount=self.r.randint(0, 900))
            t += timedelta(seconds=pick() if human else gap())
        if want and not self.taken.intersection(want):
            seats = list(want)
        else:
            seats = self.free_seats(n, together=together)
        if not seats:
            return t, None
        if len(seats) > LIMIT_PER_RUN:
            E(acc, t, "SEAT_HOLD", 422, seatIds=seats, seatCount=len(seats), errorCode="LIMIT_PER_RUN")
            return t + timedelta(seconds=gap()), None
        self.next_resv += 1
        resv = self.next_resv
        self.taken.update(seats)
        self.resv_seats[resv] = seats
        E(acc, t, "SEAT_HOLD", 201, seatIds=seats, seatCount=len(seats), reservationId=resv)
        if not pay:
            return t, resv
        t += timedelta(seconds=pay_gap() if pay_gap else gap() + self.r.uniform(3, 20))  # 결제 화면에서 카드 넣는 시간
        # 실제 로그와 같은 모양: 예약 번호·결제 상태만 (매수·카드는 없음. 카드 연결은 members.jsonl의 cards로)
        E(acc, t, "PAY", 201, reservationId=resv, paymentStatus="APPROVED")
        return t, resv

    def cancel(self, acc: Account, t: datetime, resv: int, seats: int = 0, run=None):
        """취소하면 좌석이 다시 풀린다 (다른 계정이 바로 잡을 수 있다 = 넘겨주기)"""
        self.emit(acc, t, "CANCEL", 200, run=run or self.cur_run, reservationId=resv)
        self.taken.difference_update(self.resv_seats.get(resv, []))

    # ------------------------------------------------------------ 시간 모양

    def human_gap(self, lo=1.5, med=5.0, hi=60.0):
        """사람의 쉬는 시간: 로그 정규 (중앙값 med초, 가끔 길게). 부를 때마다 이 사람의 속도를 새로 뽑는다
        (실제 사람 데이터의 개인차 HUMAN_SPEED_SIGMA). 사람을 흉내 내는 봇도 같은 함수를 쓴다"""
        r = self.r
        med = med * r.lognormvariate(0, HUMAN_SPEED_SIGMA)
        lo = min(lo, med / 2)
        return lambda: min(hi, max(lo, r.lognormvariate(math.log(med), 0.7)))

    def browse(self, lo, hi):
        """사람이 운행을 둘러보는 횟수. 중앙값은 (lo+hi)/2, 퍼짐은 실제 사람 데이터(HUMAN_BROWSE_SIGMA).
        그래서 거의 안 둘러보고 바로 사는 사람도, 한참 비교하는 사람도 생긴다"""
        med = max(0.5, (lo + hi) / 2)
        return min(20, int(round(self.r.lognormvariate(math.log(med), HUMAN_BROWSE_SIGMA))))

    def fixed_gap(self, sec, cv):
        r = self.r
        return lambda: max(0.01, r.gauss(sec, sec * cv))

    def uniform_gap(self, a, b):
        r = self.r
        return lambda: r.uniform(a, b)

    def arrival(self, spread_min=30.0, rush=0.6, run=None):
        """판매 오픈 뒤 들어오는 시각. rush 비율은 처음 몇 분에 몰린다"""
        r = self.r
        t0 = self.open_of(run or self.cur_run)
        if r.random() < rush:
            return t0 + timedelta(seconds=r.expovariate(1 / 90))
        return t0 + timedelta(minutes=r.uniform(0, spread_min))

    def open_of(self, run):
        return dict(self.runs).get(run, self.t0)

    def some_run(self):
        return self.r.choice(self.runs)[0]


# ====================================================================== 수법
# 각 함수 = 한 사람(또는 한 조직). group 번호를 받아 계정을 만들고 행동한다.

def h_casual(w: World, g):
    a = w.account("NORMAL", "h_casual", g)
    w.buy(a, w.arrival(), w.r.choice([1, 1, 2, 2, 3, 4]), w.human_gap(), browse=w.browse(1, 5),
          retry_other=w.r.choice([0, 0, 0, 1]))


def h_quick(w: World, g):  # 손이 빠른 익숙한 사람
    a = w.account("NORMAL", "h_quick", g)
    w.buy(a, w.arrival(rush=0.9), w.r.choice([1, 2, 4]), w.human_gap(lo=0.4, med=1.4, hi=8),
          browse=w.browse(0, 2), retry_other=w.r.choice([0, 1, 2]))


def h_slow(w: World, g):  # 천천히 고르는 사람 (어르신·모바일)
    a = w.account("NORMAL", "h_slow", g)
    w.buy(a, w.arrival(rush=0.2, spread_min=120), w.r.choice([1, 2]), w.human_gap(lo=4, med=14, hi=120),
          browse=w.browse(2, 8))


def h_family_device(w: World, g):  # 가족이 기기 하나를 몇 시간 차로 같이 씀
    shared = f"fam{g}"
    t = w.arrival()
    for k in range(w.r.randint(2, 3)):
        a = w.account("NORMAL", "h_family_device", g, device=shared, ip=shared)
        w.buy(a, t, w.r.choice([1, 2, 3]), w.human_gap(), browse=w.browse(1, 4))
        t += timedelta(hours=w.r.uniform(1, 6))


def h_family_bulk(w: World, g):  # 가족 몫까지 한도 가득 (정상인데 많이 삼)
    a = w.account("NORMAL", "h_family_bulk", g)
    w.buy(a, w.arrival(), LIMIT_PER_RUN, w.human_gap(), browse=w.browse(1, 4), together=True)


def h_changed_mind(w: World, g):  # 사고 나서 한참 뒤 한 번 취소
    a = w.account("NORMAL", "h_changed_mind", g)
    n = w.r.choice([1, 2])
    t, resv = w.buy(a, w.arrival(), n, w.human_gap(), browse=w.browse(1, 4))
    if resv:
        w.cancel(a, t + timedelta(hours=w.r.uniform(2, 72)), resv, n)


def h_quick_regret(w: World, g):  # 결제 직후 실수를 알아채고 취소 (사람도 한다)
    a = w.account("NORMAL", "h_quick_regret", g)
    t, resv = w.buy(a, w.arrival(), 1, w.human_gap(), browse=w.browse(1, 3))
    if resv:
        w.cancel(a, t + timedelta(minutes=w.r.uniform(1, 15)), resv, 1)
        w.buy(a, t + timedelta(minutes=w.r.uniform(2, 20)), 1, w.human_gap(), browse=1)


def h_unlucky(w: World, g):  # 인기 좌석이 계속 먼저 팔려 여러 번 실패 (같은 좌석도 두어 번)
    a = w.account("NORMAL", "h_unlucky", g)
    w.buy(a, w.arrival(rush=0.95), w.r.choice([1, 2]), w.human_gap(lo=0.8, med=2.5),
          browse=w.browse(0, 2), retry_same=w.r.choice([0, 2, 3]), retry_other=w.r.randint(1, 4))


def h_new_member(w: World, g):  # 명절이라 오늘 가입한 사람
    a = w.account("NORMAL", "h_new_member", g, age=0)
    w.buy(a, w.arrival(), w.r.choice([1, 2]), w.human_gap(), browse=w.browse(1, 5))


def b_fast(w: World, g):  # 조회 연타
    a = w.account("MACRO", "b_fast", g)
    gap = w.uniform_gap(0.02, 0.15)
    w.buy(a, w.T + timedelta(seconds=w.r.uniform(0, 3)), w.r.choice([2, 4]), gap, browse=w.r.randint(20, 80))


def b_steady(w: World, g):  # 고정 간격 (sleep 하나)
    a = w.account("MACRO", "b_steady", g)
    sec = w.r.choice([0.3, 0.5, 0.8, 1.0, 1.5, 2.0])
    w.buy(a, w.T + timedelta(seconds=w.r.uniform(0, 5)), 4, w.fixed_gap(sec, w.r.uniform(0.001, 0.03)),
          browse=w.r.randint(10, 40), retry_other=w.r.randint(0, 5))


def b_jitter(w: World, g):  # 무작위 간격을 섞은 매크로 (고정 간격 탐지를 피하려고)
    a = w.account("MACRO", "b_jitter", g)
    lo = w.r.uniform(0.2, 1.0)
    w.buy(a, w.T + timedelta(seconds=w.r.uniform(0, 10)), 4, w.uniform_gap(lo, lo * w.r.uniform(1.5, 4)),
          browse=w.r.randint(5, 30), retry_other=w.r.randint(0, 6))


def b_sniper(w: World, g):  # 원하는 좌석 하나를 계속 두드림
    a = w.account("MACRO", "b_sniper", g)
    w.buy(a, w.T + timedelta(seconds=w.r.uniform(0, 2)), 1, w.uniform_gap(0.1, 0.8),
          browse=w.r.randint(0, 3), retry_same=w.r.randint(4, 30))


def b_multi_device(w: World, g):  # 한 기기에서 여러 계정이 몇 초 안에
    t = w.T + timedelta(seconds=w.r.uniform(0, 30))
    for _ in range(w.r.randint(2, 6)):
        a = w.account("RESELLER", "b_multi_device", g, device=f"md{g}", ip=f"md{g}")
        w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.3, 2.0), browse=w.r.randint(0, 2))
        t += timedelta(seconds=w.r.uniform(0.5, 20))


def b_multi_card(w: World, g):  # 기기는 다르게, 카드 하나로 결제
    t = w.T + timedelta(seconds=w.r.uniform(0, 30))
    for _ in range(w.r.randint(2, 6)):
        a = w.account("RESELLER", "b_multi_card", g, card=f"mc{g}", ip=f"mc{g}")
        w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.3, 2.0), browse=w.r.randint(0, 2))
        t += timedelta(seconds=w.r.uniform(1, 40))


def b_distributed(w: World, g):  # 명의·기기·카드를 다 나누고 사람처럼 천천히, 시간도 흩음 (가장 어려움)
    t = w.T + timedelta(seconds=w.r.uniform(0, 60))
    for _ in range(w.r.randint(3, 6)):
        a = w.account("RESELLER", "b_distributed", g, ip=f"ds{g}" if w.r.random() < 0.7 else "own",
                      age=w.r.randint(0, 3))
        w.buy(a, t, LIMIT_PER_RUN, w.human_gap(lo=0.3, med=1.5, hi=8), browse=w.r.choice([0, 0, 1]))
        t += timedelta(seconds=w.r.uniform(75, 200))


def b_noheader(w: World, g):  # 기기 ID 헤더를 빼고 오는 다계정
    t = w.T + timedelta(seconds=w.r.uniform(0, 30))
    for _ in range(w.r.randint(2, 5)):
        a = w.account("RESELLER", "b_noheader", g, device=None, ip=f"nh{g}")
        w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.5, 3.0), browse=0)
        t += timedelta(seconds=w.r.uniform(5, 90))


def b_limit_prober(w: World, g):  # 한도를 모르고 많이 시도 → 422 뒤 낮춤
    a = w.account("RESELLER", "b_limit_prober", g)
    t = w.T + timedelta(seconds=w.r.uniform(0, 10))
    gap = w.uniform_gap(0.2, 1.0)
    for n in (9, 8, 6):
        t, _ = w.buy(a, t, n, gap, browse=0)
    w.buy(a, t, LIMIT_PER_RUN, gap, browse=0)


def b_cancel_rebuy(w: World, g):  # 사고 바로 취소하고 다시 사기 (좌석 돌리기)
    a = w.account("CANCEL_ABUSER", "b_cancel_rebuy", g)
    t = w.T + timedelta(seconds=w.r.uniform(0, 60))
    for _ in range(w.r.randint(2, 6)):
        t, resv = w.buy(a, t, 2, w.uniform_gap(0.3, 2.0), browse=0)
        if not resv:
            break
        t += timedelta(minutes=w.r.uniform(0.2, 8))
        w.cancel(a, t, resv, 2)
        t += timedelta(seconds=w.r.uniform(5, 60))


# ---------------------------------------------------------------- 사람 — 오탐을 부르는 정상 행동 (함정)

def h_office(w: World, g):  # 회사·학교 공용 IP 뒤의 여러 사람 (각자 기기)
    for _ in range(w.r.randint(4, 15)):
        a = w.account("NORMAL", "h_office", g, ip=f"of{g}")
        w.buy(a, w.arrival(rush=0.5), w.r.choice([1, 2, 2, 3]), w.human_gap(), browse=w.browse(1, 4))


def h_pc_bang(w: World, g):  # PC방·도서관 공용 PC: 몇 분 차이로 다른 사람이 같은 기기
    t = w.arrival(rush=0.8)
    for _ in range(w.r.randint(2, 4)):
        a = w.account("NORMAL", "h_pc_bang", g, device=f"pc{g}", ip=f"pc{g}")
        w.buy(a, t, w.r.choice([1, 2]), w.human_gap(lo=1, med=3.5), browse=w.browse(0, 3))
        t += timedelta(minutes=w.r.uniform(2, 25))


def h_organizer(w: World, g):  # 단체 여행 총무: 여러 날·여러 편에 한도까지 (정상 대량 구매)
    a = w.account("NORMAL", "h_organizer", g)
    for run in w.r.sample([r for r, _ in w.runs], w.r.randint(2, 5)):
        w.buy(a, w.arrival(rush=0.4, run=run), LIMIT_PER_RUN, w.human_gap(), browse=w.browse(1, 4),
              together=True, run=run)


def h_open_rusher(w: World, g):  # 오픈 순간 정신없이 누르는 사람 (빠르고 실패 많음)
    a = w.account("NORMAL", "h_open_rusher", g)
    w.buy(a, w.T + timedelta(seconds=w.r.uniform(0, 4)), w.r.choice([1, 2, 4]),
          w.human_gap(lo=0.25, med=0.8, hi=4), browse=w.browse(0, 2), retry_same=w.r.choice([0, 1, 2, 3]),
          retry_other=w.r.randint(2, 8), queue=(0.5, 20))


def h_refresher(w: World, g):  # 좌석 배치도를 계속 새로 고치며 창가 자리를 기다림
    a = w.account("NORMAL", "h_refresher", g)
    w.buy(a, w.arrival(), w.r.choice([1, 2]), w.human_gap(lo=2, med=6), browse=w.browse(1, 3),
          refresh=w.r.randint(5, 25))


def h_private_browser(w: World, g):  # 저장소가 막힌 브라우저: 화면을 열 때마다 기기 ID가 새로 생김
    a = w.account("NORMAL", "h_private_browser", g, device_pool="rotate")
    w.buy(a, w.arrival(), w.r.choice([1, 2]), w.human_gap(), browse=w.browse(1, 4))


def h_two_devices(w: World, g):  # 휴대폰으로 보다가 PC로 결제 (한 사람, 기기 둘, IP 둘)
    a = w.account("NORMAL", "h_two_devices", g, device_pool=[f"ph{g}", f"pcx{g}"], ip_pool=[f"lte{g}", f"home{g}"])
    w.buy(a, w.arrival(), w.r.choice([1, 2, 3]), w.human_gap(), browse=w.browse(2, 6))


def h_vpn(w: World, g):  # 개인정보 보호용 VPN: IP가 자주 바뀜
    a = w.account("NORMAL", "h_vpn", g, ip_pool="rotate")
    w.buy(a, w.arrival(), w.r.choice([1, 2]), w.human_gap(), browse=w.browse(1, 4))


# ---------------------------------------------------------------- 봇 — 더 어렵거나 색다른 수법

def b_open_ms(w: World, g):  # 오픈 시각에 밀리초 단위로 맞춰 들어와 군더더기 없이 끝냄
    a = w.account("MACRO", "b_open_ms", g)
    w.buy(a, w.T + timedelta(milliseconds=w.r.uniform(0, 150)), LIMIT_PER_RUN, w.uniform_gap(0.05, 0.2),
          browse=0, queue=(0.2, 3))


def b_assist(w: World, g):  # 사람이 둘러보다가 선점 단계만 매크로 (선점 시도만 순간 연타)
    a = w.account("MACRO", "b_assist", g)
    w.buy(a, w.arrival(rush=0.9), LIMIT_PER_RUN, w.human_gap(), browse=w.r.randint(1, 4),
          retry_other=w.r.randint(5, 20), hold_gap=w.uniform_gap(0.03, 0.08))


def b_ip_rotator(w: World, g):  # 요청마다 IP를 바꾸는 연타 봇
    a = w.account("MACRO", "b_ip_rotator", g, ip_pool="rotate")
    w.buy(a, w.T + timedelta(seconds=w.r.uniform(0, 5)), LIMIT_PER_RUN, w.uniform_gap(0.05, 0.4),
          browse=w.r.randint(10, 40), retry_other=w.r.randint(0, 6))


def b_human_mimic(w: World, g):  # 오래된 계정 여러 개가 사람 속도·둘러보기까지 흉내, IP도 돌림
    t = w.T + timedelta(seconds=w.r.uniform(0, 40))
    for _ in range(w.r.randint(3, 6)):
        a = w.account("RESELLER", "b_human_mimic", g, age=w.r.randint(90, 700), ip_pool="rotate")
        w.buy(a, t, LIMIT_PER_RUN, w.human_gap(lo=0.8, med=3, hi=20), browse=w.r.randint(1, 4),
              retry_other=w.r.choice([0, 1]))
        t += timedelta(seconds=w.r.uniform(20, 240))


def b_sleeper_farm(w: World, g):  # 몇 달 묵혀 둔 계정들이 오픈 몇 초 안에 한꺼번에 깨어남 (기기는 다 다름)
    t = w.T
    for _ in range(w.r.randint(4, 6)):
        a = w.account("RESELLER", "b_sleeper_farm", g, age=w.r.randint(150, 900),
                      ip_pool=[f"sf{g}-{k}" for k in range(w.r.randint(1, 3))])
        w.buy(a, t + timedelta(seconds=w.r.uniform(0, 6)), LIMIT_PER_RUN, w.uniform_gap(0.3, 1.5), browse=0,
              queue=(0.5, 15))


def b_slow_drip(w: World, g):  # 몇 시간에 걸쳐 조금씩 (계정마다 1~2석, 사람 속도)
    t = w.arrival(rush=0.1, spread_min=60)
    for _ in range(w.r.randint(4, 6)):
        a = w.account("RESELLER", "b_slow_drip", g, ip=f"sd{g}", age=w.r.randint(5, 200))
        w.buy(a, t, w.r.choice([1, 2]), w.human_gap(), browse=w.r.randint(1, 3))
        t += timedelta(minutes=w.r.uniform(20, 120))


def b_seat_handoff(w: World, g):  # 되팔이의 좌석 넘겨주기: 산 좌석을 취소한 몇 초 뒤 손님 계정이 그 좌석을 잡음
    seller = w.account("RESELLER", "b_seat_handoff", g, ip=f"ho{g}")
    t, resv = w.buy(seller, w.T + timedelta(seconds=w.r.uniform(0, 30)), LIMIT_PER_RUN, w.uniform_gap(0.3, 1.5))
    if not resv:
        return
    seats = list(w.resv_seats[resv])
    t += timedelta(hours=w.r.uniform(1, 48))   # 바깥에서 손님을 구하는 시간
    for k in range(w.r.randint(1, 2)):          # 손님 한두 명에게 나눠 넘김
        part = seats[: len(seats) // 2] if k == 0 and len(seats) > 1 and w.r.random() < 0.5 else seats
        buyer = w.account("RESELLER", "b_seat_handoff", g, ip=f"ho{g}" if w.r.random() < 0.5 else "own")
        w.cancel(seller, t, resv)
        w.buy(buyer, t + timedelta(seconds=w.r.uniform(0.5, 8)), len(part), w.uniform_gap(0.2, 1.0), want=part,
              queue=(0.2, 3))
        break


def b_hold_blocker(w: World, g):  # 좌석을 잡기만 하고 결제하지 않고 다시 잡기 (남이 못 사게 묶어 둠)
    a = w.account("MACRO", "b_hold_blocker", g)
    t = w.T + timedelta(seconds=w.r.uniform(0, 20))
    for _ in range(w.r.randint(3, 8)):
        t, resv = w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.3, 1.2), pay=False, queue=(0.2, 3))
        if not resv:
            break
        t += timedelta(minutes=w.r.uniform(4, 9.5))   # 결제 기한 직전에 놓아 줌
        w.cancel(a, t, resv)
        t += timedelta(seconds=w.r.uniform(1, 10))


def b_multi_run_hoarder(w: World, g):  # 한 명의로 여러 날·여러 편을 한도까지 빠르게
    a = w.account("RESELLER", "b_multi_run_hoarder", g)
    for run in w.r.sample([r for r, _ in w.runs], w.r.randint(4, 10)):
        w.buy(a, w.open_of(run) + timedelta(seconds=w.r.uniform(0, 20)), LIMIT_PER_RUN, w.uniform_gap(0.2, 1.0),
              run=run, queue=(0.5, 20))


def b_device_farm(w: World, g):  # 계정마다 기기 ID를 새로 만들지만 결제 카드는 몇 장으로 돌려씀
    cards = [f"df{g}-{k}" for k in range(w.r.randint(1, 2))]
    t = w.T + timedelta(seconds=w.r.uniform(0, 20))
    for _ in range(w.r.randint(3, 6)):
        a = w.account("RESELLER", "b_device_farm", g, card=w.r.choice(cards), ip_pool="rotate")
        w.buy(a, t, LIMIT_PER_RUN, w.uniform_gap(0.3, 2.0), browse=w.r.choice([0, 1]))
        t += timedelta(seconds=w.r.uniform(10, 120))


def b_burst_quiet(w: World, g):  # 짧게 몰아치고 한참 쉬었다 다시 (평균 간격으로는 사람처럼 보임)
    a = w.account("MACRO", "b_burst_quiet", g)
    t = w.T + timedelta(seconds=w.r.uniform(0, 10))
    for _ in range(w.r.randint(2, 4)):
        t, _ = w.buy(a, t, w.r.choice([1, 2]), w.uniform_gap(0.05, 0.3), browse=w.r.randint(8, 25), queue=(0.5, 10))
        t += timedelta(minutes=w.r.uniform(3, 30))


# ---------------------------------------------------------------- 실제 데이터 둘레 (ML_REAL_CALIB)

def real_gap(w: World, p, scale=1.0):
    """실제 계정 하나의 간격 모양(중앙값·p10·변동계수) 둘레로 간격을 뽑는다. 같은 사람이라도 매번 조금씩 다르게"""
    r = w.r
    # scale: 생성기는 대기열·카드 입력에 고정 시간을 더하므로(buy) 그만큼 줄여 실제 간격 중앙값에 맞춘다
    med = max(0.3, p["int_median_s"] * scale * r.lognormvariate(0, 0.25))
    cv = min(1.5, max(0.2, (p.get("int_cv") or 0.7) * r.lognormvariate(0, 0.2)))
    sigma = math.sqrt(math.log(1 + cv * cv))
    lo = max(0.25, min(med / 2, (p.get("int_p10_s") or med / 3) * r.uniform(0.7, 1.1)))
    return lambda: min(90.0, max(lo, r.lognormvariate(math.log(med), sigma)))


def real_stage(w: World, p, key, fallback):
    """단계 시간(log1p 초) 하나 둘레로 뽑는 함수. 프로필에 없으면 None (생성기 기본 방식)"""
    v = p.get(key)
    if v is None:
        return fallback
    med = max(0.3, math.expm1(v))
    return lambda: max(0.3, w.r.lognormvariate(math.log(med), 0.35))


def h_focused(w: World, g):  # 명절 예매에 집중한 실제 사람: 빠르고 거의 바로 산다 (2차 대회 실제 사람 둘레)
    p = w.r.choice(REAL["human"])
    a = w.account("NORMAL", "h_focused", g)
    browse = max(0, int(p["browse_before_hold"]) + w.r.choice([-1, 0, 0, 0, 1]))
    n = max(1, min(LIMIT_PER_RUN, int(p["seats"]) + w.r.choice([0, 0, 0, 1])))
    # 대회 실제 대기는 대부분 1~25초(입장 지연 포함)라 생성기 기본(2~90초)보다 짧다
    w.buy(a, w.arrival(rush=0.8), n, real_gap(w, p, 0.6), browse=browse,
          retry_other=max(0, int(p["n_hold"]) - 1), refresh=w.r.choice([0, 0, 1]), queue=(0.5, 8),
          pick_gap=real_stage(w, p, "view_to_hold_med_log", None), pay_gap=real_stage(w, p, "hold_to_pay_med_log", None))


def b_real_mimic(w: World, g):  # 사람처럼 보이는 브라우저 봇 (실제 botc 둘레): 명의·기기·카드는 따로, 계정마다 1~2분 간격
    ps = REAL["bot"].get("botc") or []
    if not ps:
        return
    t = w.T + timedelta(seconds=w.r.uniform(0, 60))
    for _ in range(w.r.randint(4, 6)):
        p = w.r.choice(ps)
        a = w.account("RESELLER", "b_real_mimic", g, age=w.r.randint(0, 30))
        w.buy(a, t, LIMIT_PER_RUN, real_gap(w, p, 0.6), browse=int(p["browse_before_hold"]) + w.r.choice([0, 0, 1]),
              queue=(0.5, 15), pick_gap=real_stage(w, p, "view_to_hold_med_log", None),
              pay_gap=real_stage(w, p, "hold_to_pay_med_log", None))
        t += timedelta(seconds=w.r.uniform(75, 200))


def b_real_macro(w: World, g):  # 단순 반복 매크로 (실제 botb 둘레): 카드 하나를 여러 명의가 같이 쓰고, 다 사고도 더 잡으려다 매수 제한
    ps = REAL["bot"].get("botb") or []
    if not ps:
        return
    card = f"rm{g}"
    t = w.T + timedelta(seconds=w.r.uniform(0, 20))
    for _ in range(w.r.randint(4, 6)):
        p = w.r.choice(ps)
        a = w.account("RESELLER", "b_real_macro", g, card=card, age=w.r.randint(0, 30))
        gap = real_gap(w, p)
        t2, resv = w.buy(a, t, LIMIT_PER_RUN, gap, browse=int(p["browse_before_hold"]),
                         retry_other=max(0, int(p["n_hold"]) - 1 - int(p.get("limit_rejects") or 0)))
        for _ in range(int(p.get("limit_rejects") or 0) if resv else 0):
            t2 += timedelta(seconds=gap())
            w.emit(a, t2, "VIEW_SEAT", run=w.cur_run, availableCount=w.r.randint(0, 900))
            t2 += timedelta(seconds=gap())
            seats = w.r.sample(SEAT_POOL, LIMIT_PER_RUN)
            w.emit(a, t2, "SEAT_HOLD", 422, run=w.cur_run, seatIds=seats, seatCount=len(seats), errorCode="LIMIT_PER_RUN")
        t += timedelta(seconds=w.r.uniform(5, 60))


# 수법 → 계열. 생성 데이터 안에서 "한 계열을 통째로 빼고 학습 → 그 계열로 시험"할 때 쓴다
FAMILY = {
    "b_fast": "speed", "b_steady": "speed", "b_jitter": "speed", "b_open_ms": "speed", "b_assist": "speed",
    "b_ip_rotator": "speed", "b_burst_quiet": "speed",
    "b_sniper": "retry",
    "b_multi_device": "multi_account", "b_multi_card": "multi_account", "b_noheader": "multi_account",
    "b_sleeper_farm": "multi_account", "b_device_farm": "multi_account",
    "b_distributed": "evasive", "b_human_mimic": "evasive", "b_slow_drip": "evasive",
    "b_cancel_rebuy": "cancel", "b_hold_blocker": "cancel", "b_seat_handoff": "cancel",
    "b_limit_prober": "hoard", "b_multi_run_hoarder": "hoard",
    "b_real_mimic": "evasive", "b_real_macro": "multi_account",
}


# (수법, 라벨 쪽, 비중). 비중은 상대값
STRATEGIES = [
    # 사람 (정상)
    (h_casual, "human", 34), (h_quick, "human", 8), (h_slow, "human", 8), (h_family_device, "human", 3),
    (h_family_bulk, "human", 5), (h_changed_mind, "human", 5), (h_quick_regret, "human", 2),
    (h_unlucky, "human", 5), (h_new_member, "human", 5),
    # 사람 — 오탐 함정
    (h_office, "human", 2), (h_pc_bang, "human", 2), (h_organizer, "human", 2), (h_open_rusher, "human", 4),
    (h_refresher, "human", 3), (h_private_browser, "human", 2), (h_two_devices, "human", 3), (h_vpn, "human", 2),
    # 봇
    (b_fast, "bot", 2), (b_steady, "bot", 2), (b_jitter, "bot", 2), (b_sniper, "bot", 2),
    (b_multi_device, "bot", 1), (b_multi_card, "bot", 1), (b_distributed, "bot", 1),
    (b_noheader, "bot", 1), (b_limit_prober, "bot", 1), (b_cancel_rebuy, "bot", 2),
    # 봇 — 더 어렵거나 색다른 수법
    (b_open_ms, "bot", 1), (b_assist, "bot", 2), (b_ip_rotator, "bot", 1), (b_human_mimic, "bot", 1),
    (b_sleeper_farm, "bot", 1), (b_slow_drip, "bot", 1), (b_seat_handoff, "bot", 1), (b_hold_blocker, "bot", 1),
    (b_multi_run_hoarder, "bot", 1), (b_device_farm, "bot", 1), (b_burst_quiet, "bot", 2),
]
# 오탐 함정 사람을 더 많이 (ML_TRAP_BOOST=배수). 2026-11-24 실제 둘레 1차 모델에서 모임 주선자 64%·오픈 직후 33%·
# 바로 후회 35%·가족 몫 25%·빠른 사람 15% 오탐 → 이 사람들을 더 많이 보여 줘 "정상인데 이렇게도 산다"를 배우게 한다
TRAP_HUMANS = {"h_organizer", "h_open_rusher", "h_quick_regret", "h_family_bulk", "h_quick"}
if os.environ.get("ML_TRAP_BOOST"):
    _x = float(os.environ["ML_TRAP_BOOST"])
    STRATEGIES = [(f, side, wt * _x if f.__name__ in TRAP_HUMANS else wt) for f, side, wt in STRATEGIES]
if REAL:
    # 실제 데이터 둘레. 사람 쪽은 생성 사람의 약 1/3을 실제 사람 모양으로 (원래 사람 수법은 그대로 둬서 다양함을 잃지 않게)
    STRATEGIES += [(h_focused, "human", 40), (b_real_mimic, "bot", 2), (b_real_macro, "bot", 2)]


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
        w.cur_run = w.some_run()
        fn(w, g)

    os.makedirs(a.out, exist_ok=True)
    w.events.sort(key=lambda e: (e["member_id"], e["ts"]))
    last: dict[int, str] = {}
    with open(os.path.join(a.out, "events.jsonl"), "w", encoding="utf-8") as f:
        for e in w.events:   # interval_ms = 같은 회원의 앞 이벤트와의 간격 (실제 로그와 같은 정의)
            prev = last.get(e["member_id"])
            e["interval_ms"] = None if prev is None else int(
                (datetime.fromisoformat(e["ts"]) - datetime.fromisoformat(prev)).total_seconds() * 1000)
            last[e["member_id"]] = e["ts"]
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    with open(os.path.join(a.out, "runs.jsonl"), "w", encoding="utf-8") as f:   # 판매 오픈 시각 (실제 데이터의 runs.jsonl과 같은 모양)
        for rid, t0 in w.runs:
            f.write(json.dumps({"run_id": rid, "open_ts": t0.isoformat(timespec="milliseconds")}) + "\n")
    with open(os.path.join(a.out, "members.jsonl"), "w", encoding="utf-8") as f:
        for acc in w.accounts:
            d = {k: v for k, v in acc.__dict__.items() if k not in ("device_pool", "ip_pool")}
            d["device_pool"] = acc.device_pool if acc.device_pool in (None, "rotate") else len(acc.device_pool)
            d["ip_pool"] = acc.ip_pool if acc.ip_pool in (None, "rotate") else len(acc.ip_pool)
            f.write(json.dumps(d | {"source": "generator", "seed": a.seed}, ensure_ascii=False) + "\n")

    by: dict[str, int] = {}
    for acc in w.accounts:
        by[acc.strategy] = by.get(acc.strategy, 0) + 1
    print(f"계정 {len(w.accounts)} · 이벤트 {len(w.events)} → {a.out}")
    for k in sorted(by, key=lambda s: (s[0], -by[s])):
        print(f"  {k:18s} {by[k]}")


if __name__ == "__main__":
    main()
