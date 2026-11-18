"""실제 행동 로그(behavior_log, BehaviorEvent JSON 한 줄씩)를 Subject로 바꾼다. 라벨은 읽지 않는다.

대상 키: member_id가 있으면 회원, 없으면 기기 해시. 연결 계정 수·동시 선점 계정 수는 기기 해시 기준으로만
센다. IP 해시는 쓰지 않는다 (같은 와이파이의 사람들이 한 IP를 공유해서 가짜 신호가 된다).
이메일·UA·태그 같은 값은 입력에 넣지 않는다.
"""
from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Iterable

from .schema import MAX_LEN, TYPE_INDEX, Subject, status_class

SYNC_WINDOW_S = 60.0


def _pick(d: dict, *names):
    for n in names:
        if n in d and d[n] is not None:
            return d[n]
    return None


def _ts_seconds(v) -> float:
    if isinstance(v, (int, float)):
        return float(v) / 1000.0 if v > 1e11 else float(v)
    if isinstance(v, dict) and "$date" in v:          # mongoexport
        return _ts_seconds(v["$date"])
    return datetime.fromisoformat(str(v).replace("Z", "+00:00")).timestamp()


def read_events(lines: Iterable[str]) -> list[dict]:
    out = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        et = _pick(d, "eventType", "event_type")
        if et not in TYPE_INDEX:
            continue                                  # 모르는 종류는 건너뛴다
        ts = _pick(d, "ts", "occurred_at", "occurredAt")
        if ts is None:
            continue
        mid = _pick(d, "memberId", "member_id")
        dev = _pick(d, "deviceHash", "device_hash")
        status = _pick(d, "responseStatus", "response_status")
        out.append({"type": TYPE_INDEX[et], "t": _ts_seconds(ts), "member": mid, "device": dev,
                    "status": status_class(int(status)) if status is not None else 0})
    out.sort(key=lambda e: e["t"])
    return out


def _sync_counts(events: list[dict]) -> dict[str, int]:
    """기기마다, 60초 안에 SEAT_HOLD를 한 서로 다른 회원 수의 최댓값"""
    hold = TYPE_INDEX["SEAT_HOLD"]
    by_dev = defaultdict(list)
    for e in events:
        if e["type"] == hold and e["device"] and e["member"] is not None:
            by_dev[e["device"]].append((e["t"], e["member"]))
    best = {}
    for dev, items in by_dev.items():
        items.sort()
        m, lo = 0, 0
        for hi in range(len(items)):
            while items[hi][0] - items[lo][0] > SYNC_WINDOW_S:
                lo += 1
            m = max(m, len({x[1] for x in items[lo:hi + 1]}))
        best[dev] = m
    return best


def build_subjects(events: list[dict], max_len: int = MAX_LEN, stride: int = MAX_LEN // 2) -> list[Subject]:
    """대상마다 이벤트를 창(max_len, 겹침 stride)으로 잘라 Subject 목록을 만든다. key가 같으면 같은 대상"""
    members_per_dev = defaultdict(set)
    for e in events:
        if e["device"] and e["member"] is not None:
            members_per_dev[e["device"]].add(e["member"])
    sync = _sync_counts(events)

    groups = defaultdict(list)
    for e in events:
        key = f"m:{e['member']}" if e["member"] is not None else (f"d:{e['device']}" if e["device"] else None)
        if key:
            groups[key].append(e)

    out = []
    for key, evs in groups.items():
        devs = {e["device"] for e in evs if e["device"]}
        linked = max([len(members_per_dev[d]) for d in devs], default=1)
        sy = max([sync.get(d, 0) for d in devs], default=0)
        starts = list(range(0, max(len(evs) - max_len, 0) + 1, stride)) or [0]
        if starts[-1] + max_len < len(evs):
            starts.append(len(evs) - max_len)
        for s in starts:
            w = evs[s:s + max_len]
            dt = [0.0] + [(w[i]["t"] - w[i - 1]["t"]) * 1000.0 for i in range(1, len(w))]
            out.append(Subject([e["type"] for e in w], dt, [e["status"] for e in w], linked, sy, None, "", key))
    return out


def load_file(path: Path) -> list[Subject]:
    with open(path, encoding="utf-8") as f:
        subjects = build_subjects(read_events(f))
    from .group import apply_dup           # 같은 로그 안에서 간격이 거의 같은 다른 대상 수
    apply_dup(subjects)
    return subjects
