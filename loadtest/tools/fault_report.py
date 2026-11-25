#!/usr/bin/env python3
"""장애 시연 결과 정리 — fault_demo.sh가 부른다. 요청 CSV(k6 --out csv)와 사건 시각(events.tsv)을 읽어
정상 · 장애 · 복구 뒤 구간별 숫자, 5초 단위 그래프, 장애 동안 기능별 결과, 정합성 점검을 report.md로 쓴다.

    python loadtest/tools/fault_report.py <결과 폴더> <redis|kafka> <kill|flush> <정합성 종료 코드>
"""
from __future__ import annotations

import csv
import math
import os
import sys
from collections import defaultdict

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

BUCKET = 5  # 초

# k6 요청 이름(tags.name) → 사람이 읽는 기능 이름과 쓰는 저장소
NAMES = {
    "stations": ("역 목록", "Oracle"),
    "run_detail": ("운행 상세", "Oracle"),
    "queue_enter": ("대기열 진입", "Redis"),
    "queue_status": ("대기 순번", "Redis"),
    "queue_leave": ("대기열 나가기", "Redis"),
    "seat_map": ("좌석 배치도", "Redis 토큰 + Oracle"),
    "replay": ("화면 녹화", "Kafka → MongoDB"),
    "hold": ("좌석 선점", "Redis 1차 + Oracle"),
    "pay": ("결제", "Redis 멱등 + Oracle + PG"),
    "cancel": ("결제 전 취소", "Oracle + Redis 정리"),
}


def ts_ms(v: str) -> float:
    x = float(v)
    if x > 1e15:
        return x / 1000      # µs
    if x > 1e12:
        return x             # ms
    return x * 1000          # s


def pct(values, p):
    if not values:
        return float("nan")
    s = sorted(values)
    return s[min(len(s) - 1, max(0, math.ceil(p / 100 * len(s)) - 1))]


def kind(status: str) -> str:
    try:
        c = int(status or 0)
    except ValueError:
        c = 0
    if c == 0:
        return "noresp"
    if c == 503:
        return "s503"
    if c >= 500:
        return "s5xx"
    if c >= 400:
        return "s4xx"
    return "ok"


def main():
    out, target, mode, integrity = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
    ev = {}
    with open(os.path.join(out, "events.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.strip():
                t, label = line.rstrip("\n").split("\t")
                ev.setdefault(label, float(t))
    t0 = ev["load_start"]
    fault, recover_start, recovered = ev["fault_start"], ev["recover_start"], ev["recovered"]
    end = ev.get("load_end", float("inf"))

    reqs = []  # (상대 초, 이름, 종류, 걸린 ms, 상태)
    with open(os.path.join(out, "requests.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r.get("metric_name") != "http_req_duration":
                continue
            if r.get("scenario") not in (None, "", "load"):
                continue
            t = ts_ms(r["timestamp"])
            if t < t0 or t > end + 20000:
                continue
            reqs.append(((t - t0) / 1000, r.get("name") or "?", kind(r.get("status")), float(r["metric_value"]), r.get("status")))
    if not reqs:
        print("요청 기록이 없습니다 (requests.csv)")
        return

    F, R, RC = (fault - t0) / 1000, (recover_start - t0) / 1000, (recovered - t0) / 1000
    phases = [("정상 (장애 전)", 0, F), (f"장애 ({target} 꺼짐)", F, RC), ("복구 뒤", RC, float("inf"))]

    def summary(rows, span):
        n = len(rows)
        c = defaultdict(int)
        for x in rows:
            c[x[2]] += 1
        fail = c["s503"] + c["s5xx"] + c["noresp"]
        return {
            "n": n, "rps": n / span if span > 0 else 0, "ok": c["ok"], "s4xx": c["s4xx"], "s503": c["s503"],
            "s5xx": c["s5xx"], "noresp": c["noresp"], "fail_pct": 100 * fail / n if n else 0,
            "p95": pct([x[3] for x in rows if x[2] in ("ok", "s4xx")], 95),
            "p95_503": pct([x[3] for x in rows if x[2] == "s503"], 95),
        }

    last = max(x[0] for x in reqs)
    lines = []
    w = lines.append
    w(f"# 장애 시연 결과 — {target} ({'데이터를 지우고 ' if mode == 'flush' else ''}SIGKILL)")
    w("")
    w(f"- 부하: 정상 사용자 계속 예매 (k6 시나리오 14) · 요청 {len(reqs):,}건 · {last:.0f}초")
    w(f"- 사건: +{F:.0f}초 {target} 죽임 → +{R:.0f}초 다시 띄움 → +{RC:.0f}초 healthy")
    w("")
    w("## 구간별")
    w("")
    w("| 구간 | 시간 | 요청 | 초당 | 성공(2xx) | 4xx(경합·한도) | **503** | 다른 5xx | 응답 없음 | 실패율 | 성공 p95 | 503 p95 |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|")
    stats = {}
    for name, a, b in phases:
        rows = [x for x in reqs if a <= x[0] < b]
        span = (min(b, last) - a)
        s = summary(rows, span)
        stats[name] = s
        f = lambda v: "—" if v != v else f"{v:.0f}ms"  # noqa: E731 (NaN)
        w(f"| {name} | +{a:.0f}~{min(b, last):.0f}초 | {s['n']:,} | {s['rps']:.1f} | {s['ok']:,} | {s['s4xx']:,} | **{s['s503']:,}** | "
          f"{s['s5xx']:,} | {s['noresp']:,} | {s['fail_pct']:.1f}% | {f(s['p95'])} | {f(s['p95_503'])} |")
    w("")

    # 장애 동안 기능별: 무엇이 멈추고 무엇이 계속 됐나
    w(f"## 장애 동안 기능별 (+{F:.0f}~{RC:.0f}초)")
    w("")
    w("| 기능 | 쓰는 곳 | 요청 | 성공 | 503 | 다른 실패 |")
    w("|---|---|---|---|---|---|")
    by = defaultdict(lambda: defaultdict(int))
    for x in reqs:
        if F <= x[0] < RC:
            by[x[1]]["n"] += 1
            by[x[1]][x[2]] += 1
    for key in sorted(by, key=lambda k: list(NAMES).index(k) if k in NAMES else 99):
        c = by[key]
        label, store = NAMES.get(key, (key, ""))
        w(f"| {label} | {store} | {c['n']} | {c['ok'] + c['s4xx']} | {c['s503']} | {c['s5xx'] + c['noresp']} |")
    w("")

    # 5초 단위 그래프
    w("## 5초 단위")
    w("")
    w("```")
    w("  시각   요청  성공 ▇ · 4xx ▒ · 503 ░ · 다른 실패 ✕")
    buckets = defaultdict(lambda: defaultdict(int))
    for x in reqs:
        buckets[int(x[0] // BUCKET)][x[2]] += 1
    peak = max(sum(b.values()) for b in buckets.values())
    scale = 50 / peak if peak else 1
    for i in range(0, int(last // BUCKET) + 1):
        b = buckets.get(i, {})
        n = sum(b.values())
        bar = ("▇" * round(b.get("ok", 0) * scale) + "▒" * round(b.get("s4xx", 0) * scale)
               + "░" * round(b.get("s503", 0) * scale) + "✕" * round((b.get("s5xx", 0) + b.get("noresp", 0)) * scale))
        t = i * BUCKET
        tag = ""
        if t <= F < t + BUCKET:
            tag = f"  ← {target} 죽임"
        elif t <= R < t + BUCKET:
            tag = "  ← 다시 띄움"
        elif t <= RC < t + BUCKET:
            tag = "  ← healthy"
        w(f"  +{t:>3}s {n:>5}  {bar}{tag}")
    w("```")
    w("")

    # 복구: 다시 띄운 뒤 실패가 0인 5초 칸이 처음 나온 때
    rec = None
    for i in range(int(R // BUCKET), int(last // BUCKET) + 1):
        b = buckets.get(i, {})
        if sum(b.values()) >= 5 and b.get("s503", 0) + b.get("s5xx", 0) + b.get("noresp", 0) == 0:
            rec = i * BUCKET + BUCKET - R
            break
    first503 = min((x[0] for x in reqs if x[2] == "s503" and x[0] >= F), default=None)
    w("## 요약")
    w("")
    pre, mid, post = (stats[p[0]] for p in phases)
    w(f"- 장애 전 실패율 {pre['fail_pct']:.1f}% → 장애 중 {mid['fail_pct']:.1f}% (503 {mid['s503']:,}건, 다른 5xx {mid['s5xx']}건, "
      f"응답 없음 {mid['noresp']}건) → 복구 뒤 {post['fail_pct']:.1f}%")
    if first503 is not None:
        w(f"- 첫 503: 죽이고 {first503 - F:.1f}초 뒤. 503 응답 p95 {mid['p95_503']:.0f}ms (요청을 오래 붙잡지 않음)")
    w(f"- 복구: 다시 띄우고 {'약 %d초 안에 실패 0' % rec if rec is not None else '부하가 끝날 때까지 실패가 남음'}"
      f" · 복구 뒤 성공 p95 {post['p95']:.0f}ms (장애 전 {pre['p95']:.0f}ms)")
    first = os.path.join(out, "integrity_first.txt")
    late = os.path.exists(first)
    w(f"- 정합성 점검: **{'통과 (어긋남 0)' if integrity == 0 else '실패 — 아래 FAIL 항목'}**"
      + (" — 처음 점검의 FAIL은 60초 뒤 다시 보니 사라짐 = 유실이 아니라 늦게 도착 (아래 처음 점검)" if late and integrity == 0 else ""))
    w("")
    w("## 정합성 점검 (check_booking.sh)")
    w("")
    w("```")
    with open(os.path.join(out, "integrity.txt"), encoding="utf-8", errors="replace") as f:
        w(f.read().rstrip())
    w("```")

    if late:
        w("")
        w("### 처음 점검 (부하 끝나고 40초 뒤)")
        w("")
        w("```")
        with open(first, encoding="utf-8", errors="replace") as f:
            w(f.read().rstrip())
        w("```")

    text = "\n".join(lines) + "\n"
    with open(os.path.join(out, "report.md"), "w", encoding="utf-8") as f:
        f.write(text)
    print()
    print(text)
    print(f"보고서: {os.path.join(out, 'report.md')}")


if __name__ == "__main__":
    main()
