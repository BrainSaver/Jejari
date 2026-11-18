"""공격 생성기(gen_bot_data.py)의 CSV 이벤트 로그를 Subject로 바꾸고, 같은 어휘의 사람 세션을 만든다.

CSV 어휘: login search select_train reserve_click queue_join queue_pass seat_select payment cancel
모델 어휘(BehaviorType): VIEW_TRAIN VIEW_SEAT QUEUE_ENTER SEAT_HOLD PAY CANCEL ...
매핑은 아래 CSV_TO_TYPE 한 곳에서 바꾼다. None인 이벤트(login·reserve_click)는 API 로그에 없는 화면 동작이라
버리고, 간격은 남은 이벤트의 시각으로 다시 계산한다.

읽지 않는 열: email(정답 표시), ua_hash, ip_hash, seat, n_seats. ip_hash를 안 쓰는 이유는 대회장에서
사람들이 같은 와이파이의 IP를 공유해 가짜 신호가 되기 때문이다. label·bot_type은 학습·평가 정답으로만 쓴다.
"""
from __future__ import annotations

import csv
from collections import defaultdict
from pathlib import Path

import numpy as np

from .schema import STATUS_OK, TYPE_INDEX, Subject

CSV_TO_TYPE = {"login": None, "search": "VIEW_TRAIN", "select_train": "VIEW_TRAIN", "reserve_click": None,
               "queue_join": "QUEUE_ENTER", "queue_pass": "VIEW_SEAT", "seat_select": "SEAT_HOLD",
               "payment": "PAY", "cancel": "CANCEL"}


def events_to_subject(events: list[tuple[str, float]], label: int | None, family: str,
                      max_len: int = 64) -> Subject | None:
    """(CSV 이벤트 이름, ts_ms) 목록 → Subject. 매핑이 없는 이벤트는 버린다. 남는 게 없으면 None"""
    kept = [(TYPE_INDEX[CSV_TO_TYPE[name]], ts) for name, ts in sorted(events, key=lambda e: e[1])
            if CSV_TO_TYPE.get(name)]
    kept = kept[:max_len]
    if not kept:
        return None
    dt = [0.0] + [max(kept[i][1] - kept[i - 1][1], 1.0) for i in range(1, len(kept))]
    return Subject([t for t, _ in kept], dt, [STATUS_OK] * len(kept), 1, 0, label, family)


def read_csv(path: Path) -> list[Subject]:
    by_session = defaultdict(list)
    meta = {}
    with open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            sid = row["session_id"]
            by_session[sid].append((row["event"], float(row["ts_ms"])))
            meta[sid] = (int(row["label"]), row.get("bot_type") or "csv_bot")
    out = []
    for sid, evs in by_session.items():
        label, fam = meta[sid]
        s = events_to_subject(evs, label, fam)
        if s:
            out.append(s)
    return out


def gen_human(rng: np.random.Generator) -> Subject:
    """같은 어휘·같은 뼈대의 사람 세션 (가정이다 — 실제 사람 로그가 생기면 교체한다).

    사람의 특징으로 본 것: 속도가 사람마다 다르고(배율), 이벤트마다 간격이 크게 흔들리고(로그정규 σ 0.6~1.0),
    검색·열차 선택을 되풀이하며, 결제 입력에 오래 걸린다. 줄 대기는 짧은 경우(낮은 부하)와 긴 경우가 섞인다.
    """
    f = float(rng.lognormal(0.0, 0.35))
    sigma = float(rng.uniform(0.6, 1.0))

    def gap(median):
        return max(120.0, float(rng.lognormal(np.log(median * f), sigma)))

    t = 0.0
    ev: list[tuple[str, float]] = [("login", 0.0)]

    def add(name, dt):
        nonlocal t
        t += dt
        ev.append((name, t))

    add("search", gap(2500))
    if rng.random() < 0.05:
        add("search", float(rng.uniform(80, 200)))                  # 더블 클릭
    for _ in range(int(rng.choice([0, 0, 1, 2, 3]))):
        add("search" if rng.random() < 0.5 else "select_train", gap(3000))
    add("select_train", gap(3500))
    add("reserve_click", gap(1500))
    add("queue_join", gap(1200))
    add("queue_pass", gap(3000) if rng.random() < 0.7 else float(rng.uniform(10_000, 120_000)))
    for _ in range(int(rng.choice([1, 1, 1, 2, 2, 3, 4]))):
        add("seat_select", gap(2800))
    add("payment", gap(15_000))
    if rng.random() < 0.1:
        add("cancel", gap(60_000))
    return events_to_subject(ev, 0, "csv_human")


def make_humans(n: int, seed: int) -> list[Subject]:
    rng = np.random.default_rng(seed)
    return [gen_human(rng) for _ in range(n)]
