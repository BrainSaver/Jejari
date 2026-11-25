#!/usr/bin/env python3
"""해명 모델(ML_EXON)이 왜 어떤 사람을 못 풀었는지 본다. 학습하지 않고 이미 학습한 모델로 점수만 매긴다.

    python3 tools/ml/diagnose_exon.py data/ml/model-real-r8/model.pkl <폴더>/features.csv [종류,종류,…]
    → 종류마다: 해명 그룹에 들었나 · 강한 증거로 막혔나 · 해명 확률이 문턱을 넘었나 · 해명 전·후 표시 · 막힌 계정의 근거

계정마다 "못 풀린 이유"를 하나로 나눈다 (위에서부터 먼저 걸리는 것):
  해명 전에도 표시 안 됨 / 그룹 밖 (조건 미달) / 강한 증거(기기·카드 공유) / 해명 확률 < 문턱 / 풀렸는데 상한(cap)이 문턱 이상
"""
from __future__ import annotations

import os
import pickle
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from score import gru_scores, parts, row_reasons  # noqa: E402

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


def cond_mask(feats, X, conds):
    m = np.ones(len(X), dtype=bool)
    for f, op, v in conds:
        if f not in feats:
            return np.zeros(len(X), dtype=bool)
        col = np.nan_to_num(X[:, feats.index(f)], nan=-np.inf if op == ">=" else np.inf)
        m &= (col >= v) if op == ">=" else (col <= v)
    return m


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    from compare import load
    pkl, feats_csv = sys.argv[1], sys.argv[2]
    want = sys.argv[3].split(",") if len(sys.argv) > 3 else None
    with open(pkl, "rb") as f:
        model = pickle.load(f)
    rows, _ = load(feats_csv)
    feats = model["features"]
    X = np.array([[float(r[c]) if r[c] not in ("", "nan") else np.nan for c in feats] for r in rows])
    seq, why = gru_scores(model, pkl, rows, os.path.dirname(feats_csv))
    P = parts(model, X, seq)
    base = np.maximum.reduce(list(P.values()))
    by = [max(P, key=lambda k: P[k][j]) for j in range(len(rows))]
    thr = model["threshold"] if seq is not None else model.get("threshold_base", model["threshold"])
    ex = model.get("exon") or {"segments": [], "hard": [], "cap": None}
    cap = ex["cap"]

    hard = np.zeros(len(X), dtype=bool)
    for c in ex["hard"]:
        hard |= cond_mask(feats, X, [c])
    hard_by = {c[0]: cond_mask(feats, X, [c]) for c in ex["hard"]}
    seg_in, seg_p = {}, {}
    for seg in ex["segments"]:
        m = cond_mask(feats, X, seg["conds"])
        seg_in[seg["name"]] = m
        p = np.full(len(X), np.nan)
        if m.any():
            p[m] = seg["clf"].predict_proba(X[m])[:, 1]
        seg_p[seg["name"]] = p
    # score.exon_mask와 같은 순서: 앞 그룹에서 풀리면 뒤 그룹은 안 봄
    cleared = np.zeros(len(X), dtype=bool)
    blocked = np.zeros(len(X), dtype=bool)   # 든 그룹 어디서든 강한 증거로 막힘
    for seg in ex["segments"]:
        h = np.zeros(len(X), dtype=bool)
        for c in ex["hard"]:
            if c[0] not in seg.get("allow_hard", ()):
                h |= cond_mask(feats, X, [c])
        blocked |= seg_in[seg["name"]] & h
        m = seg_in[seg["name"]] & ~h & ~cleared
        cleared[m] = seg_p[seg["name"]][m] >= seg["q"]
    allow = {s["name"]: s.get("allow_hard", ()) for s in ex["segments"]}
    blocked_in = lambda s, j: any(hard_by[c[0]][j] for c in ex["hard"] if c[0] not in allow[s])
    final = np.where(cleared, np.minimum(base, cap), base) if cap is not None else base

    print(f"모델 {pkl} · 문턱 {thr:.1f}점 · 해명 상한 {cap} · 쓴 모델 {' + '.join(P)}"
          + (f" (GRU 빠짐: {why})" if why and model.get("gru") else ""))
    print("해명 그룹: " + " · ".join(f"{s['name']} {s['conds']} 문턱 P(사람)≥{s['q']:.3f}" for s in ex["segments"]))
    print(f"강한 증거: {ex['hard']}\n")

    strategies = want or sorted({r["strategy"] for r in rows if r["label"] == "NORMAL"})
    for k in strategies:
        idx = [j for j, r in enumerate(rows) if r["strategy"] == k]
        if not idx:
            print(f"## {k}: 계정 없음\n")
            continue
        n = len(idx)
        pre = sum(base[j] >= thr for j in idx)
        post = sum(final[j] >= thr for j in idx)
        print(f"## {k} ({rows[idx[0]]['label']}) — 계정 {n} · 해명 전 표시 {pre} ({100 * pre / n:.1f}%) → 해명 뒤 {post} ({100 * post / n:.1f}%)")
        for name in seg_in:
            ins = [j for j in idx if seg_in[name][j]]
            ps = [seg_p[name][j] for j in ins]
            q = next(s["q"] for s in ex["segments"] if s["name"] == name)
            print(f"   그룹 {name}: 든 계정 {len(ins)}/{n}" + (
                f" · P(사람) 중앙값 {np.median(ps):.3f} · 문턱 넘음 {sum(p >= q for p in ps)}" if ins else ""))
        print("   강한 증거: " + " · ".join(f"{f} {sum(hard_by[f][j] for j in idx)}" for f in hard_by))
        for f in ("seats_paid_ratio", "n_cancel", "accounts_on_device", "accounts_on_card"):
            if f in feats:
                col = X[idx, feats.index(f)]
                print(f"   {f}: 값 분포 {dict(sorted(Counter(np.round(col, 2).tolist()).items()))}")
        reason = Counter()
        flagged_rows = []
        for j in idx:
            if final[j] < thr:
                continue
            flagged_rows.append(j)
            if base[j] < thr:
                why_k = "?"
            elif not any(seg_in[s][j] for s in seg_in):
                why_k = "그룹 밖 (조건 미달)"
            elif blocked[j] and not any(seg_in[s][j] and not blocked_in(s, j) for s in seg_in):
                why_k = "강한 증거(기기·카드 공유)"
            elif not cleared[j]:
                why_k = "해명 확률 < 문턱"
            else:
                why_k = "풀렸는데 상한이 문턱 이상"
            reason[why_k] += 1
        print("   해명 뒤에도 표시된 이유: " + (" · ".join(f"{a} {b}" for a, b in reason.most_common()) or "없음"))
        top = Counter(by[j] for j in flagged_rows)
        if top:
            print("   표시된 계정에서 가장 높게 본 모델: " + " · ".join(f"{a} {b}" for a, b in top.most_common()))
        rc = defaultdict(int)
        for j in flagged_rows:
            for x in row_reasons(model, X[j], by[j]):
                rc["GRU 행동 순서" if "(GRU)" in x else x.split(" (")[0].rsplit(" ", 1)[0]] += 1   # "같은 기기 계정 3 (사람 1~2)" → "같은 기기 계정"
        if rc:
            print("   근거로 자주 나온 특징: " + " · ".join(f"{a} {b}" for a, b in sorted(rc.items(), key=lambda t: -t[1])[:6]))
        for j in flagged_rows[:5]:
            segs = ",".join(f"{s}:{seg_p[s][j]:.2f}" for s in seg_in if seg_in[s][j]) or "-"
            print(f"     예) {rows[j]['member_id']} 점수 {base[j]:.1f}→{final[j]:.1f} [{by[j]}] 그룹 {segs}"
                  f"{' 강한증거' if hard[j] else ''}: " + " · ".join(row_reasons(model, X[j], by[j])))
        print()


if __name__ == "__main__":
    main()
