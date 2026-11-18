"""행동 로그(BehaviorEvent)를 모델 입력으로 바꾸는 약속.

원문 식별정보·이메일·태그·라벨은 입력에 넣지 않는다. 이벤트 종류, 시각 간격, 응답 상태,
기기 해시 단위 공유 신호(연결 계정 수·동시 선점 계정 수)만 쓴다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

# BehaviorType.java 와 같은 순서. 이름을 바꾸거나 순서를 바꾸면 저장된 모델이 못 읽는다 (추가만 한다)
EVENT_TYPES = ["VIEW_TRAIN", "VIEW_SEAT", "QUEUE_ENTER", "SEAT_HOLD", "PAY", "CANCEL",
               "WAITLIST_APPLY", "WAITLIST_CANCEL"]
TYPE_INDEX = {t: i for i, t in enumerate(EVENT_TYPES)}
N_TYPES = len(EVENT_TYPES)

MAX_LEN = 64          # 한 번에 보는 이벤트 수 (더 길면 창을 밀며 본다)
CTX_DIM = 3           # log1p(연결 계정 수), log1p(동시 선점 계정 수), log1p(간격이 같은 다른 세션 수)
FAST_MS = 200         # RiskPolicy.fast_interval_ms 와 같은 기준 (피처로만 쓴다)
STATUS_OK, STATUS_CONFLICT, STATUS_OTHER = 0, 1, 2
N_STATUS = 3
IN_DIM = N_TYPES + N_STATUS + 2   # 종류 원핫 + 상태 원핫 + log 간격 + 빠른 간격 표시


def status_class(http_status: int) -> int:
    if 200 <= http_status < 300:
        return STATUS_OK
    if http_status == 409:
        return STATUS_CONFLICT
    return STATUS_OTHER


@dataclass
class Subject:
    """한 대상(회원 또는 기기)의 이벤트 순서. label은 학습·평가에만 쓰고 모델 입력에는 안 들어간다."""
    types: list[int]
    dt_ms: list[float]          # 앞 이벤트와의 간격. 첫 이벤트는 0
    status: list[int]           # status_class 값
    linked: int = 1
    sync: int = 0
    label: int | None = None    # 1=봇, 0=사람, None=모름 (실제 로그)
    family: str = ""            # 시뮬레이터 유형 이름 (평가표용)
    key: str = ""               # 실제 로그의 대상 키
    same_seat_fail: int = 0     # 한 좌석 선점 실패 최대 횟수. 규칙 기준선 비교 전용, 모델 입력에는 안 들어간다
    dup: int = 0                # 간격이 거의 같은 다른 세션 수 (group.dup_counts). 0이면 없거나 계산 안 함

    def __post_init__(self):
        if not (len(self.types) == len(self.dt_ms) == len(self.status)):
            raise ValueError("types·dt_ms·status 길이가 다릅니다")


@dataclass
class Batch:
    types: np.ndarray    # [N, L] int64
    status: np.ndarray   # [N, L] int64
    dt: np.ndarray       # [N, L] float32 (ms)
    length: np.ndarray   # [N] int64
    ctx: np.ndarray      # [N, CTX_DIM] float32
    label: np.ndarray    # [N] float32 (모르면 -1)
    families: list[str] = field(default_factory=list)


def to_batch(subjects: list[Subject], max_len: int = MAX_LEN) -> Batch:
    n = len(subjects)
    types = np.zeros((n, max_len), dtype=np.int64)
    status = np.zeros((n, max_len), dtype=np.int64)
    dt = np.zeros((n, max_len), dtype=np.float32)
    length = np.zeros(n, dtype=np.int64)
    ctx = np.zeros((n, CTX_DIM), dtype=np.float32)
    label = np.full(n, -1, dtype=np.float32)
    for i, s in enumerate(subjects):
        m = min(len(s.types), max_len)
        if m == 0:
            raise ValueError("이벤트가 없는 대상입니다")
        types[i, :m] = s.types[:m]
        status[i, :m] = s.status[:m]
        dt[i, :m] = s.dt_ms[:m]
        dt[i, 0] = 0.0
        length[i] = m
        ctx[i] = (np.log1p(max(s.linked, 0)), np.log1p(max(s.sync, 0)), np.log1p(max(s.dup, 0)))
        if s.label is not None:
            label[i] = s.label
    return Batch(types, status, dt, length, ctx, label, [s.family for s in subjects])
