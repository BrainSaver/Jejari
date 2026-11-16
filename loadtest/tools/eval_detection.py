#!/usr/bin/env python3
"""봇 시뮬레이션 정답지(ground_truth.csv)와 서버의 탐지 결과를 비교해 탐지율·오탐률을 계산한다.

입력
  --truth     data/ground_truth.csv  (userId,label[,memberId])
  --suspects  서버가 '의심'으로 판정한 사용자 목록. JSON(리스트 또는 {"items":[...]}) 또는 CSV.
              각 항목은 문자열 ID 이거나, --id-field 로 지정한 필드를 가진 객체.
  --map       (선택) 서버가 HMAC 가명값만 보여줄 때: pseudo_id,userId 두 열의 CSV

목표(기획서): 탐지율 90% 이상, 오탐률 5% 이하. 미달이면 종료 코드 1.
"""
import argparse
import csv
import json
import sys
from collections import defaultdict


def load_suspects(path: str, id_field: str) -> set:
    with open(path, encoding="utf-8") as f:
        text = f.read()
    ids = set()
    if path.lower().endswith(".csv"):
        for row in csv.DictReader(text.splitlines()):
            ids.add(row[id_field].strip())
        return ids
    data = json.loads(text)
    if isinstance(data, dict):
        data = data.get("items") or data.get("content") or data.get("data") or []
    for item in data:
        ids.add(str(item[id_field] if isinstance(item, dict) else item).strip())
    return ids


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--truth", default="data/ground_truth.csv")
    p.add_argument("--suspects", required=True)
    p.add_argument("--id-field", default="userId")
    p.add_argument("--map")
    p.add_argument("--min-detect", type=float, default=0.90)
    p.add_argument("--max-false-positive", type=float, default=0.05)
    a = p.parse_args()

    with open(a.truth, encoding="utf-8") as f:
        truth = {r["userId"]: r["label"] for r in csv.DictReader(f)}

    # 개발 모드에서는 서버가 회원 ID(memberId)만 알 수 있어서, 정답지의 memberId도 userId로 바꿔 준다
    with open(a.truth, encoding="utf-8") as f:
        member_to_user = {r["memberId"]: r["userId"] for r in csv.DictReader(f) if r.get("memberId")}

    suspects = load_suspects(a.suspects, a.id_field)
    suspects = {member_to_user.get(s, s) for s in suspects}
    if a.map:
        with open(a.map, encoding="utf-8") as f:
            m = {r["pseudo_id"]: r["userId"] for r in csv.DictReader(f)}
        suspects = {m[s] for s in suspects if s in m}

    bots = {u for u, l in truth.items() if l != "normal"}
    normals = set(truth) - bots
    detected = bots & suspects
    false_pos = normals & suspects

    per_label = defaultdict(lambda: [0, 0])
    for u in bots:
        per_label[truth[u]][1] += 1
        if u in suspects:
            per_label[truth[u]][0] += 1

    detect_rate = len(detected) / len(bots) if bots else 0.0
    fp_rate = len(false_pos) / len(normals) if normals else 0.0

    print(f"봇 {len(bots)}명 중 {len(detected)}명 탐지  → 탐지율 {detect_rate:.1%}")
    for label, (hit, total) in sorted(per_label.items()):
        print(f"  - {label:<11} {hit}/{total} ({hit / total:.1%})")
    print(f"정상 {len(normals)}명 중 {len(false_pos)}명 오탐 → 오탐률 {fp_rate:.1%}")

    ok = detect_rate >= a.min_detect and fp_rate <= a.max_false_positive
    print("결과:", "목표 달성" if ok else "목표 미달")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
