"""봇 탐지 모델: 1D-CNN(국소 패턴) → 양방향 GRU(순서) → 어텐션·최대 풀링 → (+ 공유 신호) → 점수.

입력은 원본 배열(종류·상태·간격 ms)이고, 모델 안에서 피처로 바꾼다. 그래야 학습 중에
간격 전체를 일정 배율로 늘이고 줄이는 증강을 쉽게 할 수 있다 (절대 속도 하나에 외우지 않게).
"""
from __future__ import annotations

import torch
from torch import nn

from .schema import CTX_DIM, FAST_MS, IN_DIM, N_STATUS, N_TYPES


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
    def __init__(self, hidden: int = 64, dropout: float = 0.2):
        super().__init__()
        self.conv = nn.Sequential(nn.Conv1d(IN_DIM, hidden, 3, padding=1), nn.GELU(),
                                  nn.Conv1d(hidden, hidden, 3, padding=1), nn.GELU())
        self.gru = nn.GRU(hidden, hidden, batch_first=True, bidirectional=True)
        self.attn = nn.Linear(2 * hidden, 1)
        self.ctx = nn.Sequential(nn.Linear(CTX_DIM, 16), nn.GELU())
        self.head = nn.Sequential(nn.Linear(4 * hidden + 16, 64), nn.GELU(), nn.Dropout(dropout), nn.Linear(64, 1))

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
        z = torch.cat([pooled_attn, pooled_max, self.ctx(ctx)], dim=-1)
        return self.head(z).squeeze(-1)                  # 로짓 (시그모이드 하면 봇 확률)
