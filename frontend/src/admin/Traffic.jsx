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

export default function Traffic() {
  const claims = useOutletContext();
  const [params, setParams] = useSearchParams();
  const range = RANGES.some(([k]) => k === params.get('range')) ? params.get('range') : '1h';
  const view = usePolling(() => get(`/api/v1/admin/traffic?range=${range}`), 10_000, [range]);
  const flow = usePolling(() => get('/api/v1/admin/traffic/flow'), 2_000, []);
  const v = view.data;

  return (
    <>
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
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>

      <HealthBar v={v} error={view.error} />
      <FlowMap flow={flow.data} error={flow.error} />
      {v?.available && (
        <>
          <Kpis n={v.now} />
          <Meters n={v.now} />
          <div className="tr-grid">
            {v.charts.map((c) => (
              <LineChart key={c.key} chart={c} t={v.t} step={v.stepSec} />
            ))}
          </div>
          <Endpoints rows={v.endpoints} />
        </>
      )}
    </>
  );
}

function HealthBar({ v, error }) {
  if (error && !v) return <div className="alert stop">관제 값을 못 읽었어요: {error.message}</div>;
  if (!v) return <div className="tr-health sky"><span className="st sky">불러오는 중</span></div>;
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
      <span className="faint tr-at">{hhmm(v.at, true)} 기준</span>
    </div>
  );
}

function Kpis({ n }) {
  const tiles = [
    ['초당 요청', fmt(n.rps), n.surge != null ? `평소의 ${fmt(n.surge)}배 · 최고 ${fmt(n.peakRps)}` : `최고 ${fmt(n.peakRps)}`,
      n.surge >= 3 ? 'surge' : false],
    ['응답 p95', n.p95Ms == null ? '—' : `${fmt(n.p95Ms, 0)}ms`, `p50 ${fmt(n.p50Ms, 0)} · p99 ${fmt(n.p99Ms, 0)}ms`, n.p95Ms >= 1000],
    ['5xx 오류', n.errorPct == null ? '—' : `${fmt(n.errorPct, 2)}%`, `429 제한 ${fmt(n.rejectPct)}%`, n.errorPct >= 1],
    ['대기열', fmt(n.queueWaiting, 0), `입장 ${fmt(n.queueActive, 0)}명`, false],
    ['예약 · 결제', `${fmt(n.reservePerSec)} · ${fmt(n.payPerSec)}`, `건/초 · 409 충돌 ${fmt(n.conflictPerSec)}/초`, false],
    ['앱 서버', `${n.instancesUp}/${n.instancesTotal}`, n.instancesUp < n.instancesTotal ? '응답 없는 앱 있음' : '모두 응답',
      n.instancesUp < n.instancesTotal],
  ];
  return (
    <div className="kpis">
      {tiles.map(([l, v, d, bad]) => (
        <div key={l} className={'kpi' + (bad === 'surge' ? ' surge' : bad ? ' alarm' : '')}>
          <div className="l">{l}</div>
          <div className="n">{v}</div>
          <div className="d">{d}</div>
        </div>
      ))}
    </div>
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

const UNIT = { rps: '/초', ms: 'ms', pct: '%', people: '명' };

/** 선 그래프: 범례 + 끝 값 이름표(4개 이하) + 마우스 십자선·툴팁. 축 하나, 선 2px */
function LineChart({ chart, t, step }) {
  const box = useRef(null);
  const [w, setW] = useState(520);
  const [hi, setHi] = useState(null);
  useEffect(() => {
    const ro = new ResizeObserver(([e]) => setW(Math.max(260, Math.round(e.contentRect.width))));
    ro.observe(box.current);
    return () => ro.disconnect();
  }, []);
  const H = 200, L = 46, R = chart.lines.length <= 4 ? 112 : 14, T = 10, B = 24;
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
  const u = UNIT[chart.unit] || '';
  const empty = !chart.lines.some((l) => l.v.some((v) => v != null));

  return (
    <section className="panel tr-chart">
      <h2>{chart.title}</h2>
      {chart.lines.length > 1 && (
        <div className="tr-keys">
          {chart.lines.map((l, k) => (
            <span key={l.name}><i style={{ background: PALETTE[k] }} />{l.name}</span>
          ))}
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
          {chart.lines.map((l, k) => (
            <path key={l.name} d={path(l.v)} fill="none" stroke={PALETTE[k]} strokeWidth="2" strokeLinejoin="round" />
          ))}
          {R > 20 && tags.map((o) => (
            <text key={o.l.name} x={L + pw + 6} y={o.y + 4} className="tr-tag">
              {fmt(o.l.v[o.i])}{u} {o.l.name.length > 6 ? o.l.name.slice(0, 5) + '…' : o.l.name}
            </text>
          ))}
          {hi != null && (
            <>
              <line x1={x(hi)} x2={x(hi)} y1={T} y2={T + ph} stroke="var(--ink)" strokeDasharray="3 3" />
              {chart.lines.map((l, k) => l.v[hi] != null && (
                <circle key={l.name} cx={x(hi)} cy={y(l.v[hi])} r="4" fill={PALETTE[k]} stroke="var(--card)" strokeWidth="2" />
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
                <span><i style={{ background: PALETTE[k] }} />{l.name}</span>
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

function Endpoints({ rows }) {
  return (
    <section className="panel">
      <h2>많이 불린 API <span className="mut tr-h-sub">최근 1분</span></h2>
      {rows.length === 0 ? (
        <div className="mut">최근 1분 동안 요청이 없어요</div>
      ) : (
        <table>
          <thead>
            <tr><th>API</th><th className="tr-opt">영역</th><th className="n">초당</th><th className="n tr-opt">비중</th><th className="n">p95</th><th className="n">5xx</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.method + r.uri}>
                <td className="tr-uri"><span className="st plain sky">{r.method}</span> {r.uri}</td>
                <td className="tr-opt">{r.group}</td>
                <td className="n">{fmt(r.rps)}</td>
                <td className="n tr-opt">
                  <span className="tr-share"><i style={{ width: `${r.share}%` }} /></span>{fmt(r.share, 0)}%
                </td>
                <td className={'n' + (r.p95Ms >= 1000 ? ' tr-bad' : '')}>{r.p95Ms == null ? '—' : `${fmt(r.p95Ms, 0)}ms`}</td>
                <td className={'n' + (r.errorPct >= 1 ? ' tr-bad' : '')}>{fmt(r.errorPct, 2)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
