"""여러 세션을 묶어 보는 신호: 같은 로그 안에서 앞 M개 간격이 거의 같은 다른 세션이 몇 개인가.

같은 타이밍 템플릿을 여러 계정이 나눠 쓰는 매크로(replay·multi_account/staggered)는 세션 하나만 보면 사람처럼 보이지만,
서로 간격이 거의 같다. 사람끼리는 간격이 독립이라 우연히 겹칠 확률이 아주 낮다 (5개 간격의 평균 로그 차이 0.13 이하. 사람은 8.8%가 1개 이상, 1.1%가 2개 이상 우연히 겹침).
묶는 키는 이메일·IP·UA가 아니라 **간격과 이벤트 순서 자체**다.
"""
from __future__ import annotations

import numpy as np

from .schema import Subject

M = 5           # 비교할 간격 수 (이벤트 M+1개 이상 필요). 변환 뒤 세션이 6~9이벤트라 5로 정함
TOL = 0.13      # 간격의 평균 |로그 차이| 허용치


def dup_counts(subjects: list[Subject], m: int = M, tol: float = TOL, chunk: int = 256) -> np.ndarray:
    """대상마다 '간격이 거의 같은 다른 대상' 수. 같은 key(같은 회원의 다른 창)는 서로 세지 않는다"""
    n = len(subjects)
    out = np.zeros(n, dtype=np.int64)
    ok = [i for i, s in enumerate(subjects) if len(s.types) >= m + 1]
    if len(ok) < 2:
        return out
    idx = np.array(ok)
    lg = np.array([np.log(np.maximum(subjects[i].dt_ms[1:m + 1], 1.0)) for i in ok], dtype=np.float32)
    ty = np.array([subjects[i].types[:m + 1] for i in ok], dtype=np.int16)
    keys = np.array([subjects[i].key for i in ok])
    for a in range(0, len(ok), chunk):
        sl = slice(a, a + chunk)
        d = np.abs(lg[sl][:, None, :] - lg[None, :, :]).mean(2)
        hit = (d <= tol) & (ty[sl][:, None, :] == ty[None, :, :]).all(2)
        same_key = (keys[sl][:, None] == keys[None, :]) & (keys[sl][:, None] != "")
        hit &= ~same_key
        hit[np.arange(hit.shape[0]), np.arange(a, a + hit.shape[0])] = False     # 자기 자신
        out[idx[sl]] = hit.sum(1)
    return out


def apply_dup(subjects: list[Subject]) -> None:
    for s, c in zip(subjects, dup_counts(subjects)):
        s.dup = int(c)
