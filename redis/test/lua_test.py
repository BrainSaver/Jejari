# -*- coding: utf-8 -*-
"""동시성 §14.2 Lua 스크립트 검증.

Spring 뼈대가 아직 없어서 스크립트만 따로 돌려 본다. 나중에 통합 테스트로 옮긴다.
실행: python redis/test/lua_test.py
접속 정보는 저장소 루트 .env 에서 읽는다 (REDIS_HOST/PORT/PASSWORD).
docker compose up -d 로 띄운 Redis에 그대로 붙는다.
"""
import os, sys, time, pathlib
from concurrent.futures import ThreadPoolExecutor
import redis

# 윈도 콘솔 기본 코드페이지(cp949)에서 '—' 같은 글자가 깨지므로 UTF-8로 고정한다
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

def _load_env():
    """저장소 루트 .env 를 읽는다 (compose·Spring이 쓰는 같은 파일). 없으면 넘어간다."""
    here = pathlib.Path(__file__).resolve()
    p = next((d / ".env" for d in here.parents if (d / ".env").exists()), None)
    if p is None:
        return
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


_load_env()

HOST = os.environ.get("REDIS_HOST", "localhost")
PORT = int(os.environ.get("REDIS_PORT", "6379"))
PW   = os.environ.get("REDIS_PASSWORD") or None   # compose가 requirepass를 건다
LUA  = pathlib.Path(__file__).resolve().parents[1] / "lua"

def rconn():
    return redis.Redis(host=HOST, port=PORT, password=PW, decode_responses=True)

r = rconn()
S = {p.stem: r.register_script(p.read_text(encoding="utf-8")) for p in LUA.glob("*.lua")}

ok_n, fail_n = 0, 0
def check(name, cond, detail=""):
    global ok_n, fail_n
    if cond:
        ok_n += 1;  print("  OK   %-54s %s" % (name, detail))
    else:
        fail_n += 1; print("  FAIL %-54s %s" % (name, detail))

def ms(sec_from_now): return int(time.time() * 1000) + int(sec_from_now * 1000)
def seats(run, ids, seg=0): return ["seat:hold:%s:%s:%s" % (run, i, seg) for i in ids]
def reset(): r.flushdb()

# ─────────────────────────── hold.lua (§2.1-4)
print("\n[hold.lua] 좌석 선점")
reset()
k = seats(9001, [1001, 1002])
check("빈 좌석 2개 선점", S["hold"](keys=k, args=["R1", ms(600), ""]) == 1)
check("  값이 예약 ID", r.get(k[0]) == "R1" and r.get(k[1]) == "R1")
check("  TTL이 걸림", 0 < r.pttl(k[0]) <= 600_000, "pttl=%dms" % r.pttl(k[0]))
check("같은 예약 재시도는 성공 (멱등)", S["hold"](keys=k, args=["R1", ms(600), ""]) == 1)

reset()
k3 = seats(9001, [1001, 1002, 1003])
S["hold"](keys=[k3[1]], args=["R1", ms(600), ""])          # 가운데 한 칸만 남이 쥠
check("하나라도 남이 쥐면 실패", S["hold"](keys=k3, args=["R2", ms(600), ""]) == 0)
check("  실패 시 아무것도 안 씀 (전부 아니면 전무)",
      r.get(k3[0]) is None and r.get(k3[1]) == "R1" and r.get(k3[2]) is None)

reset()
k = seats(9001, [1001])
r.set(k[0], "REOFFER:R1")
check("REOFFER 인계값은 비어 있는 것으로 봄 (§9.6)",
      S["hold"](keys=k, args=["R2", ms(600), "REOFFER:R1"]) == 1 and r.get(k[0]) == "R2")
r.set(k[0], "REOFFER:R9")
check("  다른 예약의 REOFFER는 못 가져감",
      S["hold"](keys=k, args=["R2", ms(600), "REOFFER:R1"]) == 0)
r.set(k[0], "SOLD:R1")
check("  SOLD 좌석은 못 가져감", S["hold"](keys=k, args=["R2", ms(600), ""]) == 0)

# ─────────────────────────── T1: 같은 좌석 동시 선점 (§16 T1)
print("\n[T1] 같은 좌석에 100명 동시 선점")
reset()
k = seats(9001, [2001, 2002])
def grab(i):
    cli = rconn()
    return cli.register_script((LUA / "hold.lua").read_text(encoding="utf-8"))(
        keys=k, args=["R%d" % i, ms(600), ""])
with ThreadPoolExecutor(max_workers=50) as ex:
    res = list(ex.map(grab, range(100)))
winners = sum(res)
check("승자 정확히 1명", winners == 1, "성공 %d / 100" % winners)
check("  두 좌석 모두 같은 예약", r.get(k[0]) == r.get(k[1]), "%s / %s" % (r.get(k[0]), r.get(k[1])))

# ─────────────────────────── release.lua (§2.3)
print("\n[release.lua] compare-and-delete")
reset()
k = seats(9001, [1001, 1002])
r.set(k[0], "R1"); r.set(k[1], "R2")
check("내 키만 지움", S["release"](keys=k, args=["R1"]) == 1)
check("  남의 키는 그대로", r.get(k[0]) is None and r.get(k[1]) == "R2")

# ─────────────────────────── extend.lua (§4.2-1)
print("\n[extend.lua] 마감 연장")
reset()
k = seats(9001, [1001, 1002, 1003])
r.set(k[0], "R1", px=60_000); r.set(k[1], "R2", px=60_000)   # k[2]는 없음
n = S["extend"](keys=k, args=["R1", ms(600)])
check("내 키만 연장 (반환 1)", n == 1, "n=%d" % n)
check("  연장됨", r.pttl(k[0]) > 100_000, "pttl=%dms" % r.pttl(k[0]))
check("  남의 키 TTL 그대로", r.pttl(k[1]) <= 60_000)
check("  없는 키는 만들지 않음 ★", r.exists(k[2]) == 0)

# ─────────────────────────── rehold.lua (§6.2-2)
print("\n[rehold.lua] PG 결과 모를 때 다시 잡기")
reset()
k = seats(9001, [1001, 1002, 1003])
r.set(k[0], "R1"); r.set(k[1], "R2")                          # k[2]는 비어 있음
n = S["rehold"](keys=k, args=["R1", ms(300)])
check("내 키 + 빈 키만 (반환 2)", n == 2, "n=%d" % n)
check("  빈 키를 내 것으로", r.get(k[2]) == "R1")
check("  남이 가져간 키는 그대로 ★", r.get(k[1]) == "R2")

# ─────────────────────────── sold.lua (§5.1-10)
print("\n[sold.lua] 확정 — 무조건 덮어씀")
reset()
k = seats(9001, [1001, 1002])
r.set(k[0], "R9")                                             # 남의 값이어도
check("값과 상관없이 덮어씀", S["sold"](keys=k, args=["R1", ms(3600)]) == 2)
check("  SOLD:R1 로", r.get(k[0]) == "SOLD:R1" and r.get(k[1]) == "SOLD:R1")
check("  만료가 출발 시각", r.pttl(k[0]) > 3_000_000)

# ─────────────────────────── reoffer.lua (§8.2, §14.3)
print("\n[reoffer.lua] 대기자 몫으로 묶기")
reset()
k = seats(9001, [1001, 1002, 1003, 1004])
r.set(k[0], "R1"); r.set(k[1], "SOLD:R1"); r.set(k[2], "R9")  # k[3] 없음
n = S["reoffer"](keys=k, args=["R1", 60_000])
check("HOLD·SOLD 내 것만 (반환 2)", n == 2, "n=%d" % n)
check("  REOFFER:R1 로", r.get(k[0]) == "REOFFER:R1" and r.get(k[1]) == "REOFFER:R1")
check("  남의 키 그대로", r.get(k[2]) == "R9")
check("  없는 키는 안 만듦", r.exists(k[3]) == 0)
time.sleep(0.4)
before = r.pttl(k[0])
n2 = S["reoffer"](keys=k, args=["R1", 60_000])
after = r.pttl(k[0])
check("두 번 돌려도 같은 결과 (§14.3 멱등)", n2 == 2)
check("  이미 REOFFER면 TTL을 늘리지 않음 ★", after <= before, "%dms → %dms" % (before, after))

# ─────────────────────────── limit_incr.lua (§2.1-2, §10.4, §10.6)
print("\n[limit_incr.lua] 매수 한도 1차")
reset()
RUN, DAY, EVT = "limit:9001:CI1", "limit:day:20261116:CI1", "limit:event:2:CI1"
K = [RUN, DAY, EVT]
def A(n, maxes, restores=("0","0","0")):
    a = [n]
    for i in range(3):
        a += [maxes[i], restores[i], ms(3600)]
    return a
check("4매 요청 통과 (한도 4/20/8)", S["limit_incr"](keys=K, args=A(4, (4,20,8))) == [1])
check("  값이 4", r.get(RUN) == "4" and r.get(DAY) == "4" and r.get(EVT) == "4")
res = S["limit_incr"](keys=K, args=A(1, (4,20,8)))
check("열차당 한도 초과 → {0, 1}", res == [0, 1], "res=%s" % res)
check("  초과 시 전부 원복 ★", r.get(RUN) == "4" and r.get(DAY) == "4" and r.get(EVT) == "4")

reset()
res = S["limit_incr"](keys=K, args=A(5, (10,20,4)))
check("명절 한도에서 걸리면 {0, 3}", res == [0, 3], "res=%s" % res)
check("  앞 키들도 전부 원복 ★", r.get(RUN) == "0" and r.get(DAY) == "0")

reset()
check("키 없으면 purchase_counter 값으로 복원 후 증가 (§10.4 · T14)",
      S["limit_incr"](keys=K, args=A(1, (10,20,10), ("7","3","2"))) == [1])
check("  7+1=8 / 3+1=4 / 2+1=3", (r.get(RUN), r.get(DAY), r.get(EVT)) == ("8","4","3"),
      "%s / %s / %s" % (r.get(RUN), r.get(DAY), r.get(EVT)))
check("  복원된 키에 TTL이 걸림", r.pttl(RUN) > 0)

print("\n[limit_incr.lua] 같은 CI 10명이 동시에 (한도 4)")
reset()
def inc(_):
    cli = rconn()
    sc = cli.register_script((LUA / "limit_incr.lua").read_text(encoding="utf-8"))
    return sc(keys=K, args=A(1, (4, 20, 8)))[0]
with ThreadPoolExecutor(max_workers=10) as ex:
    got = sum(ex.map(inc, range(10)))
check("정확히 한도만큼만 통과", got == 4, "통과 %d / 10" % got)
check("  카운터가 한도와 일치", r.get(RUN) == "4", "RUN=%s" % r.get(RUN))

# ─────────────────────────── limit_decr.lua (§14.2, §14.3, T26)
print("\n[limit_decr.lua] 예약당 한 번만 감소")
reset()
MARK = "limit:dec:R1"
r.set(RUN, "4", px=3_600_000); r.set(DAY, "4", px=3_600_000); r.set(EVT, "4", px=3_600_000)
check("첫 호출은 감소 (반환 1)", S["limit_decr"](keys=[MARK, RUN, DAY, EVT], args=[2, ms(3600)]) == 1)
check("  4 → 2", r.get(RUN) == "2" and r.get(DAY) == "2" and r.get(EVT) == "2")
check("두 번째 호출은 아무것도 안 함 (반환 0) ★ T26",
      S["limit_decr"](keys=[MARK, RUN, DAY, EVT], args=[2, ms(3600)]) == 0)
check("  값이 그대로 2", r.get(RUN) == "2" and r.get(DAY) == "2")

reset()
r.set(RUN, "1", px=3_600_000)
S["limit_decr"](keys=["limit:dec:R2", RUN, DAY], args=[5, ms(3600)])
check("0 밑으로 안 내려감", r.get(RUN) == "0", "RUN=%s" % r.get(RUN))
check("  0으로 고칠 때 TTL 유지 (KEEPTTL)", r.pttl(RUN) > 0, "pttl=%d" % r.pttl(RUN))
check("  없던 키는 만들지 않음 ★", r.exists(DAY) == 0)

reset()
print("\n" + "=" * 72)
print("  통과 %d · 실패 %d" % (ok_n, fail_n))
print("=" * 72)
sys.exit(1 if fail_n else 0)
