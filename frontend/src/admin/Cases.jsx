import ReplayPlayer from './ReplayPlayer.jsx';
import { useEffect, useMemo, useState } from 'react';
import { Link, useOutletContext, useParams } from 'react-router';
import { get, post } from '../api/client.js';
import { Who } from './AdminLayout.jsx';
import RequestLog from './RequestLog.jsx';
import { ago } from './Risk.jsx';
import { ConfirmButton, ErrorBox } from './ui.jsx';
import { hasRole } from './useAdminAuth.js';

/**
 * A-06 케이스 목록·상세 (ADM-05·06).
 *   GET  /api/v1/admin/cases?status=&page=           목록
 *   GET  /api/v1/admin/cases/{id}                    요약·마커·연결 계정·점수 이력
 *   GET  /api/v1/admin/cases/{id}/timeline           행동 로그(MongoDB) 타임라인, 의심 구간(inWindow)
 *   POST /api/v1/admin/cases/{id}/actions            판정·조치 (REVIEWER 이상, 사유 5자 이상)
 *   POST /api/v1/admin/cases/{id}/unmask             가림 해제 (SUPERVISOR, 사유 필수, admin_action에 먼저 기록)
 *
 * 백엔드가 보이게: 마커는 규칙 코드 그대로, 의심 구간 밖 이벤트는 흐리게, 이벤트 사이 간격(intervalMs)을 그대로 적는다.
 * 조치는 한 트랜잭션(admin_action → 케이스 → risk_score → 회원 상태)이고 커밋 뒤 Redis 위험 캐시가 바로 바뀐다.
 */
const STATUS = { OPEN: 'amber', REVIEWING: 'sky', CONFIRMED: 'stop', DISMISSED: 'go' };
const VERDICTS = [['RESELLER', '되팔이'], ['MACRO', '매크로'], ['CANCEL_ABUSE', '취소 남용'], ['NORMAL', '오탐']];
const ACTIONS = { NORMAL: [['CLEAR', '해제 (점수 0, 제한 풀기)']], OTHER: [['CONFIRM', '확정만'], ['RESTRICT', '예매 제한'], ['BAN', '이용 정지']] };
const COLORS = ['var(--ink)', 'var(--stop)', 'var(--go)', '#1D4ED8', '#D97757', 'var(--amber-d)'];

export function CaseList() {
  const claims = useOutletContext();
  const [status, setStatus] = useState('');
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    get('/api/v1/admin/cases?size=50' + (status ? `&status=${status}` : '')).then(setData).catch(setError);
  }, [status]);
  return (
    <>
      <div className="head"><h1>케이스</h1><span style={{ flex: 1 }} /><Who claims={claims} /></div>
      <ErrorBox error={error} />
      <section className="panel">
        <h2>
          <span className="grow">의심 케이스 {data ? `${data.items.length}건` : ''}</span>
          <select value={status} onChange={(e) => setStatus(e.target.value)} style={{ width: 160, minHeight: 32 }} aria-label="상태">
            <option value="">모든 상태</option>
            {Object.keys(STATUS).map((s) => <option key={s}>{s}</option>)}
          </select>
        </h2>
        <table>
          <thead><tr><th>케이스</th><th>대상</th><th>발동 규칙</th><th className="n">열 때 점수</th><th className="n">지금 점수</th><th>상태</th><th>열린 시각</th></tr></thead>
          <tbody>
            {(data?.items || []).map((c) => (
              <tr key={c.caseId}>
                <td><Link className="link" to={`/admin/cases/${c.caseId}`}>C-{c.caseId}</Link></td>
                <td>{c.subjectType} <span className="faint num">{c.subjectHash.slice(0, 4)}…</span></td>
                <td><span className="st plain stop">{c.triggerType}</span></td>
                <td className="n num">{Math.round(c.scoreAtOpen)}</td>
                <td className="n num">{c.currentScore != null ? Math.round(c.currentScore) : '—'}</td>
                <td><span className={'st ' + (STATUS[c.status] || '')}>{c.status}</span>{c.verdict && <span className="faint"> {c.verdict}</span>}</td>
                <td className="mut">{ago(c.openedAt)}</td>
              </tr>
            ))}
            {data && !data.items.length && <tr><td colSpan={7} className="mut">케이스가 없어요.</td></tr>}
          </tbody>
        </table>
      </section>
    </>
  );
}

/** 왜 이 계정이 묶였나 (case_session.link_reason, 케이스를 열 때 박아 둔 값) */
const LINK = { TRIGGER: '탐지된 세션', SAME_CI: '같은 명의', SAME_DEVICE: '같은 기기', SAME_PAYMENT: '같은 카드', SAME_IP: '같은 IP', AFTER_OPEN: '연 뒤 연결' };

export default function CaseDetail() {
  const claims = useOutletContext();
  const { caseId } = useParams();
  const [d, setD] = useState(null);
  const [tl, setTl] = useState(null);
  const [error, setError] = useState(null);
  const [range, setRange] = useState(null); // 타임라인을 실제로 물은 범위 (24시간보다 길어 잘랐으면 표시)
  const load = () => {
    get(`/api/v1/admin/cases/${caseId}`)
      .then((detail) => {
        setD(detail);
        // 서버는 타임라인을 한 번에 24시간까지만 준다. 의심 구간이 더 길면 끝에서 24시간으로 잘라 묻는다
        const DAY = 24 * 3600_000;
        const to = Date.parse(detail.windowTo) + 5 * 60_000;
        const from = Math.max(Date.parse(detail.windowFrom) - 5 * 60_000, to - DAY + 1000);
        const cut = Date.parse(detail.windowFrom) - 5 * 60_000 < from;
        setRange({ cut, from: new Date(from).toISOString(), to: new Date(to).toISOString() });
        return get(`/api/v1/admin/cases/${caseId}/timeline?from=${new Date(from).toISOString()}&to=${new Date(to).toISOString()}`);
      })
      .then(setTl)
      .catch(setError);
  };
  useEffect(load, [caseId]);

  const s = d?.summary;
  const color = useMemo(() => Object.fromEntries((d?.accounts || []).map((a, i) => [a.label, COLORS[i % COLORS.length]])), [d]);

  return (
    <>
      <div className="head">
        <h1>케이스 C-{caseId}</h1>
        <span className="crumb"><Link className="link" to="/admin/risk">위험 사용자</Link> / {s ? `${s.subjectType} ${s.subjectHash.slice(0, 4)}…` : ''}</span>
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>
      <ErrorBox error={error} onClose={() => setError(null)} />
      {s && (
        <div className="kpis" style={{ gridTemplateColumns: 'repeat(4,minmax(0,1fr))' }}>
          <div className={'kpi' + (Number(s.currentScore ?? s.scoreAtOpen) >= 80 ? ' alarm' : '')}>
            <div className="l">위험 점수</div>
            <div className="n">{Math.round(s.currentScore ?? s.scoreAtOpen)}</div>
            <div className="d">열 때 {Math.round(s.scoreAtOpen)} · {s.subjectType} {s.subjectHash.slice(0, 6)}…</div>
            <span className="src">risk_score · 1분 배치</span>
          </div>
          <div className="kpi">
            <div className="l">같은 키로 묶인 계정</div>
            <div className="n">{d.accounts.length}</div>
            <div className="d">{d.accounts.map((a) => `${a.label} ${a.maskedName}${LINK[a.linkReason] ? ` (${LINK[a.linkReason]})` : ''}`).join(' · ')}</div>
          </div>
          <div className="kpi">
            <div className="l">발동 규칙</div>
            <div className="n" style={{ fontSize: 18, padding: '8px 0' }}><span className="st stop">{s.triggerType}</span></div>
            <div className="d">{s.triggerDetail || d.reasons || ''}</div>
          </div>
          <div className="kpi">
            <div className="l">상태</div>
            <div className="n" style={{ fontSize: 22, padding: '6px 0' }}><span className={'st ' + (STATUS[s.status] || '')} style={{ fontSize: 14 }}>{s.status}</span></div>
            <div className="d">{ago(s.openedAt)} 열림{s.verdict ? ` · 판정 ${s.verdict}` : ''}</div>
          </div>
        </div>
      )}
      {d?.scoreHistory?.length > 1 && <ScoreChart points={d.scoreHistory} />}
      <div className="cols">
        <Timeline tl={tl} color={color} range={range} />
        {s && (
          <section className="panel">
            <Judge caseId={caseId} summary={s} claims={claims} onDone={load} />
            <hr className="sep" />
            <Unmask caseId={caseId} claims={claims} />
            {d.markers?.length > 0 && (
              <>
                <hr className="sep" />
                <h2 style={{ fontSize: 14 }}>탐지 마커 {d.markers.length}개</h2>
                {d.markers.map((m) => (
                  <div key={m.markerId} className="row" style={{ fontSize: 13, padding: '4px 0', borderBottom: '1px solid var(--line2)' }}>
                    <span className={'st plain ' + (m.severity === 'HIGH' ? 'stop' : 'amber')}>{m.ruleCode}</span>
                    <span className="grow">{m.label}</span>
                    <span className="faint num">{time(m.at)}</span>
                  </div>
                ))}
              </>
            )}
          </section>
        )}
      </div>
      <ReplayPlayer caseId={caseId} markers={d?.markers} />
      <RequestLog filter={(r) => r.path.includes('/admin/cases')} rows={6} />
    </>
  );
}

function Timeline({ tl, color, range }) {
  const rows = useMemo(() => {
    if (!tl) return [];
    const marks = new Map();
    tl.markers.forEach((m) => marks.set(m.at, [...(marks.get(m.at) || []), m]));
    const out = [];
    let prev = null;
    for (const e of tl.events) {
      const t = Date.parse(e.ts);
      if (prev != null && t - prev > 30_000) out.push({ gap: gapText(t - prev), key: 'g' + e.ts });
      out.push({ e, marks: marks.get(e.ts) || [], key: e.ts + e.account + e.eventType });
      prev = t;
    }
    return out;
  }, [tl]);
  return (
    <section className="panel">
      <h2>
        <span className="grow">
          타임라인 <span className="mut" style={{ fontWeight: 500 }}>{tl ? `${time(tl.windowFrom)} ~ ${time(tl.windowTo)} 의심 구간 · ${tl.events.length}건` : ''}</span>
        </span>
        <a className="btn ghost sm" href="#replay">재생 ↓</a>
      </h2>
      <p className="sub">MongoDB 행동 로그. 빨간 줄이 의심 구간 안, 흐린 줄은 앞뒤 맥락이에요. {tl?.truncated ? '많아서 앞부분만 보여요.' : ''}
        {range?.cut ? ` 의심 구간이 24시간보다 길어 마지막 24시간만 보여요 (서버 조회 한도).` : ''}</p>
      {tl && !tl.events.length && <p className="mut">이 기간 행동 로그가 없어요.</p>}
      <ol className="tl">
        {rows.map((r, idx) =>
          r.gap ? (
            <li key={r.key} className="gap">{r.gap} 뒤</li>
          ) : (
            <li key={r.key} className={(r.e.inWindow ? 'hl' : 'out')} style={{ '--i': Math.min(idx, 40) }}>
              <span className="mono">{time(r.e.ts, true)}</span>
              <span className="who-c" style={{ color: color[r.e.account] }}>{r.e.account}</span>
              <span className="grow">
                {r.e.eventType}{r.e.runId ? ` ${r.e.runId}` : ''}
                {r.e.responseStatus && r.e.responseStatus >= 400 ? <span className="faint"> · {r.e.responseStatus}</span> : null}
                {r.e.intervalMs != null && r.e.intervalMs < 1000 ? <span style={{ fontSize: 12, color: 'var(--stop)', fontWeight: 700 }}> · 앞 이벤트와 {r.e.intervalMs}ms</span> : null}
              </span>
              {r.marks.map((m) => <span key={m.markerId} className={'st plain ' + (m.severity === 'HIGH' ? 'stop' : 'amber')}>{m.ruleCode}</span>)}
            </li>
          ),
        )}
      </ol>
    </section>
  );
}

function Judge({ caseId, summary, claims, onDone }) {
  const can = hasRole(claims, 'REVIEWER');
  const closed = summary.status === 'CONFIRMED' || summary.status === 'DISMISSED';
  const [verdict, setVerdict] = useState('RESELLER');
  const [action, setAction] = useState('CONFIRM');
  const [memo, setMemo] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const options = verdict === 'NORMAL' ? ACTIONS.NORMAL : ACTIONS.OTHER;
  useEffect(() => setAction(options[0][0]), [verdict]);

  const save = async () => {
    setError(null);
    try {
      setResult(await post(`/api/v1/admin/cases/${caseId}/actions`, { verdict, action, memo }));
      onDone();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <h2>판정과 조치</h2>
      {!can && <p className="sub">REVIEWER 이상만 판정할 수 있어요. 지금은 읽기만 돼요.</p>}
      {closed && <div className="alert sky">이미 판정이 끝난 케이스예요 ({summary.status}{summary.verdict ? ` · ${summary.verdict}` : ''}).</div>}
      <ErrorBox error={error} />
      {result && (
        <div className="alert sky">
          저장했어요. 케이스 {result.caseStatus}, 조치 {result.action} (admin_action #{result.actionId}), 회원 상태 바뀐 계정 {result.membersChanged}개.
          위험 캐시(Redis)도 바로 바뀌었어요.
        </div>
      )}
      <fieldset disabled={!can || closed} style={{ border: 0, padding: 0, margin: 0 }}>
        <label className="field"><span className="lbl">판정</span>
          <select value={verdict} onChange={(e) => setVerdict(e.target.value)}>
            {VERDICTS.map(([v, l]) => <option key={v} value={v}>{v} ({l})</option>)}
          </select>
        </label>
        <label className="field"><span className="lbl">조치</span>
          <select value={action} onChange={(e) => setAction(e.target.value)}>
            {options.map(([v, l]) => <option key={v} value={v}>{v} ({l})</option>)}
          </select>
        </label>
        <label className="field"><span className="lbl">사유</span>
          <textarea rows={3} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="예: 같은 CI 4계정이 0.03초 안에 동시 진입·선점" />
          <span className="hint">5자 이상. admin_action에 남아요.</span>
        </label>
        <ConfirmButton className="btn wide" confirm={`한 번 더 누르면 ${action} 저장`} onConfirm={save} disabled={memo.trim().length < 5}>판정 저장</ConfirmButton>
      </fieldset>
    </>
  );
}

function Unmask({ caseId, claims }) {
  const can = hasRole(claims, 'SUPERVISOR');
  const [memo, setMemo] = useState('');
  const [rows, setRows] = useState(null);
  const [error, setError] = useState(null);
  const go = async () => {
    setError(null);
    try {
      const r = await post(`/api/v1/admin/cases/${caseId}/unmask`, { memo });
      setRows(r.accounts);
    } catch (e) {
      setError(e);
    }
  };
  return (
    <>
      <h2 style={{ fontSize: 14 }}>개인정보 보기</h2>
      <p className="sub" style={{ margin: '-6px 0 12px' }}>감독자(SUPERVISOR)만 가능해요. 사유와 함께 먼저 기록되고, 기록이 실패하면 원문도 안 나와요. 화면을 떠나면 사라져요.</p>
      <ErrorBox error={error} />
      {rows ? (
        <table>
          <thead><tr><th>회원</th><th>이름</th><th>휴대폰</th><th>이메일</th><th>상태</th></tr></thead>
          <tbody>{rows.map((a) => <tr key={a.memberId}><td className="num">{a.memberId}</td><td>{a.name}</td><td className="num">{a.phone}</td><td>{a.email}</td><td>{a.status}</td></tr>)}</tbody>
        </table>
      ) : (
        <fieldset disabled={!can} style={{ border: 0, padding: 0, margin: 0 }}>
          <label className="field"><span className="lbl">해제 사유</span>
            <input value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="예: 경찰 수사 협조 요청 2027-0042" />
          </label>
          <ConfirmButton className="btn ghost wide" confirm="한 번 더 누르면 기록하고 보여 줘요" onConfirm={go} disabled={memo.trim().length < 5}>가림 해제</ConfirmButton>
        </fieldset>
      )}
    </>
  );
}

function ScoreChart({ points }) {
  const W = 1000;
  const H = 80;
  const t0 = Date.parse(points[0].at);
  const t1 = Date.parse(points[points.length - 1].at) || t0 + 1;
  const x = (t) => 30 + ((Date.parse(t) - t0) / Math.max(1, t1 - t0)) * (W - 40);
  const y = (s) => H - (Number(s) / 100) * (H - 6);
  return (
    <section className="panel">
      <h2>점수 변화 <span className="mut" style={{ fontWeight: 500 }}>risk_score_history · {points.length}번 계산</span></h2>
      <svg viewBox={`0 -4 ${W} ${H + 24}`} style={{ width: '100%', height: 'auto', display: 'block' }} role="img" aria-label={`점수 ${Math.round(points[0].score)}에서 ${Math.round(points[points.length - 1].score)}로`}>
        {[0, 60, 80, 100].map((v) => (
          <g key={v}>
            <line x1="30" x2={W} y1={y(v)} y2={y(v)} stroke={v === 80 ? 'var(--stop-t)' : 'var(--line2)'} />
            <text x="24" y={y(v) + 4} textAnchor="end" fontSize="11" fill="var(--faint)">{v}</text>
          </g>
        ))}
        <polyline fill="none" stroke="var(--stop)" strokeWidth="2.5" points={points.map((p) => `${x(p.at)},${y(p.score)}`).join(' ')} />
        {points.map((p) => <circle key={p.at} cx={x(p.at)} cy={y(p.score)} r="3.5" fill="var(--stop)" />)}
        <text x="30" y={H + 18} fontSize="12" fill="var(--mut)">{time(points[0].at)}</text>
        <text x={W} y={H + 18} fontSize="12" fill="var(--mut)" textAnchor="end">{time(points[points.length - 1].at)}</text>
      </svg>
    </section>
  );
}

/** Instant → 서울 시각 "10:00:00" (ms=true면 ".120"까지) */
function time(t, ms) {
  if (!t) return '';
  const d = new Date(t);
  const s = d.toLocaleTimeString('en-GB', { timeZone: 'Asia/Seoul' });
  return ms ? `${s}.${String(d.getMilliseconds()).padStart(3, '0')}` : s;
}
function gapText(ms) {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}초`;
  if (s < 3600) return `${Math.floor(s / 60)}분 ${s % 60 ? `${s % 60}초` : ''}`.trim();
  return `${Math.floor(s / 3600)}시간 ${Math.floor((s % 3600) / 60)}분`;
}
