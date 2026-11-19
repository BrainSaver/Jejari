import { useEffect, useMemo, useRef, useState } from 'react';
import { get } from '../api/client.js';
import { ErrorBox } from './ui.jsx';

/**
 * ADM-11 화면 녹화 재생 (A-06). 케이스와 겹치는 녹화 세션을 골라 "영상처럼" 다시 그린다.
 *   GET /api/v1/admin/cases/{id}/replays              세션 목록
 *   GET /api/v1/admin/cases/{id}/replays/{sessionId}  {session, events, server}
 *
 * 화면을 실제로 찍은 게 아니라 좌표·요소 ID만 있으므로, 화면 틀 위에 마우스 궤적·클릭·화면 이름을 그린다.
 * 옆에는 같은 세션의 서버 이벤트(선점·결제·취소, 응답 코드)를 같은 시간축으로 보여 줘 "클릭 → 서버 결과"를 한 줄로 본다.
 * 좌표는 화면 비율 0~1000이라 녹화한 기기의 해상도와 상관없이 재생된다.
 */
const W = 1000;
const H = 625; // 16:10
const TOP = 44; // 위쪽 화면 이름 띠
const SPEEDS = [1, 4, 16];
const TRAIL_MS = 1500;
const CLICK_MS = 900;

export default function ReplayPlayer({ caseId, markers }) {
  const [sessions, setSessions] = useState(null);
  const [sid, setSid] = useState(null);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    get(`/api/v1/admin/cases/${caseId}/replays`)
      .then((s) => {
        setSessions(s);
        if (s.length) setSid(s[0].sessionId);
      })
      .catch(setError);
  }, [caseId]);

  useEffect(() => {
    if (!sid) return;
    setData(null);
    get(`/api/v1/admin/cases/${caseId}/replays/${encodeURIComponent(sid)}`).then(setData).catch(setError);
  }, [caseId, sid]);

  return (
    <section className="panel" id="replay">
      <h2>
        <span className="grow">
          화면 녹화 재생 <span className="mut" style={{ fontWeight: 500 }}>replay_event · 케이스와 겹치는 세션 {sessions ? sessions.length : '…'}개</span>
        </span>
      </h2>
      <ErrorBox error={error} onClose={() => setError(null)} />
      {sessions && !sessions.length && (
        <p className="mut" style={{ fontSize: 14 }}>이 케이스의 의심 구간에 녹화된 세션이 없어요. 녹화는 회원 화면에서 로그인 세션 단위로 남아요.</p>
      )}
      {sessions?.length > 0 && (
        <div className="row" style={{ flexWrap: 'wrap', gap: 6, marginBottom: 10 }}>
          {sessions.map((s, i) => (
            <button key={s.sessionId} type="button" className={'chip' + (s.sessionId === sid ? ' on' : '')} onClick={() => setSid(s.sessionId)}>
              세션 {i + 1} · {clock(s.startedAt)} · {s.eventCount}건 · {s.mode}{s.legalHold ? ' · 보존' : ''}
            </button>
          ))}
        </div>
      )}
      {data && <Player data={data} markers={markers || []} />}
    </section>
  );
}

function Player({ data, markers }) {
  const tl = useMemo(() => build(data), [data]);
  const [t, setT] = useState(tl.start);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(4);
  const raf = useRef(0);
  const stepsRef = useRef(null);

  useEffect(() => {
    setT(tl.start);
    setPlaying(false);
  }, [tl]);

  useEffect(() => {
    if (!playing) return undefined;
    let last = performance.now();
    const step = (now) => {
      const dt = (now - last) * speed;
      last = now;
      setT((cur) => {
        const next = cur + dt;
        if (next >= tl.end) {
          setPlaying(false);
          return tl.end;
        }
        return next;
      });
      raf.current = requestAnimationFrame(step);
    };
    raf.current = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf.current);
  }, [playing, speed, tl]);

  const page = lastBefore(tl.pages, t);
  const screen = page?.screen || '';
  const layout = lastBefore(tl.layouts, t);
  const boxes = layout && layout.screen === screen ? layout.boxes : [];
  const hidden = lastBefore(tl.visibility, t)?.target === 'hidden';
  const trail = tl.points.filter((p) => p.t <= t && p.t >= t - TRAIL_MS);
  const cursor = lastBefore(tl.points, t) || lastBefore(tl.clicks, t);
  const clicks = tl.clicks.filter((c) => c.t <= t && c.t >= t - CLICK_MS);
  const span = Math.max(1, tl.end - tl.start);
  const caseMarks = markers.map((m) => Date.parse(m.at)).filter((x) => x >= tl.start && x <= tl.end);
  const done = tl.steps.filter((s) => s.t <= t);

  // 줄글은 지금 줄이 보이게 따라 내려간다
  useEffect(() => {
    const el = stepsRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [done.length]);

  const y = (v) => TOP + (v / 1000) * (H - TOP);
  const seatName = (id) => data.seats?.[id] || null;

  return (
    <div className="cols" style={{ alignItems: 'start' }}>
      <div>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`녹화 화면 ${screenName(screen)}`}
          style={{ width: '100%', background: 'var(--paper)', border: '1px solid var(--line)', borderRadius: 8, display: 'block' }}>
          <rect x="0" y="0" width={W} height={TOP} fill="var(--ink)" />
          <text x="18" y="29" fontSize="20" fill="var(--cream)" fontWeight="700">{screenName(screen)}</text>
          <text x={W - 18} y="29" fontSize="16" fill="var(--cream)" textAnchor="end" opacity="0.8">{clock(new Date(t))}</text>
          {/* 그때 회원 화면의 뼈대 (버튼·입력 칸·좌석의 위치와 상태). 화면 글자는 녹화하지 않아 버튼 문구만 */}
          {boxes.map((b, i) => <BoxShape key={i} b={b} y={y} seatName={seatName} />)}
          {!boxes.length && screen && (
            <text x={W / 2} y={H / 2} fontSize="18" fill="var(--mut)" textAnchor="middle">이 화면의 배치는 녹화되지 않았어요 (클릭·마우스만)</text>
          )}
          {trail.length > 1 && (
            <polyline points={trail.map((p) => `${p.x},${y(p.y)}`).join(' ')} fill="none" stroke="var(--ink2)" strokeWidth="3" strokeOpacity="0.55" strokeLinejoin="round" />
          )}
          {clicks.map((c) => {
            const k = 1 - (t - c.t) / CLICK_MS;
            const name = seatName(c.target) || c.label || '';
            return (
              <g key={c.seq}>
                <circle cx={c.x} cy={y(c.y)} r={10 + (1 - k) * 26} fill="none" stroke="var(--stop)" strokeWidth="3" opacity={k} />
                {name && (
                  <g opacity={k}>
                    <rect x={c.x + 12} y={y(c.y) - 40} width={name.length * 15 + 16} height="28" rx="6" fill="var(--stop)" />
                    <text x={c.x + 20} y={y(c.y) - 20} fontSize="16" fill="var(--cream)" fontWeight="700">{name}</text>
                  </g>
                )}
              </g>
            );
          })}
          {cursor && cursor.x != null && (
            <path d={`M${cursor.x},${y(cursor.y)} l0,22 l6,-6 l10,0 z`} fill="var(--ink)" stroke="var(--card)" strokeWidth="1.5" />
          )}
          {hidden && (
            <g>
              <rect x="0" y={TOP} width={W} height={H - TOP} fill="var(--ink)" opacity="0.4" />
              <text x={W / 2} y={H / 2} fontSize="26" fill="var(--cream)" textAnchor="middle" fontWeight="700">다른 탭으로 나감</text>
            </g>
          )}
        </svg>
        <div className="row" style={{ gap: 8, marginTop: 8 }}>
          <button type="button" className="btn sm" onClick={() => { if (t >= tl.end) setT(tl.start); setPlaying(!playing); }}>
            {playing ? '일시정지' : '재생'}
          </button>
          {SPEEDS.map((s) => (
            <button key={s} type="button" className={'chip' + (speed === s ? ' on' : '')} onClick={() => setSpeed(s)}>{s}배</button>
          ))}
          <span className="grow" />
          <span className="mut num" style={{ fontSize: 13 }}>{fmtDur(t - tl.start)} / {fmtDur(span)}</span>
        </div>
        <div style={{ position: 'relative', marginTop: 6 }}>
          <input type="range" min={tl.start} max={tl.end} value={t} step="10" aria-label="재생 위치"
            onChange={(e) => { setPlaying(false); setT(Number(e.target.value)); }} style={{ width: '100%' }} />
          {caseMarks.map((m, i) => (
            <span key={i} title="탐지 마커" style={{ position: 'absolute', top: -4, left: `${((m - tl.start) / span) * 100}%`, width: 3, height: 10, background: 'var(--stop)' }} />
          ))}
        </div>
        <Legend />
      </div>
      <div>
        <h2 style={{ fontSize: 14 }}>무엇을 했나 <span className="mut" style={{ fontWeight: 500 }}>화면 동작과 서버 결과를 시간순으로</span></h2>
        <div ref={stepsRef} style={{ maxHeight: 420, overflowY: 'auto' }}>
          {!done.length && <p className="mut" style={{ fontSize: 13 }}>[재생]을 누르면 한 줄씩 나와요. 재생 막대를 끌어도 돼요.</p>}
          {done.map((s, i) => (
            <div key={i} className="row" style={{ fontSize: 13, padding: '4px 0', borderBottom: '1px solid var(--line2)', alignItems: 'baseline',
              ...(i === done.length - 1 ? { background: 'var(--sign-t)' } : {}) }}>
              <span className="faint num" style={{ flex: 'none' }}>{clock(new Date(s.t))}</span>
              <span className={'st plain ' + s.tone} style={{ flex: 'none' }}>{s.kind}</span>
              <span className="grow">{s.text(seatName)}</span>
            </div>
          ))}
        </div>
        <p className="mut" style={{ fontSize: 12, marginTop: 10 }}>
          화면 글자와 입력값은 녹화하지 않아요 (버튼 문구와 입력 길이만). 마우스 궤적은 FULL 녹화(위험 점수 70 이상 또는 표본 10%)에서만 남아요.
        </p>
      </div>
    </div>
  );
}

/** 화면 뼈대 상자 하나. 좌석은 상태 색, 버튼은 문구, 입력 칸은 빈 칸 */
function BoxShape({ b, y, seatName }) {
  const x = b.x;
  const top = y(b.y);
  const w = Math.max(4, b.w);
  const h = Math.max(4, y(b.y + b.h) - top);
  if (b.k.startsWith('seat-')) {
    const st = b.k.slice(5);
    const fill = { free: 'var(--card)', hold: 'var(--amber-t)', taken: 'var(--line)', mine: 'var(--go)', lost: 'var(--stop-t)' }[st];
    return (
      <g>
        <title>{(seatName(b.id) || '좌석') + ' · ' + SEAT_KO[st]}</title>
        <rect x={x} y={top} width={w} height={h} rx="3" fill={fill} stroke="var(--line)" />
      </g>
    );
  }
  if (b.k === 'heading') {
    return b.label
      ? <text x={x} y={top + h * 0.75} fontSize={Math.min(26, h * 0.8)} fill="var(--ink)" fontWeight="700">{b.label}</text>
      : <rect x={x} y={top + h * 0.2} width={Math.min(w, 260)} height={h * 0.6} rx="4" fill="var(--line)" />;
  }
  const isInput = b.k === 'input' || b.k === 'select';
  return (
    <g opacity={b.off ? 0.45 : 1}>
      <rect x={x} y={top} width={w} height={h} rx={b.k === 'tab' ? h / 2 : 6}
        fill={isInput ? 'var(--card)' : b.k === 'link' ? 'none' : 'var(--sky-t)'}
        stroke={b.k === 'link' ? 'none' : 'var(--line)'} />
      {b.label && h > 12 && (
        <text x={x + w / 2} y={top + h / 2 + 5} fontSize={Math.min(15, h * 0.55)} fill="var(--ink2)" textAnchor="middle"
          textDecoration={b.k === 'link' ? 'underline' : undefined}>{b.label.length * 13 > w ? b.label.slice(0, Math.max(1, Math.floor(w / 13))) + '…' : b.label}</text>
      )}
      {b.k === 'select' && <text x={x + w - 14} y={top + h / 2 + 5} fontSize="14" fill="var(--mut)">▾</text>}
    </g>
  );
}

function Legend() {
  const items = [['var(--card)', '빈 좌석'], ['var(--line)', '판매됨'], ['var(--amber-t)', '다른 사람이 고르는 중'], ['var(--go)', '이 사람이 고름']];
  return (
    <div className="row" style={{ gap: 12, flexWrap: 'wrap', marginTop: 6, fontSize: 12 }}>
      {items.map(([c, l]) => (
        <span key={l} className="row" style={{ gap: 4 }}><span style={{ width: 12, height: 12, background: c, border: '1px solid var(--line)', borderRadius: 2 }} />{l}</span>
      ))}
      <span className="row" style={{ gap: 4 }}><span style={{ width: 12, height: 12, border: '2px solid var(--stop)', borderRadius: 6 }} />클릭</span>
    </div>
  );
}

const SEAT_KO = { free: '빈 좌석', hold: '다른 사람이 고르는 중', taken: '판매됨', mine: '이 사람이 고름', lost: '놓침' };

/** 화면 ID → 한글 이름 */
const SCREENS = [
  [/^home$/, '열차 검색'], [/^queue\/:id$/, '줄 서기'], [/^runs\/:id\/seats$/, '좌석 선택'], [/^pay\/:id$/, '결제'],
  [/^pay\/:id\/result$/, '결제 결과'], [/^reservations$/, '예매 내역'], [/^waitlist$/, '취소표'], [/^me$/, '내 정보'],
  [/^login$/, '로그인'], [/^signup$/, '회원가입'], [/^notifications$/, '알림'],
];
function screenName(s) {
  if (!s) return '—';
  const hit = SCREENS.find(([re]) => re.test(s));
  return hit ? hit[1] : s;
}

/** 서버 행동 로그 → 한글 */
const SERVER_KO = {
  VIEW_TRAIN: '열차 목록 조회', VIEW_SEAT: '좌석표 조회', QUEUE_ENTER: '줄 서기', SEAT_HOLD: '좌석 선점', PAY: '결제',
  CANCEL: '취소', WAITLIST_APPLY: '취소표 신청', WAITLIST_CANCEL: '취소표 대기 취소',
};
function statusKo(code) {
  if (code >= 200 && code < 300) return '성공';
  return { 409: '실패 — 이미 다른 사람이 잡음/상태 충돌', 403: '거절 — 권한·입장 토큰 없음', 429: '거절 — 요청이 너무 많음',
    404: '없음', 401: '로그인 필요', 422: '거절 — 매수 제한', 410: '기한 지남' }[code] || `실패 (${code})`;
}

/** 이벤트를 재생용 시간축으로. 마우스 궤적의 각 점은 "보낸 시각 − (마지막 dt − 그 점의 dt)" */
function build({ events, server }) {
  const all = events.map((e) => ({ ...e, t: Date.parse(e.ts) }));
  const points = [];
  for (const e of all) {
    if (e.type !== 'MOUSE_PATH' || !e.path?.length) continue;
    const lastDt = e.path[e.path.length - 1][0];
    for (const [dt, x, y] of e.path) points.push({ t: e.t - (lastDt - dt), x, y });
  }
  points.sort((a, b) => a.t - b.t);
  const srv = server.map((s) => ({ ...s, t: Date.parse(s.ts) }));
  const times = [...all.map((e) => e.t), ...points.map((p) => p.t), ...srv.map((s) => s.t)];
  const start = times.length ? Math.min(...times) : Date.now();
  const end = times.length ? Math.max(...times) + 500 : start + 1000;

  // "무엇을 했나": 화면 이동·클릭·입력·탭 이동 + 서버 결과. 연달아 같은 입력 칸은 한 줄로
  const steps = [];
  for (const e of all) {
    if (e.type === 'PAGE_VIEW') steps.push({ t: e.t, kind: '화면', tone: 'sky', text: () => `${screenName(e.screen)} 화면을 엶` });
    else if (e.type === 'CLICK') {
      steps.push({ t: e.t, kind: '클릭', tone: 'amber', text: (seat) => {
        const s = seat(e.target);
        if (s) return `좌석 ${s} 누름`;
        if (e.label) return `[${e.label}] 누름`;
        return e.target ? `${e.target} 누름` : '빈 곳 누름';
      } });
    } else if (e.type === 'INPUT') {
      const prev = steps[steps.length - 1];
      if (prev?.input === e.target) {
        prev.len = e.input_masked;
        prev.t2 = e.t;
      } else {
        const st = { t: e.t, kind: '입력', tone: '', input: e.target, len: e.input_masked, text: () => `입력 칸에 씀 (${(st.len || '').replace('len:', '')}자, 내용은 녹화 안 함)` };
        steps.push(st);
      }
    } else if (e.type === 'VISIBILITY') {
      steps.push({ t: e.t, kind: '탭', tone: '', text: () => (e.target === 'hidden' ? '다른 탭·창으로 나감' : '돌아옴') });
    }
  }
  for (const s of srv) {
    const bad = s.response_status >= 400;
    steps.push({ t: s.t, kind: '서버', tone: bad ? 'stop' : 'go',
      text: () => `${SERVER_KO[s.event_type] || s.event_type} → ${statusKo(s.response_status)}${s.run_id ? ` (운행 ${s.run_id})` : ''}` });
  }
  steps.sort((a, b) => a.t - b.t);

  return {
    all,
    points,
    server: srv,
    steps,
    pages: all.filter((e) => e.type === 'PAGE_VIEW'),
    layouts: all.filter((e) => e.type === 'LAYOUT'),
    clicks: all.filter((e) => e.type === 'CLICK'),
    visibility: all.filter((e) => e.type === 'VISIBILITY'),
    start,
    end,
  };
}

function lastBefore(list, t) {
  let found = null;
  for (const x of list) {
    if (x.t > t) break;
    found = x;
  }
  return found;
}

const clock = (d) => new Date(d).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
function fmtDur(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
