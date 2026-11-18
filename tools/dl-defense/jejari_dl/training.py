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


# ------------------------------------------------------------------ 모델 여러 개 OR 결합 · 단계형 판정

def fit_union_thresholds(ps: list[np.ndarray], target_fpr: float, spec_fpr: float) -> list[float]:
    """보정 사람의 점수 ps[0](주 모델), ps[1:](전용 모델들)로 OR 결합 문턱을 고른다.

    전용 모델마다 사람의 spec_fpr 가 넘는 값을 문턱으로 하고, 합친 오탐이 target_fpr 이하가 되는 가장 낮은 값을
    주 모델 문턱으로 한다. spec_fpr 가 0이면 전용 모델은 끈다(문턱 무한대).
    """
    if not ps:
        raise ValueError("점수가 없습니다")
    n = len(ps[0])
    thrs = [None]
    hit = np.zeros(n, dtype=bool)
    for p in ps[1:]:
        t = float(np.quantile(p, 1 - spec_fpr, method="higher")) if spec_fpr > 0 else float("inf")
        thrs.append(t)
        hit |= p >= t
    cand = np.sort(np.unique(ps[0]))
    t_main = float(cand[-1]) + 1e-6
    for t in cand:
        if ((ps[0] >= t) | hit).mean() <= target_fpr:
            t_main = float(t)
            break
    thrs[0] = t_main
    return thrs


def assign_tier(ps: list[np.ndarray], hard: list[float], soft: list[float]) -> np.ndarray:
    """0=정상, 1=약한 불이익 후보(soft 문턱 이상), 2=강한 불이익 후보(hard 문턱 이상). soft는 hard보다 느슨하다(포함)"""
    if len(ps) != len(hard) or len(hard) != len(soft):
        raise ValueError("모델 수와 문턱 수가 다릅니다")
    h = np.any([p >= t for p, t in zip(ps, hard)], axis=0)
    s = np.any([p >= min(t, th) for p, t, th in zip(ps, soft, hard)], axis=0)    # soft ⊇ hard 보장
    return np.where(h, 2, np.where(s, 1, 0))
