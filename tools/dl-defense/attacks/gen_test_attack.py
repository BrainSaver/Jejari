#!/usr/bin/env python3
"""방어 시험용 공격 생성기 (표준 라이브러리만). 학습에 쓴 v1~v3 어디에도 없는 변형 2개 + 합성 사람.

  pool  템플릿 4개를 전체 계정이 나눠 쓰는 매크로 (±8%, 계정마다 ip·ua 다름, 시작 시각 흩어짐)
        학습에 있던 staggered(그룹마다 템플릿 1개, ±15%)보다 풀이 크고 흔들림이 좁다
  fast  사람의 리듬(세션별 속도·자기상관·긴 멈춤)을 그대로 쓰되 모든 지연을 0.35배로 줄인 매크로
        (--fast-scale-lo/hi 로 배율 범위를 주면 세션마다 무작위. 사람이 0명이어도 된다)

사용: python3 gen_test_attack.py --seed 777 --per-variant 40 --humans 400 --out test_attack.csv
출력은 v3와 같은 컬럼. 사람(label=0)은 v3와 같은 모델이다 (이 시험의 오탐은 합성 사람 기준).
"""
import argparse, csv, hashlib, math, random

BASE = {"login": 2500, "search": 2200, "select_train": 3500, "reserve_click": 1200,
        "queue_join": 800, "queue_pass": 1500, "seat_select": 2600, "seat_deselect": 1800,
        "payment": 9000, "cancel": 4000}
PRE = ["login", "search", "select_train", "reserve_click", "queue_join", "queue_pass"]


def h(s):
    return hashlib.sha256(s.encode()).hexdigest()[:12]


class Human:
    def __init__(self, r, floor=250):
        self.r, self.floor = r, floor
        self.s, self.ar = r.lognormvariate(0, .35), 0.0

    def d(self, step, scale=1.0):
        r = self.r
        self.ar = .6 * self.ar + r.gauss(0, .15)
        x = BASE[step] * self.s * scale * math.exp(r.gauss(0, .5) + self.ar)
        if r.random() < .07:
            x *= r.uniform(3, 8)
        return max(self.floor, x)


def human_flow(r, scale=1.0, floor=250):
    hu = Human(r, floor)
    n = r.choice([1, 1, 1, 2, 2, 3, 4])
    ev = [(e, hu.d(e, scale)) for e in PRE]
    if r.random() < .18:
        ev += [("seat_select", hu.d("seat_select", scale)), ("seat_deselect", hu.d("seat_deselect", scale))]
    ev += [("seat_select", hu.d("seat_select", scale)) for _ in range(n)] + [("payment", hu.d("payment", scale))]
    if r.random() < .1:
        ev.append(("cancel", hu.d("cancel", scale)))
    return ev


def run(a):
    r = random.Random(a.seed)
    t0 = 1_700_000_000_000
    out, sid = [], 0
    templates = [{e: r.uniform(400, 3000) for e in BASE} for _ in range(4)]

    def emit(label, fam, var, ev, start, who, k):
        nonlocal sid
        sid += 1
        t = prev = start
        for i, (e, d) in enumerate(ev):
            t += d
            out.append(dict(session_id=f"s{sid:04d}", email=f"lt{a.tag}.{who}.{k}@loadtest.example.com", label=label,
                            family=fam, variant=var, step_idx=i, event=e, ts_ms=int(t), dt_ms=int(t - prev),
                            train=r.randint(1, 3), seat="", n_seats=sum(1 for x in ev if x[0] == "seat_select"),
                            ip_hash=h(f"ip{sid}"), ua_hash=h(f"ua{r.random()}")))
            prev = t

    variants = set(a.variants.split(","))
    for k in range(a.per_variant if "pool" in variants else 0):                       # pool
        tpl = templates[r.randrange(4)]
        n = r.choice([1, 2])
        ev = [(e, max(150, tpl[e] * r.uniform(.92, 1.08))) for e in PRE]
        ev += [("seat_select", max(150, tpl["seat_select"] * r.uniform(.92, 1.08))) for _ in range(n)]
        ev.append(("payment", max(300, tpl["payment"] * r.uniform(.92, 1.08))))
        emit(1, "test_attack", "pool", ev, t0 + r.uniform(0, 1_200_000), "bot.pool", k)
    for k in range(a.per_variant if "fast" in variants else 0):                       # fast
        scale = a.fast_scale_lo if a.fast_scale_lo == a.fast_scale_hi else r.uniform(a.fast_scale_lo, a.fast_scale_hi)
        emit(1, "test_attack", "fast", human_flow(r, scale=scale, floor=120), t0 + 3_000_000 + r.uniform(0, 900_000),
             "bot.fast", k)
    for k in range(a.humans):
        emit(0, "human_synth", "human", human_flow(r), t0 + 6_000_000 + r.uniform(0, 900_000), "human", k)
    out.sort(key=lambda x: (x["session_id"], x["step_idx"]))
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)
    print(f"{sid} sessions, {len(out)} events -> {a.out}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="t4")
    ap.add_argument("--per-variant", type=int, default=40)
    ap.add_argument("--humans", type=int, default=400)
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--variants", default="pool,fast", help="만들 변형 (쉼표)")
    ap.add_argument("--fast-scale-lo", type=float, default=.35, help="fast의 지연 배율 범위. 같으면 고정, 다르면 세션마다 무작위")
    ap.add_argument("--fast-scale-hi", type=float, default=.35)
    ap.add_argument("--out", default="test_attack.csv")
    run(ap.parse_args())
