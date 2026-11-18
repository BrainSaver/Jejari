#!/usr/bin/env python3
"""행동 로그(BehaviorEvent JSON 한 줄씩)에 위험 점수를 매긴다. 조회·출력만 하고 아무것도 고치지 않는다.

    mongoexport --collection behavior_log ... > events.jsonl
    python score.py --model out/model.pt --input events.jsonl --top 20

출력은 대상(회원 또는 기기)마다 JSON 한 줄: key, score(0~100), flag, reasons(model·shared_device), events.
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


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="out/model.pt")
    ap.add_argument("--input", required=True)
    ap.add_argument("--top", type=int, default=0, help="점수 높은 순 N개만 (0이면 전부)")
    a = ap.parse_args(argv)
    model, thr = training.load(Path(a.model))
    rows = score_file(model, thr, a.input)
    for r in rows[:a.top] if a.top else rows:
        print(json.dumps(r, ensure_ascii=False))
    print(f"# 대상 {len(rows)}개, 문턱 이상 {sum(r['flag'] for r in rows)}개", file=sys.stderr)


if __name__ == "__main__":
    main()
