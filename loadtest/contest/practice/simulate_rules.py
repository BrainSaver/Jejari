#!/usr/bin/env python3
"""제안 규칙 모의 시험 — 규칙을 코드로 넣기 전에, 꺼낸 행동 로그에 적용해 봇마다 무엇이 걸리는지 본다.

  python3 -I loadtest/contest/practice/simulate_rules.py data/ml/practice-<태그> [더 많은 폴더…] [--step 0.5]

입력: tools/ml/export_live.sh가 만든 폴더 (events.jsonl · members.jsonl). 종류(strategy) = 봇 이름, human = (합성) 사람.
규칙은 docs/강동균/features/연습봇-평가와-규칙-제안.md 3절의 제안을 그대로 옮긴 것이다. 실제 엔진(com.jejari.risk)은
CI·기기 단위로 1일 창을 보지만, 여기서는 계정 단위로 그 운행의 로그만 본다 (대회 한 번 = 하루 안).
"""
import argparse
import collections
import datetime as dt
import json
import os

CASE = {"INHUMAN_STEP"}  # 케이스를 여는(마커) 제안 규칙. 나머지는 점수만


def ts(e):
    return dt.datetime.fromisoformat(e["ts"].replace("Z", "+00:00")).timestamp()


def load(folder):
    kind = {}
    with open(os.path.join(folder, "members.jsonl"), encoding="utf-8") as f:
        for line in f:
            m = json.loads(line)
            kind[m["member_id"]] = m["strategy"]
    ev = collections.defaultdict(list)
    with open(os.path.join(folder, "events.jsonl"), encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            if e.get("member_id") in kind:
                ev[e["member_id"]].append(e)
    for es in ev.values():
        es.sort(key=ts)
    return kind, ev


def rules_for(es, ip_cis, a):
    hit = set()
    ok = lambda e: 200 <= e.get("response_status", 0) < 300
    # 1 INHUMAN_STEP: 좌석표 → 선점, 선점(성공) → 결제가 a.step초 미만인 일이 a.times번 이상
    fast = 0
    last_map = last_hold = None
    for e in es:
        t = e["event_type"]
        if t == "VIEW_SEAT":
            last_map = ts(e)
        elif t == "SEAT_HOLD":
            if last_map is not None and ts(e) - last_map < a.step:
                fast += 1
            last_map = None
            last_hold = ts(e) if ok(e) else None
        elif t == "PAY" and last_hold is not None:
            if ts(e) - last_hold < a.step:
                fast += 1
            last_hold = None
    if fast >= a.times:
        hit.add("INHUMAN_STEP")
    types = [e["event_type"] for e in es]
    # 2 NO_SEARCH_ENTRY
    if "QUEUE_ENTER" in types and "VIEW_TRAIN" not in types:
        hit.add("NO_SEARCH_ENTRY")
    # 3 DEVICE_ANOMALY: 기기 ID가 하나도 없거나 3개 이상
    devs = {e.get("device_hash") for e in es if e.get("device_hash")}
    if not devs or len(devs) >= 3:
        hit.add("DEVICE_ANOMALY")
    # 4 QUEUE_CHURN: 대기열 진입 3번 이상 또는 운행 3개 이상
    q = [e for e in es if e["event_type"] == "QUEUE_ENTER"]
    if len(q) >= 3 or len({e.get("run_id") for e in q}) >= 3:
        hit.add("QUEUE_CHURN")
    # 5 HOLD_NO_PAY: 성공한 선점 - 결제가 3 이상 (결제 전 취소·만료)
    holds = sum(1 for e in es if e["event_type"] == "SEAT_HOLD" and ok(e))
    pays = sum(1 for e in es if e["event_type"] == "PAY" and ok(e))
    if holds - pays >= 3:
        hit.add("HOLD_NO_PAY")
    # 6 SHARED_IP: 이 계정이 선점한 IP에서 서로 다른 CI 3개 이상이 선점
    ips = {e.get("ip_hash") for e in es if e["event_type"] == "SEAT_HOLD" and e.get("ip_hash")}
    if any(len(ip_cis[i]) >= 3 for i in ips):
        hit.add("SHARED_IP")
    return hit


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folders", nargs="+")
    ap.add_argument("--step", type=float, default=0.5, help="INHUMAN_STEP 문턱(초)")
    ap.add_argument("--times", type=int, default=2, help="INHUMAN_STEP 최소 횟수")
    a = ap.parse_args()
    per = collections.defaultdict(lambda: {"n": 0, "case": 0, "rules": collections.Counter()})
    for folder in a.folders:
        kind, ev = load(folder)
        ip_cis = collections.defaultdict(set)
        for es in ev.values():
            for e in es:
                if e["event_type"] == "SEAT_HOLD" and e.get("ip_hash") and e.get("ci_hash"):
                    ip_cis[e["ip_hash"]].add(e["ci_hash"])
        for mid, es in ev.items():
            k = kind[mid]
            hit = rules_for(es, ip_cis, a)
            per[k]["n"] += 1
            per[k]["case"] += bool(hit & CASE)
            per[k]["rules"].update(hit)
    print(f"제안 규칙 모의 시험 (INHUMAN_STEP: {a.step}초 미만 {a.times}번 이상 = 케이스, 나머지는 점수만)\n")
    print(f"{'종류':16}{'계정':>4}  {'케이스':>6}  걸린 규칙 (계정 수)")
    caught = total = 0
    for k in sorted(per, key=lambda k: (k == "human", k)):
        r = per[k]
        mark = "" if k == "human" else ("  ← 잡힘" if r["case"] == r["n"] else ("  ← 일부" if r["case"] else "  ← 통과"))
        rules = " · ".join(f"{x} {c}" for x, c in r["rules"].most_common()) or "-"
        print(f"{k:16}{r['n']:>4}  {r['case']:>6}  {rules}{mark}")
        if k != "human":
            total += 1
            caught += r["case"] == r["n"]
    h = per.get("human")
    print(f"\n봇 종류 {total}개 중 전 계정 케이스 {caught}개 · 사람 케이스 {h['case'] if h else '-'}/{h['n'] if h else '-'} (합성 사람이면 근거 약함)")


if __name__ == "__main__":
    main()
