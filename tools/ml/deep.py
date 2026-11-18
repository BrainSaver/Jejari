"""딥러닝 위험 점수 모델 — 이벤트 시퀀스를 읽는 GRU, 그리고 요약 특징을 함께 쓰는 하이브리드.

    pip install torch scikit-learn numpy
    python3 tools/ml/deep.py data/ml/g7          # compare.py와 같은 방식으로 비교 (계열 하나 빼기)

같은 폴더에 features.py를 먼저 돌려 features.csv가 있어야 한다.

왜 시퀀스인가
  compare.py의 MLP는 부스팅과 같은 요약 특징 32개를 받아서, 처음 보는 계열을 42%만 잡았다 (부스팅 79%).
  요약 특징으로는 결정 나무가 더 낫다. 딥러닝을 쓸 이유는 요약하면 사라지는 **순서**를 직접 보는 데 있다:
  "실패 → 같은 좌석 재시도 → 실패" 같은 반복, 둘러보기 없이 바로 대기열, 간격이 이어지는 모양.

모델
  - GRU (시퀀스만): 이벤트 한 칸(seq.STEP_FEATURES) → 선형 → 양방향 GRU → 평균·최대 풀링 → 봇일 확률
  - 하이브리드: 위 GRU 요약 + 요약 특징(train.py와 같은 목록)을 이어 붙여 판단. 계정 사이 연결(같은 기기·카드)은
    시퀀스 하나로는 안 보여서 요약 특징에서 가져온다

평가는 compare.py와 같다: 사람은 묶음 단위 60/20/20, 문턱 = 사람 검증 세트 오탐 5%, "봇 계열 하나 빼기".
부스팅 + Isolation Forest(train.py의 본 모델)와 같은 표에 놓고, 셋을 합친 것도 본다.
"""
from __future__ import annotations

import argparse
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import numpy as np
    import torch
    from torch import nn
    from sklearn.ensemble import HistGradientBoostingClassifier, IsolationForest
    from sklearn.impute import SimpleImputer
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
except ImportError as e:
    sys.exit(f"필요한 패키지가 없습니다 ({e.name}): pip install torch scikit-learn numpy")

from compare import FP_TARGET, load, split_humans  # noqa: E402
from seq import MAX_LEN, STEP_DIM, load_sequences  # noqa: E402
from train import COLS  # noqa: E402  (요약 특징에서 뺀 것은 train.py와 같게)

SEED = 7
torch.set_num_threads(max(1, os.cpu_count() or 1))


# ---------------------------------------------------------------- 모델

class SeqNet(nn.Module):
    def __init__(self, n_tab: int = 0, hid: int = 48):
        super().__init__()
        self.inp = nn.Sequential(nn.Linear(STEP_DIM, 32), nn.ReLU())
        self.gru = nn.GRU(32, hid, batch_first=True, bidirectional=True)
        self.tab = nn.Sequential(nn.Linear(n_tab, 32), nn.ReLU()) if n_tab else None
        self.head = nn.Sequential(nn.Linear(4 * hid + (32 if n_tab else 0), 32), nn.ReLU(),
                                  nn.Dropout(0.2), nn.Linear(32, 1))

    def forward(self, x, mask, tab=None):
        h, _ = self.gru(self.inp(x))                       # [B, T, 2H]
        m = mask.unsqueeze(-1)
        mean = (h * m).sum(1) / m.sum(1).clamp(min=1)
        mx = h.masked_fill(m == 0, -1e4).max(1).values
        z = [mean, mx]
        if self.tab is not None:
            z.append(self.tab(tab))
        return self.head(torch.cat(z, 1)).squeeze(1)


def pad(seqs):
    n = len(seqs)
    x = np.zeros((n, MAX_LEN, STEP_DIM), dtype=np.float32)
    mask = np.zeros((n, MAX_LEN), dtype=np.float32)
    for i, s in enumerate(seqs):
        if s:
            x[i, :len(s)] = s
            mask[i, :len(s)] = 1
    return torch.from_numpy(x), torch.from_numpy(mask)


class Deep:
    """sklearn 모양(fit · predict_proba)으로 감싼 학습기. tab=None이면 시퀀스만"""

    def __init__(self, hybrid: bool, epochs: int = 14, seed: int = SEED):
        self.hybrid, self.epochs, self.seed = hybrid, epochs, seed

    def _tab(self, T, fit=False):
        if fit:
            self.prep = make_pipeline(SimpleImputer(strategy="median"), StandardScaler()).fit(T)
        return torch.from_numpy(np.clip(self.prep.transform(T), -5, 5).astype(np.float32))

    def fit(self, X, T, y):
        torch.manual_seed(self.seed)
        rng = np.random.default_rng(self.seed)
        x, mask = X
        tab = self._tab(T, fit=True) if self.hybrid else None
        self.net = SeqNet(n_tab=tab.shape[1] if self.hybrid else 0)
        opt = torch.optim.Adam(self.net.parameters(), lr=2e-3, weight_decay=1e-4)
        yt = torch.from_numpy(y.astype(np.float32))
        pos = float(y.sum())
        loss_fn = nn.BCEWithLogitsLoss(pos_weight=torch.tensor((len(y) - pos) / max(pos, 1.0)))
        self.net.train()
        for _ in range(self.epochs):
            for b in np.array_split(rng.permutation(len(y)), max(1, len(y) // 128)):
                bi = torch.from_numpy(b)
                out = self.net(x[bi], mask[bi], tab[bi] if tab is not None else None)
                loss = loss_fn(out, yt[bi])
                opt.zero_grad()
                loss.backward()
                nn.utils.clip_grad_norm_(self.net.parameters(), 1.0)
                opt.step()
        return self

    @torch.no_grad()
    def predict(self, X, T):
        self.net.eval()
        x, mask = X
        tab = self._tab(T) if self.hybrid else None
        return torch.sigmoid(self.net(x, mask, tab)).numpy()


# ---------------------------------------------------------------- 평가

def take(X, idx):
    return X[0][idx], X[1][idx]


def pct_of(ref, s):
    """사람 기준 점수(ref)에서 몇 백분위인가 (train.py·score.py와 같은 합치는 방식)"""
    ref = np.sort(ref)
    return 100.0 * np.searchsorted(ref, s, side="right") / len(ref)


def evaluate(folder: str, epochs: int):
    rows, Xall = load(os.path.join(folder, "features.csv"))
    T = Xall[:, COLS]
    seqs = load_sequences(folder)
    S = pad([seqs.get(int(r["member_id"]), []) for r in rows])
    is_bot = np.array([r["label"] != "NORMAL" for r in rows])
    fam = np.array([r.get("family") or "" for r in rows])
    hs = np.array([s or "" for s in split_humans(rows, random.Random(SEED))])
    families = sorted({f for f, b in zip(fam, is_bot) if b})
    val = np.flatnonzero(hs == "val")
    rng = np.random.default_rng(SEED)
    rng.shuffle(val)
    ref_i, thr_i = val[: len(val) // 2], val[len(val) // 2:]   # 백분위 기준 / 문턱 (train.py와 같게 반씩)
    te_h = hs == "test"
    print(f"계정 {len(rows)} (사람 {int((~is_bot).sum())} · 봇 {int(is_bot.sum())}) · 이벤트 칸 {STEP_DIM} · "
          f"요약 특징 {T.shape[1]} · 문턱 = 사람 오탐 {FP_TARGET:.0%} · 에폭 {epochs}\n")

    names = ["부스팅", "부스팅+IF (지금 모델)", "GRU (시퀀스만)", "GRU 하이브리드", "부스팅+IF+GRU", "부스팅+IF+하이브리드"]
    res = {n: {} for n in names}
    for f in families:
        tr = (hs == "train") | (is_bot & (fam != f))
        y = is_bot[tr].astype(int)
        gbm = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(T[tr], y)
        iso = make_pipeline(SimpleImputer(strategy="median"), StandardScaler(),
                            IsolationForest(n_estimators=300, random_state=SEED)).fit(T[hs == "train"])
        g = gbm.predict_proba(T)[:, 1]
        i = -iso.score_samples(T)
        seq = Deep(hybrid=False, epochs=epochs).fit(take(S, tr), T[tr], y).predict(S, T)
        hyb = Deep(hybrid=True, epochs=epochs).fit(take(S, tr), T[tr], y).predict(S, T)
        P = {k: pct_of(v[ref_i], v) for k, v in {"g": g, "i": i, "s": seq, "h": hyb}.items()}
        scores = {
            names[0]: g, names[1]: np.maximum(P["g"], P["i"]),
            names[2]: seq, names[3]: hyb,
            names[4]: np.maximum.reduce([P["g"], P["i"], P["s"]]),
            names[5]: np.maximum.reduce([P["g"], P["i"], P["h"]]),
        }
        tgt = is_bot & (fam == f)
        for n, s in scores.items():
            thr = float(np.quantile(s[thr_i], 1 - FP_TARGET))
            res[n][f] = (int((s[tgt] >= thr).sum()), int(tgt.sum()), float((s[te_h] >= thr).mean()))
        print(f"  {f:<14} " + "  ".join(f"{n.split(' ')[0]} {100 * res[n][f][0] / res[n][f][1]:5.1f}%" for n in names), flush=True)

    print("\n계열 하나 빼기 — 학습 때 못 본 계열을 잡은 비율 / 사람(시험) 오탐")
    print(f"  {'계열':<14}" + "".join(f"{n:>24}" for n in names))
    for f in families:
        print(f"  {f:<14}" + "".join(f"{f'{res[n][f][0]}/{res[n][f][1]} {100 * res[n][f][0] / res[n][f][1]:5.1f}%':>24}" for n in names))
    line = []
    for n in names:
        hit = sum(h for h, _, _ in res[n].values())
        tot = sum(t for _, t, _ in res[n].values())
        fp = sum(p for _, _, p in res[n].values()) / len(res[n])
        line.append(f"{100 * hit / tot:5.1f}% / {100 * fp:4.1f}%")
    print(f"  {'전체(탐지/오탐)':<14}" + "".join(f"{c:>24}" for c in line))


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("folder", help="생성 데이터 폴더 (events.jsonl · members.jsonl · features.csv)")
    p.add_argument("--epochs", type=int, default=14)
    a = p.parse_args()
    evaluate(a.folder, a.epochs)


if __name__ == "__main__":
    main()
