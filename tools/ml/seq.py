"""행동 로그 → 계정별 이벤트 시퀀스 (딥러닝 시퀀스 모델 입력).

features.py가 계정 하나를 숫자 32개로 **요약**한다면, 여기는 이벤트를 **순서 그대로** 한 줄씩 숫자로 바꾼다.
요약하면 사라지는 것(어떤 순서로 눌렀나, 간격이 어떻게 이어지나, 실패 뒤에 무엇을 했나)을 모델이 직접 보게 하려는 것.

    events.jsonl (features.py와 같은 폴더) → 계정마다 [이벤트 수 × STEP_DIM] 배열

이벤트 한 칸 (STEP_FEATURES 순서):
  - 종류 7칸 (VIEW_TRAIN · VIEW_SEAT · QUEUE_ENTER · SEAT_HOLD · PAY · CANCEL · 그 밖)
  - 직전 이벤트와의 간격 log1p(초) · 첫 이벤트 표시
  - 응답 4칸 (2xx · 409 · 422 · 그 밖의 실패)
  - 선점 좌석 수 log1p · 직전 선점과 같은 좌석을 다시 노림 · 대기열 거절 · 결제 미승인
  - 기기가 직전과 다름 · 기기 정보 없음 · 운행이 직전과 다름
  - 판매 오픈 기준 시각 (부호 있는 log1p(초) / 10) · 오픈 시각을 모름

날값(좌석 번호·열차 ID·시각)과 규칙 엔진 문턱은 넣지 않는다 (features.py와 같은 원칙).
IP는 넣지 않는다 — 로컬 대회는 모두 한 IP라 가짜 신호가 된다 (train.py의 EXCLUDE와 같은 이유).
첫 화면 자동 검색(meta.auto)은 features.load가 이미 뺀다.
"""
from __future__ import annotations

import json
import math
import os
from datetime import datetime

from features import load

TYPES = ["VIEW_TRAIN", "VIEW_SEAT", "QUEUE_ENTER", "SEAT_HOLD", "PAY", "CANCEL"]
STEP_FEATURES = (
    [f"type_{t.lower()}" for t in TYPES] + ["type_other"]
    + ["gap_log", "is_first"]
    + ["st_2xx", "st_409", "st_422", "st_fail"]
    + ["hold_seats_log", "same_seats_again", "queue_rejected", "pay_not_approved"]
    + ["device_changed", "device_missing", "run_changed"]
    + ["since_open", "open_unknown"]
)
STEP_DIM = len(STEP_FEATURES)
MAX_LEN = 96  # 생성 데이터 99백분위 55, 최대 96


def _ts(s: str) -> datetime:
    return datetime.fromisoformat(s[:23])


def _open_times(folder: str) -> dict:
    p = os.path.join(folder, "runs.jsonl")
    out = {}
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            for line in f:
                r = json.loads(line)
                out[r["run_id"]] = _ts(r["open_ts"])
    return out


def encode(evs: list, opens: dict) -> list:
    """한 계정의 이벤트(시각순) → [[STEP_DIM 개 숫자], …] (최대 MAX_LEN)"""
    rows = []
    prev = None
    prev_seats = None
    for e in evs[:MAX_LEN]:
        meta = e.get("meta") or {}
        t = e["event_type"]
        x = [0.0] * STEP_DIM
        x[TYPES.index(t) if t in TYPES else len(TYPES)] = 1.0
        now = _ts(e["ts"])
        if prev is None:
            x[7], x[8] = 0.0, 1.0
        else:
            x[7] = math.log1p(max(0.0, (now - _ts(prev["ts"])).total_seconds()))
        st = int(e.get("response_status") or 0)
        x[9 + (0 if 200 <= st < 300 else 1 if st == 409 else 2 if st == 422 else 3)] = 1.0
        if t == "SEAT_HOLD":
            x[13] = math.log1p(float(meta.get("seatCount") or 0))
            seats = tuple(sorted(meta.get("seatIds") or []))
            x[14] = 1.0 if seats and seats == prev_seats else 0.0
            prev_seats = seats
        x[15] = 1.0 if t == "QUEUE_ENTER" and meta.get("rejected") else 0.0
        x[16] = 1.0 if t == "PAY" and meta.get("paymentStatus", "APPROVED") != "APPROVED" else 0.0
        dev = e.get("device_hash")
        x[17] = 1.0 if prev is not None and dev != prev.get("device_hash") else 0.0
        x[18] = 1.0 if not dev else 0.0
        x[19] = 1.0 if prev is not None and e.get("run_id") != prev.get("run_id") else 0.0
        op = opens.get(e.get("run_id"))
        if op is None:
            x[21] = 1.0
        else:
            d = (now - op).total_seconds()
            x[20] = math.copysign(math.log1p(abs(d)), d) / 10.0
        rows.append(x)
        prev = e
    return rows


def load_sequences(folder: str) -> dict:
    """member_id → 인코딩된 시퀀스. 이벤트가 하나도 없는 계정은 빈 목록"""
    events, members = load(folder)
    opens = _open_times(folder)
    return {m: encode(events.get(m, []), opens) for m in members}
