"""학습·추론. 대회 데이터로는 학습하지 않는다 (가상 데이터 전용)."""
from __future__ import annotations

import copy
import json
from dataclasses import dataclass, asdict
from pathlib import Path

import numpy as np
import torch
from torch import nn

from .model import BotDetector
from .schema import Batch, Subject, to_batch


@dataclass
class TrainConfig:
    epochs: int = 25
    batch_size: int = 128
    lr: float = 2e-3
    weight_decay: float = 1e-2
    hidden: int = 64
    scale_aug: tuple = (0.7, 1.4)    # 간격 전체에 곱하는 배율 범위
    seed: int = 0


def _tensors(b: Batch, device):
    return (torch.from_numpy(b.types).to(device), torch.from_numpy(b.status).to(device),
            torch.from_numpy(b.dt).to(device), torch.from_numpy(b.length).to(device),
            torch.from_numpy(b.ctx).to(device), torch.from_numpy(b.label).to(device))


@torch.no_grad()
def predict(model: BotDetector, subjects: list[Subject] | Batch, batch_size: int = 512) -> np.ndarray:
    """봇 확률 [N]"""
    b = subjects if isinstance(subjects, Batch) else to_batch(subjects)
    device = next(model.parameters()).device
    model.eval()
    ty, st, dt, ln, cx, _ = _tensors(b, device)
    out = []
    for i in range(0, len(ln), batch_size):
        sl = slice(i, i + batch_size)
        out.append(torch.sigmoid(model(ty[sl], st[sl], dt[sl], ln[sl], cx[sl])).cpu().numpy())
    return np.concatenate(out) if out else np.zeros(0)


def fit(train: list[Subject], val: list[Subject], cfg: TrainConfig | None = None, log=print) -> BotDetector:
    cfg = cfg or TrainConfig()
    torch.manual_seed(cfg.seed)
    rng = np.random.default_rng(cfg.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = BotDetector(cfg.hidden).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=cfg.lr, weight_decay=cfg.weight_decay)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=cfg.epochs)
    tr = _tensors(to_batch(train), device)
    va = _tensors(to_batch(val), device)
    model.init_stats(tr[0], tr[2], tr[3], tr[5])      # 종류별 사람 기준 속도·통계 정규화 (학습 데이터에서만)
    pos = float(tr[5].sum())
    loss_fn = nn.BCEWithLogitsLoss(pos_weight=torch.tensor((len(train) - pos) / max(pos, 1.0), device=device))

    best, best_state = float("inf"), None
    n = len(train)
    for ep in range(cfg.epochs):
        model.train()
        perm = rng.permutation(n)
        total = 0.0
        for i in range(0, n, cfg.batch_size):
            idx = torch.from_numpy(perm[i:i + cfg.batch_size]).to(device)
            ty, st, dt, ln, cx, y = (t[idx] for t in tr)
            scale = torch.empty(len(idx), 1, device=device).uniform_(*cfg.scale_aug)
            loss = loss_fn(model(ty, st, dt * scale, ln, cx), y)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            total += float(loss.detach()) * len(idx)
        sched.step()
        model.eval()
        with torch.no_grad():
            vl = float(loss_fn(model(*va[:5]), va[5]))
        if vl < best:
            best, best_state = vl, copy.deepcopy(model.state_dict())
        if log and (ep % 5 == 4 or ep == cfg.epochs - 1):
            log(f"  epoch {ep + 1:>2}/{cfg.epochs}  train loss {total / n:.4f}  val loss {vl:.4f}")
    model.load_state_dict(best_state)
    return model.cpu()


def pick_threshold(human_probs: np.ndarray, max_fpr: float) -> float:
    """검증용 사람 점수에서 오탐률이 max_fpr 이하가 되는 가장 낮은 문턱"""
    if len(human_probs) == 0:
        raise ValueError("검증용 사람 데이터가 없습니다")
    return float(np.quantile(human_probs, 1.0 - max_fpr, method="higher"))


def save(model: BotDetector, path: Path, threshold: float, cfg: TrainConfig, extra: dict | None = None):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"state": model.state_dict(), "threshold": threshold, "cfg": asdict(cfg), "extra": extra or {}}, path)
    path.with_suffix(".json").write_text(json.dumps({"threshold": threshold, "cfg": asdict(cfg),
                                                     "extra": extra or {}}, ensure_ascii=False, indent=2),
                                         encoding="utf-8")


def load(path: Path) -> tuple[BotDetector, float]:
    ck = torch.load(path, map_location="cpu", weights_only=True)
    model = BotDetector(ck["cfg"]["hidden"])
    model.load_state_dict(ck["state"])
    model.eval()
    return model, float(ck["threshold"])
