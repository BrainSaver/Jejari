#!/usr/bin/env python3
"""행동 로그(BehaviorEvent JSON 한 줄씩)에 위험 점수를 매긴다. 조회·출력만 하고 아무것도 고치지 않는다.

    mongoexport --collection behavior_log ... > events.jsonl
    python score.py --model out/model.pt --input events.jsonl --top 20

출력은 대상(회원 또는 기기)마다 JSON 한 줄: key, score(0~100), flag, reasons(model·shared_device), events.
--union 이면 단계형: tier(0 정상 · 1 약한 불이익 후보 · 2 강한 불이익 후보)와 모델별 점수(scores)를 더 낸다.
이메일·라벨·UA는 읽지 않는다. 점수는 참고용이며 규칙 엔진·매수 제한을 대신하지 않는다.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from jejari_dl import realdata, training  # noqa: E402


# 한 기기에서 60초 안에 서로 다른 계정 2개 이상이 선점하면 모델과 별개로 표시한다.
# 모델이 "처음 보는 공유 수법"을 일반화하지 못하는 것이 leave-one-family-out 평가에서 확인됐기 때문이다.
SHARED_DEVICE_SYNC = 2


def score_file(model, thr, path):
    subjects = realdata.load_file(Path(path))
    if not subjects:
        return []
    probs = training.predict(model, subjects)
    best, counts, sync = {}, {}, {}
    for s, p in zip(subjects, probs):
        best[s.key] = max(best.get(s.key, 0.0), float(p))
        counts[s.key] = counts.get(s.key, 0) + len(s.types)
        sync[s.key] = max(sync.get(s.key, 0), s.sync)
    rows = []
    for k, p in best.items():
        reasons = (["model"] if p >= thr else []) + (["shared_device"] if sync[k] >= SHARED_DEVICE_SYNC else [])
        rows.append({"key": k, "score": int(round(100 * p)), "flag": bool(reasons), "reasons": reasons,
                     "events": counts[k]})
    rows.sort(key=lambda r: -r["score"])
    return rows


def score_file_union(spec, path):
    """OR 결합 + 단계형. spec은 calibrate_union.py 가 쓴 JSON의 models 목록.

    대상마다 tier(0 정상 · 1 약한 불이익 후보 · 2 강한 불이익 후보)를 낸다. 불이익의 크기는 서버가 정한다.
    """
    subjects = realdata.load_file(Path(path))
    if not subjects:
        return []
    models = [training.load(Path(m["path"]))[0] for m in spec]
    per_window = [training.predict(m, subjects) for m in models]
    keys = sorted({s.key for s in subjects})
    index = {k: i for i, k in enumerate(keys)}
    best = np.zeros((len(models), len(keys)))
    counts, sync = np.zeros(len(keys), dtype=int), np.zeros(len(keys), dtype=int)
    for j, s in enumerate(subjects):
        i = index[s.key]
        counts[i] += len(s.types)
        sync[i] = max(sync[i], s.sync)
        for m in range(len(models)):
            best[m, i] = max(best[m, i], per_window[m][j])
    hard = [m["thr"] for m in spec]
    soft = [m.get("soft_thr", m["thr"]) for m in spec]
    tier = training.assign_tier(list(best), hard, soft)
    rows = []
    for i, k in enumerate(keys):
        reasons = [m["name"] for m_i, m in enumerate(spec) if best[m_i, i] >= soft[m_i]]
        t = int(tier[i])
        if sync[i] >= SHARED_DEVICE_SYNC:
            reasons.append("shared_device")
            t = 2
        rows.append({"key": k, "tier": t, "score": int(round(100 * best[0, i])), "flag": t == 2, "reasons": reasons,
                     "scores": {m["name"]: round(float(best[m_i, i]), 4) for m_i, m in enumerate(spec)},
                     "events": int(counts[i])})
    rows.sort(key=lambda r: (-r["tier"], -r["score"]))
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="out/model.pt")
    ap.add_argument("--union", default=None, help="calibrate_union.py 가 만든 JSON. 있으면 --model 대신 단계형(tier 0·1·2)으로 낸다")
    ap.add_argument("--input", required=True)
    ap.add_argument("--top", type=int, default=0, help="점수 높은 순 N개만 (0이면 전부)")
    a = ap.parse_args(argv)
    if a.union:
        rows = score_file_union(json.loads(Path(a.union).read_text(encoding="utf-8"))["models"], a.input)
        for r in rows[:a.top] if a.top else rows:
            print(json.dumps(r, ensure_ascii=False))
        print(f"# 대상 {len(rows)}개 · 강한 단계 {sum(r['tier'] == 2 for r in rows)}개 · 약한 단계 {sum(r['tier'] == 1 for r in rows)}개",
              file=sys.stderr)
        return
    model, thr = training.load(Path(a.model))
    rows = score_file(model, thr, a.input)
    for r in rows[:a.top] if a.top else rows:
        print(json.dumps(r, ensure_ascii=False))
    print(f"# 대상 {len(rows)}개, 문턱 이상 {sum(r['flag'] for r in rows)}개", file=sys.stderr)


if __name__ == "__main__":
    main()
