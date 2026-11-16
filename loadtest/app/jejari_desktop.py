#!/usr/bin/env python3
"""제자리 트래픽 컨트롤 — 데스크톱 앱 (Tkinter, 표준 라이브러리만 사용)

    python3 app/jejari_desktop.py

탭 구성
  테스트     시나리오를 고르고 수치를 조절해 시작/중지, 실시간 지표·그래프·로그
  환경 설정  실행 위치(이 PC / VM SSH), 대상 서버, 테스트 계정
  결과 비교  지난 실행 이력과 개선 전후 비교
"""
import csv
import datetime
import json
import os
import queue
import subprocess
import sys
import threading
import time
import traceback
from pathlib import Path

import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from tkinter import font as tkfont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import core  # noqa: E402

SETTINGS_FILE = Path.home() / ".jejari_loadtest.json"
ERROR_LOG = Path.home() / ".jejari_loadtest_error.log"   # 콘솔 없이 실행할 때 오류를 여기에 남긴다

# ── 색상 ──
C = {
    "bg": "#F4F6F9", "card": "#FFFFFF", "head": "#FFFFFF", "border": "#E2E6EC",
    "text": "#1F2937", "muted": "#6B7280", "track": "#E5E7EB", "grid": "#EEF1F5",
    "primary": "#2F6BFF", "primary_d": "#1F54D6", "primary_l": "#EAF1FF", "primary_off": "#A9C1FF",
    "ok": "#15803D", "ok_bg": "#E8F7EE", "bad": "#DC2626", "bad_bg": "#FDECEC",
    "warn": "#B45309", "warn_bg": "#FFF4DD", "log_bg": "#F8FAFC", "log_fg": "#334155",
}
BANNER = {  # status → (배경, 글자, 문구)
    "pass": (C["ok_bg"], C["ok"], "통과 — 모든 통과 기준(threshold)을 만족했습니다"),
    "fail": (C["bad_bg"], C["bad"], "기준 미달 — threshold 실패 (중복 예약·실패율·지연 등). 지표와 로그를 확인하세요"),
    "error": (C["bad_bg"], C["bad"], "실행 오류 — 로그를 확인하세요 (API 경로 불일치, 서버 중지, SSH 문제 등)"),
    "stopped": (C["warn_bg"], C["warn"], "사용자가 중지함"),
}
FONT_CANDIDATES = ("Pretendard", "Malgun Gothic", "Apple SD Gothic Neo", "NanumGothic",
                   "Noto Sans CJK KR", "Noto Sans KR", "Segoe UI", "Helvetica")


def fmt(n):
    return "–" if n is None else f"{round(n):,}"


def when(ts):
    return datetime.datetime.fromtimestamp(ts).strftime("%m-%d %H:%M")


class App:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("제자리 트래픽 컨트롤")
        self.root.geometry("1280x900")
        self.root.minsize(1120, 800)
        self.root.configure(bg=C["bg"])
        self.root.report_callback_exception = self._on_tk_error
        self._setup_style()

        self.q = queue.Queue()
        self.runner = core.Runner(self.q)
        self.mock = core.MockServer(self.q)
        self.history = core.History()
        self.scenarios = core.load_scenarios()
        self.sel = None
        self.sel_idx = None
        self.param_vars = {}   # key → (kind, var, extra)
        self.running = False
        self.started_at = None
        self.current = None    # 실행 중인 작업의 시작 시점 정보 (이력 저장용)
        self.h_recs = {}
        self._syncing = False  # 입력칸 ↔ 슬라이더 동기화 중(되먹임 방지)
        self.info = {"k6": None, "users": {"exists": False, "total": 0, "bots": 0}, "error": None}
        self.series = {"rps": [], "p95": []}
        self.chips = {}
        self.charts_shown = True
        self._chart_dirty = False
        self._toast_job = None

        # 설정 변수
        self.mode = tk.StringVar(value="local")
        self.host = tk.StringVar()
        self.port = tk.StringVar(value="22")
        self.key = tk.StringVar()
        self.rdir = tk.StringVar(value="~/jejari-loadtest")
        self.url = tk.StringVar(value="http://localhost:8080")
        self.run_id = tk.StringVar(value="1001")   # queue 앱 개발 모드의 판매 중 운행(--jejari.queue.dev-on-sale-runs=1001)
        self.own = tk.BooleanVar(value=False)
        self.prom = tk.BooleanVar(value=False)
        self.prom_url = tk.StringVar(value="http://localhost:9090/api/v1/write")
        self.gen_count = tk.StringVar(value="10000")
        self.gen_bot = tk.StringVar(value="5")
        self.autoscroll = tk.BooleanVar(value=True)
        self.mock_bug = tk.StringVar(value=core.MockServer.BUGS[0][1])
        self.mock_auto = tk.BooleanVar(value=True)

        self._build()
        self._load_settings()
        self._on_mode()   # 내부에서 환경 확인(refresh_info)까지 수행
        self._on_url()
        self._select(0)
        self._show_tab("run")
        self._fit_window()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.after(100, self._pump)

    def _on_tk_error(self, exc, val, tb):
        text = "".join(traceback.format_exception(exc, val, tb))
        try:
            with open(ERROR_LOG, "a", encoding="utf-8") as f:
                f.write(f"[{datetime.datetime.now():%Y-%m-%d %H:%M:%S}]\n{text}\n")
        except OSError:
            pass
        try:
            self._ev_log(f"[오류] 앱 내부 오류: {val}  (자세한 내용: {ERROR_LOG})")
        except Exception:
            pass

    # ───────────────────────── 스타일 ─────────────────────────
    def _setup_style(self):
        try:
            fams = set(tkfont.families(self.root))
        except Exception:
            fams = set()
        self.F = next((f for f in FONT_CANDIDATES if f in fams), "TkDefaultFont")
        for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            try:
                tkfont.nametofont(name).configure(family=self.F, size=10)
            except Exception:
                pass
        self.mono = next((f for f in ("Consolas", "Menlo", "D2Coding", "DejaVu Sans Mono") if f in fams),
                         "TkFixedFont")

        s = ttk.Style()
        try:
            s.theme_use("clam")
        except Exception:
            pass
        bg, card, bd, tx, mu, pr = C["bg"], C["card"], C["border"], C["text"], C["muted"], C["primary"]
        s.configure(".", background=bg, foreground=tx, bordercolor=bd, troughcolor=C["track"])
        s.configure("TFrame", background=bg)
        s.configure("Card.TFrame", background=card)
        s.configure("TLabel", background=bg, foreground=tx)
        s.configure("Muted.TLabel", background=bg, foreground=mu)
        s.configure("Card.TLabel", background=card, foreground=tx)
        s.configure("CardMuted.TLabel", background=card, foreground=mu)
        s.configure("CardTitle.TLabel", background=card, foreground=tx, font=(self.F, 11, "bold"))
        s.configure("Tile.TLabel", background=card, foreground=mu, font=(self.F, 9))
        s.configure("Value.TLabel", background=card, foreground=tx, font=(self.F, 18, "bold"))
        s.configure("Link.TLabel", background=bg, foreground=pr, font=(self.F, 10, "underline"))
        s.configure("Chip.TLabel", background=C["primary_l"], foreground=C["primary_d"],
                    font=(self.F, 9, "bold"), padding=(10, 4))
        for name in ("Checkbutton", "Radiobutton"):
            s.configure(f"Card.T{name}", background=card, foreground=tx)
            s.map(f"Card.T{name}", background=[("active", card)],
                  indicatorcolor=[("selected", pr), ("!selected", "#FFFFFF")])
        # 버튼
        s.configure("TButton", background=card, foreground=tx, bordercolor="#C9D0DB", lightcolor=card,
                    darkcolor=card, focuscolor=card, padding=(12, 6), relief="flat")
        s.map("TButton", background=[("active", "#F1F4F9"), ("disabled", "#F4F6F9")],
              foreground=[("disabled", "#A0A7B4")])
        for name, base, hover in (("Primary", pr, C["primary_d"]), ("Danger", C["bad"], "#B91C1C")):
            s.configure(f"{name}.TButton", background=base, foreground="#FFFFFF", bordercolor=base,
                        lightcolor=base, darkcolor=base, focuscolor=base, padding=(12, 10),
                        font=(self.F, 11, "bold"))
            s.map(f"{name}.TButton", background=[("active", hover), ("disabled", C["primary_off"])],
                  foreground=[("disabled", "#FFFFFF")], bordercolor=[("disabled", C["primary_off"])])
        s.configure("Small.TButton", padding=(10, 3), font=(self.F, 9))
        # 입력
        for name in ("TEntry", "TSpinbox", "TCombobox"):
            s.configure(name, fieldbackground=card, background=card, foreground=tx, bordercolor=bd,
                        lightcolor=bd, darkcolor=bd, padding=5, arrowcolor=mu)
            s.map(name, bordercolor=[("focus", pr)], lightcolor=[("focus", pr)], darkcolor=[("focus", pr)])
        s.configure("Horizontal.TScale", background=pr, troughcolor=C["track"], bordercolor=C["track"],
                    lightcolor=pr, darkcolor=pr)
        # 표
        s.configure("Treeview", background=card, fieldbackground=card, foreground=tx, rowheight=30, font=(self.F, 10),
                    borderwidth=0, bordercolor=card, lightcolor=card, darkcolor=card)
        s.configure("Treeview.Heading", background="#F3F5F8", foreground=mu, relief="flat",
                    font=(self.F, 9, "bold"), padding=(8, 5), bordercolor=bd)
        s.map("Treeview", background=[("selected", C["primary_l"])], foreground=[("selected", tx)])
        s.map("Treeview.Heading", background=[("active", "#F3F5F8")])
        # 노트북(로그/결과)
        s.configure("TNotebook", background=bg, bordercolor=bd, tabmargins=(0, 0, 0, 0))
        s.configure("TNotebook.Tab", background=bg, foreground=mu, padding=(16, 7), bordercolor=bd,
                    lightcolor=bg, darkcolor=bg)
        s.map("TNotebook.Tab", background=[("selected", card)], foreground=[("selected", tx)],
              lightcolor=[("selected", card)], darkcolor=[("selected", card)])
        s.configure("Vertical.TScrollbar", background="#D5DAE2", troughcolor=card, bordercolor=card,
                    arrowcolor=mu, lightcolor="#D5DAE2", darkcolor="#D5DAE2", gripcount=0)

    def font(self, size=10, bold=False):
        return (self.F, size, "bold") if bold else (self.F, size)

    def _card(self, parent, title=None, pad=14):
        """흰 카드. (바깥 프레임, 내용을 넣을 프레임) 반환 — 바깥 프레임을 배치하고 안쪽에 grid로 채운다."""
        outer = tk.Frame(parent, bg=C["card"], highlightbackground=C["border"], highlightthickness=1, bd=0)
        inner = ttk.Frame(outer, style="Card.TFrame", padding=pad)
        inner.pack(fill="both", expand=True)
        if title:
            ttk.Label(inner, text=title, style="CardTitle.TLabel").pack(anchor="w", pady=(0, 8))
        body = ttk.Frame(inner, style="Card.TFrame")
        body.pack(fill="both", expand=True)
        return outer, body

    # ───────────────────────── 전체 구성 ─────────────────────────
    def _build(self):
        r = self.root
        r.columnconfigure(0, weight=1)
        r.rowconfigure(1, weight=1)
        self._build_header(r)
        body = ttk.Frame(r)
        body.grid(row=1, column=0, sticky="nsew")
        body.columnconfigure(0, weight=1)
        body.rowconfigure(0, weight=1)
        self.tabs = {}
        for key, builder in (("run", self._build_run), ("env", self._build_env), ("hist", self._build_hist)):
            f = ttk.Frame(body, padding=16)
            f.grid(row=0, column=0, sticky="nsew")
            builder(f)
            self.tabs[key] = f

    def _build_header(self, parent):
        head = tk.Frame(parent, bg=C["head"], highlightbackground=C["border"], highlightthickness=1)
        head.grid(row=0, column=0, sticky="ew")
        tk.Label(head, text="제자리", bg=C["head"], fg=C["primary"], font=self.font(15, True)).pack(
            side="left", padx=(20, 4), pady=(10, 0))
        tk.Label(head, text="트래픽 컨트롤", bg=C["head"], fg=C["muted"], font=self.font(11)).pack(
            side="left", padx=(0, 28), pady=(10, 0))
        self.tab_btn = {}
        for key, text in (("run", "테스트"), ("env", "환경 설정"), ("hist", "결과 비교")):
            holder = tk.Frame(head, bg=C["head"])
            holder.pack(side="left", padx=2)
            lab = tk.Label(holder, text=text, bg=C["head"], fg=C["muted"], font=self.font(10, True),
                           padx=14, pady=12, cursor="hand2")
            lab.pack()
            ind = tk.Frame(holder, bg=C["head"], height=3)
            ind.pack(fill="x")
            lab.bind("<Button-1>", lambda e, k=key: self._show_tab(k))
            self.tab_btn[key] = (lab, ind)
        # 오른쪽 상태 표시
        self.chip_users = tk.Label(head, text="", bg=C["head"], fg=C["muted"], font=self.font(9))
        self.chip_users.pack(side="right", padx=(0, 20))
        self.chip_k6 = tk.Label(head, text="k6 확인 중…", bg=C["head"], fg=C["muted"], font=self.font(9, True))
        self.chip_k6.pack(side="right", padx=(0, 14))

    def _show_tab(self, key):
        for k, f in self.tabs.items():
            if k == key:
                f.grid()
                f.tkraise()
            else:
                f.grid_remove()
        for k, (lab, ind) in self.tab_btn.items():
            on = k == key
            lab.config(fg=C["primary"] if on else C["muted"])
            ind.config(bg=C["primary"] if on else C["head"])
        if key == "hist":
            self._refresh_history()

    # ───────────────────────── 탭 1: 테스트 ─────────────────────────
    def _build_run(self, tab):
        tab.columnconfigure(0, weight=0)
        tab.columnconfigure(1, weight=1)
        tab.rowconfigure(0, weight=1)

        # 왼쪽: 시나리오 목록
        outer, body = self._card(tab, "시나리오", pad=10)
        outer.grid(row=0, column=0, sticky="ns", padx=(0, 14))
        self.sc_rows = []
        for i, s in enumerate(self.scenarios):
            row = tk.Frame(body, bg=C["card"], cursor="hand2")
            row.pack(fill="x", pady=1)
            bar = tk.Frame(row, bg=C["card"], width=3)
            bar.pack(side="left", fill="y")
            num = tk.Label(row, text=str(i + 1), bg=C["card"], fg=C["muted"], width=2, font=self.font(10, True))
            num.pack(side="left", padx=(6, 0))
            name = tk.Label(row, text=s["title"], bg=C["card"], fg=C["text"], anchor="w", font=self.font(10),
                            padx=6, pady=9, width=20)
            name.pack(side="left", fill="x", expand=True)
            for w in (row, bar, num, name):
                w.bind("<Button-1>", lambda e, i=i: self._select(i))
            self.sc_rows.append((row, bar, num, name))

        # 오른쪽
        right = ttk.Frame(tab)
        right.grid(row=0, column=1, sticky="nsew")
        right.columnconfigure(0, weight=1)
        right.rowconfigure(5, weight=1, minsize=120)   # 종료 결과: 공간이 모자라도 이만큼은 보장

        # 대상 요약 줄
        strip = ttk.Frame(right)
        strip.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        self.lbl_ctx = ttk.Label(strip, text="", style="Muted.TLabel")
        self.lbl_ctx.pack(side="left")
        link = ttk.Label(strip, text="변경 ›", style="Link.TLabel", cursor="hand2")
        link.pack(side="left", padx=10)
        link.bind("<Button-1>", lambda e: self._show_tab("env"))
        ttk.Button(strip, text="📄 로그 창", style="Small.TButton", command=self._show_log).pack(side="right")
        self.btn_charts = ttk.Button(strip, text="▾ 그래프 접기", style="Small.TButton", command=self._toggle_charts)
        self.btn_charts.pack(side="right", padx=(0, 6))
        self.lbl_toast = ttk.Label(strip, text="", style="Muted.TLabel", foreground=C["ok"])
        self.lbl_toast.pack(side="right", padx=10)

        # 시나리오 설정 카드
        outer, pf = self._card(right)
        outer.grid(row=1, column=0, sticky="ew")
        pf.columnconfigure(0, weight=1)
        top = ttk.Frame(pf, style="Card.TFrame")
        top.grid(row=0, column=0, sticky="ew")
        top.columnconfigure(0, weight=1)
        self.lbl_title = ttk.Label(top, text="", style="CardTitle.TLabel", font=self.font(13, True))
        self.lbl_title.grid(row=0, column=0, sticky="w")
        self.preset_frame = ttk.Frame(top, style="Card.TFrame")
        self.preset_frame.grid(row=0, column=1, sticky="e")
        for key, label in (("light", "가볍게"), ("normal", "보통"), ("heavy", "명절급")):
            ttk.Button(self.preset_frame, text=label, style="Small.TButton",
                       command=lambda k=key: self._apply_preset(k)).pack(side="left", padx=(6, 0))
        self.lbl_desc = ttk.Label(pf, text="", style="CardMuted.TLabel", wraplength=760, justify="left")
        self.lbl_desc.grid(row=1, column=0, sticky="w", pady=(2, 8))
        self.params_frame = ttk.Frame(pf, style="Card.TFrame")
        self.params_frame.grid(row=2, column=0, sticky="ew")
        self.params_frame.columnconfigure(0, weight=1)
        self.lbl_warn = ttk.Label(pf, text="", style="Card.TLabel", foreground=C["warn"], wraplength=760)
        self.lbl_warn.grid(row=3, column=0, sticky="w", pady=(6, 0))
        self.lbl_warn.grid_remove()
        bf = ttk.Frame(pf, style="Card.TFrame")
        bf.grid(row=4, column=0, sticky="ew", pady=(10, 0))
        bf.columnconfigure(0, weight=1)
        self.btn_start = ttk.Button(bf, text="▶  시작", style="Primary.TButton", command=self.start)
        self.btn_start.grid(row=0, column=0, sticky="ew")
        self.btn_stop = ttk.Button(bf, text="■  중지", style="Danger.TButton", command=self.stop)
        self.btn_stop.grid(row=0, column=0, sticky="ew")
        self.btn_stop.grid_remove()
        self.lbl_err = ttk.Label(pf, text="", style="Card.TLabel", foreground=C["bad"], wraplength=760)
        self.lbl_err.grid(row=5, column=0, sticky="w", pady=(6, 0))
        self.lbl_err.grid_remove()

        # 판정 배너
        self.banner = tk.Label(right, text="", anchor="w", justify="left", wraplength=960, padx=14, pady=8,
                               font=self.font(10, True))
        self.banner.grid(row=2, column=0, sticky="ew", pady=(10, 0))
        self.banner.grid_remove()

        # 실시간 지표
        outer, mf = self._card(right)
        outer.grid(row=3, column=0, sticky="ew", pady=(10, 0))
        self.tiles = {}
        for i, (key, name) in enumerate([("time", "경과 시간"), ("vus", "동시 VU"), ("rps", "요청/초"),
                                         ("fail", "실패율"), ("p95", "응답 p95"), ("reqs", "총 요청")]):
            mf.columnconfigure(i, weight=1)
            t = ttk.Frame(mf, style="Card.TFrame")
            t.grid(row=0, column=i, sticky="w")
            ttk.Label(t, text=name, style="Tile.TLabel").pack(anchor="w")
            lbl = ttk.Label(t, text="–", style="Value.TLabel")
            lbl.pack(anchor="w")
            self.tiles[key] = lbl
        self.custom_frame = ttk.Frame(mf, style="Card.TFrame")
        self.custom_frame.grid(row=1, column=0, columnspan=6, sticky="w", pady=(10, 0))

        # 그래프
        outer, cf = self._card(right, pad=12)
        self.charts_outer = outer
        outer.grid(row=4, column=0, sticky="ew", pady=(10, 0))
        cf.columnconfigure((0, 1), weight=1)
        self.cv_rps = self._chart(cf, 0, "요청/초")
        self.cv_p95 = self._chart(cf, 1, "응답 p95 (ms)")

        # 종료 결과 (로그는 별도 창)
        resf = tk.Frame(right, bg=C["card"], highlightbackground=C["border"], highlightthickness=1)
        resf.grid(row=5, column=0, sticky="nsew", pady=(10, 0))
        resf.columnconfigure(0, weight=1)
        resf.rowconfigure(1, weight=1)
        tk.Label(resf, text="종료 결과", bg=C["card"], fg=C["text"], font=self.font(10, True), anchor="w",
                 padx=12, pady=7).grid(row=0, column=0, columnspan=2, sticky="ew")
        self.res_tree = ttk.Treeview(resf, columns=("name", "val"), show="headings", height=4,
                                     selectmode="extended")
        self.res_tree.heading("name", text="지표", anchor="w")
        self.res_tree.heading("val", text="값", anchor="w")
        self.res_tree.column("name", width=240, anchor="w", stretch=False)
        self.res_tree.column("val", width=560, anchor="w")
        rsb = ttk.Scrollbar(resf, command=self.res_tree.yview)
        self.res_tree.configure(yscrollcommand=rsb.set)
        self.res_tree.grid(row=1, column=0, sticky="nsew")
        rsb.grid(row=1, column=1, sticky="ns")
        rbar = ttk.Frame(resf, style="Card.TFrame", padding=(8, 6))
        rbar.grid(row=2, column=0, columnspan=2, sticky="ew")
        ttk.Button(rbar, text="복사", style="Small.TButton", command=self._res_copy).pack(side="left")
        ttk.Button(rbar, text="전체 복사", style="Small.TButton",
                   command=lambda: self._res_copy(all_=True)).pack(side="left", padx=6)
        ttk.Button(rbar, text="결과 폴더 열기", style="Small.TButton", command=self._open_results).pack(side="left")
        ttk.Label(rbar, text="행을 선택(Ctrl/Shift+클릭) 후 복사 · 선택이 없으면 전체", style="CardMuted.TLabel").pack(
            side="right", padx=12)
        for seq in ("<Control-c>", "<Control-C>", "<Command-c>"):
            try:
                self.res_tree.bind(seq, lambda e: self._res_copy())
            except tk.TclError:
                pass
        for seq in ("<Control-a>", "<Control-A>", "<Command-a>"):
            try:
                self.res_tree.bind(seq, self._res_select_all)
            except tk.TclError:
                pass
        self._popup(self.res_tree, [("복사", self._res_copy), ("전체 복사", lambda: self._res_copy(all_=True)),
                                    ("전체 선택", self._res_select_all)])
        self._build_log_window()

    def _chart(self, parent, col, title):
        f = ttk.Frame(parent, style="Card.TFrame")
        f.grid(row=0, column=col, sticky="ew", padx=(0 if col == 0 else 14, 0))
        f.columnconfigure(0, weight=1)
        ttk.Label(f, text=title, style="Tile.TLabel").grid(sticky="w")
        cv = tk.Canvas(f, height=64, bg=C["card"], highlightthickness=0)
        cv.grid(sticky="ew")
        cv.bind("<Configure>", lambda e: self._draw())
        return cv

    # ───────────────────────── 탭 2: 환경 설정 ─────────────────────────
    def _build_env(self, tab):
        wrap = ttk.Frame(tab)
        wrap.grid(row=0, column=0, sticky="nw")
        wrap.columnconfigure(0, minsize=640)

        # 실행 위치
        outer, f = self._card(wrap, "실행 위치")
        outer.grid(row=0, column=0, sticky="ew", pady=(0, 12))
        f.columnconfigure(0, weight=1)
        ttk.Radiobutton(f, text="이 PC  (내 PC의 k6 실행)", variable=self.mode, value="local",
                        style="Card.TRadiobutton", command=self._on_mode).grid(row=0, column=0, sticky="w")
        ttk.Radiobutton(f, text="VM  (SSH로 원격 실행)", variable=self.mode, value="ssh",
                        style="Card.TRadiobutton", command=self._on_mode).grid(row=1, column=0, sticky="w",
                                                                              pady=(4, 0))
        self.ssh_frame = ttk.Frame(f, style="Card.TFrame")
        self.ssh_frame.grid(row=2, column=0, sticky="ew", pady=(10, 0))
        self.ssh_frame.columnconfigure(1, weight=1)
        ttk.Label(self.ssh_frame, text="호스트", style="Card.TLabel").grid(row=0, column=0, sticky="w", pady=3)
        ttk.Entry(self.ssh_frame, textvariable=self.host).grid(row=0, column=1, sticky="ew", padx=8)
        ttk.Entry(self.ssh_frame, textvariable=self.port, width=6).grid(row=0, column=2)
        ttk.Label(self.ssh_frame, text="키 파일", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=3)
        ttk.Entry(self.ssh_frame, textvariable=self.key).grid(row=1, column=1, sticky="ew", padx=8)
        ttk.Button(self.ssh_frame, text="찾기", command=self._pick_key).grid(row=1, column=2)
        ttk.Label(self.ssh_frame, text="VM 경로", style="Card.TLabel").grid(row=2, column=0, sticky="w", pady=3)
        ttk.Entry(self.ssh_frame, textvariable=self.rdir).grid(row=2, column=1, columnspan=2, sticky="ew",
                                                               padx=(8, 0))
        ttk.Label(self.ssh_frame, text="예: ubuntu@10.0.0.5  (비밀번호 없는 키 인증 필요)",
                  style="CardMuted.TLabel").grid(row=3, column=0, columnspan=3, sticky="w", pady=(4, 0))
        row = ttk.Frame(f, style="Card.TFrame")
        row.grid(row=3, column=0, sticky="ew", pady=(12, 0))
        ttk.Button(row, text="연결 확인", command=self.refresh_info).pack(side="left")
        self.btn_upload = ttk.Button(row, text="프로젝트 업로드", command=self.upload)
        self.btn_upload.pack(side="left", padx=8)
        self.lbl_k6 = ttk.Label(f, text="k6 확인 중…", style="Card.TLabel")
        self.lbl_k6.grid(row=4, column=0, sticky="w", pady=(10, 0))
        self.lbl_users = ttk.Label(f, text="", style="Card.TLabel")
        self.lbl_users.grid(row=5, column=0, sticky="w")

        # 대상 서버
        outer, f = self._card(wrap, "대상 서버")
        outer.grid(row=1, column=0, sticky="ew", pady=(0, 12))
        f.columnconfigure(0, weight=1)
        ttk.Entry(f, textvariable=self.url).grid(row=0, column=0, columnspan=2, sticky="ew")
        self.url.trace_add("write", lambda *_: self._on_url())
        ttk.Label(f, text="k6가 실행되는 곳에서 접근 가능한 주소", style="CardMuted.TLabel").grid(
            row=1, column=0, columnspan=2, sticky="w", pady=(2, 0))
        ttk.Label(f, text="RUN_ID", style="Card.TLabel").grid(row=2, column=0, sticky="w", pady=(10, 0))
        ttk.Entry(f, textvariable=self.run_id, width=8).grid(row=2, column=1, sticky="e", pady=(10, 0))
        self.chk_own = ttk.Checkbutton(f, text="직접 소유했거나 테스트 허가를 받은 서버입니다", variable=self.own,
                                       style="Card.TCheckbutton")
        self.chk_own.grid(row=3, column=0, columnspan=2, sticky="w", pady=(10, 0))
        ttk.Checkbutton(f, text="Prometheus/Grafana로도 전송", variable=self.prom, style="Card.TCheckbutton",
                        command=self._on_prom).grid(row=4, column=0, columnspan=2, sticky="w", pady=(6, 0))
        self.ent_prom = ttk.Entry(f, textvariable=self.prom_url)
        self.ent_prom.grid(row=5, column=0, columnspan=2, sticky="ew", pady=(4, 0))

        # 테스트 계정
        outer, f = self._card(wrap, "테스트 계정")
        outer.grid(row=2, column=0, sticky="ew")
        ttk.Label(f, text="계정 수", style="Card.TLabel").grid(row=0, column=0)
        ttk.Entry(f, textvariable=self.gen_count, width=9).grid(row=0, column=1, padx=8)
        ttk.Label(f, text="봇 %", style="Card.TLabel").grid(row=0, column=2)
        ttk.Entry(f, textvariable=self.gen_bot, width=5).grid(row=0, column=3, padx=8)
        self.btn_gen = ttk.Button(f, text="만들기", command=self.gen_users)
        self.btn_gen.grid(row=0, column=4, padx=(6, 0))

        # Mock 서버
        outer, f = self._card(wrap, "Mock 서버  (실제 서버가 아직 없을 때 연습용)")
        outer.grid(row=3, column=0, sticky="ew", pady=(12, 0))
        f.columnconfigure(0, weight=1)
        self.lbl_mock_note = ttk.Label(
            f, style="CardMuted.TLabel", wraplength=600, justify="left",
            text="가짜 서버를 이 PC에서 띄워, 시나리오와 앱이 끝까지 동작하는지 확인합니다. 시작하면 대상 서버가 "
                 f"http://localhost:{core.MockServer.PORT} 로 맞춰집니다. 성능 수치는 mock 서버 자체의 값이라 참고용입니다.")
        self.lbl_mock_note.grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Combobox(f, textvariable=self.mock_bug, values=[l for _, l in core.MockServer.BUGS],
                     state="readonly", width=46).grid(row=1, column=0, columnspan=3, sticky="w", pady=(10, 0))
        mrow = ttk.Frame(f, style="Card.TFrame")
        mrow.grid(row=2, column=0, columnspan=3, sticky="w", pady=(10, 0))
        self.btn_mock_start = ttk.Button(mrow, text="▶ 시작", command=self.mock_start)
        self.btn_mock_start.pack(side="left")
        self.btn_mock_stop = ttk.Button(mrow, text="■ 중지", command=self.mock_stop)
        self.btn_mock_stop.pack(side="left", padx=8)
        self.btn_mock_reset = ttk.Button(mrow, text="상태 초기화", command=self.mock_reset)
        self.btn_mock_reset.pack(side="left")
        self.lbl_mock = ttk.Label(f, text="● 꺼짐", style="Card.TLabel", foreground=C["muted"])
        self.lbl_mock.grid(row=3, column=0, sticky="w", pady=(10, 0))
        ttk.Checkbutton(f, text="시나리오 시작 전마다 자동으로 상태 초기화", variable=self.mock_auto,
                        style="Card.TCheckbutton").grid(row=4, column=0, sticky="w", pady=(4, 0))
        self.btn_mock_stop.state(["disabled"])
        self.btn_mock_reset.state(["disabled"])

    # ───────────────────────── 탭 3: 결과 비교 ─────────────────────────
    def _build_hist(self, tab):
        tab.columnconfigure(0, weight=1)
        tab.rowconfigure(1, weight=1)
        tab.rowconfigure(2, weight=2)
        ttk.Label(tab, text="실행이 끝날 때마다 자동 저장됩니다. 두 개를 선택(Ctrl/Shift+클릭)하면 전 → 후를 비교합니다.",
                  style="Muted.TLabel").grid(row=0, column=0, sticky="w", pady=(0, 8))

        outer, hf = self._card(tab, pad=8)
        outer.grid(row=1, column=0, sticky="nsew")
        hf.columnconfigure(0, weight=1)
        hf.rowconfigure(0, weight=1)
        cols = ("when", "title", "status", "tps", "p95", "fail", "params")
        self.h_tree = ttk.Treeview(hf, columns=cols, show="headings", selectmode="extended", height=7)
        for c, text, w, anchor in (("when", "시각", 100, "w"), ("title", "시나리오", 170, "w"),
                                   ("status", "판정", 80, "w"), ("tps", "요청/초", 90, "e"),
                                   ("p95", "p95(ms)", 90, "e"), ("fail", "실패율", 80, "e"),
                                   ("params", "설정", 400, "w")):
            self.h_tree.heading(c, text=text, anchor=anchor)
            self.h_tree.column(c, width=w, anchor=anchor, stretch=(c == "params"))
        sb = ttk.Scrollbar(hf, command=self.h_tree.yview)
        self.h_tree.configure(yscrollcommand=sb.set)
        self.h_tree.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.h_tree.tag_configure("pass", foreground=C["ok"])
        self.h_tree.tag_configure("fail", foreground=C["bad"])
        self.h_tree.tag_configure("error", foreground=C["bad"])
        self.h_tree.tag_configure("stopped", foreground=C["warn"])
        self.h_tree.bind("<<TreeviewSelect>>", lambda e: self._on_hist_select())
        bar = ttk.Frame(hf, style="Card.TFrame")
        bar.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        ttk.Button(bar, text="선택 삭제", command=self._hist_delete).pack(side="left")
        ttk.Button(bar, text="CSV로 저장", command=self._hist_export).pack(side="left", padx=8)

        outer, cf = self._card(tab, pad=10)
        outer.grid(row=2, column=0, sticky="nsew", pady=(10, 0))
        cf.columnconfigure(0, weight=1)
        cf.rowconfigure(1, weight=1)
        self.lbl_cmp = ttk.Label(cf, text="실행 기록을 선택하세요", style="CardTitle.TLabel", wraplength=1000)
        self.lbl_cmp.grid(row=0, column=0, sticky="w", pady=(0, 6))
        self.c_tree = ttk.Treeview(cf, columns=("label", "a", "b", "chg"), show="headings", height=9)
        for c, text, w, anchor in (("label", "지표", 240, "w"), ("a", "전", 150, "e"),
                                   ("b", "후", 150, "e"), ("chg", "변화", 150, "e")):
            self.c_tree.heading(c, text=text, anchor=anchor)
            self.c_tree.column(c, width=w, anchor=anchor)
        self.c_tree.grid(row=1, column=0, sticky="nsew")
        self.c_tree.tag_configure("good", foreground=C["ok"])
        self.c_tree.tag_configure("bad", foreground=C["bad"])
        self.c_tree.tag_configure("same", foreground=C["muted"])

    # ───────────────────────── 설정 저장/복원 ─────────────────────────
    def _load_settings(self):
        try:
            d = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except Exception:
            return
        for name in ("mode", "host", "port", "key", "rdir", "url", "run_id", "prom_url", "gen_count", "gen_bot"):
            if name in d:
                getattr(self, name).set(d[name])
        for name in ("own", "prom"):
            if name in d:
                getattr(self, name).set(bool(d[name]))

    def _save_settings(self):
        d = {n: getattr(self, n).get() for n in ("mode", "host", "port", "key", "rdir", "url", "run_id",
                                                  "prom_url", "gen_count", "gen_bot", "own", "prom")}
        try:
            SETTINGS_FILE.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
        except Exception:
            pass

    # ───────────────────────── 입력 처리 ─────────────────────────
    def target(self):
        try:
            port = int(self.port.get() or 22)
        except ValueError:
            port = 22
        return core.Target(self.mode.get(), self.host.get(), port, self.key.get(), self.rdir.get(),
                           self.prom_url.get())

    def _on_mode(self):
        if self.mode.get() == "ssh":
            self.ssh_frame.grid()
            self.btn_upload.state(["!disabled"])
        else:
            self.ssh_frame.grid_remove()
            self.btn_upload.state(["disabled"])
        self._refresh_ctx()
        self._sync_mock_buttons()
        self.refresh_info()

    def _on_prom(self):
        if self.prom.get():
            self.ent_prom.grid()
        else:
            self.ent_prom.grid_remove()

    def _on_url(self):
        m = core.re.match(r"^https?://([^/:?#]+|\[[^\]]+\])", self.url.get().strip())
        local = bool(m and core.SAFE_HOST.search(m.group(1)))
        if local or not m:
            self.chk_own.grid_remove()
        else:
            self.chk_own.grid()
        self._on_prom()
        self._refresh_ctx()

    def _refresh_ctx(self):
        where = f"VM {self.host.get().strip() or '(미입력)'}" if self.mode.get() == "ssh" else "이 PC"
        u = self.info["users"]
        users = f"계정 {u['total']:,}명" if u["exists"] else "계정 없음"
        self.lbl_ctx.config(text=f"대상  {self.url.get().strip() or '(미입력)'}   ·   {where}에서 실행   ·   {users}")

    def _pick_key(self):
        p = filedialog.askopenfilename(title="SSH 개인키 선택")
        if p:
            self.key.set(p)

    def _select(self, idx):
        self.sel_idx = idx
        self.sel = self.scenarios[idx]
        for i, (row, bar, num, name) in enumerate(self.sc_rows):
            on = i == idx
            bg = C["primary_l"] if on else C["card"]
            for w in (row, num, name):
                w.config(bg=bg)
            bar.config(bg=C["primary"] if on else C["card"])
            num.config(fg=C["primary"] if on else C["muted"])
            name.config(fg=C["primary_d"] if on else C["text"])
        self.lbl_title.config(text=self.sel["title"])
        self.lbl_desc.config(text=self.sel["desc"])
        self._build_params()
        self._update_buttons()

    def _build_params(self):
        for w in self.params_frame.winfo_children():
            w.destroy()
        self.param_vars = {}
        for i, p in enumerate(self.sel["params"]):
            row = ttk.Frame(self.params_frame, style="Card.TFrame")
            row.grid(row=i, column=0, sticky="ew", pady=4)
            row.columnconfigure(1, weight=1)
            kind = p.get("type", "range")
            ttk.Label(row, text=p["label"], style="Card.TLabel", width=16).grid(row=0, column=0, sticky="w")
            if kind == "select":
                labels = [o[1] for o in p["options"]]
                var = tk.StringVar(value=next(o[1] for o in p["options"] if o[0] == p["def"]))
                ttk.Combobox(row, textvariable=var, values=labels, state="readonly", width=24).grid(
                    row=0, column=1, columnspan=3, sticky="e")
                self.param_vars[p["key"]] = ("select", var, p["options"])
            elif kind == "text":
                var = tk.StringVar(value=p["def"])
                ttk.Entry(row, textvariable=var, width=26).grid(row=0, column=1, columnspan=3, sticky="e")
                self.param_vars[p["key"]] = ("text", var, None)
            else:
                var = tk.IntVar(value=p["def"])
                sc = ttk.Scale(row, from_=p["min"], to=p["max"], orient="horizontal",
                               command=lambda v, var=var, st=p["step"]: self._on_scale(var, v, st))
                self._syncing = True
                sc.set(p["def"])
                self._syncing = False
                sc.grid(row=0, column=1, sticky="ew", padx=14)
                sp = ttk.Spinbox(row, from_=p["min"], to=p["max"], increment=p["step"], textvariable=var,
                                 width=8, command=self._check_users)
                sp.grid(row=0, column=2, sticky="e")
                sp.bind("<KeyRelease>", lambda e: self._check_users())
                ttk.Label(row, text=p.get("unit", ""), style="CardMuted.TLabel", width=6).grid(
                    row=0, column=3, sticky="w", padx=(6, 0))
                var.trace_add("write", lambda *_a, sc=sc, var=var: self._sync_scale(sc, var))
                self.param_vars[p["key"]] = ("range", var, sc)
        self._check_users()

    def _on_scale(self, var, v, step):
        if self._syncing:  # 입력칸을 고치는 중이면 슬라이더가 값을 되덮어쓰지 않는다
            return
        val = int(round(float(v) / step) * step)
        if self._int(var) != val:
            var.set(val)
        self._check_users()

    def _sync_scale(self, sc, var):
        self._syncing = True
        try:
            sc.set(int(var.get()))
        except Exception:
            pass
        finally:
            self._syncing = False
        self._check_users()

    @staticmethod
    def _int(var):
        try:
            return int(var.get())
        except Exception:
            return None

    def _apply_preset(self, key):
        if not self.sel:
            return
        for k, v in self.sel["presets"][key].items():
            if k in self.param_vars and self.param_vars[k][0] == "range":
                self.param_vars[k][1].set(v)
        self._check_users()

    def collect_params(self):
        out = {}
        for key, (kind, var, extra) in self.param_vars.items():
            if kind == "select":
                out[key] = next((o[0] for o in extra if o[1] == var.get()), "")
            elif kind == "text":
                out[key] = var.get().strip()
            else:
                out[key] = self._int(var)
                if out[key] is None:
                    out[key] = next(p["def"] for p in self.sel["params"] if p["key"] == key)
        return out

    def _param_text(self, params):
        parts = []
        for p in self.sel["params"]:
            v = params.get(p["key"])
            unit = ""
            if p.get("type") == "select":
                v = next((o[1] for o in p["options"] if o[0] == v), v)
            elif p.get("type", "range") == "range" and p.get("unit"):
                unit = " " + p["unit"]
            parts.append(f"{p['label']} {v}{unit}")
        return " · ".join(parts)

    def _heavy(self):
        """'보통' 프리셋의 1.5배를 넘는 수치가 하나라도 있으면 센 부하로 본다."""
        if not self.sel:
            return False
        for k, v in ((self.sel.get("presets") or {}).get("normal") or {}).items():
            pv = self.param_vars.get(k)
            if pv and pv[0] == "range":
                cur = self._int(pv[1])
                if cur and cur > v * 1.5:
                    return True
        return False

    def _check_users(self):
        notes = []
        up = self.sel.get("usersParam") if self.sel else None
        if up and up in self.param_vars and self.info["users"]["exists"]:
            need = (self._int(self.param_vars[up][1]) or 0) * self.sel.get("usersMult", 1) + self.sel.get("usersExtra", 0)
            if need > self.info["users"]["total"]:
                notes.append(f"계정이 {self.info['users']['total']:,}명뿐이라 일부 계정을 재사용합니다. "
                             "환경 설정에서 계정을 더 만들어 주세요.")
        if self.mode.get() == "local" and self._heavy():
            if self.mock.running:
                notes.append("Mock 서버가 이 PC에서 같이 돌아 성능 한계가 낮습니다. '가볍게'~'보통' 수치를 권장합니다.")
            else:
                notes.append("이 PC에서 부하를 만들기 때문에 수치가 크면 앱이 느려지고 목표 속도를 못 맞출 수 있습니다 "
                             "(VM 실행 권장).")
        self._msg(self.lbl_warn, "\n".join(notes))

    def _update_buttons(self):
        self.btn_start.state(["!disabled"] if self.sel and not self.running else ["disabled"])
        self.btn_gen.state(["disabled"] if self.running else ["!disabled"])
        if self.running:
            self.btn_start.grid_remove()
            self.btn_stop.grid()
        else:
            self.btn_stop.grid_remove()
            self.btn_start.grid()

    # ───────────────────────── 동작 ─────────────────────────
    def refresh_info(self):
        t = self.target()
        self.lbl_k6.config(text="k6 확인 중…", foreground=C["muted"])
        threading.Thread(target=lambda: self.q.put(("info", core.check_environment(t))), daemon=True).start()

    def start(self):
        self._msg(self.lbl_err, "")
        if not self.sel:
            return
        if not self.info["users"]["exists"]:
            self._msg(self.lbl_err, '테스트 계정이 없습니다. "환경 설정 → 테스트 계정 → 만들기"를 먼저 실행하세요.')
            return
        params = self.collect_params()
        if self.mock.running and self._heavy() and not messagebox.askyesno(
                "센 부하", "Mock 서버는 이 PC에서 같이 돌아 성능 한계가 낮습니다.\n"
                           "이 수치로 실행하면 앱이 느려지거나 멈춘 것처럼 보일 수 있어요.\n\n그래도 시작할까요?"):
            return
        if self.mock.running and self.mock_auto.get() and not self.mock.reset():
            self._msg(self.lbl_err, "Mock 서버 상태를 초기화하지 못했습니다. 환경 설정에서 상태를 확인하세요.")
            return
        if not self.mock.running and self.mode.get() == "local":
            # 개발 모드 서버라면 지난 실행이 남긴 대기열·점수를 비운다 (아니면 404라 아무 일도 없음)
            core.dev_reset(self.url.get())
        ok, err = self.runner.start_k6(self.target(), self.sel, params, self.url.get(),
                                       self.run_id.get().strip(), self.own.get(), self.prom.get())
        if not ok:
            self._msg(self.lbl_err, err)
            return
        self.current = {"sid": self.sel["id"], "title": self.sel["title"], "params": params,
                        "param_text": self._param_text(params), "url": self.url.get().strip(),
                        "mode": self.mode.get(), "keys": list(self.sel.get("keyMetrics") or [])}
        self._reset_run_view()
        self._show_log()
        self._save_settings()

    def stop(self):
        self.runner.stop()

    def gen_users(self):
        self._msg(self.lbl_err, "")
        try:
            count, ratio = int(self.gen_count.get()), float(self.gen_bot.get()) / 100
        except ValueError:
            messagebox.showwarning("입력 확인", "계정 수·봇 비율은 숫자로 입력하세요.")
            return
        ok, err = self.runner.gen_users(self.target(), count, ratio)
        if not ok:
            messagebox.showwarning("계정 생성", err)
        else:
            self._show_log()  # 진행 로그를 보여준다

    def upload(self):
        ok, err = self.runner.upload(self.target())
        if not ok:
            messagebox.showwarning("업로드", err or "업로드 실패")
        else:
            self._show_log()

    def _on_close(self):
        if self.running and not messagebox.askyesno("종료", "테스트가 실행 중입니다. 중지하고 종료할까요?"):
            return
        if self.running:
            self.runner.stop()
        self.mock.stop()
        self._save_settings()
        self.root.destroy()

    # ───────────────────────── 이벤트 처리 ─────────────────────────
    def _pump(self):
        """이벤트를 한 번에 조금씩만 처리한다. 한꺼번에 수천 줄을 처리하면 창이 얼어 '응답 없음'이 뜬다."""
        deadline = time.time() + 0.04
        batch = []
        try:
            while time.time() < deadline:
                kind, data = self.q.get_nowait()
                if kind == "log":
                    batch.append(data)
                    continue
                if batch:
                    self._flush_log(batch)
                    batch = []
                getattr(self, f"_ev_{kind}")(data)
        except queue.Empty:
            pass
        if batch:
            self._flush_log(batch)
        if self._chart_dirty:      # 그래프는 한 번에 한 번만 다시 그린다
            self._chart_dirty = False
            self._draw()
        self.root.after(30 if not self.q.empty() else 100, self._pump)

    @staticmethod
    def _log_tag(line):
        if "[오류]" in line or "ERRO" in line or "level=error" in line:
            return ("err",)
        if line.startswith("[진단]"):
            return ("diag",)
        if line.startswith("──") or line.startswith("[알림]"):
            return ("dim",)
        if line.startswith(("$", "[VM", "[업로드]", "[mock]")):
            return ("cmd",)
        return ()

    def _ev_log(self, line):
        self._flush_log([line])

    def _flush_log(self, lines):
        """로그 여러 줄을 한 번에 삽입한다 (줄마다 갱신하면 느려진다)."""
        if len(lines) > 300:   # 한 번에 너무 많으면 앞뒤만 보여 준다
            cut = len(lines) - 300
            lines = lines[:150] + [f"[알림] … {cut:,}줄 생략 …"] + lines[-150:]
        parts = []
        for ln in lines:
            parts += [ln + "\n", self._log_tag(ln)]
        # 사용자가 위로 스크롤했거나 글자를 선택 중이면 맨 아래로 끌고 가지 않는다 (선택이 풀리는 원인)
        at_bottom = self.log.yview()[1] > 0.995
        selecting = bool(self.log.tag_ranges("sel"))
        self.log.insert("end", *parts)
        if int(self.log.index("end-1c").split(".")[0]) > 3000 and not selecting:
            self.log.delete("1.0", "600.0")
        if self.autoscroll.get() and at_bottom and not selecting:
            self.log.see("end")

    def _ev_info(self, info):
        self.info = info
        if info["k6"]:
            ver = info["k6"].split(" (")[0]
            self.lbl_k6.config(text=f"✔ {ver}", foreground=C["ok"])
            self.chip_k6.config(text=f"● {ver}", fg=C["ok"])
        else:
            self.lbl_k6.config(text=f"✖ k6 없음 — {info['error'] or 'k6를 설치하세요'}", foreground=C["bad"])
            self.chip_k6.config(text="● k6 없음", fg=C["bad"])
        u = info["users"]
        txt = f"계정 {u['total']:,}명 (봇 {u['bots']:,})" if u["exists"] else "계정 없음 — '만들기'로 생성하세요"
        self.lbl_users.config(text=txt)
        self.chip_users.config(text=f"계정 {u['total']:,}명" if u["exists"] else "계정 없음")
        self._refresh_ctx()
        self._check_users()

    def _ev_state(self, st):
        was_running = self.running
        self.running = st["running"]
        if not self.running:
            self.started_at = None
        elif not was_running:  # 중지 요청 등으로 상태가 다시 오는 경우엔 시계를 유지
            self.started_at = time.time()
            self._clock()
        self._update_buttons()
        self.root.title("제자리 트래픽 컨트롤" + (f" — 실행 중: {st['title']}" if self.running else ""))

    def _clock(self):
        if self.running and self.started_at:
            self.tiles["time"].config(text=f"{int(time.time() - self.started_at)}s")
            self.root.after(500, self._clock)

    def _reset_run_view(self):
        self.series = {"rps": [], "p95": []}
        for k in ("vus", "rps", "fail", "p95", "reqs"):
            self.tiles[k].config(text="–")
        self.tiles["fail"].config(foreground=C["text"])
        for w in self.custom_frame.winfo_children():
            w.destroy()
        self.chips = {}
        self.res_tree.delete(*self.res_tree.get_children())
        self.banner.grid_remove()
        self._draw()

    def _ev_tick(self, t):
        self.tiles["vus"].config(text=fmt(t["vus"]))
        self.tiles["rps"].config(text=fmt(t["rps"]))
        self.tiles["reqs"].config(text=fmt(t["reqs"]))
        self.tiles["fail"].config(text=f"{t['failRate'] * 100:.1f}%",
                                  foreground=C["bad"] if t["failRate"] > 0.05 else C["text"])
        self.tiles["p95"].config(text="–" if t["p95"] is None else f"{round(t['p95'])}ms")
        self.series["rps"].append(t["rps"])
        self.series["p95"].append(t["p95"] or 0)
        for k in self.series:
            self.series[k] = self.series[k][-300:]
        self._chart_dirty = True
        # 지표 칩은 새로 만들지 않고 글자만 갱신한다 (깜빡임 방지)
        for name, s in (t.get("custom") or {}).items():
            if "count" in s:
                v = f"{s['count']:,}"
            elif "rate" in s:
                v = f"{s['rate'] * 100:.1f}%"
            elif "avg" in s:
                v = f"{s['avg']:.1f}"
            else:
                v = str(s.get("value", "–"))
            chip = self.chips.get(name)
            if chip is None:
                chip = ttk.Label(self.custom_frame, style="Chip.TLabel")
                chip.pack(side="left", padx=(0, 8))
                self.chips[name] = chip
            chip.config(text=f"{name}  {v}")

    def _draw(self):
        for cv, data, color, fill in ((self.cv_rps, self.series["rps"], C["primary"], C["primary_l"]),
                                      (self.cv_p95, self.series["p95"], "#0EA5A4", "#E3F6F6")):
            cv.delete("all")
            w = max(cv.winfo_width(), 100)
            h = max(cv.winfo_height(), 40)
            for k in (1, 2, 3):   # 가로 눈금선
                y = 16 + (h - 22) * k / 4
                cv.create_line(0, y, w, y, fill=C["grid"])
            if len(data) < 2:
                continue
            top = max(max(data), 1)
            pts = []
            for i, v in enumerate(data):
                pts += [i / (len(data) - 1) * (w - 4) + 2, h - 6 - v / top * (h - 22)]
            cv.create_polygon(*pts, w - 2, h - 6, 2, h - 6, fill=fill, outline="")
            cv.create_line(*pts, fill=color, width=2)
            cv.create_text(w - 4, 2, anchor="ne", fill=C["muted"], text=f"max {round(top):,}", font=(self.F, 8))

    def _ev_done(self, d):
        bg, fg, msg = BANNER.get(d["status"], (C["primary_l"], C["primary_d"], ""))
        if d["kind"] == "k6":
            text = f"{d['title']}: {msg}  ({d['seconds']}초)"
        elif d["kind"] == "upload":
            text = "프로젝트 업로드 " + ("완료" if d["status"] == "pass" else "실패 — 로그 확인")
        else:
            text = "계정 생성 " + ("완료" if d["status"] == "pass" else "실패 — 로그 확인")
        for h in (d.get("hints") or []):   # 확실히 아는 원인만 (모르면 아무것도 붙이지 않는다)
            text += f"\n    → {h}"
        self.banner.config(text=text, bg=bg, fg=fg)
        self.banner.grid()
        self.res_tree.delete(*self.res_tree.get_children())
        for name, v in (d.get("metrics") or {}).items():
            if "p(95)" in v:
                val = f"avg {v['avg']:.1f}   p95 {v['p(95)']:.1f}   max {v['max']:.1f}"
            elif "count" in v:
                val = f"count {v['count']:,}" + (f"   {v['rate']:.1f}/s" if "rate" in v else "")
            elif "rate" in v:
                val = f"{v['rate'] * 100:.2f}%"
            else:
                val = f"{v.get('value', '')}"
                if name.endswith("failed") and isinstance(v.get("value"), (int, float)):
                    val = f"{v['value'] * 100:.2f}%"
            self.res_tree.insert("", "end", values=(name, val))
        if d.get("summaryFile"):
            if (self.current or {}).get("mode") == "ssh":
                where = f"[VM] {self.rdir.get().rstrip('/')}/{d['summaryFile']}"
            else:
                f = core.ROOT / d["summaryFile"]
                where = str(f) if f.exists() else f"{f}   (파일이 만들어지지 않았습니다 — 로그 확인)"
            self.res_tree.insert("", "end", values=("결과 파일", where))
        if d["kind"] == "k6":
            self._save_history(d)
        if d["kind"] in ("gen", "upload"):
            self.refresh_info()

    # ───────────────────────── 로그 · 결과 복사 ─────────────────────────
    def _popup(self, widget, items):
        menu = tk.Menu(self.root, tearoff=0)
        for label, fn in items:
            menu.add_command(label=label, command=fn)

        def show(e):
            widget.focus_set()
            try:
                menu.tk_popup(e.x_root, e.y_root)
            finally:
                menu.grab_release()
        for seq in (("<Button-2>", "<Control-Button-1>") if sys.platform == "darwin" else ("<Button-3>",)):
            widget.bind(seq, show, add="+")

    def _log_key(self, e):
        """읽기 전용 로그: 이동·복사는 허용하고 글자 입력/삭제만 막는다."""
        ctrl = bool(e.state & 0x4) or (sys.platform == "darwin" and bool(e.state & 0x8))
        if ctrl and e.keysym.lower() in ("c", "a"):
            return None
        if e.keysym in ("Left", "Right", "Up", "Down", "Home", "End", "Prior", "Next",
                        "Shift_L", "Shift_R", "Control_L", "Control_R", "Meta_L", "Meta_R"):
            return None
        return "break"

    def _log_select_all(self, e=None):
        self.log.tag_add("sel", "1.0", "end-1c")
        return "break"

    def _log_copy(self, all_=False):
        if not all_ and self.log.tag_ranges("sel"):
            text = self.log.get("sel.first", "sel.last")
        else:
            text = self.log.get("1.0", "end-1c")
        self._clip(text)

    def _log_clear(self):
        self.log.delete("1.0", "end")

    def _open_results(self):
        if self.mode.get() == "ssh":
            messagebox.showinfo("결과 폴더", f"VM 실행 결과는 VM의 {self.rdir.get().rstrip('/')}/results 에 있습니다.\n"
                                            "(실행 기록·비교는 이 PC의 results/history.json 에 저장됩니다)")
            return
        folder = core.ROOT / "results"
        folder.mkdir(parents=True, exist_ok=True)
        try:
            if sys.platform == "win32":
                os.startfile(str(folder))
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(folder)])
            else:
                subprocess.Popen(["xdg-open", str(folder)])
        except Exception:
            messagebox.showinfo("결과 폴더", str(folder))

    def _res_select_all(self, e=None):
        self.res_tree.selection_set(self.res_tree.get_children())
        return "break"

    def _res_copy(self, all_=False):
        ids = self.res_tree.get_children() if all_ or not self.res_tree.selection() else self.res_tree.selection()
        lines = ["\t".join(str(x) for x in self.res_tree.item(i, "values")) for i in ids]
        self._clip("\n".join(lines))

    def _clip(self, text):
        if not text:
            self._toast("복사할 내용이 없습니다", ok=False)
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.root.update_idletasks()
        self._toast(f"복사했습니다 ✓  ({len(text.splitlines()):,}줄)")

    def _toast(self, msg, ok=True):
        for lbl in (self.lbl_toast, getattr(self, "lbl_toast_log", None)):
            if lbl is not None:
                lbl.config(text=msg, foreground=C["ok"] if ok else C["warn"])
        if self._toast_job:
            self.root.after_cancel(self._toast_job)

        def clear():
            for lbl in (self.lbl_toast, getattr(self, "lbl_toast_log", None)):
                if lbl is not None:
                    lbl.config(text="")
        self._toast_job = self.root.after(2500, clear)

    def _msg(self, label, text):
        """경고·오류 줄: 내용이 있을 때만 자리를 차지한다."""
        label.config(text=text)
        if text:
            label.grid()
        else:
            label.grid_remove()

    # ───────────────────────── 로그 창 · 화면 크기 ─────────────────────────
    def _build_log_window(self):
        win = tk.Toplevel(self.root)
        win.title("제자리 — 실행 로그")
        win.configure(bg=C["bg"])
        win.minsize(520, 280)
        win.protocol("WM_DELETE_WINDOW", win.withdraw)   # 닫아도 내용은 유지되고 다시 열 수 있다
        win.withdraw()
        self.log_win = win
        self._log_placed = False

        logf = tk.Frame(win, bg=C["log_bg"], highlightbackground=C["border"], highlightthickness=1)
        logf.pack(fill="both", expand=True, padx=10, pady=10)
        logf.columnconfigure(0, weight=1)
        logf.rowconfigure(0, weight=1)
        self.log = tk.Text(logf, height=9, bg=C["log_bg"], fg=C["log_fg"], wrap="word", relief="flat", bd=0,
                           padx=12, pady=10, font=(self.mono, 10), spacing1=2, spacing3=2, cursor="xterm",
                           selectbackground="#BFD4FF", selectforeground=C["text"],
                           inactiveselectbackground="#D5E2FF", highlightthickness=0)
        self.log.tag_configure("err", foreground=C["bad"])
        self.log.tag_configure("dim", foreground=C["muted"])
        self.log.tag_configure("cmd", foreground=C["primary_d"])
        self.log.tag_configure("diag", foreground=C["warn"], font=(self.mono, 10, "bold"))
        # 읽기 전용이지만 선택·복사는 되도록 (state=disabled 는 클릭해도 포커스가 안 가서 Ctrl+C 가 안 먹는다)
        self.log.bind("<Key>", self._log_key)
        self.log.bind("<Button-1>", lambda e: self.log.focus_set(), add="+")
        for seq in ("<Control-a>", "<Control-A>", "<Command-a>"):
            try:
                self.log.bind(seq, self._log_select_all)
            except tk.TclError:
                pass
        self._popup(self.log, [("복사", self._log_copy), ("전체 선택", self._log_select_all),
                               ("로그 지우기", self._log_clear)])
        sb = ttk.Scrollbar(logf, command=self.log.yview)
        self.log.configure(yscrollcommand=sb.set)
        self.log.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        bar = ttk.Frame(logf, style="Card.TFrame", padding=(8, 6))
        bar.grid(row=1, column=0, columnspan=2, sticky="ew")
        ttk.Button(bar, text="복사", style="Small.TButton", command=self._log_copy).pack(side="left")
        ttk.Button(bar, text="전체 복사", style="Small.TButton",
                   command=lambda: self._log_copy(all_=True)).pack(side="left", padx=6)
        ttk.Button(bar, text="지우기", style="Small.TButton", command=self._log_clear).pack(side="left")
        self.lbl_toast_log = ttk.Label(bar, text="", style="CardMuted.TLabel", foreground=C["ok"])
        self.lbl_toast_log.pack(side="left", padx=10)
        ttk.Checkbutton(bar, text="자동 스크롤", variable=self.autoscroll, style="Card.TCheckbutton").pack(side="right")
        ttk.Label(bar, text="드래그로 선택 · Ctrl+C 복사 · 우클릭 메뉴", style="CardMuted.TLabel").pack(
            side="right", padx=12)

    def _show_log(self):
        """로그 창을 연다 (처음엔 메인 창 옆에 배치, 이후엔 사용자가 옮긴 위치 유지)."""
        w = self.log_win
        if not self._log_placed:
            sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
            lw, lh = min(900, sw - 40), min(500, sh - 120)
            x = max(0, min(self.root.winfo_x() + 60, sw - lw - 10))
            y = max(0, min(self.root.winfo_y() + 150, sh - lh - 60))
            w.geometry(f"{lw}x{lh}+{x}+{y}")
            self._log_placed = True
        w.deiconify()
        w.lift()

    def _set_charts(self, show):
        self.charts_shown = show
        if show:
            self.charts_outer.grid()
        else:
            self.charts_outer.grid_remove()
        self.btn_charts.config(text="▾ 그래프 접기" if show else "▸ 그래프 펼치기")

    def _toggle_charts(self):
        self._set_charts(not self.charts_shown)

    def _fit_window(self):
        """내용이 잘리지 않는 높이로 창을 맞춘다. 화면이 낮으면 그래프를 먼저 접는다."""
        self.root.update_idletasks()
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        avail = sh - 90
        if self.root.winfo_reqheight() > avail:
            self._set_charts(False)
            self.root.update_idletasks()
        req = self.root.winfo_reqheight()
        h, w = min(max(req + 8, 700), avail), min(1280, sw - 40)
        self.root.geometry(f"{w}x{h}+{max(0, (sw - w) // 2)}+{max(0, (avail - h) // 2)}")
        self.root.minsize(min(1100, w), min(req - 70, h))

    # ───────────────────────── Mock 서버 ─────────────────────────
    def _sync_mock_buttons(self):
        local = self.mode.get() == "local"
        run = self.mock.running
        self.btn_mock_start.state(["!disabled"] if (local and not run) else ["disabled"])
        self.btn_mock_stop.state(["!disabled"] if run else ["disabled"])
        self.btn_mock_reset.state(["!disabled"] if run else ["disabled"])
        if not local and not run:
            self.lbl_mock.config(text="● VM 실행 모드에서는 사용할 수 없습니다 (이 PC의 mock 서버는 VM에서 접근 불가)",
                                 foreground=C["muted"])
        elif not run:
            self.lbl_mock.config(text="● 꺼짐", foreground=C["muted"])

    def mock_start(self):
        if self.mode.get() != "local":
            messagebox.showinfo("Mock 서버", "이 PC에서 k6를 실행하는 모드에서만 사용할 수 있습니다.")
            return
        label = self.mock_bug.get()
        bug = next((v for v, l in core.MockServer.BUGS if l == label), "")
        ok, err = self.mock.start(bug)
        if not ok:
            messagebox.showwarning("Mock 서버", err)
            return
        self.url.set(f"http://localhost:{core.MockServer.PORT}")
        self._toast("Mock 서버를 시작했습니다 (로그 창에서 확인)")

    def mock_stop(self):
        self.mock.stop()

    def mock_reset(self):
        self._toast("Mock 서버 상태를 초기화했습니다 ✓" if self.mock.reset() else "초기화 실패 (서버가 꺼져 있나요?)",
                    ok=self.mock.running)

    def _ev_mock(self, d):
        if d["running"]:
            name = next((l for v, l in core.MockServer.BUGS if v == d.get("bug")), "")
            self.lbl_mock.config(text=f"● 실행 중  localhost:{core.MockServer.PORT}  ·  {name}", foreground=C["ok"])
        self._sync_mock_buttons()
        self._check_users()

    # ───────────────────────── 결과 이력 · 비교 ─────────────────────────
    def _save_history(self, d):
        cur, self.current = self.current, None
        if not cur or not d.get("metrics"):
            return
        now = time.time()
        rec = {"id": str(int(now * 1000)), "ts": now, "status": d["status"], "seconds": d["seconds"],
               "metrics": d["metrics"], "summaryFile": d.get("summaryFile"), **cur}
        try:
            self.history.add(rec)
        except Exception as e:
            self._ev_log(f"[오류] 결과 이력 저장 실패: {e}")

    def _refresh_history(self):
        self.h_recs = {r["id"]: r for r in self.history.load()}
        self.h_tree.delete(*self.h_tree.get_children())
        for r in self.h_recs.values():
            rows = {x[0]: x[1] for x in core.summary_rows(r)}
            tps, p95, fail = rows.get("처리량 (요청/초)"), rows.get("p95 응답 (ms)"), rows.get("실패율 (%)")
            self.h_tree.insert("", "end", iid=r["id"], tags=(r["status"],), values=(
                when(r["ts"]), r["title"], core.STATUS_TEXT.get(r["status"], r["status"]),
                "–" if tps is None else f"{tps:,.1f}", "–" if p95 is None else f"{p95:,.1f}",
                "–" if fail is None else f"{fail:.2f}%", r.get("param_text", "")))
        self._on_hist_select()

    def _on_hist_select(self):
        self.c_tree.delete(*self.c_tree.get_children())
        sel = sorted((self.h_recs[i] for i in self.h_tree.selection() if i in self.h_recs), key=lambda r: r["ts"])
        if not sel:
            self.lbl_cmp.config(text="실행 기록을 선택하세요")
            return
        label = lambda r: f"{when(r['ts'])} {r['title']}"  # noqa: E731
        if len(sel) == 1:
            a, b = None, sel[0]
            head = f"{label(b)} — 하나 더 선택하면 전 → 후를 비교합니다"
        else:
            note = ""
            if len(sel) > 2:
                note = f"   ※ {len(sel)}개 중 가장 이른 것과 가장 늦은 것을 비교"
                sel = [sel[0], sel[-1]]
            a, b = sel
            diff = "   ※ 서로 다른 시나리오입니다" if a.get("sid") != b.get("sid") else ""
            head = f"전  {label(a)}   →   후  {label(b)}{diff}{note}"
        self.lbl_cmp.config(text=head)
        for row in core.compare_rows(a, b):
            tag = row["verdict"] if row["verdict"] in ("good", "bad", "same") else ""
            self.c_tree.insert("", "end", values=(row["label"], row["a"], row["b"], row["change"]), tags=(tag,))

    def _hist_delete(self):
        ids = list(self.h_tree.selection())
        if ids and messagebox.askyesno("삭제", f"선택한 {len(ids)}개 기록을 삭제할까요?"):
            self.history.delete(ids)
            self._refresh_history()

    def _hist_export(self):
        recs = list(self.history.load())
        if not recs:
            messagebox.showinfo("CSV로 저장", "저장할 기록이 없습니다.")
            return
        path = filedialog.asksaveasfilename(defaultextension=".csv", initialfile="jejari_results.csv",
                                            filetypes=[("CSV", "*.csv")])
        if not path:
            return
        labels = [c[0] for c in core.CORE_ROWS]
        keys = list(dict.fromkeys(k for r in recs for k in (r.get("keys") or [])))
        with open(path, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            w.writerow(["시각", "시나리오", "판정", "설정", "대상"] + labels + keys)
            for r in sorted(recs, key=lambda r: r["ts"]):
                vals = {x[0]: x[1] for x in core.summary_rows(r)}
                w.writerow([datetime.datetime.fromtimestamp(r["ts"]).strftime("%Y-%m-%d %H:%M:%S"), r["title"],
                            core.STATUS_TEXT.get(r["status"], r["status"]), r.get("param_text", ""),
                            r.get("url", "")] + ["" if vals.get(k) is None else vals[k] for k in labels + keys])

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    try:
        App().run()
    except Exception:
        err = traceback.format_exc()
        try:
            ERROR_LOG.write_text(err, encoding="utf-8")
        except OSError:
            pass
        try:
            messagebox.showerror("제자리 트래픽 컨트롤", f"앱을 시작하지 못했습니다.\n\n{err[-800:]}\n\n(전체 내용: {ERROR_LOG})")
        except Exception:
            print(err, file=sys.stderr)
