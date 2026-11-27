import { useEffect, useRef, useState } from 'react';
import { useOutletContext, useSearchParams } from 'react-router';
import { get, post, subscribeRequestLog } from '../api/client.js';
import { addDays, md, seoulMs, seoulToday } from '../lib/time.js';
import { usePolling } from '../hooks/usePolling.js';
import { Who } from './AdminLayout.jsx';
import Icon from './Icon.jsx';
import RequestLog from './RequestLog.jsx';
import { ConfirmButton, ErrorBox } from './ui.jsx';
import { hasRole } from './useAdminAuth.js';

/**
 * A-04 실시간 예매 현황 (ADM-03). GET /api/v1/admin/dashboard/runs/{runId}와 그 분당 이력(/minutes)을 10초마다 부른다.
 *
 * 관리자 화면은 "백엔드가 지금 무엇을 하고 있는지"가 보이게 만든다.
 *  - 숫자마다 어디서 읽었는지(Redis 캐시 / Redis 대기열 키 / Oracle 집계)와 얼마나 늦을 수 있는지 적는다
 *  - 서버가 값을 읽은 시각(asOf), 응답 시간, X-Trace-Id를 보여 준다 → traceId로 서버 로그를 바로 찾는다
 *  - 최근 요청 기록 표 (이 화면이 보낸 요청 그대로)
 */
const POLL_MS = 10_000;
const HISTORY_MIN = 30; // 그래프 칸 수 = 최근 30분 (서버 최대 60)
const GRAFANA = import.meta.env.VITE_GRAFANA_URL; // 비우면 버튼을 숨긴다 (Grafana는 127.0.0.1에만 열려 있음)

export default function Dashboard() {
  const claims = useOutletContext();
  const [params, setParams] = useSearchParams();
  const { runs, error: runsError } = useOnSaleRuns();
  const runId = params.get('run') || (runs.length ? String(runs[0].runId) : null);

  const { data, error, refreshNow } = usePolling(() => (runId ? readDashboard(runId) : Promise.resolve(null)), POLL_MS, [
    runId,
  ]);
  const lastReq = useLastRequest(runId);

  return (
    <>
      <div className="head">
        <h1>실시간 예매 현황</h1>
        <select
          aria-label="운행 고르기"
          style={{ width: 'auto', minHeight: 34, marginLeft: 8 }}
          value={runId || ''}
          onChange={(e) => setParams({ run: e.target.value })}
          disabled={!runs.length}
        >
          {!runs.length && <option value="">판매 중인 운행 없음</option>}
          {runs.map((r) => (
            <option key={r.runId} value={r.runId}>{r.label}</option>
          ))}
        </select>
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>

      <LiveLine data={data} error={error} lastReq={lastReq} onRefresh={refreshNow} />

      {runsError && <div className="alert stop">운행 목록을 못 읽었어요: {runsError.message}</div>}
      {!runId && !runsError && (
        <div className="alert amber">
          판매 중(ON_SALE)인 운행이 없어요. 운행·행사 화면에서 판매를 열거나, 시드의 운행 9001·9002를 쓰세요.
        </div>
      )}

      {data && <Kpis d={data} />}
      {data && <RateChart history={data.history} rates={data.rates} windowSec={data.windowSec} />}

      <div className="cols">
        <RequestLog />
        <section className="panel">
          <h2>결제 결과 모름 · 강제 취소</h2>
          <div className="soon-box" style={{ marginBottom: 16 }}>
            결과 모름 <b>건수</b>는 위 카드에 나와요. 목록과 결과 입력(ADM-09, 길동현)은 API가 생기면 이 자리에 붙어요.
          </div>
          <ForceCancel claims={claims} onDone={refreshNow} />
        </section>
      </div>
    </>
  );
}

/**
 * ADM-07 예약 강제 취소 (강다인 API). POST /api/v1/admin/reservations/{id}/cancel {"memo"}.
 * 확정 예약만, 수수료 0·전액 환불, admin_action에 사유가 남는다. SUPERVISOR만 — 서버가 막고 화면은 버튼만 끈다.
 */
const CANCEL_HINT = {
  ACTION_REASON_REQUIRED: '사유를 5자 이상 적어 주세요. 아무것도 바뀌지 않았어요.',
  ADMIN_FORBIDDEN: 'SUPERVISOR만 강제 취소할 수 있어요.',
  RESERVATION_NOT_FOUND: '없는 예약 번호예요.',
  RESERVATION_NOT_CONFIRMED: '확정된 예약이 아니거나 이미 취소됐어요. 결제 전 예약은 기한이 지나면 스스로 풀려요.',
  CANCEL_NOT_ALLOWED: '지금은 수수료를 계산할 수 없는 때예요(출발 뒤 한도·도착 뒤). 위약금 면제로 다시 취소해 주세요.',
};

function ForceCancel({ claims, onDone }) {
  const can = hasRole(claims, 'SUPERVISOR');
  const [rv, setRv] = useState('');
  const [memo, setMemo] = useState('');
  const [waive, setWaive] = useState(true); // 기본 면제 (오탐·운영 사유). 매크로로 판정했을 때만 끔
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  // 시안의 "R-501233"처럼 붙여 넣어도 되게 숫자만 남긴다 (API 경로는 숫자 ID)
  const id = rv.replace(/\D/g, '');
  const ready = can && id && memo.trim().length >= 5;

  const go = async () => {
    setError(null);
    setResult(null);
    try {
      setResult(await post(`/api/v1/admin/reservations/${id}/cancel`, { memo: memo.trim(), waiveFee: waive }));
      setMemo('');
      setWaive(true);
      onDone?.();
    } catch (e) {
      const hint = CANCEL_HINT[e.code];
      setError(hint ? Object.assign(e, { message: hint }) : e);
    }
  };

  return (
    <>
      <h2 style={{ fontSize: 14.5 }}>예약 강제 취소</h2>
      <p className="sub">확정된 예약을 취소해요. 기본은 수수료 면제·전액 환불이고, 매크로로 판정했으면 면제를 끄고 사용자 취소와 같은 수수료(단체는 단체 위약금)를 받아요. 사유는 admin_action에 남아요.</p>
      {!can && <div className="alert amber">지금 권한({claims?.adm})으로는 강제 취소할 수 없어요. SUPERVISOR만 가능해요.</div>}
      <ErrorBox error={error} onClose={() => setError(null)} />
      {result && (
        <div className="alert sky" role="status">
          예약 {result.cancel.reservationId}을 취소했어요 · {result.cancel.seatCount}석 · 환불{' '}
          <b>{Number(result.cancel.refundAmount).toLocaleString()}원</b> ({result.cancel.refundStatus}) · 수수료{' '}
          {Number(result.cancel.cancelFee).toLocaleString()}원 · admin_action #{result.actionId}
          {result.cancel.refundStatus === 'FAILED' && <div>환불이 실패했어요. 결제 담당(길동현)에게 예약 번호를 알려 주세요.</div>}
        </div>
      )}
      <fieldset disabled={!can} style={{ border: 0, padding: 0, margin: 0 }}>
        <label className="field">
          <span className="lbl">예약 번호</span>
          <input value={rv} onChange={(e) => setRv(e.target.value)} inputMode="numeric" placeholder="예: 501233" />
        </label>
        <label className="field">
          <span className="lbl">사유</span>
          <textarea rows={2} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="예: 되팔이 확정 케이스 C-204 조치" />
          <span className="hint">5자 이상 ({memo.trim().length}자)</span>
        </label>
        <label className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <input type="checkbox" checked={waive} onChange={(e) => setWaive(e.target.checked)} />
          <span>수수료·위약금 면제 (오탐·운영 사유)</span>
        </label>
        <ConfirmButton className="btn wide" confirm={`한 번 더 누르면 예약 ${id || ''} 강제 취소`} onConfirm={go} disabled={!ready}>
          강제 취소
        </ConfirmButton>
      </fieldset>
    </>
  );
}

// ------------------------------------------------------------ 데이터

/** 판매 중 운행 ID(현황 API) + 화면에 쓸 이름(운행 관리 API). 이름을 못 읽어도 ID로는 보여 준다 */
function useOnSaleRuns() {
  const [state, setState] = useState({ runs: [], error: null });
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const ids = await get('/api/v1/admin/dashboard/runs');
        let meta = [];
        try {
          const today = seoulToday();
          meta = await get(`/api/v1/admin/runs?status=ON_SALE&from=${addDays(today, -1)}&to=${addDays(today, 70)}`);
        } catch {
          /* 이름만 못 붙임 */
        }
        const byId = new Map(meta.map((m) => [m.runId, m]));
        const runs = ids.map((id) => ({ runId: id, label: runLabel(id, byId.get(id)) }));
        if (alive) setState({ runs, error: null });
      } catch (error) {
        if (alive) setState({ runs: [], error });
      }
    })();
    return () => {
      alive = false;
    };
  }, []);
  return state;
}

function runLabel(id, m) {
  if (!m) return `운행 ${id}`;
  const dep = m.departureAt ? ` ${m.departureAt.slice(11, 16)}` : '';
  const route = m.origin && m.dest ? ` ${m.origin}→${m.dest}` : '';
  return `${m.trainNo} 열차, ${md(m.runDate)}${dep}${route} · #${id}`;
}

/**
 * 현황 + 분당 이력을 한 번에 읽는다. 이력은 그래프 하나만 쓰므로, 실패해도 숫자 카드는 그대로 보이게
 * 이력만 null로 둔다 (그래프 자리에 안내 문구).
 */
async function readDashboard(runId) {
  const [now, history] = await Promise.all([
    get(`/api/v1/admin/dashboard/runs/${runId}`),
    get(`/api/v1/admin/dashboard/runs/${runId}/minutes?minutes=${HISTORY_MIN}`).catch(() => null),
  ]);
  return { ...now, history };
}

/** 이 운행 현황 API의 마지막 응답 (응답 시간·traceId 표시용) */
function useLastRequest(runId) {
  const [last, setLast] = useState(null);
  useEffect(
    () =>
      subscribeRequestLog((log) => {
        setLast(log.find((r) => r.path === `/api/v1/admin/dashboard/runs/${runId}`) || null);
      }),
    [runId],
  );
  return last;
}

// ------------------------------------------------------------ 화면 조각

function LiveLine({ data, error, lastReq, onRefresh }) {
  const now = useNow(1000);
  const age = data ? Math.max(0, Math.round((now - seoulMs(data.asOf)) / 1000)) : null;
  return (
    <div className="live" aria-live="polite">
      {error ? <span className="st stop">응답 실패</span> : <span className="st go">실시간</span>}
      <span>10초마다 갱신</span>
      {data && (
        <span>
          서버가 읽은 시각 <b className="num">{data.asOf.slice(11, 19)}</b> ({age}초 전)
        </span>
      )}
      {data && <span className={`st plain ${data.status === 'ON_SALE' ? 'go' : 'amber'}`}>{data.status}</span>}
      {lastReq && (
        <span>
          응답 <b className="num">{lastReq.ms}ms</b> · traceId <code title={lastReq.traceId || ''}>{lastReq.traceId ? lastReq.traceId.slice(0, 12) + '…' : '-'}</code>
        </span>
      )}
      {error && <span style={{ color: 'var(--stop)' }}>{error.code}: {error.message}</span>}
      <span className="grow" />
      <button type="button" className="btn ghost sm" onClick={onRefresh}>
        <Icon name="refresh" />
        지금 다시 읽기
      </button>
      {GRAFANA && (
        <a className="btn ghost sm" href={GRAFANA} target="_blank" rel="noreferrer">
          <Icon name="external" />
          Grafana
        </a>
      )}
    </div>
  );
}

function Kpis({ d }) {
  const { seats, queue, payments, windowSec } = d;
  const pct = seats.total ? Math.round((seats.sold / seats.total) * 100) : 0;
  const drainSec = queue.admitPerSec > 0 ? Math.ceil(queue.waiting / queue.admitPerSec) : null;
  return (
    <div className="kpis">
      <Kpi label="판매" n={seats.sold} d={`${seats.total.toLocaleString()}석 중 ${pct}%`} src="Redis seatmap 캐시 · ≤1초 늦음" />
      <Kpi label="선점 중 (결제 대기)" n={seats.held} d={seats.groupQuota ? `seat:hold 키가 살아 있는 좌석 · 단체 할당석 ${seats.groupQuota}석은 따로` : 'seat:hold 키가 살아 있는 좌석'} src="Redis seatmap 캐시" />
      <Kpi label="남은 좌석" n={seats.remaining} d={seats.groupQuota ? '전체 − 판매 − 선점 − 단체 할당석' : '전체 − 판매 − 선점'} src="계산값" />
      <Kpi
        label="대기열"
        n={queue.waiting}
        d={drainSec != null ? `입장 중 ${queue.active} · 소진 ${fmtSec(drainSec)}` : `입장 중 ${queue.active}`}
        src={`Redis ZSET queue:${d.runId}`}
      />
      <Kpi label="입장 속도" n={queue.admitPerSec} unit="/초" d="입장 스케줄러가 매번 기록" src={`Redis queue:throughput:${d.runId}`} />
      <Kpi
        label="결제 결과 모름"
        n={payments.unknown}
        d="확인 시각 지난 결제 · 전체 운행"
        src="Oracle payment"
        alarm={payments.unknown > 0}
      />
    </div>
  );
}

/** 값이 바뀌면 카드가 노랗게 깜빡이고 숫자가 올라온다 → "방금 서버 값이 바뀌었다"가 보인다 */
function Kpi({ label, n, unit, d, src, alarm }) {
  const prev = useRef(n);
  const [bump, setBump] = useState(0);
  useEffect(() => {
    if (prev.current !== n) setBump((b) => b + 1);
    prev.current = n;
  }, [n]);
  return (
    <div key={bump} className={'kpi' + (alarm ? ' alarm' : '') + (bump ? ' flash' : '')}>
      <div className="l">{label}</div>
      <div className={'n' + (bump ? ' up' : '')}>
        {Number(n).toLocaleString()}
        {unit && <span style={{ fontSize: 18 }}>{unit}</span>}
      </div>
      <div className="d">{d}</div>
      <span className="src">{src}</span>
    </div>
  );
}

// 만료는 오방색 파랑(토큰 --saek의 청색)을 쓴다. 앞의 세 색과 겹치지 않고 팀 팔레트 안에 있다.
// 막대는 늘 이 순서로 서고 범례·툴팁이 이름을 달아 주므로, 색만으로 구분하지 않아도 읽힌다.
// 취소는 빗금도 친다: 결제(초록 --go)와 취소(빨강 --stop)는 적록색약에서 거의 같은 색이다 (ΔE 4.2, 권장 8 이상).
// 팔레트는 팀 공용이라 색을 바꾸지 않고 무늬로 가른다 (2026-11-27)
const SERIES = [
  ['reservations', '예약', 'var(--ink)'],
  ['payments', '결제', 'var(--go)'],
  ['cancels', '취소', 'var(--stop)', true],
  ['expires', '만료', '#1D4ED8'],
];
const HATCH = 'repeating-linear-gradient(135deg, var(--stop) 0 2px, #fff 2px 3.5px)';

/**
 * 분 경계로 자른 막대(서버 /minutes). 화면을 열자마자 지난 30분이 보이고, 마지막 칸은 진행 중인 분이라
 * 10초마다 자라며 흐리게 그린다. 범례 숫자는 카드와 같은 "최근 60초" 값이다.
 */
function RateChart({ history, rates, windowSec }) {
  const W = 1000;
  const H = 120;
  const rows = history?.minutes || [];
  const max = Math.max(10, ...rows.flatMap((h) => SERIES.map(([k]) => h[k])));
  const top = niceCeil(max);
  const slot = (W - 40) / Math.max(rows.length, 1);
  const bw = Math.max(2, (slot - 6) / SERIES.length);
  const y = (v) => H - (v / top) * (H - 8);
  const label = rows.length
    ? `최근 ${rows.length}분 분당 예약·결제·취소·만료. 최근 ${windowSec}초 예약 ${rates.reservations}건, `
      + `결제 ${rates.payments}건, 취소 ${rates.cancels}건, 만료 ${rates.expires}건`
    : '분당 이력을 못 읽음';

  return (
    <section className="panel">
      <h2>
        <span className="grow">분당 예약·결제·취소·만료</span>
        <span className="row" style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--mut)', gap: 14 }}>
          {SERIES.map(([k, name, color, hatch]) => (
            <span key={k}>
              <i className="dot" style={{ background: hatch ? HATCH : color }} />
              {name} <b className="num">{rates[k]}</b>
            </span>
          ))}
        </span>
      </h2>
      <p className="sub">
        막대 한 칸 = 1분 (Oracle 집계, 캐시 없음) · 최근 {rows.length || HISTORY_MIN}분 · 마지막 흐린 칸은 진행 중인 분 ·
        범례 숫자는 최근 {windowSec}초 · <b>만료</b>는 결제로 이어지지 못하고 풀린 선점
      </p>
      {!rows.length ? (
        <div className="soon-box">분당 이력을 못 읽었어요. 숫자 카드는 그대로 갱신돼요.</div>
      ) : (
        <svg viewBox={`0 -8 ${W} ${H + 30}`} style={{ width: '100%', height: 'auto', display: 'block' }} role="img" aria-label={label}>
          <defs>
            <pattern id="hatch-stop" width="5" height="5" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <rect width="5" height="5" fill="var(--stop)" />
              <line x1="0" y1="0" x2="0" y2="5" stroke="#fff" strokeWidth="1.6" />
            </pattern>
          </defs>
          {[0, 0.5, 1].map((f) => (
            <g key={f}>
              <line x1="32" x2={W} y1={y(top * f)} y2={y(top * f)} stroke="var(--line2)" />
              <text x="24" y={y(top * f) + 4} textAnchor="end" fontSize="11" fill="var(--faint)">{Math.round(top * f)}</text>
            </g>
          ))}
          {rows.map((h, i) => {
            const x0 = 40 + i * slot;
            const live = i === rows.length - 1;
            return (
              <g key={h.minute} opacity={live ? 0.5 : 1}>
                <title>{`${h.minute.slice(11, 16)}${live ? ' (진행 중)' : ''} · 예약 ${h.reservations} · 결제 ${h.payments} · 취소 ${h.cancels} · 만료 ${h.expires}`}</title>
                {SERIES.map(([k, , color, hatch], j) => (
                  <rect key={k} className="chartbar" x={x0 + j * bw} y={y(h[k])} width={bw - 1} height={Math.max(1, H - y(h[k]))} rx="2" fill={hatch ? 'url(#hatch-stop)' : color} />
                ))}
                {((Number(h.minute.slice(14, 16)) % 5 === 0 && i < rows.length - 2) || live) && (
                  <text x={x0 + (bw * SERIES.length) / 2} y={H + 18} textAnchor="middle" fontSize="12" fill="var(--mut)">{h.minute.slice(11, 16)}</text>
                )}
              </g>
            );
          })}
        </svg>
      )}
    </section>
  );
}

// ------------------------------------------------------------ 작은 도우미

function useNow(ms) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

const fmtSec = (s) => (s >= 60 ? `${Math.floor(s / 60)}분 ${s % 60}초` : `${s}초`);
function niceCeil(v) {
  const p = 10 ** Math.floor(Math.log10(v));
  return Math.ceil(v / p) * p;
}
