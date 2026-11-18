"""봇 탐지 모델: 1D-CNN(국소 패턴) → 양방향 GRU(순서) → 어텐션·최대 풀링 → (+ 공유 신호) → 점수.

입력은 원본 배열(종류·상태·간격 ms)이고, 모델 안에서 피처로 바꾼다. 그래야 학습 중에
간격 전체를 일정 배율로 늘이고 줄이는 증강을 쉽게 할 수 있다 (절대 속도 하나에 외우지 않게).
"""
from __future__ import annotations

import torch
from torch import nn

from .schema import CTX_DIM, FAST_MS, IN_DIM, N_STATUS, N_TYPES, TYPE_INDEX

SEAT = TYPE_INDEX["SEAT_HOLD"]
STAT_DIM = 12


def featurize(types, status, dt, length):
    """[N,L] 정수·실수 텐서 → [N,L,IN_DIM] 피처, [N,L] 마스크"""
    n, l = types.shape
    steps = torch.arange(l, device=types.device)[None, :]
    mask = steps < length[:, None]
    type_oh = nn.functional.one_hot(types, N_TYPES).float()
    status_oh = nn.functional.one_hot(status, N_STATUS).float()
    log_dt = torch.log1p(dt) / 14.0                      # 600초 ≈ 13.3
    fast = ((dt < FAST_MS) & (steps > 0)).float()
    x = torch.cat([type_oh, status_oh, log_dt[..., None], fast[..., None]], dim=-1)
    return x * mask[..., None], mask


class BotDetector(nn.Module):
    """세션 요약 통계(STAT_DIM개)를 모델 안에서 계산해 함께 쓴다. 이벤트 종류별 기준 속도(type_mu)는
    학습 데이터의 사람에서 추정한다 (init_stats). 생성기의 기준값을 쓰지 않는다."""

    def __init__(self, hidden: int = 64, dropout: float = 0.2):
        super().__init__()
        self.register_buffer("type_mu", torch.zeros(N_TYPES))
        self.register_buffer("stat_mu", torch.zeros(STAT_DIM))
        self.register_buffer("stat_sd", torch.ones(STAT_DIM))
        self.stat_mlp = nn.Sequential(nn.Linear(STAT_DIM, 32), nn.GELU())
        self.conv = nn.Sequential(nn.Conv1d(IN_DIM, hidden, 3, padding=1), nn.GELU(),
                                  nn.Conv1d(hidden, hidden, 3, padding=1), nn.GELU())
        self.gru = nn.GRU(hidden, hidden, batch_first=True, bidirectional=True)
        self.attn = nn.Linear(2 * hidden, 1)
        self.ctx = nn.Sequential(nn.Linear(CTX_DIM, 16), nn.GELU())
        self.head = nn.Sequential(nn.Linear(4 * hidden + 16 + 32, 64), nn.GELU(), nn.Dropout(dropout), nn.Linear(64, 1))

    def forward(self, types, status, dt, length, ctx):
        x, mask = featurize(types, status, dt, length)
        h = self.conv(x.transpose(1, 2)).transpose(1, 2)
        packed = nn.utils.rnn.pack_padded_sequence(h, length.cpu(), batch_first=True, enforce_sorted=False)
        out, _ = self.gru(packed)
        out, _ = nn.utils.rnn.pad_packed_sequence(out, batch_first=True, total_length=x.shape[1])
        a = self.attn(out).squeeze(-1).masked_fill(~mask, float("-inf"))
        w = torch.softmax(a, dim=1).unsqueeze(-1)
        pooled_attn = (w * out).sum(1)
        pooled_max = out.masked_fill(~mask[..., None], float("-inf")).max(1).values
        stats = self.session_stats(types, dt, length)
        z = torch.cat([pooled_attn, pooled_max, self.ctx(ctx), self.stat_mlp(stats)], dim=-1)
        return self.head(z).squeeze(-1)                  # 로짓 (시그모이드 하면 봇 확률)

    # ---------------------------------------------------------------- 세션 요약 통계

    def _raw_stats(self, types, dt, length):
        """정규화 간격 z = log(1+dt) - 종류별 사람 기준. 첫 이벤트(dt=0)는 뺀다"""
        l = types.shape[1]
        idx = torch.arange(l, device=types.device)[None, :]
        valid = ((idx >= 1) & (idx < length[:, None])).float()
        z = torch.log1p(dt) - self.type_mu[types]
        cnt = valid.sum(1).clamp(min=1.0)
        mean = (z * valid).sum(1) / cnt
        c = (z - mean[:, None]) * valid
        std = ((c ** 2).sum(1) / cnt + 1e-6).sqrt()
        pair = valid[:, :-1] * valid[:, 1:]
        ac = (c[:, :-1] * c[:, 1:] * pair).sum(1) / ((c ** 2).sum(1) + 1e-6)
        has = valid.sum(1) > 0
        zmax = torch.where(has, torch.where(valid > 0, z, torch.full_like(z, -1e4)).max(1).values, torch.zeros_like(mean))
        zmin = torch.where(has, torch.where(valid > 0, z, torch.full_like(z, 1e4)).min(1).values, torch.zeros_like(mean))
        skew = (((c ** 3).sum(1) / cnt) / (std ** 3 + 1e-6)).clamp(-5, 5)
        mad = ((z[:, 1:] - z[:, :-1]).abs() * pair).sum(1) / pair.sum(1).clamp(min=1.0)
        fast = ((dt < 250).float() * valid).sum(1) / cnt
        seat = (types == SEAT).float() * valid
        other = valid - seat
        sc, oc = seat.sum(1), other.sum(1)
        seat_mean = torch.where(sc > 0, (z * seat).sum(1) / sc.clamp(min=1.0), mean)
        other_mean = torch.where(oc > 0, (z * other).sum(1) / oc.clamp(min=1.0), mean)
        return torch.stack([mean, std, ac, zmax, zmin, skew, mad, fast, seat_mean, other_mean,
                            seat_mean - other_mean, torch.log(cnt) / 4], dim=1)

    def session_stats(self, types, dt, length):
        return ((self._raw_stats(types, dt, length) - self.stat_mu) / self.stat_sd).clamp(-10, 10)

    @torch.no_grad()
    def init_stats(self, types, dt, length, label):
        """학습 데이터의 사람(label 0)으로 종류별 기준 속도를, 전체로 통계의 평균·표준편차를 정한다"""
        l = types.shape[1]
        idx = torch.arange(l, device=types.device)[None, :]
        valid = (idx >= 1) & (idx < length[:, None])
        human = (label == 0)[:, None] & valid
        lg = torch.log1p(dt)
        allv = lg[valid].mean() if valid.any() else torch.zeros(())
        mu = torch.full((N_TYPES,), float(allv), device=types.device)
        for t in range(N_TYPES):
            m = human & (types == t)
            if m.any():
                mu[t] = lg[m].mean()
        self.type_mu.copy_(mu)
        raw = self._raw_stats(types, dt, length)
        self.stat_mu.copy_(raw.mean(0))
        self.stat_sd.copy_(raw.std(0).clamp(min=1e-3))
