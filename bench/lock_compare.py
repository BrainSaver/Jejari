# -*- coding: utf-8 -*-
"""§17.1 좌석 선점 락 방식 비교 — A 비관적 / B 낙관적 / C 현재 설계(Redis + DB PK)

Spring 뼈대가 아직 없어서 Python으로 먼저 잰다. 재는 대상은 "락 방식"이고,
세 방식에 똑같이 붙는 비용(FK·JPA·HTTP)은 넣지 않았다. 뼈대가 올라오면
SeatHoldStrategy 뒤로 옮겨 같은 시나리오를 다시 잰다.

측정 방법
  - 세 전략이 **같은 picks(요청별 좌석 배정)** 를 쓴다. 전략마다 다시 뽑으면
    좌석 분포가 달라져 성공 건수부터 어긋난다.
  - 전략마다 워밍업 1회를 버리고 ROUNDS회 측정해 **중앙값**을 쓴다.
    1회 측정은 편차가 커서(같은 조건에서 TPS가 2~4배 흔들렸다) 믿을 수 없다.

실행: python bench/lock_compare.py
필요: Oracle(localhost:1521/FREEPDB1) · Redis(localhost:6379) · pip install oracledb redis
"""
import os
import sys
import time
import random
import threading
import statistics
import pathlib
from concurrent.futures import ThreadPoolExecutor

import oracledb
import redis

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ORA = dict(user=os.environ.get("ORACLE_USER", "jejari"),
           password=os.environ.get("ORACLE_PASSWORD", "jejari"),
           dsn=os.environ.get("ORACLE_DSN", "localhost:1521/FREEPDB1"))
RKW = dict(host=os.environ.get("REDIS_HOST", "localhost"),
           port=int(os.environ.get("REDIS_PORT", "6379")), decode_responses=True)
LUA = pathlib.Path(__file__).resolve().parents[1] / "redis" / "lua"

RUN_ID = 9001
MAX_RETRY_B = 20          # 낙관적 락 재시도 한도
ROUNDS = int(os.environ.get("ROUNDS", "5"))

pool = oracledb.create_pool(min=8, max=64, increment=4, **ORA)
rcli = redis.Redis(**RKW)
HOLD = rcli.register_script((LUA / "hold.lua").read_text(encoding="utf-8"))
RELEASE = rcli.register_script((LUA / "release.lua").read_text(encoding="utf-8"))


class Stat:
    """요청별 결과를 모은다."""

    def __init__(self):
        self.lat = []        # 요청별 소요 ms
        self.ok = 0          # 선점 성공
        self.lost = 0        # 정상 거절 (남이 먼저 잡음)
        self.err = 0         # 예외
        self.db_calls = 0    # DB 왕복 수 (statement 단위)
        self.retries = 0     # 낙관적 락 재시도 횟수
        self.lock_wait = []  # 비관적 락 대기 ms
        self.lk = threading.Lock()

    def add(self, ms, result, db=0, retry=0, wait=None):
        with self.lk:
            self.lat.append(ms)
            self.db_calls += db
            self.retries += retry
            if wait is not None:
                self.lock_wait.append(wait)
            if result == "ok":
                self.ok += 1
            elif result == "lost":
                self.lost += 1
            else:
                self.err += 1


def pct(xs, p):
    if not xs:
        return 0.0
    xs = sorted(xs)
    k = max(0, min(len(xs) - 1, int(round(p / 100 * len(xs))) - 1))
    return xs[k]


# ───────────────────────────── A 비관적 락 ─────────────────────────────
def strat_pessimistic(rid, seat, st):
    """운행 좌석 행을 미리 만들어 두고 SELECT ... FOR UPDATE 로 줄을 세운다."""
    t0 = time.perf_counter()
    db = 0
    wait = None
    try:
        with pool.acquire() as con:
            cur = con.cursor()
            w0 = time.perf_counter()
            cur.execute("SELECT status FROM exp_seat WHERE run_id=:r AND seat_id=:s FOR UPDATE",
                        r=RUN_ID, s=seat)
            db += 1
            row = cur.fetchone()
            wait = (time.perf_counter() - w0) * 1000      # 락을 얻기까지 걸린 시간
            if row and row[0] == "AVAILABLE":
                cur.execute("UPDATE exp_seat SET status='HOLD', reservation_id=:R, "
                            "updated_at=SYSTIMESTAMP WHERE run_id=:r AND seat_id=:s",
                            R=rid, r=RUN_ID, s=seat)
                db += 1
                con.commit()
                db += 1
                st.add((time.perf_counter() - t0) * 1000, "ok", db, 0, wait)
                return
            con.rollback()
            db += 1
            st.add((time.perf_counter() - t0) * 1000, "lost", db, 0, wait)
    except Exception:
        st.add((time.perf_counter() - t0) * 1000, "err", db, 0, wait)


# ───────────────────────────── B 낙관적 락 ─────────────────────────────
def strat_optimistic(rid, seat, st):
    """락 없이 읽고 version 조건부 UPDATE. 0행이면 다시 읽는다."""
    t0 = time.perf_counter()
    db = 0
    retry = 0
    try:
        with pool.acquire() as con:
            cur = con.cursor()
            while retry < MAX_RETRY_B:
                cur.execute("SELECT status, version FROM exp_seat WHERE run_id=:r AND seat_id=:s",
                            r=RUN_ID, s=seat)
                db += 1
                row = cur.fetchone()
                if not row or row[0] != "AVAILABLE":
                    con.rollback()
                    db += 1
                    st.add((time.perf_counter() - t0) * 1000, "lost", db, retry)
                    return
                cur.execute("UPDATE exp_seat SET status='HOLD', reservation_id=:R, "
                            "version=version+1, updated_at=SYSTIMESTAMP "
                            "WHERE run_id=:r AND seat_id=:s AND version=:v AND status='AVAILABLE'",
                            R=rid, r=RUN_ID, s=seat, v=row[1])
                db += 1
                if cur.rowcount == 1:
                    con.commit()
                    db += 1
                    st.add((time.perf_counter() - t0) * 1000, "ok", db, retry)
                    return
                con.rollback()      # 그 사이 남이 바꿈 → 다시 읽는다
                db += 1
                retry += 1
            st.add((time.perf_counter() - t0) * 1000, "err", db, retry)
    except Exception:
        st.add((time.perf_counter() - t0) * 1000, "err", db, retry)


# ──────────────────── C 현재 설계 (Redis 1차 + DB PK 최종) ────────────────────
def strat_current(rid, seat, st):
    """hold.lua 로 먼저 거르고, 이긴 요청만 DB에 INSERT한다 (§2.1-4, §2.2-4)."""
    t0 = time.perf_counter()
    db = 0
    key = ["seat:hold:%d:%d:0" % (RUN_ID, seat)]
    deadline = int(time.time() * 1000) + 600_000
    try:
        if HOLD(keys=key, args=[str(rid), deadline, ""]) == 0:
            # 패배자. DB 왕복 0회 (§2.1-4 "DB 쓰기는 0건")
            st.add((time.perf_counter() - t0) * 1000, "lost", 0)
            return
        with pool.acquire() as con:
            cur = con.cursor()
            try:
                cur.execute("INSERT INTO exp_seat_inv "
                            "(run_id, seat_id, segment_no, reservation_id, status, hold_expires_at) "
                            "VALUES (:r, :s, 0, :R, 'HOLD', SYSTIMESTAMP + INTERVAL '10' MINUTE)",
                            r=RUN_ID, s=seat, R=rid)
                db += 1
                con.commit()
                db += 1
                st.add((time.perf_counter() - t0) * 1000, "ok", db)
                return
            except oracledb.IntegrityError:      # ORA-00001 → DB가 최종 거절
                con.rollback()
                db += 1
                RELEASE(keys=key, args=[str(rid)])   # §2.3 보상
                st.add((time.perf_counter() - t0) * 1000, "lost", db)
    except Exception:
        RELEASE(keys=key, args=[str(rid)])
        st.add((time.perf_counter() - t0) * 1000, "err", db)


STRATS = [("A 비관적 락  (SELECT FOR UPDATE)", strat_pessimistic),
          ("B 낙관적 락  (version 조건부 UPDATE)", strat_optimistic),
          ("C 현재 설계  (Redis hold.lua + DB PK)", strat_current)]


def reset(seat_ids):
    rcli.flushdb()
    with pool.acquire() as con:
        cur = con.cursor()
        cur.execute("DELETE FROM exp_seat")
        cur.execute("DELETE FROM exp_seat_inv")
        cur.executemany("INSERT INTO exp_seat (run_id, seat_id, status) VALUES (:1, :2, 'AVAILABLE')",
                        [(RUN_ID, s) for s in seat_ids])
        con.commit()


def one_round(fn, picks, seat_ids, workers):
    """한 회차를 돌리고 (TPS, 평균ms, p95, p99, DB왕복, 성공, 오류, Stat) 를 돌려준다."""
    reset(seat_ids)
    st = Stat()
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as ex:
        list(ex.map(lambda a: fn(a[0], a[1], st), picks))
    elapsed = time.perf_counter() - t0
    tps = len(picks) / elapsed if elapsed else 0
    return (tps, statistics.fmean(st.lat), pct(st.lat, 95), pct(st.lat, 99),
            st.db_calls, st.ok, st.err, st)


def run_case(title, seat_ids, users, workers):
    print("\n" + "=" * 96)
    print("  %s — 좌석 %d개 · 동시 요청 %d · 스레드 %d" % (title, len(seat_ids), users, workers))
    print("=" * 96)
    print("  %-38s %6s %7s %8s %8s %8s %7s %5s" %
          ("전략 (%d회 중앙값)" % ROUNDS, "성공", "TPS", "평균ms", "p95ms", "p99ms", "DB왕복", "오류"))

    # 세 전략이 똑같은 입력을 받아야 공정하다
    rng = random.Random(42)
    picks = [(i, rng.choice(seat_ids)) for i in range(users)]

    rows = []
    for name, fn in STRATS:
        one_round(fn, picks, seat_ids, workers)                 # 워밍업 (버린다)
        reps = [one_round(fn, picks, seat_ids, workers) for _ in range(ROUNDS)]
        med = lambda i: statistics.median(x[i] for x in reps)
        tps_sorted = sorted(x[0] for x in reps)
        print("  %-38s %6d %7.0f %8.1f %8.1f %8.1f %7d %5d" %
              (name, med(5), med(0), med(1), med(2), med(3), med(4), med(6)))
        rows.append((name, reps[-1][7], tps_sorted))

    print("\n  부가 지표 (워밍업 1회 제외, %d회)" % ROUNDS)
    for name, st, tps_sorted in rows:
        extra = ["TPS %.0f~%.0f" % (tps_sorted[0], tps_sorted[-1])]
        if st.lock_wait:
            extra.append("락 대기 평균 %.1fms / p99 %.1fms"
                         % (statistics.fmean(st.lock_wait), pct(st.lock_wait, 99)))
        if st.retries:
            extra.append("낙관적 재시도 %d회" % st.retries)
        extra.append("정상 거절 %d" % st.lost)
        print("    %-38s %s" % (name, " · ".join(extra)))

    print("\n  중복 예약 검증 (목표 0)")
    with pool.acquire() as con:
        cur = con.cursor()
        cur.execute("SELECT COUNT(*) FROM (SELECT run_id, seat_id FROM exp_seat_inv "
                    "GROUP BY run_id, seat_id HAVING COUNT(*) > 1)")
        inv_dup = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM (SELECT run_id, seat_id FROM exp_seat "
                    "WHERE status='HOLD' GROUP BY run_id, seat_id HAVING COUNT(*) > 1)")
        seat_dup = cur.fetchone()[0]
    print("    같은 좌석이 두 번 팔린 건수: exp_seat_inv %d · exp_seat %d" % (inv_dup, seat_dup))
    return rows


if __name__ == "__main__":
    run_case("시나리오 1 · 같은 좌석 1개에 100명", [5001], 100, 50)
    run_case("시나리오 2 · 인기 운행 좌석 20개에 300명", list(range(6001, 6021)), 300, 60)
    run_case("시나리오 3 · 좌석 500개에 500명 (경쟁 적음)", list(range(7001, 7501)), 500, 60)
    pool.close()
    print("\n끝.")
