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

  const screen = lastBefore(tl.pages, t)?.screen || '—';
  const hidden = lastBefore(tl.visibility, t)?.target === 'hidden';
  const trail = tl.points.filter((p) => p.t <= t && p.t >= t - TRAIL_MS);
  const cursor = lastBefore(tl.points, t) || lastBefore(tl.clicks, t);
  const clicks = tl.clicks.filter((c) => c.t <= t && c.t >= t - CLICK_MS);
  const scroll = lastBefore(tl.scrolls, t)?.y ?? 0;
  const server = tl.server.filter((s) => s.t <= t).slice(-6);
  const recent = tl.all.filter((e) => e.t <= t).slice(-6);
  const span = Math.max(1, tl.end - tl.start);
  const caseMarks = markers.map((m) => Date.parse(m.at)).filter((x) => x >= tl.start && x <= tl.end);

  return (
    <div className="cols" style={{ alignItems: 'start' }}>
      <div>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`녹화 화면 ${screen}`}
          style={{ width: '100%', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: 8, display: 'block' }}>
          <rect x="0" y="0" width={W} height="44" fill="var(--line2)" />
          <text x="18" y="29" fontSize="20" fill="var(--ink)" fontWeight="700">{screen}</text>
          <text x={W - 18} y="29" fontSize="16" fill="var(--mut)" textAnchor="end">{clock(new Date(t))}</text>
          <rect x={W - 10} y={44 + (scroll / 1000) * (H - 104)} width="6" height="60" rx="3" fill="var(--line)" />
          {trail.length > 1 && (
            <polyline points={trail.map((p) => `${p.x},${(p.y / 1000) * H}`).join(' ')} fill="none" stroke="var(--ink2)" strokeWidth="3" strokeOpacity="0.6" strokeLinejoin="round" />
          )}
          {clicks.map((c) => {
            const k = 1 - (t - c.t) / CLICK_MS;
            return (
              <g key={c.seq}>
                <circle cx={c.x} cy={(c.y / 1000) * H} r={10 + (1 - k) * 26} fill="none" stroke="var(--stop)" strokeWidth="3" opacity={k} />
                {c.target && <text x={c.x + 16} y={(c.y / 1000) * H - 14} fontSize="16" fill="var(--stop)" opacity={k}>{c.target}</text>}
              </g>
            );
          })}
          {cursor && cursor.x != null && (
            <path d={`M${cursor.x},${(cursor.y / 1000) * H} l0,22 l6,-6 l10,0 z`} fill="var(--ink)" stroke="var(--card)" strokeWidth="1.5" />
          )}
          {hidden && (
            <g>
              <rect x="0" y="44" width={W} height={H - 44} fill="var(--ink)" opacity="0.35" />
              <text x={W / 2} y={H / 2} fontSize="26" fill="var(--card)" textAnchor="middle" fontWeight="700">탭이 숨겨짐</text>
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
      </div>
      <div>
        <h2 style={{ fontSize: 14 }}>서버에서 일어난 일</h2>
        {!server.length && <p className="mut" style={{ fontSize: 13 }}>아직 없음</p>}
        {server.map((s, i) => (
          <div key={i} className="row" style={{ fontSize: 13, padding: '3px 0', borderBottom: '1px solid var(--line2)' }}>
            <span className="faint num">{clock(new Date(s.t))}</span>
            <span className="grow">{s.event_type}{s.run_id ? ` · 운행 ${s.run_id}` : ''}</span>
            <span className={'st plain ' + (s.response_status >= 400 ? 'stop' : 'go')}>{s.response_status}</span>
          </div>
        ))}
        <h2 style={{ fontSize: 14, marginTop: 14 }}>화면 이벤트</h2>
        {recent.map((e) => (
          <div key={e.seq} className="row" style={{ fontSize: 13, padding: '3px 0', borderBottom: '1px solid var(--line2)' }}>
            <span className="faint num">{clock(new Date(e.t))}</span>
            <span className="grow">{e.type}{e.target ? ` · ${e.target}` : ''}{e.input_masked ? ` · ${e.input_masked}` : ''}</span>
          </div>
        ))}
        <p className="mut" style={{ fontSize: 12, marginTop: 10 }}>
          입력값은 녹화하지 않아요 (길이만). 마우스 궤적은 FULL 녹화(위험 점수 70 이상 또는 표본 10%)에서만 남아요.
        </p>
      </div>
    </div>
  );
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
  return {
    all,
    points,
    server: srv,
    pages: all.filter((e) => e.type === 'PAGE_VIEW'),
    clicks: all.filter((e) => e.type === 'CLICK'),
    scrolls: all.filter((e) => e.type === 'SCROLL'),
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
