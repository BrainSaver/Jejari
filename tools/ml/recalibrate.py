#!/usr/bin/env python3
"""① 사람 기준을 실데이터로 다시 맞춘 후보 모델 — 다시 학습하지 않고 "사람이면 보통 이 정도"만 바꾼다.

    python3 tools/ml/recalibrate.py <기준 모델 폴더> <실데이터 폴더> <출력 폴더> [--labels labels.jsonl] [--fp 0.02]

    실데이터 폴더 = tools/ml/export_live.sh --recent N 이 만든 것 + features.py (모델이 쓰는 특징 칸이 있어야 함)

왜: 지금 모델의 점수는 "생성기가 지어낸 사람" 기준 백분위다. 실제 사람이 그보다 빠르거나 느리면 그대로 오탐·미탐이 된다.
    실데이터는 대부분 사람이므로, 모델 셋(부스팅·IF·GRU)의 원래 출력을 실제 계정들에서 다시 재서 백분위 기준·문턱·근거 범위를
    바꾼다. 사용자가 많아질수록 이 기준이 실제 사람에 가까워진다.

사람 기준에 넣는 계정 (봇이 섞이면 봇을 "평범한 사람"으로 배워 탐지가 무너진다)
  - 기준 모델이 사람 쪽으로 본 계정 + 관리자가 사람으로 판정한 계정 (오탐을 바로잡는 통로)
  - 관리자가 봇으로 판정한 계정, 기준 모델이 봇 쪽으로 봤는데 판정이 없는 계정은 뺀다
  - 남은 것 중 점수 위 TRIM(2%)도 뺀다 (아직 안 잡힌 봇일 수 있음)
문턱: 남은 "사람으로 보이는" 실제 계정에서 봇 쪽으로 보는 비율이 --fp(기본 2%)가 되는 점수.
      생성 데이터 기준의 5%보다 낮게 잡는다 — 실데이터에는 정답이 없어, 이 비율이 곧 "의심 받는 사람" 상한이 된다.
출력: model.pkl(+ gru.pt · type.pkl 복사) · recalibrate.json. 기준 모델 파일은 바꾸지 않는다.
"""
from __future__ import annotations

import argparse
import copy
import json
import os
import pickle
import shutil
import sys
from datetime import datetime

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from compare import load  # noqa: E402
from score import gru_scores, iso_x, score_matrix  # noqa: E402

TRIM = 0.02
MIN_ACCOUNTS = 200


def judged(path):
    """관리자 판정: (봇으로 판정한 계정, 사람으로 판정한 계정)"""
    if not path or not os.path.exists(path):
        return set(), set()
    with open(path, encoding="utf-8") as f:
        ds = [json.loads(l) for l in f if l.strip()]
    return {d["member_id"] for d in ds if d.get("is_bot")}, {d["member_id"] for d in ds if not d.get("is_bot")}


def recalibrate(base_dir, real_dir, out_dir, labels=None, fp=0.02, quiet=False):
    with open(os.path.join(base_dir, "model.pkl"), "rb") as f:
        model = pickle.load(f)
    rows, _ = load(os.path.join(real_dir, "features.csv"))
    missing = [c for c in model["features"] if rows and c not in rows[0]]
    if missing:
        raise SystemExit(f"실데이터 features.csv에 모델 특징이 없음: {missing[:5]} (ML_STAGE=1로 features.py)")
    X = np.array([[float(r[c]) if r[c] not in ("", "nan") else np.nan for c in model["features"]] for r in rows])
    seq, why = gru_scores(model, os.path.join(base_dir, "model.pkl"), rows, real_dir)
    if model.get("gru") and seq is None:
        raise SystemExit(f"GRU 출력을 못 구함 ({why}) — GRU가 있는 모델은 events.jsonl·torch가 필요")

    bots, humans = judged(labels)
    ids = np.array([int(r["member_id"]) for r in rows])
    keep = ~np.isin(ids, list(bots))
    before = score_matrix(model, X, seq)
    # 사람 기준 = 기준 모델이 사람 쪽으로 본 계정 + 관리자가 사람으로 판정한 계정.
    # 기준 모델이 봇 쪽으로 봤고 아무도 판정하지 않은 계정은 뺀다 — 봇이 많이 섞인 날 봇을 "평범한 사람"으로
    # 배워 탐지가 무너지는 것을 막으려고 (봇 20% 섞인 시험에서 상위 2%만 뺐더니 탐지 98 → 12%로 떨어짐)
    human = keep & ((before < model["threshold"]) | np.isin(ids, list(humans)))
    # 그래도 남는 미판정 봇에 대비해 점수 순위 위 TRIM은 한 번 더 뺀다 (판정된 사람은 빼지 않음)
    idx = np.flatnonzero(human & ~np.isin(ids, list(humans)))
    top = idx[np.argsort(-before[idx], kind="stable")[: int(np.ceil(TRIM * len(idx)))]]
    human[top] = False
    n = int(human.sum())
    if n < MIN_ACCOUNTS:
        raise SystemExit(f"사람으로 볼 실제 계정이 {n}개뿐 (< {MIN_ACCOUNTS}). 사용자가 더 쌓인 뒤 다시")

    new = copy.deepcopy(model)
    Xh = X[human]
    new["ref_gbm"] = np.sort(model["gbm"].predict_proba(Xh)[:, 1])
    new["ref_iso"] = np.sort(-model["iso"].score_samples(iso_x(model, Xh)))
    if seq is not None:
        new["gru"]["ref"] = np.sort(np.asarray(seq)[human])
    new["human_q"] = {f: [float(np.nanquantile(Xh[:, i], q)) for q in (0.05, 0.5, 0.95)]
                      for i, f in enumerate(model["features"])}
    after_h = score_matrix(new, Xh, None if seq is None else np.asarray(seq)[human])
    new["threshold"] = float(np.quantile(after_h, 1 - fp))
    new["threshold_base"] = float(np.quantile(score_matrix(new, Xh, None), 1 - fp))
    after_all = score_matrix(new, X, seq)
    info = {
        "at": datetime.now().isoformat(timespec="seconds"), "base": os.path.abspath(base_dir),
        "real_accounts": len(rows), "judged_bots_excluded": int((~keep).sum()),
        "judged_humans_kept": int((human & np.isin(ids, list(humans))).sum()), "trimmed": int((keep & ~human).sum()),
        "human_reference": n, "fp_target": fp,
        "threshold": {"before": round(float(model["threshold"]), 2), "after": round(new["threshold"], 2)},
        "flagged_real": {"before": round(float((before >= model["threshold"]).mean()), 4),
                         "after": round(float((after_all >= new["threshold"]).mean()), 4)},
    }
    new["calibrated"] = info
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "model.pkl"), "wb") as f:
        pickle.dump(new, f)
    for extra in ("gru.pt", "type.pkl"):
        p = os.path.join(base_dir, extra)
        if os.path.exists(p) and os.path.abspath(p) != os.path.abspath(os.path.join(out_dir, extra)):
            shutil.copyfile(p, os.path.join(out_dir, extra))
    with open(os.path.join(out_dir, "recalibrate.json"), "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=1)
    if not quiet:
        print(f"사람 기준 갱신: 실제 계정 {len(rows)} (판정 봇 {info['judged_bots_excluded']} · 봇 쪽·상위 {info['trimmed']} 뺌, "
              f"판정 사람 {info['judged_humans_kept']} 넣음) → 기준 {n}명")
        print(f"  문턱 {info['threshold']['before']} → {info['threshold']['after']} · 실데이터에서 봇 쪽으로 봄 "
              f"{info['flagged_real']['before']:.1%} → {info['flagged_real']['after']:.1%}")
        print(f"→ {out_dir}")
    return info


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("base"); ap.add_argument("real"); ap.add_argument("out")
    ap.add_argument("--labels"); ap.add_argument("--fp", type=float, default=0.02)
    a = ap.parse_args()
    if os.path.abspath(a.out) == os.path.abspath(a.base):
        sys.exit("기준 모델 폴더에 덮어쓰지 않습니다. 다른 출력 폴더를 주세요")
    recalibrate(a.base, a.real, a.out, a.labels, a.fp)


if __name__ == "__main__":
    main()
