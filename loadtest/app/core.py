"""제자리 부하 테스트 데스크톱 앱의 엔진 (UI와 분리, 표준 라이브러리만 사용).

실행 위치(Target)
  - 로컬: 이 PC에서 k6 실행
  - SSH : VM에서 k6 실행. 시스템 `ssh` 명령(키 인증)을 그대로 사용한다.
          지표는 `ssh -L` 포트포워딩으로 가져오고, 중지는 원격 PID에 SIGINT를 보낸다.

UI와는 queue.Queue 로 이벤트를 주고받는다.
  ('log', str) ('tick', dict) ('done', dict) ('state', dict) ('info', dict)
"""
import collections
import datetime
import io
import json
import os
import queue
import re
import shlex
import signal
import subprocess
import sys
import tarfile
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
K6_API_PORT = 6565          # k6 REST API (k6가 실행되는 머신 기준)
K6_API_FORWARD_PORT = 16565  # SSH 모드에서 내 PC로 포워딩하는 포트
BUILTIN_METRICS = ["http_reqs", "http_req_failed", "http_req_duration", "iterations", "vus"]
SAFE_HOST = re.compile(r"^(localhost|127\.0\.0\.1|\[::1\]|::1|host\.docker\.internal)$|\.(local|test|localhost|internal)$")
HOST_RE = re.compile(r"^[A-Za-z0-9_][\w.@-]*$")          # 맨 앞 '-' 금지 (옵션 주입 방지)
RDIR_RE = re.compile(r"^[\w ./~-]+$")


# 앱을 pythonw(콘솔 없음)로 띄워도 k6·ssh 같은 자식 프로세스가 검은 cmd 창을 새로 만들지 않게 한다.
NOWIN = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}


# 부하를 만드는 프로세스(k6·mock 서버)는 낮은 우선순위로 돌려, 앱 창이 CPU를 먼저 받게 한다 (안 그러면 '응답 없음'이 뜬다).
NOWIN_LOW = ({"creationflags": subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS}
             if sys.platform == "win32" else {})
LOG_LINES_PER_SEC = 80   # 화면에 보여 줄 로그의 초당 최대 줄 수 (k6 는 실패 요청마다 경고를 찍어 수천 줄이 쏟아질 수 있다)


def lower_priority(proc):
    if sys.platform != "win32":
        try:
            os.setpriority(os.PRIO_PROCESS, proc.pid, 5)
        except (OSError, AttributeError):
            pass


def python_exe():
    """자식 파이썬 프로세스용 실행 파일. pythonw 로 떠 있으면 출력을 받을 수 있는 python.exe 를 쓴다."""
    exe = sys.executable
    if sys.platform == "win32" and exe.lower().endswith("pythonw.exe"):
        cand = exe[:-len("pythonw.exe")] + "python.exe"
        if os.path.exists(cand):
            return cand
    return exe


def load_scenarios():
    with open(ROOT / "scenarios.json", encoding="utf-8") as f:
        return json.load(f)


# ───────────────────────── 실행 위치 ─────────────────────────
class Target:
    def __init__(self, mode="local", host="", port=22, key="", remote_dir="~/jejari-loadtest",
                 prom_url="http://localhost:9090/api/v1/write"):
        self.mode = mode
        self.host = host.strip()
        self.port = int(port or 22)
        self.key = key.strip()
        self.remote_dir = (remote_dir or "~/jejari-loadtest").strip()
        self.prom_url = prom_url.strip()

    @property
    def remote(self):
        return self.mode == "ssh"

    def validate(self):
        if not self.remote:
            return None
        if not HOST_RE.match(self.host):
            return "SSH 호스트 형식이 올바르지 않습니다. 예: ubuntu@10.0.0.5"
        if not RDIR_RE.match(self.remote_dir):
            return "원격 프로젝트 경로에 허용되지 않는 문자가 있습니다."
        if not 1 <= self.port <= 65535:
            return "SSH 포트 범위 오류"
        return None

    def ssh_base(self, forward=False):
        cmd = ["ssh", "-p", str(self.port), "-o", "BatchMode=yes",
               "-o", "StrictHostKeyChecking=accept-new", "-o", "ServerAliveInterval=15"]
        if self.key:
            cmd += ["-i", self.key]
        if forward:
            cmd += ["-L", f"{K6_API_FORWARD_PORT}:127.0.0.1:{K6_API_PORT}"]
        return cmd + [self.host]

    def rdir(self):
        d = self.remote_dir
        if d == "~":
            return '"$HOME"'
        if d.startswith("~/"):
            return '"$HOME"/' + shlex.quote(d[2:])
        return shlex.quote(d)

    def poll_port(self):
        return K6_API_FORWARD_PORT if self.remote else K6_API_PORT

    def to_dict(self):
        return {"mode": self.mode, "host": self.host, "port": self.port, "key": self.key,
                "remote_dir": self.remote_dir, "prom_url": self.prom_url}


def run_capture(argv, timeout=15, input_bytes=None):
    try:
        r = subprocess.run(argv, capture_output=True, timeout=timeout, input=input_bytes, **NOWIN)
        return r.returncode, r.stdout.decode("utf-8", "replace"), r.stderr.decode("utf-8", "replace")
    except FileNotFoundError:
        return 127, "", f"{argv[0]} 명령을 찾을 수 없습니다"
    except subprocess.TimeoutExpired:
        return 124, "", "시간 초과"


def remote_shell(target, script, timeout=15, input_bytes=None):
    return run_capture(target.ssh_base() + [script], timeout, input_bytes)


# ───────────────────────── 환경 확인 ─────────────────────────
def check_environment(target):
    """k6 버전과 users.csv 현황. 반환: dict(k6, users{exists,total,bots}, error)"""
    err = target.validate()
    if err:
        return {"k6": None, "users": {"exists": False, "total": 0, "bots": 0}, "error": err}
    info = {"k6": None, "users": {"exists": False, "total": 0, "bots": 0}, "error": None}

    if target.remote:
        code, out, e = remote_shell(target, "k6 version", timeout=15)
        if code == 127:
            info["error"] = "ssh 명령을 찾을 수 없습니다. OpenSSH 클라이언트를 설치하세요."
            return info
        if code != 0 and not out.strip():
            info["error"] = f"SSH 연결/명령 실패: {e.strip() or '키 인증 설정을 확인하세요'}"
            return info
        info["k6"] = out.strip().splitlines()[0] if code == 0 and out.strip() else None
        script = (f'cd {target.rdir()} 2>/dev/null && [ -f data/users.csv ] && '
                  # csv 모듈은 CRLF로 쓰므로 라벨에서 영문/밑줄 외 문자를 제거하고 비교한다
                  "awk -F, 'NR>1{t++; l=$4; gsub(/[^a-z_]/,\"\",l); if(l!=\"normal\")b++} END{print t+0, b+0}' data/users.csv")
        code, out, _ = remote_shell(target, script)
        if code == 0 and out.split():
            t, b = out.split()[:2]
            info["users"] = {"exists": True, "total": int(t), "bots": int(b)}
        elif code != 0:
            code2, _, _ = remote_shell(target, f"[ -d {target.rdir()}/scenarios ]")
            if code2 != 0:
                info["error"] = "VM에 프로젝트가 없습니다. '프로젝트 업로드'를 먼저 누르세요."
    else:
        code, out, _ = run_capture(["k6", "version"], timeout=5)
        if code == 0 and out.strip():
            info["k6"] = out.strip().splitlines()[0]
        f = ROOT / "data" / "users.csv"
        if f.exists():
            rows = f.read_text(encoding="utf-8").strip().splitlines()[1:]
            bots = sum(1 for r in rows if (r.split(",") + [""] * 4)[3].strip() not in ("", "normal"))
            info["users"] = {"exists": True, "total": len(rows), "bots": bots}
    return info


# ───────────────────────── k6 인자 구성 ─────────────────────────
def build_k6_args(sc, params, base_url, run_id, own, prom, stamp=None):
    """검증된 k6 인자와 summary 상대경로를 만든다. 오류는 ValueError."""
    m = re.match(r"^(https?)://([^/:?#]+|\[[^\]]+\])(:\d+)?(/[^?#]*)?$", str(base_url or "").strip())
    if not m:
        raise ValueError("대상 URL 형식이 올바르지 않습니다. 예: http://localhost:8080")
    host = m.group(2)
    local = bool(SAFE_HOST.search(host))
    if not local and not own:
        raise ValueError('로컬/내부 주소가 아닙니다. 직접 소유했거나 허가받은 서버일 때만 "내 서버입니다"를 체크하세요.')
    base = f"{m.group(1)}://{host}{m.group(3) or ''}{(m.group(4) or '').rstrip('/')}"
    if not re.match(r"^[\w.-]{1,32}$", str(run_id)):
        raise ValueError("RUN_ID 형식 오류")

    env, raw = {}, {}
    for p in sc["params"]:
        v = params.get(p["key"], p["def"])
        t = p.get("type", "range")
        if t == "select":
            if v not in [o[0] for o in p["options"]]:
                raise ValueError(f"{p['label']}: 허용되지 않는 값")
            raw[p["key"]] = env[p["key"]] = v
        elif t == "text":
            v = str(v or "")
            if not re.match(r"^[\w.-]{0,64}$", v):
                raise ValueError(f"{p['label']}: 영문/숫자/._- 만 가능")
            raw[p["key"]] = env[p["key"]] = v
        else:
            try:
                v = float(v)
            except (TypeError, ValueError):
                raise ValueError(f"{p['label']}: 숫자가 필요합니다")
            v = int(min(p["max"], max(p["min"], round(v))))
            raw[p["key"]] = v
            env[p["key"]] = f"{v}{p['suffix']}" if p.get("suffix") else v
    for k, spec in (sc.get("derived") or {}).items():
        if "min" in spec:
            env[k] = min(raw[x] if isinstance(x, str) else x for x in spec["min"])

    stamp = stamp or datetime.datetime.now().strftime("%Y%m%d%H%M%S")
    summary = f"results/{sc['file'][:-3]}_{stamp}.json"
    args = ["run", f"scenarios/{sc['file']}", "--quiet", "--no-color",
            "--address", f"127.0.0.1:{K6_API_PORT}", "--summary-export", summary,
            "-e", f"BASE_URL={base}", "-e", f"RUN_ID={run_id}"]
    if not local:
        args += ["-e", "I_OWN_THIS_TARGET=yes"]
    for k, v in env.items():
        if v != "" and v is not None:
            args += ["-e", f"{k}={v}"]
    if prom:
        args += ["-o", "experimental-prometheus-rw", "--tag", f"testid={sc['id']}_{stamp}"]
    return args, summary


# ───────────────────────── 실행기 ─────────────────────────
class Runner:
    def __init__(self, events):
        self.q = events
        self.lock = threading.Lock()
        self.job = None  # dict

    # ---- 공통
    @property
    def busy(self):
        return self.job is not None

    def _log(self, s):
        j = self.job
        if j:
            j["lines"].append(s)
        self.q.put(("log", s))

    def _state(self):
        j = self.job
        self.q.put(("state", {"running": bool(j), "title": j["title"] if j else None,
                              "stopping": bool(j and j["stopping"])}))

    def _begin(self, kind, title, **extra):
        with self.lock:
            if self.job:
                return False
            self.job = {"kind": kind, "title": title, "stopping": False, "started": time.time(),
                        "proc": None, "lines": collections.deque(maxlen=400), **extra}
        self._state()
        return True

    def _end(self):
        with self.lock:
            self.job = None
        self._state()

    def _pump(self, proc):
        t0, n, dropped = time.time(), 0, 0
        for line in iter(proc.stdout.readline, ""):
            line = line.rstrip("\r\n")
            if not line.strip():
                continue
            now = time.time()
            if now - t0 >= 1:
                if dropped:
                    self._log(f"[알림] 로그가 너무 많아 {dropped:,}줄을 화면에서 생략했습니다 (원인 진단에는 반영됩니다)")
                t0, n, dropped = now, 0, 0
            n += 1
            if n > LOG_LINES_PER_SEC:
                dropped += 1
                j = self.job
                if j:
                    j["lines"].append(line)    # 화면에는 안 보이지만 진단용으로 보관
                continue
            self._log(line)
        if dropped:
            self._log(f"[알림] 로그가 너무 많아 {dropped:,}줄을 화면에서 생략했습니다 (원인 진단에는 반영됩니다)")

    # ---- k6 실행
    def start_k6(self, target, sc, params, base_url, run_id, own, prom, users_total=0):
        err = target.validate()
        if err:
            return False, err
        try:
            args, summary = build_k6_args(sc, params, base_url, run_id, own, prom)
        except ValueError as e:
            return False, str(e)
        if not self._begin("k6", sc["title"], sc=sc, summary=summary, target=target):
            return False, "이미 실행 중인 작업이 있습니다."
        threading.Thread(target=self._run_k6, args=(target, args, summary, prom), daemon=True).start()
        return True, None

    def _run_k6(self, target, args, summary, prom):
        job = self.job
        prom_env = {}
        if prom:
            prom_env = {"K6_PROMETHEUS_RW_SERVER_URL": target.prom_url,
                        "K6_PROMETHEUS_RW_TREND_STATS": "p(95),p(99),avg,max"}
        if target.remote:
            exports = " ".join(f"{k}={shlex.quote(v)}" for k, v in prom_env.items())
            script = (f"cd {target.rdir()} && mkdir -p results && echo $$ > .k6.pid && "
                      f"exec env {exports} k6 " + " ".join(shlex.quote(a) for a in args))
            argv = target.ssh_base(forward=True) + [script]
            env = None
            self._log(f"[VM {target.host}] k6 {' '.join(args)}")
        else:
            (ROOT / "results").mkdir(parents=True, exist_ok=True)
            argv = ["k6"] + args
            env = {**os.environ, **prom_env}
            self._log("$ " + " ".join(argv))
        try:
            proc = subprocess.Popen(argv, cwd=None if target.remote else str(ROOT), env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace", bufsize=1, **NOWIN_LOW)
            lower_priority(proc)
        except FileNotFoundError:
            self._log("[오류] " + ("ssh" if target.remote else "k6") + " 명령을 찾을 수 없습니다.")
            self._finish(job, None, spawn_failed=True)
            return
        job["proc"] = proc
        pump = threading.Thread(target=self._pump, args=(proc,), daemon=True)
        pump.start()
        poller = threading.Thread(target=self._poll, args=(job, proc, target.poll_port()), daemon=True)
        poller.start()
        code = proc.wait()
        pump.join(timeout=2)
        self._finish(job, code)

    def _poll(self, job, proc, port):
        prev = None
        while proc.poll() is None:
            time.sleep(1)
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/metrics", timeout=0.8) as r:
                    data = json.load(r).get("data", [])
            except Exception:
                continue
            by = {m["id"]: m["attributes"]["sample"] for m in data}
            now = time.time()
            reqs = by.get("http_reqs", {}).get("count", 0)
            rps = max(0.0, (reqs - prev[1]) / (now - prev[0])) if prev else 0.0
            prev = (now, reqs)
            dur = by.get("http_req_duration", {})
            fail = by.get("http_req_failed", {})
            self.q.put(("tick", {
                "t": int(now - job["started"]),
                "vus": by.get("vus", {}).get("value", 0),
                "rps": round(rps, 1), "reqs": reqs,
                "failRate": fail.get("rate", fail.get("value", 0)) or 0,
                "p95": dur.get("p(95)"), "avg": dur.get("avg"),
                "custom": {n: by[n] for n in job["sc"]["keyMetrics"] if n in by},
            }))

    def _read_summary(self, job, target):
        try:
            if target.remote:
                code, out, _ = remote_shell(target, f"cat {target.rdir()}/{shlex.quote(job['summary'])}")
                raw = json.loads(out) if code == 0 else {}
            else:
                raw = json.loads((ROOT / job["summary"]).read_text(encoding="utf-8"))
            res = {}
            for n in BUILTIN_METRICS + job["sc"]["keyMetrics"]:
                m = raw.get("metrics", {}).get(n)
                if m:
                    res[n] = m.get("values", m)
            return res
        except Exception:
            return {}

    def _finish(self, job, code, spawn_failed=False):
        if spawn_failed:
            status = "error"
        elif job["stopping"]:
            status = "stopped"
        elif code == 0:
            status = "pass"
        elif code == 99:
            status = "fail"
        else:
            status = "error"
        metrics = {}
        if job["kind"] == "k6" and not spawn_failed:
            metrics = self._read_summary(job, job["target"])
            if not metrics and status != "stopped":
                self._log("[알림] 결과 파일을 읽지 못했습니다. 위 로그의 k6 오류를 확인하세요 "
                          f"(저장 위치: {job['summary']})")
        hints = diagnose(job["kind"], status, list(job["lines"]), metrics, job.get("sc"))
        for h in hints:
            self._log("[진단] " + h)
        self._log(f"── 종료: {status} (exit={code}) ──")
        self.q.put(("done", {"status": status, "code": code, "title": job["title"], "kind": job["kind"],
                             "hints": hints,
                             "seconds": int(time.time() - job["started"]),
                             "summaryFile": job.get("summary"), "metrics": metrics}))
        self._end()

    # ---- 중지
    def stop(self):
        job = self.job
        if not job:
            return
        job["stopping"] = True
        self._state()
        self._log("── 중지 요청 (k6가 정리 후 종료합니다) ──")
        proc = job.get("proc")
        if not proc:
            return
        target = job.get("target")
        if job["kind"] != "k6":
            proc.terminate()  # 계정 생성·업로드는 정리할 것이 없으므로 바로 종료
        elif target.remote:
            remote_shell(target, f"kill -INT $(cat {target.rdir()}/.k6.pid) 2>/dev/null; true", timeout=10)
        elif sys.platform == "win32":
            proc.terminate()
        else:
            proc.send_signal(signal.SIGINT)

        def force():
            time.sleep(12)
            if proc.poll() is None:
                proc.kill()
        threading.Thread(target=force, daemon=True).start()

    # ---- 계정 생성
    def gen_users(self, target, count, bot_ratio):
        err = target.validate()
        if err:
            return False, err
        count = int(min(200000, max(10, count)))
        bot_ratio = float(min(0.5, max(0.0, bot_ratio)))
        if not self._begin("gen", "테스트 계정 생성", target=target):
            return False, "이미 실행 중인 작업이 있습니다."

        def work():
            job = self.job
            args = ["tools/gen_users.py", "--count", str(count), "--bot-ratio", str(bot_ratio)]
            if target.remote:
                argv = target.ssh_base() + [f"cd {target.rdir()} && python3 " + " ".join(map(shlex.quote, args))]
            else:
                argv = [python_exe()] + args
            self._log("$ " + " ".join(argv))
            try:
                proc = subprocess.Popen(argv, cwd=None if target.remote else str(ROOT),
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                        encoding="utf-8", errors="replace", **NOWIN)
            except FileNotFoundError:
                self._log("[오류] 실행 파일을 찾을 수 없습니다.")
                self._finish(job, None, spawn_failed=True)
                return
            job["proc"] = proc
            pump = threading.Thread(target=self._pump, args=(proc,), daemon=True)
            pump.start()
            code = proc.wait()
            pump.join(timeout=2)
            self._finish(job, code)

        threading.Thread(target=work, daemon=True).start()
        return True, None

    # ---- 프로젝트 업로드 (SSH)
    def upload(self, target):
        err = target.validate()
        if err or not target.remote:
            return False, err or "SSH 모드에서만 사용합니다."
        if not self._begin("upload", "프로젝트 업로드", target=target):
            return False, "이미 실행 중인 작업이 있습니다."

        def work():
            job = self.job
            buf = io.BytesIO()
            skip_dirs = {"data", "results", ".git", "__pycache__", "node_modules"}

            def flt(ti):
                parts = Path(ti.name).parts
                if any(p in skip_dirs for p in parts) or ti.name.endswith((".zip", ".pyc")):
                    return None
                return ti
            with tarfile.open(fileobj=buf, mode="w:gz") as tar:
                tar.add(str(ROOT), arcname=".", filter=flt)
            self._log(f"[업로드] {len(buf.getvalue()) // 1024} KB → {target.host}:{target.remote_dir}")
            code, _, e = remote_shell(target, f"mkdir -p {target.rdir()} && tar xzf - -C {target.rdir()}",
                                      timeout=120, input_bytes=buf.getvalue())
            if code != 0:
                self._log(f"[오류] 업로드 실패: {e.strip()}")
            else:
                self._log("[업로드] 완료 (data/ results/ 는 VM의 기존 것을 유지)")
            self._finish(job, code)

        threading.Thread(target=work, daemon=True).start()
        return True, None


# ───────────────────────── 실행 이력 · 개선 전후 비교 ─────────────────────────
HIGHER, LOWER = "higher", "lower"   # 값이 클수록 / 작을수록 좋은 지표
HISTORY_FILE = ROOT / "results" / "history.json"
HISTORY_MAX = 300
STATUS_TEXT = {"pass": "통과", "fail": "기준 미달", "error": "오류", "stopped": "중지됨"}
# (표시 이름, k6 지표, 필드, 배수, 서식, 좋은 방향)
CORE_ROWS = [
    ("총 요청", "http_reqs", "count", 1, "{:,.0f}", HIGHER),
    ("처리량 (요청/초)", "http_reqs", "rate", 1, "{:,.1f}", HIGHER),
    ("평균 응답 (ms)", "http_req_duration", "avg", 1, "{:,.1f}", LOWER),
    ("p95 응답 (ms)", "http_req_duration", "p(95)", 1, "{:,.1f}", LOWER),
    ("최대 응답 (ms)", "http_req_duration", "max", 1, "{:,.1f}", LOWER),
    ("실패율 (%)", "http_req_failed", "rate", 100, "{:,.2f}", LOWER),
]


def _num(metrics, name, field):
    m = (metrics or {}).get(name)
    if not isinstance(m, dict):
        return None
    v = m.get(field)
    if v is None and field == "rate":
        v = m.get("value")      # 최신 k6의 Rate 지표는 value 로 내보낸다
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def _key_metric(m):
    """시나리오 핵심 지표(Counter/Rate/Trend/Gauge) 하나를 (값, 서식)으로."""
    if not isinstance(m, dict):
        return None, "{}"
    if isinstance(m.get("count"), (int, float)):
        return m["count"], "{:,.0f}"
    if isinstance(m.get("avg"), (int, float)):
        return m["avg"], "{:,.1f}"
    r = m.get("rate", m.get("value"))
    if "passes" in m or "fails" in m or "rate" in m:
        return (r * 100 if isinstance(r, (int, float)) else None), "{:,.1f}%"
    return (r if isinstance(r, (int, float)) else None), "{:,.1f}"


def summary_rows(rec):
    """기록 하나를 [(이름, 값, 서식, 방향)] 로. 값이 없으면 None."""
    mt = (rec or {}).get("metrics") or {}
    rows = [(label, (None if (v := _num(mt, name, field)) is None else v * mult), fmt, d)
            for label, name, field, mult, fmt, d in CORE_ROWS]
    for name in (rec or {}).get("keys") or []:
        if name in mt:
            v, fmt = _key_metric(mt[name])
            rows.append((name, v, fmt, None))
    return rows


def compare_rows(a, b):
    """a(전) → b(후) 비교표. 각 행: dict(label, a, b, change, verdict[good|bad|same|neutral|''])."""
    ra = {r[0]: r for r in summary_rows(a)} if a else {}
    rb = {r[0]: r for r in summary_rows(b)} if b else {}
    labels = [r[0] for r in CORE_ROWS]
    labels += [k for k in list(ra) + list(rb) if k not in labels and k not in labels[len(CORE_ROWS):]]
    out = []
    for lab in dict.fromkeys(labels):
        x, y = ra.get(lab), rb.get(lab)
        fmt = (y or x)[2] if (y or x) else "{}"
        d = (y or x)[3] if (y or x) else None
        va, vb = (x[1] if x else None), (y[1] if y else None)
        txt = lambda v: "–" if v is None else fmt.format(v)
        change, verdict = "", ""
        if va is not None and vb is not None:
            if va == vb or (va != 0 and abs((vb - va) / abs(va)) < 0.01):
                change, verdict = "동일", "same"
            elif va == 0:
                change = "신규 발생" if vb > 0 else "감소"
                verdict = "neutral" if d is None else ("good" if (vb > 0) == (d == HIGHER) else "bad")
            else:
                pct = (vb - va) / abs(va) * 100
                change = f"{pct:+.1f}%"
                verdict = "neutral" if d is None else ("good" if (pct > 0) == (d == HIGHER) else "bad")
        out.append({"label": lab, "a": txt(va), "b": txt(vb), "change": change, "verdict": verdict})
    return out


class History:
    """실행 결과 이력 (results/history.json). 최신이 앞."""

    def __init__(self, path=None):
        self.path = Path(path) if path else HISTORY_FILE

    def load(self):
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:
            return []
        data = [r for r in data if isinstance(r, dict) and "id" in r]
        return sorted(data, key=lambda r: r.get("ts", 0), reverse=True)

    def _write(self, recs):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(recs, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(self.path)

    def add(self, rec):
        self._write(([rec] + self.load())[:HISTORY_MAX])

    def delete(self, ids):
        ids = set(ids)
        self._write([r for r in self.load() if r["id"] not in ids])


# ───────────────────────── Mock 서버 (앱에서 시작/중지) ─────────────────────────
class MockServer:
    """tools/mock_server.py 를 하위 프로세스로 띄우고 로그를 앱으로 흘려보낸다."""
    PORT = 8080
    BUGS = [("", "정상 동작 (모든 시나리오 통과)"),
            ("dup-seat", "중복 예약 버그 → 시나리오 2 기준 미달"),
            ("no-recover", "좌석 미복구 버그 → 시나리오 3 기준 미달"),
            ("dup-pay", "중복 결제 버그 → 시나리오 4 기준 미달"),
            ("order", "대기 순서 버그 → 시나리오 5 기준 미달"),
            ("token-bypass", "토큰 우회 버그 → 시나리오 6 기준 미달")]

    def __init__(self, events):
        self.q = events
        self.proc = None

    @property
    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def start(self, bug=""):
        if self.running:
            return True, None
        argv = [python_exe(), "-u", str(ROOT / "tools" / "mock_server.py"), "--port", str(self.PORT)]
        if bug:
            argv += ["--bug", bug]
        try:
            proc = subprocess.Popen(argv, cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace", bufsize=1, **NOWIN_LOW)
            lower_priority(proc)
        except OSError as e:
            return False, f"Mock 서버를 시작하지 못했습니다: {e}"
        try:
            proc.wait(timeout=0.8)       # 포트 사용 중 등으로 곧바로 종료되면 이유를 보여 준다
        except subprocess.TimeoutExpired:
            pass
        else:
            out = (proc.stdout.read() or "").strip()
            return False, out or "Mock 서버가 바로 종료되었습니다."
        self.proc = proc
        threading.Thread(target=self._pump, args=(proc,), daemon=True).start()
        self.q.put(("mock", {"running": True, "bug": bug}))
        return True, None

    def _pump(self, proc):
        for line in iter(proc.stdout.readline, ""):
            if line.strip():
                self.q.put(("log", line.rstrip("\r\n")))
        code = proc.wait()
        self.q.put(("log", f"[mock] 종료됨 (exit={code})"))
        self.q.put(("mock", {"running": False, "bug": ""}))

    def stop(self):
        if self.running:
            self.proc.terminate()

    def reset(self):
        """서버 상태를 초기화한다(좌석·예약·대기열·이력). 성공 여부 반환."""
        if not self.running:
            return False
        try:
            req = urllib.request.Request(f"http://127.0.0.1:{self.PORT}/__mock/reset", data=b"", method="POST")
            with urllib.request.urlopen(req, timeout=2):
                return True
        except Exception:
            return False


def dev_reset(base_url, timeout=3):
    """로컬 서버의 개발 모드 관리 API로 대기열·위험 점수·정책 캐시를 비운다 (POST /api/v1/dev/reset).
    개발 모드가 아니거나 로컬 주소가 아니면 아무것도 하지 않는다. 반환: True(비움) / False(안 함·실패)
    """
    m = re.match(r"^(https?)://([^/:?#]+|\[[^\]]+\])(:\d+)?", str(base_url or "").strip())
    if not m or not SAFE_HOST.search(m.group(2)):
        return False
    try:
        req = urllib.request.Request(f"{m.group(1)}://{m.group(2)}{m.group(3) or ''}/api/v1/dev/reset",
                                     data=b"", method="POST")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return 200 <= r.status < 300
    except Exception:
        return False


# ───────────────────────── 원인 진단 ─────────────────────────
# 실행이 실패했을 때 '확실히 아는 원인'만 짧게 알려 준다. 패턴에 맞지 않으면 아무 말도 하지 않는다.
LOG_RULES = [
    (r"k6 명령을 찾을 수 없습니다|k6: (command )?not found",
     "k6를 찾을 수 없습니다 — k6를 설치했는지, 설치 후 앱을 다시 켰는지 확인하세요."),
    (r"ssh 명령을 찾을 수 없습니다",
     "ssh 명령을 찾을 수 없습니다 — OpenSSH 클라이언트를 설치하세요."),
    (r"Permission denied \(publickey|Permission denied, please try again",
     "VM 로그인에 실패했습니다 — 키 파일 경로와 사용자 이름을 확인하세요."),
    (r"Could not resolve hostname",
     "VM 호스트 이름을 찾을 수 없습니다 — 호스트(user@host) 입력을 확인하세요."),
    (r"ssh: connect to host .*(timed out|refused|No route to host)",
     "VM에 접속할 수 없습니다 — 호스트 주소·포트와 네트워크(방화벽)를 확인하세요."),
    (r"Host key verification failed",
     "VM의 호스트 키가 이전과 달라 접속이 거부됐습니다 — ~/.ssh/known_hosts 의 해당 줄을 지우세요."),
    (r"python3: (command )?not found",
     "VM에 python3가 없습니다 — VM에 python3를 설치하세요."),
    (r"actively refused|connection refused|ECONNREFUSED|connectex: .*refused",
     "대상 서버에 연결할 수 없습니다 — 서버가 꺼져 있거나 주소·포트가 다릅니다. "
     "서버가 없으면 환경 설정의 Mock 서버를 시작하세요."),
    (r"no such host|Name or service not known|No such host is known",
     "대상 주소를 찾을 수 없습니다 — 대상 URL의 호스트 이름을 확인하세요."),
    (r"i/o timeout|context deadline exceeded|Client\.Timeout|request timeout",
     "서버 응답이 너무 느려 시간 초과가 났습니다 — 부하 수치를 낮추거나 서버 상태를 확인하세요."),
    (r"users\.csv.*(no such file|not found|cannot find|could not|GoError)|"
     r"(no such file|cannot find|could not).*users\.csv",
     "테스트 계정 파일(data/users.csv)을 읽지 못했습니다 — 환경 설정에서 '만들기'로 계정을 먼저 만드세요."),
    (r"Redis에 연결되지 않았습니다|사전 점검 실패: 진입 요청이 503",
     "서버는 떠 있지만 Redis에 연결하지 못합니다 — Redis를 켜세요 (docker start jejari-redis)."),
    (r"개발 모드가 아닙니다",
     "서버가 개발 모드가 아닙니다 — --jejari.dev-mode=true 로 서버를 다시 띄우세요."),
    (r"운행 \S+이 판매 중이 아닙니다",
     "대상 운행이 판매 중이 아닙니다 — 서버를 --jejari.queue.dev-on-sale-runs=운행ID 로 띄우거나 앱의 RUN_ID를 맞추세요."),
    (r"좌석 선점 API.*아직 없습니다",
     "이 서버에는 좌석 선점 API가 아직 없습니다 — 좌석·결제·취소표 시나리오는 환경 설정의 Mock 서버로 돌리세요."),
    (r"다른 서버가 이 포트",
     "다른 프로그램이 이 포트를 쓰고 있을 수 있습니다 — 포트를 확인하거나 서버를 다른 포트로 띄우세요."),
    (r"사전 준비 실패",
     "개발용 관리 API를 쓸 수 없습니다 — 서버를 개발 모드(--jejari.dev-mode=true)로 띄웠는지, Mock 서버라면 최신인지 확인하세요."),
    (r"6565.*(address already in use|only one usage|bind)|(address already in use|only one usage).*6565",
     "k6 상태 포트(6565)가 이미 사용 중입니다 — 이미 실행 중인 k6가 있으면 종료한 뒤 다시 시작하세요."),
]

# (지표 이름, 문제 조건, 안내 문구) — 값은 Counter 는 count, Rate 는 %, Trend 는 avg
METRIC_RULES = [
    ("seat_success", lambda v: v > 1, "같은 좌석이 {v:,.0f}명에게 동시에 선점됐습니다(중복 예약) — 좌석 락·선점 로직을 확인하세요."),
    ("seat_other", lambda v: v > 0, "좌석 요청에서 예상 밖 응답(5xx 등)이 {v:,.0f}건 있었습니다 — 서버 로그를 확인하세요."),
    ("seat_not_recovered", lambda v: v > 0, "결제 실패·기한 초과 뒤에도 좌석이 복구되지 않은 건이 {v:,.0f}건 있습니다."),
    ("seat_wrongly_released", lambda v: v > 0, "결제에 성공했는데 좌석이 풀려 버린 건이 {v:,.0f}건 있습니다."),
    ("duplicate_payments", lambda v: v > 0, "같은 결제 요청이 여러 번 처리된 건이 {v:,.0f}건 있습니다(중복 결제) — 멱등성 키 처리를 확인하세요."),
    ("duplicate_holds", lambda v: v > 0, "같은 선점 요청이 여러 예약으로 처리된 건이 {v:,.0f}건 있습니다 — 멱등성 처리를 확인하세요."),
    ("waitlist_order_violations", lambda v: v > 0, "취소표가 대기 순서대로 배정되지 않았습니다 — 재배정 순서 로직을 확인하세요."),
    ("waitlist_assigned", lambda v: v == 0, "취소가 일어났는데 대기자 배정이 한 번도 되지 않았습니다."),
    ("valid_rejected", lambda v: v > 0, "정상 입장 토큰으로 좌석 배치도를 호출했는데 막힌 건이 {v:,.0f}건 있습니다 — 토큰 검증이 정상 사용자를 막고 있습니다."),
    ("bot_admitted_after_all_normals", lambda v: v < 100,
     "위험 점수가 높은 계정이 정상 사용자보다 먼저 입장한 경우가 있습니다(뒤에 입장한 비율 {v:.0f}%) — 위험 점수가 순번에 반영되는지, 봇 점수 × 1초가 정상 사용자 전원의 입장 시간보다 긴지 확인하세요."),
    ("normal_admitted_before_bots", lambda v: v < 100,
     "정상 사용자가 입장하는 동안 봇이 이미 입장해 있던 경우가 있습니다({v:.0f}%만 정상) — 순번 조정이 의도대로 반영되지 않았습니다."),
    ("bypass_allowed", lambda v: v > 0, "입장 토큰이 없거나 위조·무효인데도 좌석 API가 통과된 건이 {v:,.0f}건 있습니다."),
    ("queue_admitted", lambda v: v < 50, "대기열 입장 성공률이 {v:.0f}%로 낮습니다 — 입장 처리가 느리거나 대기 시간이 너무 깁니다."),
    ("dropped_iterations", lambda v: v > 100,
     "목표 속도를 따라가지 못한 요청이 {v:,.0f}건입니다 — 부하를 만드는 PC가 한계라 수치를 낮춰야 합니다."),
]


def diagnose(kind, status, lines, metrics, sc=None, max_hints=3):
    """실패 원인을 한두 줄로. 확실히 아는 것만 반환하고, 모르면 빈 리스트."""
    if status in ("pass", "stopped"):
        return []
    text = "\n".join(lines or [])
    hints = []
    for pat, msg in LOG_RULES:
        if re.search(pat, text, re.I):
            hints.append(msg)
    if kind == "k6" and metrics and status in ("fail", "error") and not hints:
        for name, bad, msg in METRIC_RULES:
            if name in metrics:
                v, _fmt = _key_metric(metrics[name])
                if v is not None and bad(v):
                    hints.append(msg.format(v=v))
        if not hints:   # 위 지표로 설명되지 않을 때만 일반 실패율을 알린다
            f = _num(metrics, "http_req_failed", "rate")
            if f is not None and f > 0.05 and status == "fail":
                hints.append(f"요청 실패율이 {f * 100:.1f}%로 기준(5%)을 넘었습니다.")
    return list(dict.fromkeys(hints))[:max_hints]
