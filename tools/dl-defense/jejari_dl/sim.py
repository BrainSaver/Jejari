"""가상 행동 데이터 생성기 (사람 + 봇 6유형).

대회 봇의 값(간격·UA·이메일)을 쓰지 않는다. 봇은 "수법의 성질"로만 만든다:
  일정한 간격, 둘러보지 않고 바로 선점, 같은 좌석 연타, 기기 하나에 명의 여러 개, 취소·재구매 반복.
각 유형의 매개변수를 넓은 범위에서 뽑아 한 값에 외워지지 않게 한다.
"""
from __future__ import annotations

import numpy as np

from .schema import (STATUS_CONFLICT, STATUS_OK, STATUS_OTHER, TYPE_INDEX, Subject)

HUMAN_PERSONAS = ["careful", "casual", "hurried", "refresher", "family"]
BOT_FAMILIES = ["fixed_interval", "sniper", "multi_account", "jitter_evader", "cancel_cycler", "waitlist_macro"]

T = TYPE_INDEX


class _Builder:
    def __init__(self):
        self.types, self.dt, self.status = [], [], []

    def add(self, etype: str, dt_ms: float, status: int = STATUS_OK):
        self.types.append(T[etype])
        self.dt.append(0.0 if not self.types[:-1] else max(float(dt_ms), 1.0))
        self.status.append(status)

    def __len__(self):
        return len(self.types)


def _lognormal(rng, median_ms, sigma, floor=120.0):
    return max(floor, rng.lognormal(np.log(median_ms), sigma))


def _human_gap(rng, median, sigma):
    gap = _lognormal(rng, median, sigma)
    return gap


# ---------------------------------------------------------------- 사람

def gen_human(rng: np.random.Generator, persona: str | None = None, max_len: int = 64) -> Subject:
    persona = persona or rng.choice(HUMAN_PERSONAS)
    median = {"careful": 6000, "casual": 3500, "hurried": 1500, "refresher": 1100, "family": 4500}[persona]
    sigma = rng.uniform(0.5, 1.0)
    b = _Builder()

    def gap():
        return _human_gap(rng, median, sigma)

    def maybe_double_click(etype, st=STATUS_OK):
        # 사람도 가끔 두 번 누른다 (80~200ms). 오탐을 재려면 있어야 한다
        if rng.random() < 0.08:
            b.add(etype, rng.uniform(80, 200), st)

    rounds = 1 + (rng.random() < (0.5 if persona == "family" else 0.1)) + (rng.random() < 0.05)
    for _ in range(int(rounds)):
        n_search = rng.integers(1, 6) if persona != "refresher" else rng.integers(5, 16)
        for _ in range(n_search):
            b.add("VIEW_TRAIN", gap() if persona != "refresher" else rng.uniform(500, 1500))
            maybe_double_click("VIEW_TRAIN")
        for _ in range(rng.integers(1, 7)):
            b.add("VIEW_SEAT", gap())
        b.add("QUEUE_ENTER", gap())
        b.add("VIEW_SEAT", rng.uniform(5_000, 240_000))        # 줄을 서서 기다림
        for _ in range(rng.integers(0, 3)):
            b.add("VIEW_SEAT", gap())
        fails = rng.integers(0, 6 if persona in ("refresher", "hurried") else 3)
        for _ in range(fails):
            b.add("SEAT_HOLD", gap(), STATUS_CONFLICT)
            maybe_double_click("SEAT_HOLD", STATUS_CONFLICT)
            if rng.random() < 0.5:
                b.add("VIEW_SEAT", gap())
        b.add("SEAT_HOLD", gap())
        b.add("PAY", rng.uniform(12_000, 150_000), STATUS_OK if rng.random() > 0.05 else STATUS_OTHER)
        if rng.random() < 0.15:
            b.add("CANCEL", rng.uniform(60_000, 3_600_000))
        if len(b) >= max_len:
            break
    same_seat = int(min(fails, rng.integers(0, 3)))                       # 사람은 같은 좌석을 몇 번 다시 누를 뿐
    linked = 1 if rng.random() > 0.05 else int(rng.integers(2, 4))      # 가족 폰 공유 등
    sync = 0 if rng.random() > 0.03 else 1
    return Subject(b.types[:max_len], b.dt[:max_len], b.status[:max_len], linked, sync, 0, persona,
                   same_seat_fail=same_seat)


# ---------------------------------------------------------------- 봇

def _steady(rng, base, rel_jitter):
    return max(30.0, base * (1 + rng.normal(0, rel_jitter)))


def _bot_fixed_interval(rng, max_len):
    base = rng.uniform(250, 3000)
    jit = rng.uniform(0.002, 0.04)
    b = _Builder()
    if rng.random() < 0.5:
        b.add("VIEW_TRAIN", _steady(rng, base, jit))
    worst = 0
    for _ in range(int(rng.integers(2, 6))):
        b.add("QUEUE_ENTER", _steady(rng, base, jit))
        k = int(rng.integers(0, 5))
        worst = max(worst, k)
        for _ in range(k):
            b.add("SEAT_HOLD", _steady(rng, base, jit), STATUS_CONFLICT)
        b.add("SEAT_HOLD", _steady(rng, base, jit))
        b.add("PAY", _steady(rng, base, jit))
        if rng.random() < 0.3:
            b.add("CANCEL", _steady(rng, base, jit))
    return Subject(b.types[:max_len], b.dt[:max_len], b.status[:max_len], 1, 0, 1, "fixed_interval",
                   same_seat_fail=worst)


def _bot_sniper(rng, max_len):
    b = _Builder()
    b.add("QUEUE_ENTER", rng.uniform(100, 800))
    n_fail = int(rng.integers(10, 45))
    for _ in range(n_fail):
        b.add("SEAT_HOLD", rng.uniform(30, 380), STATUS_CONFLICT if rng.random() < 0.9 else STATUS_OTHER)
    b.add("SEAT_HOLD", rng.uniform(30, 380))
    b.add("PAY", rng.uniform(300, 1200))
    return Subject(b.types[:max_len], b.dt[:max_len], b.status[:max_len], 1, 0, 1, "sniper",
                   same_seat_fail=n_fail)


def _bot_multi_account(rng, max_len):
    """한 기기에서 명의 여러 개. 한 명의 행동은 사람과 비슷하다 → 공유 신호로만 구별된다"""
    s = gen_human(rng, str(rng.choice(["casual", "hurried"])), max_len)
    s.linked = int(rng.integers(3, 11))
    s.sync = int(rng.integers(2, 7))
    s.label, s.family = 1, "multi_account"
    return s


def _bot_jitter_evader(rng, max_len):
    """간격에 사람만큼 흔들림을 줘서 규칙성 검사를 피한다. 경로(둘러보지 않음)와 속도로만 구별된다"""
    median = rng.uniform(400, 1500)
    sigma = rng.uniform(0.5, 0.9)
    b = _Builder()

    def gap():
        return _lognormal(rng, median, sigma, floor=60.0)
    worst = 0
    for _ in range(int(rng.integers(2, 5))):
        if rng.random() < 0.3:
            b.add("VIEW_SEAT", gap())
        b.add("QUEUE_ENTER", gap())
        k = int(rng.integers(0, 6))
        worst = max(worst, k)
        for _ in range(k):
            b.add("SEAT_HOLD", gap(), STATUS_CONFLICT)
        b.add("SEAT_HOLD", gap())
        b.add("PAY", gap())
    return Subject(b.types[:max_len], b.dt[:max_len], b.status[:max_len], 1, 0, 1, "jitter_evader",
                   same_seat_fail=worst)


def _bot_cancel_cycler(rng, max_len):
    base = rng.uniform(800, 5000)
    jit = rng.uniform(0.05, 0.2)
    b = _Builder()
    b.add("QUEUE_ENTER", _steady(rng, base, jit))
    for _ in range(int(rng.integers(3, 9))):
        b.add("SEAT_HOLD", _steady(rng, base, jit))
        b.add("PAY", _steady(rng, base, jit))
        b.add("CANCEL", _steady(rng, base * 3, jit))
        if rng.random() < 0.4:
            b.add("WAITLIST_APPLY", _steady(rng, base, jit), STATUS_OTHER)
    return Subject(b.types[:max_len], b.dt[:max_len], b.status[:max_len], 1, 0, 1, "cancel_cycler")


def _bot_waitlist_macro(rng, max_len):
    base = rng.uniform(500, 5000)
    jit = rng.uniform(0.01, 0.08)
    b = _Builder()
    for i in range(int(rng.integers(12, 60))):
        b.add("WAITLIST_APPLY", _steady(rng, base, jit), STATUS_CONFLICT if rng.random() < 0.7 else STATUS_OTHER)
        if rng.random() < 0.5:
            b.add("WAITLIST_CANCEL", _steady(rng, base, jit))
    return Subject(b.types[:max_len], b.dt[:max_len], b.status[:max_len], 1, 0, 1, "waitlist_macro")


_BOTS = {"fixed_interval": _bot_fixed_interval, "sniper": _bot_sniper, "multi_account": _bot_multi_account,
         "jitter_evader": _bot_jitter_evader, "cancel_cycler": _bot_cancel_cycler,
         "waitlist_macro": _bot_waitlist_macro}


def gen_bot(rng: np.random.Generator, family: str, max_len: int = 64) -> Subject:
    return _BOTS[family](rng, max_len)


def make_dataset(n_human: int, n_bot: int, seed: int, families: list[str] | None = None,
                 max_len: int = 64) -> list[Subject]:
    """사람 n_human명 + 봇 n_bot명(유형은 고르게 섞음). seed가 같으면 같은 데이터"""
    rng = np.random.default_rng(seed)
    families = families or BOT_FAMILIES
    out = [gen_human(rng, None, max_len) for _ in range(n_human)]
    for i in range(n_bot):
        out.append(gen_bot(rng, families[i % len(families)], max_len))
    order = rng.permutation(len(out))
    return [out[i] for i in order]
