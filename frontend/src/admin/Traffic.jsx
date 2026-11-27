import { useEffect, useRef, useState } from 'react';
import { useOutletContext, useSearchParams } from 'react-router';
import { get } from '../api/client.js';
import { usePolling } from '../hooks/usePolling.js';
import { Who } from './AdminLayout.jsx';
import FlowMap from './FlowMap.jsx';
import Icon from './Icon.jsx';

/**
 * A-10 트래픽 관제. 서비스 전체에 지금 얼마나 몰리고, 어디가 느리거나 깨지는지 한 화면에.
 *   GET /api/v1/admin/traffic?range=30m|1h|6h|24h   KPI·상태·그래프·많이 불린 API (10초마다)
 *   GET /api/v1/admin/traffic/flow                  실시간 흐름도 (2초마다)
 *
 * 값은 서버가 Prometheus(5초 수집)에서 고정 쿼리로 읽는다 — Grafana "제자리 · 앱 개요"와 같은 지표를 관리자 로그인만으로 본다.
 * 순서는 큰 회사 관제 화면의 흔한 순서: ① 지금 괜찮은가(상태 띠) ② 얼마나 몰리나(흐름도·KPI) ③ 어디가 병목인가(자원·그래프·API 표)
 */
const RANGES = [['30m', '30분'], ['1h', '1시간'], ['6h', '6시간'], ['24h', '24시간']];
const PALETTE = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300']; // dataviz 검증 (밝은 바탕 #FFFDF8)
const GRAFANA = import.meta.env.VITE_GRAFANA_URL;
const LEVEL = { OK: ['go', '정상'], WARN: ['amber', '주의'], CRIT: ['stop', '위험'], UNKNOWN: ['sky', '확인 불가'] };

const fmt = (v, d = 1) =>
  v == null ? '—' : Math.abs(v) >= 100 ? Math.round(v).toLocaleString() : Math.abs(v) >= 10 ? v.toFixed(0) : v.toFixed(d);
const hhmm = (s, withSec) =>
  new Date(s * 1000).toLocaleTimeString('ko-KR', {
    timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: withSec ? '2-digit' : undefined, hour12: false,
  });

/** 마지막으로 값을 받은 시각 (이 브라우저 시계). 서버 값이 늦게 오거나 끊겼는지 보려고 */
function useReceivedAt(data) {
  const [at, setAt] = useState(null);
  useEffect(() => {
    if (data) setAt(Date.now());
  }, [data]);
  return at;
}

function useNow(ms) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

/** 브라우저 탭 제목에 상태를 붙인다 — 다른 탭을 보고 있어도 "위험"이 보이게 (관제실 화면의 흔한 장치) */
function useTabTitle(level) {
  useEffect(() => {
    const before = document.title;
    return () => (document.title = before);
  }, []);
  useEffect(() => {
    const mark = { CRIT: '● 위험', WARN: '● 주의', OK: '정상', UNKNOWN: '확인 불가' }[level];
    document.title = (mark ? mark + ' · ' : '') + '트래픽 관제 · 제자리';
  }, [level]);
}

export default function Traffic() {
  const claims = useOutletContext();
  const [params, setParams] = useSearchParams();
  const range = RANGES.some(([k]) => k === params.get('range')) ? params.get('range') : '1h';
  const view = usePolling(() => get(`/api/v1/admin/traffic?range=${range}`), 10_000, [range]);
  const flow = usePolling(() => get('/api/v1/admin/traffic/flow'), 2_000, []);
  const v = view.data;
  const now = useNow(1000);
  const viewAt = useReceivedAt(view.data);
  const flowAt = useReceivedAt(flow.data);
  // 폴링 간격의 2.5배 넘게 새 값이 없으면 "끊김" — 옛 값을 지금 값처럼 보여 주지 않는다
  const viewStale = viewAt != null && now - viewAt > 25_000;
  const flowStale = flowAt != null && now - flowAt > 6_000;
  useTabTitle(viewStale ? 'UNKNOWN' : v?.health?.level);
  const page = useRef(null);
  const [full, setFull] = useState(false);
  useEffect(() => {
    const on = () => setFull(document.fullscreenElement === page.current);
    document.addEventListener('fullscreenchange', on);
    return () => document.removeEventListener('fullscreenchange', on);
  }, []);
  const toggleFull = () =>
    document.fullscreenElement ? document.exitFullscreen?.() : page.current?.requestFullscreen?.().catch(() => {});

  return (
    <div ref={page} className={'tr-page' + (full ? ' full' : '')}>
      <div className="head">
        <h1>트래픽 관제</h1>
        <div className="tr-seg" role="group" aria-label="기간">
          {RANGES.map(([k, l]) => (
            <button key={k} type="button" aria-pressed={k === range} onClick={() => setParams({ range: k })}>
              {l}
            </button>
          ))}
        </div>
        {GRAFANA && (
          <a className="btn ghost sm" href={GRAFANA} target="_blank" rel="noreferrer">
            <Icon name="external" /> Grafana
          </a>
        )}
        <button type="button" className="btn ghost sm" onClick={toggleFull} aria-pressed={full}>
          <Icon name="expand" /> {full ? '나가기' : '크게 보기'}
        </button>
        <span style={{ flex: 1 }} />
        {!full && <Who claims={claims} />}
      </div>

      <HealthBar v={v} error={view.error} stale={viewStale} age={viewAt ? Math.round((now - viewAt) / 1000) : null} />
      <FlowMap flow={flow.data} error={flow.error} stale={flowStale} age={flowAt ? Math.round((now - flowAt) / 1000) : null} />
      {v?.available && (
        <div className={viewStale ? 'tr-stale' : undefined}>
          <Kpis n={v.now} charts={v.charts} />
          {v.pipeline && <DataLoss p={v.pipeline} />}
          <Meters n={v.now} />
          <div className="tr-grid">
            {v.charts.map((c) => (
              <LineChart key={c.key} chart={c} t={v.t} step={v.stepSec} restarts={v.restarts} wide={c.key === 'total'} />
            ))}
          </div>
          <Endpoints rows={v.endpoints} />
          <p className="tr-foot faint">
            Prometheus가 5초마다 앱에서 거둔 값을 서버가 고정 쿼리로 읽어 보여 줘요. 그래프 값은 {v.stepSec}초 간격 평균이라
            실제보다 5~{Math.max(30, v.stepSec)}초 늦어요. 이 화면 자신의 요청과 actuator(수집)는 세지 않아요.
          </p>
        </div>
      )}
    </div>
  );
}

function HealthBar({ v, error, stale, age }) {
  if (error && !v) return <div className="alert stop">관제 값을 못 읽었어요: {error.message}</div>;
  if (!v) return <div className="tr-health sky"><span className="st sky">불러오는 중</span></div>;
  if (stale) {
    return (
      <div className="tr-health sky" role="status">
        <span className="st sky">연결 끊김</span>
        <span className="tr-reasons">
          <span>{age}초째 새 값이 안 와요{error ? ` (${error.message})` : ''}. 아래 숫자는 마지막으로 받은 값이에요</span>
        </span>
      </div>
    );
  }
  const lv = v.health.level;
  const [cls, label] = LEVEL[lv] || LEVEL.UNKNOWN;
  const reasons = v.available ? v.health.reasons : [{ level: 'CRIT', text: v.reason }];
  return (
    <div className={`tr-health ${cls}`} role="status" aria-live="polite">
      <span className={`st ${cls}`}>{label}</span>
      <span className="tr-reasons">
        {reasons.length === 0 && <span>모든 지표가 정상 범위예요</span>}
        {reasons.map((r) => (
          <span key={r.metric || r.text} className={`tr-reason ${r.level.toLowerCase()}`}>
            <b>{r.level === 'CRIT' ? '위험' : r.level === 'WARN' ? '주의' : '알림'}</b> {r.text}
          </span>
        ))}
      </span>
      <span className="faint tr-at">{hhmm(v.at, true)} 기준{age != null ? ` · ${age}초 전 받음` : ''}</span>
    </div>
  );
}

function Kpis({ n, charts }) {
  const line = (key, i) => charts.find((c) => c.key === key)?.lines[i]?.v;
  const surgeText = n.surge != null ? `평소의 ${fmt(n.surge)}배 · 최고 ${fmt(n.peakRps)}` : `최고 ${fmt(n.peakRps)} · 평소 값 쌓는 중`;
  const tiles = [
    ['초당 요청', fmt(n.rps), surgeText, n.surge >= 3 ? 'surge' : false, line('total', 0)],
    ['응답 p95', n.p95Ms == null ? '—' : `${fmt(n.p95Ms, 0)}ms`, `p50 ${fmt(n.p50Ms, 0)} · p99 ${fmt(n.p99Ms, 0)}ms`, n.p95Ms >= 1000,
      line('latency', 1)],
    ['5xx 오류', n.errorPct == null ? '—' : `${fmt(n.errorPct, 2)}%`, `4xx ${fmt(n.clientErrorPct)}% · 429 ${fmt(n.rejectPct)}%`,
      n.errorPct >= 1, line('errors', 0)],
    ['대기열', fmt(n.queueWaiting, 0), `입장 ${fmt(n.queueActive, 0)}명`, false, line('queue', 0)],
    ['예약 성공/초', fmt(n.reservePerSec), `결제 ${fmt(n.payPerSec)}/초 · 409 충돌 ${fmt(n.conflictPerSec)}/초`, false,
      line('business', 0)],
    ['앱 서버', `${n.instancesUp}/${n.instancesTotal}`, n.instancesUp < n.instancesTotal ? '응답 없는 앱 있음' : '모두 응답',
      n.instancesUp < n.instancesTotal, null],
  ];
  return (
    <div className="kpis tr-kpis">
      {tiles.map(([l, v, d, bad, spark]) => (
        <div key={l} className={'kpi' + (bad === 'surge' ? ' surge' : bad ? ' alarm' : '')}>
          <div className="l">{l}</div>
          <div className="n">{v}</div>
          <div className="d">{d}</div>
          {spark && <Spark v={spark} />}
        </div>
      ))}
    </div>
  );
}

/** KPI 아래 작은 추세선 (그래프 기간 전체). 축·숫자 없이 모양만 — 값은 위 큰 숫자 */
function Spark({ v }) {
  const pts = v.map((y, i) => [i, y]).filter(([, y]) => y != null);
  if (pts.length < 2) return <div className="tr-spark" />;
  const max = Math.max(...pts.map(([, y]) => y)) || 1;
  const W = 100, H = 24;
  const d = pts.map(([i, y], k) => `${k ? 'L' : 'M'}${((i / (v.length - 1)) * W).toFixed(1)} ${(H - 2 - (y / max) * (H - 4)).toFixed(1)}`).join('');
  return (
    <svg className="tr-spark" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
      <path d={d + `L${W} ${H}L0 ${H}Z`} className="a" />
      <path d={d} className="l" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/** 자원 게이지: 막대 + 주의·위험 눈금 (서버 TrafficService.health와 같은 문턱) */
function Meters({ n }) {
  const rows = [
    ['CPU', n.cpuPct, 80, 95],
    ['힙 메모리', n.heapPct, 85, 95],
    ['DB 커넥션', n.dbPoolPct, 80, 95, n.dbPending ? `대기 ${fmt(n.dbPending, 0)}` : null],
    ['Tomcat 스레드', n.tomcatPct, 80, 95],
    ['Redis 메모리', n.redisMemPct, 80, 90],
    // 로그인 대기실 (LoginGate): 몰리면 줄을 서고 다 차면 "N초 뒤 자동 재시도". 다른 API에는 영향 없음
    ['로그인 대기실', n.loginQueuePct, 50, 90, n.loginQueue ? `${fmt(n.loginQueue, 0)}명` : null],
  ];
  return (
    <section className="panel">
      <h2>자원 <span className="mut tr-h-sub">가장 바쁜 앱 기준 · 눈금 = 주의 · 위험</span></h2>
      <div className="tr-meters">
        {rows.map(([l, v, w, c, extra]) => {
          const lv = v == null ? '' : v >= c ? 'crit' : v >= w ? 'warn' : 'ok';
          return (
            <div key={l} className="tr-meter">
              <div className="row">
                <span className="grow">{l}</span>
                <b className={`num ${lv}`}>{v == null ? '—' : `${Math.round(v)}%`}</b>
                {extra && <span className="st amber">{extra}</span>}
              </div>
              <div className="tr-bar" role="meter" aria-label={l} aria-valuemin={0} aria-valuemax={100} aria-valuenow={v ?? 0}>
                <i className={lv} style={{ width: `${Math.min(100, v ?? 0)}%` }} />
                <s style={{ left: `${w}%` }} />
                <s className="c" style={{ left: `${c}%` }} />
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

const UNIT = { rps: '/초', ms: 'ms', pct: '%', people: '명', count: '건' };

/**
 * 데이터 놓침·밀림: 사람이 몰릴 때 봇 탐지 재료(행동 로그)를 버리거나, 이벤트 처리가 밀리는지.
 * 문턱은 서버 TrafficService.pipelineReasons와 같다 (버림·실패는 1건부터 주의)
 */
const REASON = { malformed: '형식 오류', retries_exhausted: '재시도 소진', handler_failed: '처리 실패' };

function DataLoss({ p }) {
  const n0 = (v) => (v == null ? '—' : Math.round(v).toLocaleString());
  const qLv = p.queuePct == null ? '' : p.queuePct >= 90 ? 'crit' : p.queuePct >= 50 ? 'warn' : 'ok';
  const oLv = p.outboxOldestSec == null ? '' : p.outboxOldestSec >= 120 ? 'crit' : p.outboxOldestSec >= 30 ? 'warn' : 'ok';
  const cells = [
    ['행동 로그 수집', p.behaviorPerSec == null ? '—' : `${fmt(p.behaviorPerSec)}/초`, 'Kafka가 받은 것', 'ok'],
    ['MongoDB 적재', p.storedPerSec == null ? '—' : `${fmt(p.storedPerSec)}/초`, '수집과 비슷해야 정상', p.behaviorPerSec > 1 && (p.storedPerSec ?? 0) < p.behaviorPerSec * 0.5 ? 'warn' : 'ok'],
    ['형식 오류 (최근 5분)', `${n0(p.skipped5m)}건`, '적재 안 됨 · 밀림에도 안 잡힘', p.skipped5m >= 1 ? (p.skipped5m >= (p.stored5m ?? 0) ? 'crit' : 'warn') : 'ok'],
    ['버림 (최근 5분)', `${n0(p.dropped5m)}건`, `보내기 큐가 넘쳐서 · 지금 ${fmt(p.droppedPerSec)}/초`, p.dropped5m >= 1000 ? 'crit' : p.dropped5m >= 1 ? 'warn' : 'ok'],
    ['전송 실패 (최근 5분)', `${n0(p.failed5m)}건`, 'Kafka가 받지 못함', p.failed5m >= 1000 ? 'crit' : p.failed5m >= 1 ? 'warn' : 'ok'],
    ['보내기 큐', p.queuePct == null ? '—' : `${fmt(p.queuePct)}%`, '5만 건이 차면 버리기 시작', qLv],
    ['격리 보관 (최근 1시간)', `${n0(p.deadLetters1h)}건`,
      p.deadLetters1h ? (p.deadLetters || []).slice(0, 2).map((d) => `${d.group} ${REASON[d.reason] || d.reason} ${Math.round(d.count)}`).join(' · ')
        : '소비자가 포기한 메시지 없음', p.deadLetters5m >= 1 ? 'warn' : 'ok'],
    ['예약 이벤트 미발행', `${n0(p.outboxPending)}건`,
      p.outboxPending ? `가장 오래 ${n0(p.outboxOldestSec)}초째${p.outboxFailed ? ` · 실패 ${n0(p.outboxFailed)}` : ''}` : 'outbox → Kafka 밀림 없음', oLv],
  ];
  const lagMax = Math.max(1, ...p.lags.map((l) => l.lag ?? 0));
  return (
    <section className="panel">
      <h2>데이터 놓침 · 밀림 <span className="mut tr-h-sub">몰릴 때 봇 탐지 재료를 버리거나 이벤트 처리가 늦어지는지</span></h2>
      <div className="tr-data">
        {cells.map(([l, v, d, lv]) => (
          <div key={l} className={`tr-dcell ${lv}`}>
            <div className="l">{l}</div>
            <div className="n num">{v}</div>
            <div className="d">{d}</div>
          </div>
        ))}
      </div>
      {p.lagMeasured === false && (
        <div className="alert amber">Kafka에 닿지 못해 소비 밀림을 잴 수 없어요. 이벤트·행동 로그가 멈췄을 수 있어요.</div>
      )}
      <table className="tr-lag">
        <thead>
          <tr><th>Kafka 소비 그룹</th><th className="n">밀린 메시지</th><th className="tr-opt">　</th><th>상태</th></tr>
        </thead>
        <tbody>
          {p.lags.map((l) => (
            <tr key={l.group}>
              <td>{l.name} <span className="faint mono-s">{l.group}</span></td>
              <td className="n">{n0(l.lag)}</td>
              <td className="tr-opt"><span className="tr-share wide"><i className={(l.level || '').toLowerCase()} style={{ width: `${((l.lag ?? 0) / lagMax) * 100}%` }} /></span></td>
              <td>
                {l.level == null ? <span className="faint">소비 기록 없음</span>
                  : <span className={`st ${l.level === 'CRIT' ? 'stop' : l.level === 'WARN' ? 'amber' : 'go'}`}>{l.level === 'CRIT' ? '위험' : l.level === 'WARN' ? '주의' : '정상'}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** 선 그래프: 범례 + 끝 값 이름표(4개 이하) + 마우스 십자선·툴팁. 축 하나, 선 2px */
const REF = '어제 같은 시각'; // 비교용 기준선은 회색 점선 (색 순서를 쓰지 않음)
const color = (l, k) => (l.name === REF ? 'var(--faint)' : PALETTE[k]);

function LineChart({ chart, t, step, restarts = [], wide }) {
  const box = useRef(null);
  const [w, setW] = useState(520);
  const [hi, setHi] = useState(null);
  useEffect(() => {
    const ro = new ResizeObserver(([e]) => setW(Math.max(260, Math.round(e.contentRect.width))));
    ro.observe(box.current);
    return () => ro.disconnect();
  }, []);
  const H = wide ? 220 : 200, L = 46, R = chart.lines.length <= 4 ? 150 : 14, T = 10, B = 24;
  const pw = w - L - R, ph = H - T - B;
  let max = 0;
  chart.lines.forEach((l) => l.v.forEach((x) => x != null && (max = Math.max(max, x))));
  if (chart.unit === 'pct' && chart.key === 'resources') max = 100;
  const top = niceMax(max);
  const x = (i) => L + (t.length > 1 ? (i * pw) / (t.length - 1) : 0);
  const y = (v) => T + ph - (v / top) * ph;
  const path = (vs) => {
    let d = '', pen = false;
    vs.forEach((v, i) => {
      if (v == null) return void (pen = false);
      d += (pen ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(v).toFixed(1);
      pen = true;
    });
    return d;
  };
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((k) => k * top);
  const xt = [0, 0.25, 0.5, 0.75, 1].map((k) => Math.round(k * (t.length - 1)));
  const onMove = (e) => {
    const r = e.currentTarget.getBoundingClientRect();
    const i = Math.round(((e.clientX - r.left - L) / pw) * (t.length - 1));
    setHi(i >= 0 && i < t.length ? i : null);
  };
  const lastIdx = (vs) => {
    for (let i = vs.length - 1; i >= 0; i--) if (vs[i] != null) return i;
    return -1;
  };
  // 끝 이름표가 겹치지 않게 아래로 민다
  const tags = chart.lines
    .map((l, k) => ({ k, i: lastIdx(l.v), l }))
    .filter((o) => o.i >= 0)
    .map((o) => ({ ...o, y: y(o.l.v[o.i]) }))
    .sort((a, b) => a.y - b.y);
  tags.forEach((o, j) => j && o.y < tags[j - 1].y + 13 && (o.y = tags[j - 1].y + 13));
  // 아래로 밀다가 그래프 바닥(시각 축)을 넘으면, 바닥에 맞추고 위쪽 이름표를 다시 올린다
  const floor = T + ph + 2;
  for (let j = tags.length - 1; j >= 0; j--) {
    const lim = j === tags.length - 1 ? floor : tags[j + 1].y - 13;
    if (tags[j].y > lim) tags[j].y = lim;
  }
  const t0 = t[0], tn = t[t.length - 1];
  // 가까운 재시작(배포 중 앱 두 대가 차례로 뜨는 경우 등)은 한 줄로 합친다
  const marks = [];
  restarts.filter((s) => s >= t0 && s <= tn).forEach((s) => {
    const mx = L + ((s - t0) / Math.max(1, tn - t0)) * pw;
    if (!marks.length || mx - marks[marks.length - 1] > 36) marks.push(mx);
  });
  const u = UNIT[chart.unit] || '';
  const empty = !chart.lines.some((l) => l.v.some((v) => v != null));

  return (
    <section className={'panel tr-chart' + (wide ? ' tr-wide' : '')}>
      <h2>{chart.title}</h2>
      {chart.lines.length > 1 && (
        <div className="tr-keys">
          {chart.lines.map((l, k) => (
            <span key={l.name}><i className={l.name === REF ? 'ref' : undefined} style={{ background: color(l, k) }} />{l.name}</span>
          ))}
          {wide && marks.length > 0 && <span><i className="restart" />앱 재시작 · 배포</span>}
        </div>
      )}
      <div ref={box} style={{ position: 'relative' }}>
        <svg width={w} height={H} onMouseMove={onMove} onMouseLeave={() => setHi(null)} role="img"
          aria-label={`${chart.title} — ` + chart.lines.map((l) => `${l.name} ${fmt(l.v[lastIdx(l.v)])}${u}`).join(', ')}>
          {ticks.map((v) => (
            <g key={v}>
              <line x1={L} x2={L + pw} y1={y(v)} y2={y(v)} stroke="var(--line2)" />
              <text x={L - 6} y={y(v) + 4} textAnchor="end" className="tr-ax">{v === 0 ? '0' : fmt(v, v < 1 ? 2 : 1)}</text>
            </g>
          ))}
          {xt.map((i) => (
            <text key={i} x={x(i)} y={H - 6} textAnchor={i === 0 ? 'start' : i === t.length - 1 ? 'end' : 'middle'} className="tr-ax">
              {hhmm(t[i])}
            </text>
          ))}
          {marks.map((mx, j) => (
            <g key={j} className="tr-restart">
              <line x1={mx} x2={mx} y1={T} y2={T + ph} />
              <text x={mx + 3} y={T + 9}>재시작</text>
            </g>
          ))}
          {chart.lines.map((l, k) => (
            <path key={l.name} d={path(l.v)} fill="none" stroke={color(l, k)} strokeWidth="2" strokeLinejoin="round"
              strokeDasharray={l.name === REF ? '5 4' : undefined} />
          ))}
          {R > 20 && tags.map((o) => (
            <text key={o.l.name} x={L + pw + 6} y={o.y + 4} className="tr-tag">
              {fmt(o.l.v[o.i])}{u} {o.l.name.length > 10 ? o.l.name.slice(0, 9) + '…' : o.l.name}
              <title>{o.l.name}</title>
            </text>
          ))}
          {hi != null && (
            <>
              <line x1={x(hi)} x2={x(hi)} y1={T} y2={T + ph} stroke="var(--ink)" strokeDasharray="3 3" />
              {chart.lines.map((l, k) => l.v[hi] != null && (
                <circle key={l.name} cx={x(hi)} cy={y(l.v[hi])} r="4" fill={color(l, k)} stroke="var(--card)" strokeWidth="2" />
              ))}
            </>
          )}
          {empty && <text x={L + pw / 2} y={T + ph / 2} textAnchor="middle" className="tr-ax">아직 수집된 값이 없어요</text>}
        </svg>
        {hi != null && (
          <div className="tr-tip light" style={{ left: Math.min(x(hi) + 12, w - 170), top: 6 }}>
            <b>{hhmm(t[hi], step < 60)}</b>
            {chart.lines.map((l, k) => (
              <div key={l.name}>
                <span><i style={{ background: color(l, k) }} />{l.name}</span>
                <span className="num">{fmt(l.v[hi])}{u}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function niceMax(v) {
  if (!(v > 0)) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (v <= m * p) return m * p;
  return 10 * p;
}

/** Spring이 템플릿을 못 찾은 요청은 uri 라벨이 "/**"(없는 주소·정적 파일)나 "UNKNOWN"으로 묶인다 */
const prettyUri = (u) => (u === '/**' ? '없는 주소 · 정적 파일 (/**)' : u === 'UNKNOWN' ? '알 수 없는 주소' : u);

function Endpoints({ rows }) {
  return (
    <section className="panel">
      <h2>많이 불린 API <span className="mut tr-h-sub">최근 1분</span></h2>
      {rows.length === 0 ? (
        <div className="mut">최근 1분 동안 요청이 없어요</div>
      ) : (
        <table>
          <thead>
            <tr><th>API</th><th className="tr-opt">영역</th><th className="n">초당</th><th className="n tr-opt">비중</th><th className="n">p95</th><th className="n">4xx</th><th className="n">5xx</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.method + r.uri}>
                <td className="tr-uri"><span className="st plain sky">{r.method}</span> {prettyUri(r.uri)}</td>
                <td className="tr-opt">{r.group}</td>
                <td className="n">{fmt(r.rps)}</td>
                <td className="n tr-opt">
                  <span className="tr-share"><i style={{ width: `${r.share}%` }} /></span>{fmt(r.share, 0)}%
                </td>
                <td className={'n' + (r.p95Ms >= 1000 ? ' tr-bad' : '')}>{r.p95Ms == null ? '—' : `${fmt(r.p95Ms, 0)}ms`}</td>
                <td className={'n' + (r.clientErrorPct >= 25 ? ' tr-warn' : '')}>{fmt(r.clientErrorPct, 1)}%</td>
                <td className={'n' + (r.errorPct >= 1 ? ' tr-bad' : '')}>{fmt(r.errorPct, 2)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
