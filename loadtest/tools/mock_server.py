#!/usr/bin/env python3
"""제자리 mock 서버 — 실제 서버가 없을 때 k6 시나리오·앱·결과 비교를 점검하기 위한 가짜 서버.

    python3 tools/mock_server.py                      # 정상 동작 (모든 시나리오 통과해야 함)
    python3 tools/mock_server.py --bug dup-seat       # 중복 예약 버그를 일부러 켬 → 시나리오 2 기준 미달
    python3 tools/mock_server.py --port 8080 --hold-ttl 30 --admit-rate 1000

표준 라이브러리만 사용한다. config.js 의 API 경로·필드·헤더 이름과 같은 값을 쓴다.

주의
  * 기능 점검용이다. 이 서버의 TPS·응답 시간은 '팀 서버의 성능'이 아니라 이 파이썬 프로세스의 성능이다.
    락 방식별 지연(X-Lock-Strategy)도 흉내 낸 값이다.
  * 상태는 메모리에만 있다. 시나리오 3·4·6처럼 좌석 ID가 고정인 시나리오를 다시 돌릴 때는
    http://localhost:8080/__mock 에서 '초기화'를 누르거나 서버를 재시작하자.

일부러 켜는 버그(--bug, 여러 개는 쉼표로)
  dup-seat      같은 좌석이 여러 명에게 선점됨 (검사와 기록 사이에 틈)          → 시나리오 2
  no-recover    결제 실패·기한 초과 시 좌석이 복구되지 않음                      → 시나리오 3
  dup-pay       멱등성 키·상태 확인을 무시해 결제가 여러 번 됨                   → 시나리오 4
  order         취소표가 대기 1번이 아니라 마지막 사람에게 배정됨                → 시나리오 5
  token-bypass  입장 토큰이 없어도 좌석 API가 통과됨                             → 시나리오 6
"""
import argparse
import bisect
import csv
import html
import json
import math
import os
import re
import sys
import threading
import time
import uuid
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BUGS = ("dup-seat", "no-recover", "dup-pay", "order", "token-bypass")
LOCK_DELAY = {"pessimistic": 0.006, "optimistic": 0.002, "redis": 0.001}   # 락 방식별 임계구역 시간(흉내)
ENTRY_HEADER = "x-queue-token"


class State:
    def __init__(self, cfg):
        self.cfg = cfg
        self.lock = threading.RLock()          # 전역 상태 보호 (짧게만 잡는다)
        self.seat_locks = defaultdict(threading.Lock)
        self.idem_locks = defaultdict(threading.Lock)
        self.id_map = {}                        # loginId → userId (data/users.csv)
        self.requests = 0
        self.reset()

    def reset(self):
        with self.lock:
            self.tokens = {}          # accessToken → loginId
            self.reset_queue()
            self.seats = {}           # (run, seat) → {status, res, expires, owner}
            self.res = {}             # reservationId → {key, owner, status, created, paid_at}
            self.idem = {}            # (user, kind, key) → (status, body)
            self.waitlist = defaultdict(list)
            self.waiter = {}          # (user, seatkey) → {rank, status, reservationId}
            self.last_wait = {}       # user → seatkey
            self.events = defaultdict(list)   # user → [(ts, type, seat)]
            self.c = defaultdict(int)         # 통계 카운터

    def reset_queue(self):
        """대기열·위험 점수·정책만 비운다 (개발 모드 /api/v1/dev/reset 과 같은 범위)."""
        with self.lock:
            self.qrun = {}            # run → {seq, wait(정렬 리스트), score{member}, adm{member:token}, bucket, last}
            self.member_run = {}      # member → run (회원당 동시 대기 1개)
            self.risk = {}            # member → 위험 점수
            self.policy = {}          # 정책 키 → 값 (queue.admit_per_sec 등)
            self.entry = {}           # 입장 토큰 → {user, run, revoked, at}

    # ── 좌석 ────────────────────────────────────────────────
    def seat(self, key):
        s = self.seats.get(key)
        if s is None:
            s = self.seats[key] = {"status": "AVAILABLE", "res": None, "expires": 0, "owner": None}
        return s

    def new_reservation(self, key, user):
        rid = "R" + uuid.uuid4().hex[:12]
        self.res[rid] = {"key": key, "owner": user, "status": "HELD", "created": time.time(), "paid_at": None}
        return rid

    def hold(self, key, user, rid):
        s = self.seat(key)
        s.update(status="HELD", res=rid, expires=time.time() + self.cfg.hold_ttl, owner=user)

    def release_seat(self, key):
        """좌석을 놓는다. 대기자가 있으면 순서대로 다음 사람에게 넘기고, 없으면 AVAILABLE."""
        s = self.seat(key)
        waiters = self.waitlist[key]
        if waiters:
            pick = waiters.pop(-1 if "order" in self.cfg.bugs else 0)
            rid = self.new_reservation(key, pick)
            self.hold(key, pick, rid)
            w = self.waiter.get((pick, key))
            if w:
                w.update(status="ASSIGNED", reservationId=rid)
            self.c["assigned"] += 1
        else:
            s.update(status="AVAILABLE", res=None, expires=0, owner=None)

    def expire_if_needed(self, key):
        s = self.seat(key)
        if s["status"] == "HELD" and s["expires"] < time.time():
            r = self.res.get(s["res"])
            if r and r["status"] == "HELD":
                r["status"] = "EXPIRED"
                w = self.waiter.get((r["owner"], key))
                if w and w["status"] == "ASSIGNED":
                    w["status"] = "EXPIRED"
            if "no-recover" in self.cfg.bugs:
                s["expires"] = float("inf")     # 좌석이 영원히 선점 상태로 남는다
            else:
                self.release_seat(key)
                self.c["expired"] += 1

    # ── 대기열 (실제 queue 모듈과 같은 규칙) ─────────────────────
    def policy_num(self, key, default):
        try:
            return float(self.policy.get(key, default))
        except (TypeError, ValueError):
            return float(default)

    def run_state(self, run):
        q = self.qrun.get(run)
        if q is None:
            q = self.qrun[run] = {"seq": 0, "wait": [], "info": {}, "adm": {}, "bucket": 0.0, "last": time.time()}
        return q

    def tick(self, run):
        """입장 속도(queue.admit_per_sec)만큼 맨 앞(점수가 작은 순)부터 입장시킨다. st.lock 안에서 부른다."""
        q = self.run_state(run)
        now = time.time()
        rate = self.policy_num("queue.admit_per_sec", self.cfg.admit_rate)
        q["bucket"] = min(max(1.0, rate), q["bucket"] + (now - q["last"]) * rate)
        q["last"] = now
        k = min(int(q["bucket"]), len(q["wait"]))
        if k > 0:
            taken, q["wait"] = q["wait"][:k], q["wait"][k:]
            q["bucket"] -= k
            for score, seq, member in taken:
                q["adm"][member] = "qt_" + uuid.uuid4().hex
                self.entry[q["adm"][member]] = {"user": member, "run": run, "revoked": False, "at": now}

    def penalty_ms(self, member):
        return float(self.risk.get(member, 0)) * self.policy_num("risk.penalty_ms_per_point", 1000)

    # ── 봇 탐지(관리자 화면 흉내) ───────────────────────────
    def suspects(self):
        out = []
        with self.lock:
            for user, ev in self.events.items():
                trains = sorted(t for t, k, _ in ev if k == "trains")
                # 같은 좌석을 다시 보내는 재시도는 '서로 다른 좌석 선점'으로 세지 않는다
                first = {}
                for t, k, seat in sorted(ev):
                    if k == "hold" and seat not in first:
                        first[seat] = t
                holds = sorted(first.values())
                quick_cancels = sum(1 for _, k, _ in ev if k == "cancel_quick")
                reasons = []
                if sum(1 for a, b in zip(trains, trains[1:]) if b - a < 0.12) >= 10:
                    reasons.append("클릭 간격 기준 미달")
                if any(holds[i + 4] - holds[i] <= 5 for i in range(len(holds) - 4)):
                    reasons.append("단시간 대량 선점")
                if quick_cancels >= 2:
                    reasons.append("구매 후 단시간 취소 반복")
                if reasons:
                    out.append({"userId": self.id_map.get(user, user), "loginId": user,
                                "score": 30 * len(reasons) + 40, "reasons": reasons})
        return out


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    st = None   # State (서버 시작 시 주입)

    def log_message(self, *a):   # 요청마다 출력하지 않는다
        pass

    # ── 공통 ────────────────────────────────────────────────
    def body(self):
        return self._body

    def read_body(self):
        """본문은 라우팅 전에 항상 끝까지 읽는다 (안 읽고 응답하면 keep-alive 연결의 다음 요청이 깨진다)."""
        n = int(self.headers.get("Content-Length") or 0)
        self._body = {}
        if n:
            try:
                data = json.loads(self.rfile.read(n).decode("utf-8") or "{}")
                self._body = data if isinstance(data, dict) else {}
            except Exception:
                pass

    def send(self, status, data=None, ctype="application/json; charset=utf-8"):
        raw = (data if isinstance(data, (bytes, str)) else json.dumps(data if data is not None else {},
                                                                    ensure_ascii=False))
        raw = raw.encode("utf-8") if isinstance(raw, str) else raw
        try:
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def auth(self):
        mid = (self.headers.get("X-Member-Id") or "").strip()
        if mid and re.fullmatch(r"[\w.-]{1,64}", mid):      # 개발 모드: 로그인 없이 회원 ID 헤더로 구분
            return mid
        h = self.headers.get("Authorization", "")
        tok = h[7:] if h.startswith("Bearer ") else ""
        user = self.st.tokens.get(tok)
        if not user:
            self.send(401, {"code": "AUTH_REQUIRED", "message": "로그인이 필요합니다"})
        return user

    def dispatch(self, method):
        st = self.st
        st.requests += 1
        self.read_body()
        u = urlparse(self.path)
        path, qs = u.path.rstrip("/") or "/", parse_qs(u.query)
        try:
            for m, pat, fn in ROUTES:
                if m != method:
                    continue
                mt = re.fullmatch(pat, path)
                if mt:
                    return fn(self, st, qs, *mt.groups())
            self.send(404, {"error": "NOT_FOUND", "path": path})
        except Exception as e:   # 예상 밖 오류는 5xx 로 보여 준다 (seat_other 로 집계됨)
            self.send(500, {"error": "MOCK_ERROR", "message": str(e)})

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_PUT(self):
        self.dispatch("PUT")

    def do_DELETE(self):
        self.dispatch("DELETE")


# ───────────────────────── 핸들러 ─────────────────────────
def h_login(h, st, qs):
    b = h.body()
    if not b.get("loginId") or not b.get("password"):
        return h.send(400, {"error": "BAD_REQUEST"})
    tok = "tk_" + uuid.uuid4().hex
    with st.lock:
        st.tokens[tok] = str(b["loginId"])
        st.c["login"] += 1
    h.send(200, {"accessToken": tok, "tokenType": "Bearer"})


def h_signup(h, st, qs):
    h.send(201, {"ok": True})


def h_trains(h, st, qs):
    user = h.auth()
    if not user:
        return
    with st.lock:
        st.events[user].append((time.time(), "trains", ""))
    h.send(200, {"items": [{"runId": 1, "name": "KTX 101", "seats": 400}]})


def ticket_body(st, run, member, ticket, state, rank=None, token=None):
    if state == "ADMITTED":
        return {"state": "ADMITTED", "runId": int(run) if str(run).isdigit() else run, "ticketNo": ticket,
                "pollIntervalSec": 1, "passToken": token, "passExpiresAt": "mock"}
    rate = max(1.0, st.policy_num("queue.admit_per_sec", st.cfg.admit_rate))
    eta = math.ceil(rank / rate)
    return {"state": "WAITING", "runId": int(run) if str(run).isdigit() else run, "ticketNo": ticket,
            "rank": rank, "ahead": rank - 1, "estimatedWaitSec": eta,
            "pollIntervalSec": int(min(5, max(1, (eta + 9) // 10)))}


def queue_status(st, run, member):
    """대기 중이면 (WAITING, 순번), 입장했으면 (ADMITTED, 토큰). 없으면 None. st.lock 안에서."""
    q = st.run_state(run)
    st.tick(run)
    info = q["info"].get(member)
    if info is None:
        return None
    tok = q["adm"].get(member)
    if tok is not None:
        return ticket_body(st, run, member, info[1], "ADMITTED", token=tok)
    rank = bisect.bisect_left(q["wait"], info) + 1
    return ticket_body(st, run, member, info[1], "WAITING", rank=rank)


def h_q_enter(h, st, qs, run):
    user = h.auth()
    if not user:
        return
    with st.lock:
        if st.member_run.get(user, run) != run:
            return h.send(409, {"code": "QUEUE_DUPLICATED", "message": "이미 다른 열차를 기다리고 있습니다"})
        q = st.run_state(run)
        if user not in q["info"]:
            q["seq"] += 1
            score = time.time() * 1000 + st.penalty_ms(user)          # 진입 시각 + 위험 점수 패널티
            info = (score, q["seq"], user)
            q["info"][user] = info
            bisect.insort(q["wait"], info)
            st.member_run[user] = run
            st.c["queue_enter"] += 1
        body = queue_status(st, run, user)
    h.send(200, body)


def h_q_me(h, st, qs, run):
    user = h.auth()
    if not user:
        return
    with st.lock:
        body = queue_status(st, run, user)
    if body is None:
        return h.send(404, {"code": "QUEUE_NOT_FOUND", "message": "대기 중이 아닙니다"})
    h.send(200, body)


def h_q_leave(h, st, qs, run):
    user = h.auth()
    if not user:
        return
    with st.lock:
        q = st.run_state(run)
        info = q["info"].pop(user, None)
        if info is not None:
            i = bisect.bisect_left(q["wait"], info)
            if i < len(q["wait"]) and q["wait"][i] == info:
                del q["wait"][i]
        tok = q["adm"].pop(user, None)
        for e in st.entry.values():          # 입장 토큰 즉시 무효화
            if e["user"] == user and e["run"] == run:
                e["revoked"] = True
        if st.member_run.get(user) == run:
            del st.member_run[user]
    h.send(204, b"", "text/plain")


def h_seat_map(h, st, qs, run):
    """입장 토큰으로 보호되는 좌석 배치도 (실제 앱의 QueueTokenFilter와 같은 응답 코드)."""
    user = h.auth()
    if not user:
        return
    tok = h.headers.get(ENTRY_HEADER)
    with st.lock:
        e = st.entry.get(tok) if tok else None
        valid = bool(e and e["user"] == user and e["run"] == run and not e["revoked"]
                     and time.time() - e["at"] < 300)
        if not valid:
            if "token-bypass" in st.cfg.bugs and not tok:
                st.c["token_bypassed"] += 1
            else:
                st.c["token_blocked"] += 1
                code = "QUEUE_TOKEN_REQUIRED" if not tok else "QUEUE_TOKEN_INVALID"
                return h.send(403, {"code": code, "message": "입장 토큰이 필요합니다" if not tok else "입장 토큰이 맞지 않습니다"})
    h.send(200, {"runId": run, "mock": True})


# ── 개발 모드 관리 API (실제 앱의 /api/v1/dev/**와 같은 동작) ─────────
def h_dev_reset(h, st, qs):
    st.reset_queue()
    h.send(200, {"queue": "cleared", "risk": "cleared", "policyCache": "cleared"})


def h_dev_risk_members(h, st, qs):
    try:
        frm, count, score = int((qs.get("from") or [""])[0]), int((qs.get("count") or [""])[0]), float((qs.get("score") or [""])[0])
    except ValueError:
        return h.send(400, {"code": "BAD_REQUEST", "message": "from·count는 정수, score는 숫자여야 합니다"})
    if not (1 <= count <= 100000 and 0 <= score <= 100):
        return h.send(400, {"code": "BAD_REQUEST", "message": "count는 1~100000, score는 0~100이어야 합니다"})
    with st.lock:
        for i in range(count):
            st.risk[str(frm + i)] = score
    h.send(200, {"from": frm, "count": count, "score": score})


def h_dev_policy(h, st, qs, key):
    value = str(h.body().get("value", ""))
    if not re.fullmatch(r"(queue|risk|waitlist)\.[a-z_]{1,40}", key) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", value):
        return h.send(400, {"code": "BAD_REQUEST", "message": "key는 queue.*·risk.*·waitlist.*, value는 영문·숫자·._- 32자 이내"})
    with st.lock:
        st.policy[key] = value
    h.send(200, {"key": key, "value": value, "appliesWithinMs": 0})


def idempotent(h, st, user, kind, fn):
    """Idempotency-Key 가 있으면 같은 키의 요청은 같은 응답을 돌려준다 (동시 요청도 직렬화)."""
    key = h.headers.get("Idempotency-Key")
    if not key or ("dup-pay" in st.cfg.bugs and kind == "pay"):
        return fn()
    ik = (user, kind, key)
    with st.idem_locks[ik]:
        cached = st.idem.get(ik)
        if cached:
            return cached
        result = fn()
        st.idem[ik] = result
        return result


def h_seat_hold(h, st, qs):
    user = h.auth()
    if not user:
        return
    b = h.body()
    run, seat = str(b.get("runId", "1")), str(b.get("seatId", ""))
    if not seat:
        return h.send(400, {"error": "SEAT_REQUIRED"})
    # 입장 토큰 검증
    tok = h.headers.get(ENTRY_HEADER)
    with st.lock:
        st.events[user].append((time.time(), "hold", seat))
        e = st.entry.get(tok) if tok else None
        valid = bool(e and e["user"] == user and e["run"] == run and not e["revoked"])
        if not valid and not ("token-bypass" in st.cfg.bugs and not tok):
            st.c["token_blocked"] += 1
            return h.send(403, {"error": "ENTRY_TOKEN_INVALID"})
        if not valid:
            st.c["token_bypassed"] += 1

    key = (run, seat)
    strategy = h.headers.get("X-Lock-Strategy") or st.cfg.lock
    delay = LOCK_DELAY.get(strategy, LOCK_DELAY["redis"])

    def attempt():
        if "dup-seat" in st.cfg.bugs:
            # 버그: 검사 → (틈) → 기록. 락 없이 여러 요청이 같은 좌석을 통과한다.
            with st.lock:
                st.expire_if_needed(key)
                free = st.seat(key)["status"] == "AVAILABLE"
            if not free:
                return 409, {"error": "SEAT_TAKEN"}
            time.sleep(delay + 0.004)
            with st.lock:
                return finish_hold(st, key, user)
        with st.seat_locks[key]:             # 좌석 단위 락 (방식별 임계구역 시간을 흉내)
            time.sleep(delay)
            with st.lock:
                st.expire_if_needed(key)
                if st.seat(key)["status"] != "AVAILABLE":
                    return 409, {"error": "SEAT_TAKEN"}
                return finish_hold(st, key, user)

    status, data = idempotent(h, st, user, "hold", attempt)
    h.send(status, data)


def finish_hold(st, key, user):
    mine = sum(1 for r in st.res.values() if r["owner"] == user and r["status"] in ("HELD", "PAID"))
    if mine >= st.cfg.max_per_user:
        st.c["hold_limit"] += 1
        return 409, {"error": "PURCHASE_LIMIT"}
    if st.seat(key)["status"] != "AVAILABLE":
        st.c["double_booked"] += 1           # 버그가 켜졌을 때만 발생: 이미 선점된 좌석에 또 성공
    rid = st.new_reservation(key, user)
    st.hold(key, user, rid)
    st.c["hold_ok"] += 1
    return 201, {"reservationId": rid, "seatId": key[1], "expiresInSec": st.cfg.hold_ttl}


def h_payment(h, st, qs):
    user = h.auth()
    if not user:
        return
    b = h.body()
    rid = str(b.get("reservationId", ""))
    pg = (h.headers.get("X-Mock-PG") or "success").lower()
    r = st.res.get(rid)
    if not r or r["owner"] != user:
        return h.send(404, {"error": "RESERVATION_NOT_FOUND"})

    def attempt():
        if "dup-pay" in st.cfg.bugs:
            time.sleep(0.004)                # 상태 확인 없이 결제 (버그)
        with st.lock:
            st.expire_if_needed(r["key"])
            if r["status"] != "HELD" and "dup-pay" not in st.cfg.bugs:
                return 409, {"error": "NOT_PAYABLE", "status": r["status"]}
            if pg == "fail":
                r["status"] = "FAILED"
                st.c["pay_fail"] += 1
                if "no-recover" not in st.cfg.bugs:
                    threading.Timer(0.3, saga_release, (st, rid)).start()   # Saga 보상(비동기)
                return 402, {"status": "FAILED"}
            if pg == "timeout":
                st.c["pay_timeout"] += 1
                return 504, {"status": "UNKNOWN"}       # 결과 불명: 좌석은 선점 상태로 둔다
            r["status"], r["paid_at"] = "PAID", time.time()
            st.seat(r["key"]).update(status="CONFIRMED", expires=0)
            st.c["pay_ok"] += 1
            return 201, {"paymentId": "P" + uuid.uuid4().hex[:12], "status": "PAID"}

    status, data = idempotent(h, st, user, "pay", attempt)
    if pg == "timeout" and status == 504:
        time.sleep(3)
    h.send(status, data)


def saga_release(st, rid):
    with st.lock:
        r = st.res.get(rid)
        if r and r["status"] == "FAILED":
            s = st.seat(r["key"])
            if s["res"] == rid:
                st.release_seat(r["key"])
                st.c["recovered"] += 1


def h_cancel(h, st, qs, rid):
    user = h.auth()
    if not user:
        return
    with st.lock:
        r = st.res.get(rid)
        if not r or r["owner"] != user:
            return h.send(404, {"error": "RESERVATION_NOT_FOUND"})
        if r["status"] not in ("HELD", "PAID"):
            return h.send(409, {"error": "NOT_CANCELLABLE", "status": r["status"]})
        quick = time.time() - r["created"] < 10
        r["status"] = "CANCELLED"
        st.events[user].append((time.time(), "cancel_quick" if quick else "cancel", ""))
        if st.seat(r["key"])["res"] == rid:
            st.release_seat(r["key"])        # 대기자가 있으면 순서대로 배정
        st.c["cancel"] += 1
    h.send(200, {"status": "CANCELLED"})


def h_waitlist_join(h, st, qs):
    user = h.auth()
    if not user:
        return
    b = h.body()
    key = (str(b.get("runId", "1")), str(b.get("seatId", "")))
    with st.lock:
        if user in st.waitlist[key]:
            return h.send(409, {"error": "ALREADY_WAITING"})
        st.waitlist[key].append(user)
        rank = len(st.waitlist[key])
        st.waiter[(user, key)] = {"rank": rank, "status": "WAITING", "reservationId": None}
        st.last_wait[user] = key
    h.send(201, {"rank": rank, "status": "WAITING"})


def h_waitlist_me(h, st, qs):
    user = h.auth()
    if not user:
        return
    with st.lock:
        key = st.last_wait.get(user)
        if key:
            st.expire_if_needed(key)
        w = st.waiter.get((user, key)) if key else None
        if not w:
            return h.send(404, {"error": "NOT_WAITING"})
        data = dict(w)
    h.send(200, data)


def h_seat_status(h, st, qs, run, seat):
    key = (run, seat)
    with st.lock:
        st.expire_if_needed(key)
        status = st.seat(key)["status"]
    h.send(200, {"seatId": seat, "status": status})


def h_admin_suspects(h, st, qs):
    h.send(200, {"items": st.suspects()})


def stats_snapshot(st):
    with st.lock:
        seats = defaultdict(int)
        for s in st.seats.values():
            seats[s["status"]] += 1
        return {"requests": st.requests, "seats": dict(seats), "counters": dict(st.c),
                "bugs": list(st.cfg.bugs), "lock": st.cfg.lock, "holdTtlSec": st.cfg.hold_ttl,
                "admitRatePerSec": st.cfg.admit_rate}


def h_mock_page(h, st, qs):
    s = stats_snapshot(st)
    rows = "".join(f"<tr><td>{html.escape(k)}</td><td align=right>{v:,}</td></tr>"
                   for k, v in sorted(s["counters"].items()))
    page = f"""<!doctype html><meta charset=utf-8><title>제자리 mock 서버</title>
<body style="font-family:system-ui,'Malgun Gothic',sans-serif;max-width:560px;margin:32px auto;color:#1f2937">
<h2>제자리 mock 서버</h2>
<p>요청 {s['requests']:,}건 · 좌석 {html.escape(json.dumps(s['seats']))} · 켜진 버그: <b>{html.escape(', '.join(s['bugs']) or '없음(정상)')}</b></p>
<table cellpadding=6 border=1 style="border-collapse:collapse;border-color:#e2e6ec">{rows or '<tr><td>아직 요청 없음</td></tr>'}</table>
<form method=post action="/__mock/reset" style="margin-top:16px"><button style="padding:8px 16px">상태 초기화</button></form>
<p style="color:#6b7280;font-size:13px">상태는 메모리에만 있습니다. 같은 시나리오를 다시 돌리기 전에 초기화하세요. 새로고침하면 최신 값이 보입니다.</p></body>"""
    h.send(200, page, "text/html; charset=utf-8")


def h_mock_stats(h, st, qs):
    h.send(200, stats_snapshot(st))


def h_mock_reset(h, st, qs):
    st.reset()
    st.requests = 0
    if "text/html" in (h.headers.get("Accept") or ""):
        h.send(200, "<meta charset=utf-8><meta http-equiv=refresh content='0;url=/__mock'>초기화됨", "text/html; charset=utf-8")
    else:
        h.send(200, {"status": "RESET"})


ROUTES = [
    ("POST", r"/api/auth/login", h_login),
    ("POST", r"/api/members/signup", h_signup),
    ("GET", r"/api/trains", h_trains),
    ("POST", r"/api/v1/runs/([^/]+)/queue", h_q_enter),
    ("GET", r"/api/v1/runs/([^/]+)/queue/me", h_q_me),
    ("DELETE", r"/api/v1/runs/([^/]+)/queue/me", h_q_leave),
    ("GET", r"/api/v1/runs/([^/]+)/seats", h_seat_map),
    ("POST", r"/api/v1/dev/reset", h_dev_reset),
    ("PUT", r"/api/v1/dev/risk/dev-members", h_dev_risk_members),
    ("PUT", r"/api/v1/dev/policy/([^/]+)", h_dev_policy),
    ("POST", r"/api/seats/hold", h_seat_hold),
    ("GET", r"/api/runs/([^/]+)/seats/([^/]+)", h_seat_status),
    ("POST", r"/api/payments", h_payment),
    ("POST", r"/api/reservations/([^/]+)/cancel", h_cancel),
    ("POST", r"/api/waitlist", h_waitlist_join),
    ("GET", r"/api/waitlist/me", h_waitlist_me),
    ("GET", r"/api/admin/suspects", h_admin_suspects),
    ("GET", r"/__mock", h_mock_page),
    ("GET", r"/__mock/stats", h_mock_stats),
    ("POST", r"/__mock/reset", h_mock_reset),
]


# ───────────────────────── 실행 ─────────────────────────
def reaper(st):
    """만료된 선점을 주기적으로 반환한다 (결제 기한 초과 자동 반환)."""
    while True:
        time.sleep(0.5)
        with st.lock:
            for key in list(st.seats):
                st.expire_if_needed(key)


def reporter(st):
    last, t0 = 0, time.time()
    while True:
        time.sleep(5)
        now, total = time.time(), st.requests
        if total != last:
            c = st.c
            print(f"[mock] 최근 5초 {total - last:,}건 ({(total - last) / (now - t0):,.0f}/s) | "
                  f"선점 성공 {c['hold_ok']:,} · 결제 성공 {c['pay_ok']:,} · 결제 실패 {c['pay_fail']:,}"
                  + (f" · 중복 예약 {c['double_booked']:,}" if c["double_booked"] else ""), flush=True)
        last, t0 = total, now


def load_id_map(path):
    m = {}
    try:
        with open(path, encoding="utf-8-sig") as f:
            for r in csv.DictReader(f):
                m[r["loginId"]] = r["userId"]
                if r.get("memberId"):
                    m[r["memberId"]] = r["userId"]        # 개발 모드(X-Member-Id)에서는 회원 ID가 사용자 식별값
    except Exception:
        pass
    return m


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1", help="기본은 내 PC에서만 접속 가능 (VM에서 쓰려면 0.0.0.0)")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--bug", default="", help="쉼표로 구분: " + ", ".join(BUGS))
    p.add_argument("--lock", default="redis", choices=sorted(LOCK_DELAY), help="X-Lock-Strategy 헤더가 없을 때 쓸 락 방식")
    p.add_argument("--hold-ttl", type=int, default=30, help="좌석 선점 만료(초). config.js 의 HOLD_TTL_SEC 와 맞춘다")
    p.add_argument("--admit-rate", type=float, default=1000, help="대기열이 초당 입장시키는 인원")
    p.add_argument("--admit-burst", type=int, default=500)
    p.add_argument("--max-per-user", type=int, default=4, help="1인 최대 매수")
    p.add_argument("--users", default=os.path.join(here, "..", "data", "users.csv"),
                   help="탐지율 평가용: loginId → userId 변환에 쓸 users.csv")
    cfg = p.parse_args()
    cfg.bugs = {b.strip() for b in cfg.bug.split(",") if b.strip()}
    bad = cfg.bugs - set(BUGS)
    if bad:
        sys.exit(f"알 수 없는 버그 이름: {', '.join(sorted(bad))}  (사용 가능: {', '.join(BUGS)})")

    st = State(cfg)
    st.id_map = load_id_map(cfg.users)
    Handler.st = st
    ThreadingHTTPServer.request_queue_size = 2048
    ThreadingHTTPServer.daemon_threads = True
    try:
        srv = ThreadingHTTPServer((cfg.host, cfg.port), Handler)
    except OSError as e:
        sys.exit(f"[오류] {cfg.host}:{cfg.port} 를 열 수 없습니다 ({e}). 이미 사용 중이면 --port 로 바꾸세요.")
    threading.Thread(target=reaper, args=(st,), daemon=True).start()
    threading.Thread(target=reporter, args=(st,), daemon=True).start()
    print(f"[mock] http://{cfg.host}:{cfg.port}  (상태 화면: /__mock)", flush=True)
    print(f"[mock] 모드: {'정상' if not cfg.bugs else '버그 켬 → ' + ', '.join(sorted(cfg.bugs))} · "
          f"선점 만료 {cfg.hold_ttl}초 · 입장 {cfg.admit_rate:g}명/초 · 1인 {cfg.max_per_user}매", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] 종료", flush=True)


if __name__ == "__main__":
    main()
