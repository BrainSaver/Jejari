#!/usr/bin/env python3
"""녹화 모델 특징 — 화면 녹화(replay.jsonl)에서 계정마다 "손이 움직인 모양"을 숫자로 만든다.

    python3 -I tools/ml/replay_features.py data/ml/<폴더>     # replay.jsonl + members.jsonl → replay_features.csv

행동 로그 모델(features.py)이 보지 않는 새 정보만 쓴다. 속도(요청 간격)는 일부러 넣지 않는다 — 합성 사람 스크립트의 티가
그대로 배어 있어서(2026-11-23 실험: 모델이 "1초 미만 간격 = 봇"을 배움). 여기서는 마우스가 움직인 모양만 본다.

궤적을 "이동" 단위로 자른다. 녹화기는 궤적을 16점마다 묶어 보내고 클릭은 바로 보내서, 저장 순서로는 어느 궤적이 어느 클릭으로
이어졌는지 모른다. 그래서 세션의 궤적 묶음을 순서대로 이어 하나로 만들고, ① 마우스 클릭 자리에 가장 가까운 점 ② 묶음 안
400ms 이상 멈춤 ③ 묶음 사이 30 이상 건너뜀에서 자른다. 이동 = 점 4개 이상 · 처음~끝 50 이상.

특징 (계정 단위, 이동이 없으면 비움):
  녹화 양       rec_sessions · clicks_per_session · paths_per_click
  입력 장치     frac_ptr_m · frac_ptr_t · frac_ptr_k · touch_with_path(터치 클릭인데 궤적 점이 많음)
  클릭과 궤적   click_no_path(마우스 클릭인데 근처에 궤적 점이 없음) · teleport_ratio(클릭 자리 → 클릭 자리로 바로)
  이동 모양     straight_med(현/호) · straight_hi(0.98 이상 비율) · speed_cv_med · peak_pos_med(가장 빠른 곳 위치 0~1)
                decel_med(끝 1/3 속도 ÷ 가운데 1/3) · jitter_med(점마다 방향이 흔들린 정도) · pts_per_move_med
  반복          template_share(정규화한 이동 모양이 다른 계정과 똑같은 비율)
좌표는 화면 비율 0~1000.
"""
from __future__ import annotations

import collections
import csv
import json
import math
import os
import statistics as st
import sys

FEATURES = ["rec_sessions", "clicks_per_session", "paths_per_click",
            "frac_ptr_m", "frac_ptr_t", "frac_ptr_k", "touch_with_path",
            "click_no_path", "teleport_ratio",
            "straight_med", "straight_hi", "speed_cv_med", "peak_pos_med", "decel_med", "jitter_med", "pts_per_move_med",
            "template_share"]
NEAR = 15        # 클릭 자리와 "같은 곳"으로 보는 거리
PAUSE_MS = 400
JUMP = 30
MIN_PTS, MIN_CHORD = 4, 50


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def med(v):
    return st.median(v) if v else ""


def moves_of(paths, clicks):
    """paths: [[ [t,x,y], … ], …] 저장 순서, clicks: [(x,y)] 마우스 클릭. → 이동 목록 [[(t,x,y)…]…]"""
    stream, breaks = [], set()
    for p in paths:
        pts = [q for q in p if isinstance(q, list) and len(q) >= 3]
        if not pts:
            continue
        if stream and dist(stream[-1][1:], pts[0][1:]) >= JUMP:
            breaks.add(len(stream))
        for k, q in enumerate(pts):
            if k and q[0] - pts[k - 1][0] >= PAUSE_MS:
                breaks.add(len(stream))
            stream.append((q[0], q[1], q[2], len(breaks)))
    for c in clicks:  # 클릭 자리에 가장 가까운 점 다음에서 자른다
        if not stream:
            break
        i = min(range(len(stream)), key=lambda j: dist(stream[j][1:3], c))
        if dist(stream[i][1:3], c) <= NEAR:
            breaks.add(i + 1)
    out, cur = [], []
    for i, q in enumerate(stream):
        if i in breaks and cur:
            out.append(cur)
            cur = []
        cur.append(q[:3])
    if cur:
        out.append(cur)
    return [m for m in out if len(m) >= MIN_PTS and dist(m[0][1:], m[-1][1:]) >= MIN_CHORD], stream


def shape(m):
    seg = [dist(m[k - 1][1:], m[k][1:]) for k in range(1, len(m))]
    arc = sum(seg)
    chord = dist(m[0][1:], m[-1][1:])
    v = [d / max(1, m[k + 1][0] - m[k][0]) for k, d in enumerate(seg)]
    mv = sum(v) / len(v)
    cv = (math.sqrt(sum((x - mv) ** 2 for x in v) / len(v)) / mv) if mv > 0 else 0
    peak = v.index(max(v)) / max(1, len(v) - 1)
    third = max(1, len(v) // 3)
    mid = v[third:len(v) - third] or v
    decel = (sum(v[-third:]) / third) / max(1e-9, sum(mid) / len(mid))
    ang = []
    for k in range(1, len(m) - 1):
        a1 = math.atan2(m[k][2] - m[k - 1][2], m[k][1] - m[k - 1][1])
        a2 = math.atan2(m[k + 1][2] - m[k][2], m[k + 1][1] - m[k][1])
        ang.append(abs((a2 - a1 + math.pi) % (2 * math.pi) - math.pi))
    jit = st.pstdev(ang) if len(ang) >= 2 else 0
    return chord / arc if arc else 1, cv, peak, decel, jit


def signature(m):
    """처음 → 끝이 (0,0) → (1,0)이 되게 옮기고 돌리고 줄인 뒤, 점 순서로 8점을 0.01 단위로 반올림.
    거칠게(0.05·6점) 하면 비슷한 곡선끼리 같다고 나와 사람도 "반복"으로 보였다 (2026-11-23). 손떨림 몇 픽셀이면 달라지는 정도로"""
    (x0, y0), (x1, y1) = m[0][1:], m[-1][1:]
    L = math.hypot(x1 - x0, y1 - y0) or 1
    c, s = (x1 - x0) / L, (y1 - y0) / L
    pts = [(((x - x0) * c + (y - y0) * s) / L, (-(x - x0) * s + (y - y0) * c) / L) for _, x, y in m]
    idx = [round(k * (len(pts) - 1) / 7) for k in range(8)]
    return tuple((round(pts[i][0] / 0.01), round(pts[i][1] / 0.01)) for i in idx) + (len(m),)


def main():
    folder = sys.argv[1]
    members = [json.loads(l) for l in open(os.path.join(folder, "members.jsonl"), encoding="utf-8")]
    ev = collections.defaultdict(lambda: collections.defaultdict(list))
    path_file = os.path.join(folder, "replay.jsonl")
    if os.path.exists(path_file):
        for l in open(path_file, encoding="utf-8"):
            e = json.loads(l)
            ev[e["member_id"]][e["session_id"]].append(e)

    per, sig_owner = {}, collections.defaultdict(set)
    for m in members:
        sessions = ev.get(m["member_id"], {})
        clicks = paths = ptr_m = ptr_t = ptr_k = touch_path = no_path = tele = mclicks = 0
        shapes, sigs = [], []
        for es in sessions.values():
            es.sort(key=lambda e: e["seq"])
            p_list = [e["path"] for e in es if e["type"] == "MOUSE_PATH" and e.get("path")]
            c_ev = [e for e in es if e["type"] == "CLICK"]
            mc = [(e["x"], e["y"]) for e in c_ev if e.get("pointer") == "m" and e.get("x") is not None]
            clicks += len(c_ev)
            paths += len(p_list)
            ptr_m += sum(e.get("pointer") == "m" for e in c_ev)
            ptr_t += sum(e.get("pointer") == "t" for e in c_ev)
            ptr_k += sum(e.get("pointer") == "k" for e in c_ev)
            npts = sum(len(p) for p in p_list)
            if any(e.get("pointer") == "t" for e in c_ev) and npts >= 3 * max(1, len(c_ev)):
                touch_path += 1
            mv, stream = moves_of(p_list, mc)
            pts = [q[1:3] for q in stream]
            mclicks += len(mc)
            for c in mc:
                if not pts or min(dist(q, c) for q in pts) > NEAR:
                    no_path += 1
            prev_at = None  # 연속한 두 점이 서로 다른 클릭 자리에 정확히 (MouseMotion과 같은 생각)
            for q in pts:
                at = next((i for i, c in enumerate(mc) if abs(c[0] - q[0]) <= 3 and abs(c[1] - q[1]) <= 3), None)
                if at is not None and prev_at is not None and at != prev_at:
                    tele += 1
                prev_at = at
            for x in mv:
                shapes.append(shape(x))
                sigs.append(signature(x))
        for g in sigs:
            sig_owner[g].add(m["member_id"])
        per[m["member_id"]] = (len(sessions), clicks, paths, ptr_m, ptr_t, ptr_k, touch_path, no_path, tele, mclicks, shapes, sigs)

    out = os.path.join(folder, "replay_features.csv")
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["member_id", "label", "strategy", "family", "group", "source"] + FEATURES)
        n_rec = 0
        for m in members:
            ns, clicks, paths, pm, pt, pk, tp, nopath, tele, mclicks, shapes, sigs = per[m["member_id"]]
            n_rec += ns > 0
            c = max(1, clicks)
            row = [ns, clicks / ns if ns else "", paths / c if ns else "",
                   pm / c if clicks else "", pt / c if clicks else "", pk / c if clicks else "", tp / ns if ns else "",
                   nopath / mclicks if mclicks else "", tele / mclicks if mclicks else "",
                   med([s[0] for s in shapes]), (sum(s[0] >= 0.98 for s in shapes) / len(shapes)) if shapes else "",
                   med([s[1] for s in shapes]), med([s[2] for s in shapes]), med([s[3] for s in shapes]),
                   med([s[4] for s in shapes]), med([g[-1] for g in sigs]),  # 서명의 마지막 칸 = 이동의 점 수
                   (sum(len(sig_owner[g]) > 1 for g in sigs) / len(sigs)) if sigs else ""]
            w.writerow([m["member_id"], m["label"], m["strategy"], m.get("family", ""), m.get("group", 0), m["source"]] + row)
    print(f"계정 {len(members)} (녹화 있음 {n_rec}) → {out}")


if __name__ == "__main__":
    main()
