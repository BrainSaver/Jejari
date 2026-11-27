import { useEffect, useRef, useState } from 'react';

/**
 * A-10 상단 실시간 흐름도. 요청 하나(또는 N건 묶음)를 동그란 점으로 그려 사용자 → 업무 영역 → 앱 서버 → 저장소로 흘려 보낸다.
 * 넷플릭스 Vizceral 같은 관제실 화면의 방식: 선이 굵고 점이 빽빽할수록 많이 오가는 곳, 빨간 점은 서버 오류(5xx).
 *
 *  - 점 수는 실제 초당 값(GET /api/v1/admin/traffic/flow, 최근 30초 평균)에 비례한다. 가장 바쁜 선이 초당 점 25개를 넘지 않게
 *    "점 1개 = N건"을 1·2·5 단위로 고르고 범례에 적는다 (꾸민 움직임이 아니라 그 비율 그대로)
 *  - 파란 점 = 정상, 빨간 큰 점 = 5xx (영역 노드에서 터짐), 노란 빈 점 = 429 요청 제한 (노드 앞에서 되돌아감),
 *    회색 점 = 그 밖의 4xx (409 포함 — 없는 주소·권한 없음·좌석 경합. 이용자 쪽 오류라 정상과 따로 보여 준다)
 *  - 노드 테두리: 초록 정상 · 노랑 주의(5xx 1%↑, 4xx 25%↑, 평균 1초↑) · 빨강 위험(5xx 5%↑). 노드 크기 = 초당 요청(로그)
 *  - 새 값이 6초 넘게 안 오면(서버·Prometheus 끊김) 점을 멈추고 "끊김"을 덮는다 — 옛 값으로 움직이는 화면은 거짓말이라서
 *  - 2초마다 새 값이 오면 0.8초에 걸쳐 부드럽게 바뀐다. 움직임 줄이기 설정이면 점 없이 선 굵기·숫자만 보인다
 */
const MAX_DOTS_PER_SEC = 25;
const TRAVEL_SEC = 1.6;
const C = {
  bg: '#13202F', grid: 'rgba(255,248,231,.05)', edge: 'rgba(143,211,255,.16)', node: '#1D2F45', text: '#F3EEE2',
  sub: '#9FB0C6', e4: '#76839A', ok: '#3FBF7F', warn: '#F2B705', crit: '#FF5D5D', dot: '#8FD3FF', err: '#FF5D5D', rej: '#F2B705',
};
const STORE_UNIT = { redis: '작업', oracle: '연결', mongo: '작업', kafka: '보냄' };

const fmt = (v) => (v == null ? '—' : v >= 100 ? Math.round(v).toLocaleString() : v >= 10 ? v.toFixed(0) : v.toFixed(1));
function nice(x) {
  if (x <= 1) return 1;
  const p = 10 ** Math.floor(Math.log10(x));
  const m = x / p;
  return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * p;
}
function groupLevel(g) {
  if (!g || g.rps <= 0) return 'idle';
  const e = g.err5 / g.rps;
  if (e >= 0.05) return 'crit';
  if (e >= 0.01 || (g.err4 ?? 0) / g.rps >= 0.25 || (g.avgMs ?? 0) >= 1000) return 'warn';
  return 'ok';
}

/** 노드·선 배치. 넓으면 왼→오, 좁으면(모바일) 위→아래 */
function layout(w, groups, stores) {
  const narrow = w < 640;
  const h = narrow ? 600 : 440;
  const nodes = {};
  const edges = [];
  if (narrow) {
    nodes.user = { x: w / 2, y: 44, r: 26 };
    groups.forEach((g, i) => (nodes['g' + i] = { x: (w * ((i % 3) * 2 + 1)) / 6, y: i < 3 ? 176 : 270, r: 18 }));
    nodes.app = { x: w / 2, y: 398, r: 30 };
    stores.forEach((s, i) => (nodes['s' + i] = { x: (w * (i * 2 + 1)) / 8, y: 530, r: 20 }));
  } else {
    nodes.user = { x: Math.max(70, w * 0.07), y: h / 2, r: 30 };
    groups.forEach((g, i) => (nodes['g' + i] = { x: w * 0.36, y: 46 + (i * (h - 92)) / 5, r: 20 }));
    nodes.app = { x: w * 0.64, y: h / 2, r: 36 };
    stores.forEach((s, i) => (nodes['s' + i] = { x: w * 0.84, y: h / 2 + (i - 1.5) * 92, r: 24 }));
  }
  groups.forEach((g, i) => {
    edges.push({ id: 'ug' + i, a: 'user', b: 'g' + i, rate: (f) => f.groups[i]?.rps ?? 0, mix: (f) => f.groups[i] });
    edges.push({ id: 'ga' + i, a: 'g' + i, b: 'app', rate: (f) => Math.max(0, (f.groups[i]?.rps ?? 0) - (f.groups[i]?.rejected ?? 0)) });
  });
  stores.forEach((s, i) => edges.push({ id: 'as' + i, a: 'app', b: 's' + i, kind: 'store', rate: (f) => f.stores[i]?.ops ?? 0 }));
  const k = stores.findIndex((s) => s.key === 'kafka');
  if (k >= 0) edges.push({ id: 'ka', a: 's' + k, b: 'app', kind: 'store', rate: (f) => f.kafkaConsumed ?? 0, bend: narrow ? -60 : 60 });
  for (const e of edges) {
    const A = nodes[e.a], B = nodes[e.b];
    const bend = e.bend || 0;
    e.p = narrow
      ? [A.x, A.y, A.x + bend, (A.y + B.y) / 2, B.x + bend, (A.y + B.y) / 2, B.x, B.y]
      : [A.x, A.y, (A.x + B.x) / 2, A.y + bend, (A.x + B.x) / 2, B.y + bend, B.x, B.y];
  }
  return { w, h, narrow, nodes, edges };
}

/**
 * 점 1개 = 몇 건. 가장 바쁜 선이 초당 MAX_DOTS_PER_SEC개를 넘지 않게. 요청(사용자 → 영역 → 앱)과 저장소 작업은 따로 센다 —
 * 요청 하나가 Redis 명령 여러 개가 되므로 같은 단위면 요청 쪽 점이 거의 안 보인다
 */
function unitOf(L, f, kind) {
  if (!f?.available) return 1;
  const rates = L.edges.filter((e) => (e.kind || 'req') === kind).map((e) => e.rate(f));
  return nice(Math.max(0, ...rates) / MAX_DOTS_PER_SEC);
}

function bez(p, t) {
  const u = 1 - t;
  const a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
  return [a * p[0] + b * p[2] + c * p[4] + d * p[6], a * p[1] + b * p[3] + c * p[5] + d * p[7]];
}

/** 빛나는 점 그림을 한 번만 만들어 둔다 (매 프레임 shadowBlur는 느림) */
function sprite(color, r, hollow) {
  const s = document.createElement('canvas');
  const size = (r + 6) * 2;
  s.width = s.height = size * 2;
  const g = s.getContext('2d');
  g.scale(2, 2);
  g.shadowColor = color;
  g.shadowBlur = 6;
  g.beginPath();
  g.arc(size / 2, size / 2, r, 0, Math.PI * 2);
  if (hollow) {
    g.lineWidth = 2;
    g.strokeStyle = color;
    g.stroke();
  } else {
    g.fillStyle = color;
    g.fill();
  }
  return { img: s, size };
}

export default function FlowMap({ flow, error, stale, age }) {
  const box = useRef(null);
  const canvas = useRef(null);
  const target = useRef(null);
  const [width, setWidth] = useState(900);
  const [hover, setHover] = useState(null);
  const [table, setTable] = useState(false);
  const reduce = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

  target.current = flow;
  const staleRef = useRef(null);
  staleRef.current = stale ? age : null;
  const groups = flow?.groups?.length ? flow.groups : [];
  const stores = flow?.stores?.length ? flow.stores : [];
  const lay = useRef(null);
  lay.current = layout(width, groups, stores);

  useEffect(() => {
    const ro = new ResizeObserver(([e]) => setWidth(Math.round(e.contentRect.width)));
    ro.observe(box.current);
    return () => ro.disconnect();
  }, []);

  // 그리기 루프: 화면 값(shown)이 받은 값(target)을 따라가고, 선마다 초당 값만큼 점을 낳는다
  useEffect(() => {
    const cv = canvas.current;
    const ctx = cv.getContext('2d');
    const sp = { dot: sprite(C.dot, 3), err: sprite(C.err, 4.6), rej: sprite(C.rej, 3.6, true), e4: sprite(C.e4, 2.6), flash: sprite(C.crit, 9, true) };
    const shown = new Map(); // edge id → 보이는 초당 값
    const acc = new Map();
    let dots = [];
    let bursts = [];
    let last = performance.now();
    let raf;

    const frame = (now) => {
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      const L = lay.current;
      const f = target.current;
      const dpr = window.devicePixelRatio || 1;
      if (cv.width !== Math.round(L.w * dpr) || cv.height !== Math.round(L.h * dpr)) {
        cv.width = Math.round(L.w * dpr);
        cv.height = Math.round(L.h * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = C.bg;
      ctx.fillRect(0, 0, L.w, L.h);
      ctx.strokeStyle = C.grid;
      ctx.lineWidth = 1;
      for (let x = 0; x < L.w; x += 36) {
        ctx.beginPath(); ctx.moveTo(x + 0.5, 0); ctx.lineTo(x + 0.5, L.h); ctx.stroke();
      }
      for (let y = 0; y < L.h; y += 36) {
        ctx.beginPath(); ctx.moveTo(0, y + 0.5); ctx.lineTo(L.w, y + 0.5); ctx.stroke();
      }

      const live = f?.available && staleRef.current == null;
      for (const e of L.edges) {
        const goal = live ? e.rate(f) : 0;
        const cur = shown.get(e.id) ?? goal;
        shown.set(e.id, cur + (goal - cur) * Math.min(1, dt * 2.5));
      }
      const units = { req: unitOf(L, f, 'req'), store: unitOf(L, f, 'store') };

      // 선: 굵기 = 초당 값 (로그)
      for (const e of L.edges) {
        const v = shown.get(e.id) || 0;
        ctx.strokeStyle = v > 0 ? 'rgba(143,211,255,.30)' : C.edge;
        ctx.lineWidth = v > 0 ? Math.min(7, 1 + Math.log10(1 + v) * 1.8) : 1;
        ctx.beginPath();
        ctx.moveTo(e.p[0], e.p[1]);
        ctx.bezierCurveTo(e.p[2], e.p[3], e.p[4], e.p[5], e.p[6], e.p[7]);
        ctx.stroke();
      }

      // 점 낳기
      if (!reduce && live) {
        for (const e of L.edges) {
          const v = shown.get(e.id) || 0;
          let a = (acc.get(e.id) || 0) + (v * dt) / units[e.kind || 'req'];
          while (a >= 1 && dots.length < 900) {
            a -= 1;
            let kind = 'dot';
            const g = e.mix?.(f);
            if (g && g.rps > 0) {
              const r = Math.random() * g.rps;
              if (r < g.err5) kind = 'err';
              else if (r < g.err5 + g.rejected) kind = 'rej';
              else if (r < g.err5 + g.rejected + (g.err4 ?? 0) + g.conflict) kind = 'e4';
            }
            dots.push({ e, t: 0, kind, back: false, jitter: (Math.random() - 0.5) * 6 });
          }
          acc.set(e.id, a);
        }
      }

      // 점 움직이기
      ctx.globalCompositeOperation = 'lighter';
      const next = [];
      for (const d of dots) {
        const step = dt / TRAVEL_SEC;
        if (d.kind === 'rej') {
          if (!d.back) {
            d.t += step;
            if (d.t >= 0.82) d.back = true;
          } else d.t -= step * 1.3;
          if (d.back && d.t <= 0.45) continue;
        } else {
          d.t += step;
          if (d.t >= 1) {
            if (d.kind === 'err') bursts.push({ x: d.e.p[6], y: d.e.p[7], life: 1 });
            continue;
          }
        }
        const [x, y] = bez(d.e.p, d.t);
        const s = sp[d.kind];
        const fade = d.kind === 'rej' && d.back ? Math.max(0, (d.t - 0.45) / 0.37) : d.kind === 'e4' ? 0.6 : 1;
        ctx.globalAlpha = fade;
        ctx.drawImage(s.img, x - s.size / 2, y + d.jitter * Math.sin(d.t * Math.PI) - s.size / 2, s.size, s.size);
        next.push(d);
      }
      dots = next;
      ctx.globalAlpha = 1;
      bursts = bursts.filter((b) => (b.life -= dt * 1.6) > 0);
      for (const b of bursts) {
        const s = sp.flash;
        const k = 1 + (1 - b.life) * 1.4;
        ctx.globalAlpha = b.life;
        ctx.drawImage(s.img, b.x - (s.size * k) / 2, b.y - (s.size * k) / 2, s.size * k, s.size * k);
      }
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = 'source-over';

      // 노드 이름표·테두리는 지금 받은 값으로 (effect를 만들 때의 groups를 쓰면 첫 값에 멈춰 있다)
      drawNodes(ctx, L, f, f?.groups ?? [], f?.stores ?? []);
      if (!live) {
        ctx.fillStyle = 'rgba(19,32,47,.7)';
        ctx.fillRect(0, 0, L.w, L.h);
        ctx.fillStyle = C.text;
        ctx.font = '700 15px "Noto Sans KR",sans-serif';
        ctx.textAlign = 'center';
        const msg = staleRef.current != null ? `연결 끊김 — ${staleRef.current}초째 새 값이 안 와요` : f ? f.reason || '지표를 읽지 못했습니다' : '불러오는 중…';
        ctx.fillText(msg, L.w / 2, L.h / 2);
        ctx.textAlign = 'left';
      }
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);
    const onVis = () => {
      if (document.visibilityState === 'visible') {
        last = performance.now();
        dots = [];
      }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener('visibilitychange', onVis);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groups.length, stores.length, reduce]);

  const onMove = (ev) => {
    const r = canvas.current.getBoundingClientRect();
    const x = ev.clientX - r.left, y = ev.clientY - r.top;
    const L = lay.current;
    let hit = null;
    for (const [id, n] of Object.entries(L.nodes)) {
      if ((x - n.x) ** 2 + (y - n.y) ** 2 <= (n.r + 10) ** 2) hit = { id, x: n.x, y: n.y };
    }
    setHover(hit);
  };

  const unit = unitOf(lay.current, flow, 'req');
  const sunit = unitOf(lay.current, flow, 'store');
  const total = groups.reduce((s, g) => s + g.rps, 0);
  const err = groups.reduce((s, g) => s + g.err5, 0);
  return (
    <section className="panel tr-flow-panel">
      <h2>
        <span className="grow">실시간 흐름 <span className="tr-live" aria-hidden="true" /> <span className="mut tr-h-sub">최근 30초 · 2초마다</span></span>
        <button type="button" className="btn ghost sm" onClick={() => setTable((v) => !v)} aria-pressed={table}>
          {table ? '그림으로' : '표로 보기'}
        </button>
      </h2>
      {error && <div className="alert stop">흐름 값을 못 읽었어요: {error.message}</div>}
      <div className="tr-legend" aria-hidden={table}>
        <span><i className="tr-ldot" /> 정상 요청</span>
        <span><i className="tr-ldot err" /> 서버 오류</span>
        <span><i className="tr-ldot rej" /> 요청 제한 (되돌아감)</span>
        <span><i className="tr-ldot e4" /> 사용자 쪽 오류</span>
        <span className="mut">점 1개 = 요청 {unit.toLocaleString()}건 · 저장소 작업 {sunit.toLocaleString()}건</span>
        <span className="mut grow tr-right">
          지금 {fmt(total)}건/초{total > 0 ? ` · 오류 ${((100 * err) / total).toFixed(1)}%` : ''}
        </span>
      </div>
      <div ref={box} className="tr-flow" hidden={table}>
        <canvas
          ref={canvas}
          style={{ width: '100%', height: lay.current.h, display: 'block' }}
          role="img"
          aria-label={`실시간 요청 흐름. 초당 ${fmt(total)}건, ` + groups.map((g) => `${g.name} ${fmt(g.rps)}건`).join(', ')}
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
        />
        {hover && <Tip hover={hover} flow={flow} groups={groups} stores={stores} />}
      </div>
      {table && <FlowTable flow={flow} groups={groups} stores={stores} />}
    </section>
  );
}

function drawNodes(ctx, L, f, groups, stores) {
  const ring = (lv) => (lv === 'crit' ? C.crit : lv === 'warn' ? C.warn : lv === 'ok' ? C.ok : '#3A4C63');
  const pulse = (performance.now() % 1400) / 1400;
  const circle = (n0, lv, label, sub, side, rps) => {
    // 바쁠수록 노드가 조금 커진다 (로그, 최대 +8px). 위험이면 테두리 밖으로 번지는 고리
    const n = { ...n0, r: n0.r + (rps > 0 ? Math.min(8, Math.log10(1 + rps) * 3) : 0) };
    if (lv === 'crit') {
      ctx.beginPath();
      ctx.arc(n.x, n.y, n.r + 4 + pulse * 14, 0, Math.PI * 2);
      ctx.strokeStyle = `rgba(255,93,93,${(1 - pulse) * 0.7})`;
      ctx.lineWidth = 3;
      ctx.stroke();
    }
    ctx.beginPath();
    ctx.arc(n.x, n.y, n.r, 0, Math.PI * 2);
    ctx.fillStyle = C.node;
    ctx.fill();
    ctx.lineWidth = 3;
    ctx.strokeStyle = ring(lv);
    ctx.stroke();
    ctx.font = '800 13px "Noto Sans KR",sans-serif';
    const lines = [label, sub].filter(Boolean);
    if (side === 'right' && !L.narrow) {
      const x = n.x + n.r + 10;
      const wMax = Math.max(...lines.map((s, i) => { ctx.font = (i ? '600 12px' : '800 13px') + ' "Noto Sans KR",sans-serif'; return ctx.measureText(s).width; }));
      ctx.fillStyle = 'rgba(19,32,47,.85)';
      ctx.fillRect(x - 4, n.y - 19, wMax + 8, lines.length > 1 ? 38 : 22);
      ctx.textAlign = 'left';
      lines.forEach((s, i) => {
        ctx.font = (i ? '600 12px' : '800 13px') + ' "Noto Sans KR",sans-serif';
        ctx.fillStyle = i ? C.sub : C.text;
        ctx.fillText(s, x, n.y - 4 + i * 16);
      });
    } else {
      ctx.textAlign = 'center';
      const wMax = Math.max(...lines.map((s, i) => { ctx.font = (i ? '600 11.5px' : '800 12.5px') + ' "Noto Sans KR",sans-serif'; return ctx.measureText(s).width; }));
      ctx.fillStyle = 'rgba(19,32,47,.85)';
      ctx.fillRect(n.x - wMax / 2 - 4, n.y + n.r + 3, wMax + 8, lines.length * 15 + 5);
      lines.forEach((s, i) => {
        ctx.font = (i ? '600 11.5px' : '800 12.5px') + ' "Noto Sans KR",sans-serif';
        ctx.fillStyle = i ? C.sub : C.text;
        ctx.fillText(s, n.x, n.y + n.r + 16 + i * 15);
      });
      ctx.textAlign = 'left';
    }
  };
  const live = f?.available;
  const total = groups.reduce((s, g) => s + g.rps, 0);
  circle(L.nodes.user, live ? 'ok' : 'idle', '사용자', live ? `${fmt(total)}/초` : '', null, total);
  groups.forEach((g, i) => {
    let sub = g.rps > 0 ? `${fmt(g.rps)}/초 · 평균 ${fmt(g.avgMs)}ms` : '요청 없음';
    if (i === 0 && f?.queueWaiting != null) sub = `${sub} · 줄 ${fmt(f.queueWaiting)}명`;
    if (L.narrow) sub = g.rps > 0 ? `${fmt(g.rps)}/초` : '0';
    circle(L.nodes['g' + i], live ? groupLevel(g) : 'idle', g.name, sub, 'right', g.rps);
  });
  const appLv = !live ? 'idle' : f.instancesUp === 0 ? 'crit' : f.instancesUp < f.instancesTotal ? 'warn' : 'ok';
  circle(L.nodes.app, appLv, '앱 서버', live ? `${f.instancesUp}/${f.instancesTotal}대 응답` : '');
  stores.forEach((s, i) => {
    // Kafka는 소비 밀림·행동 로그 버림을 테두리 색과 이름표로 (서버가 판정해 level·note로 보냄)
    const sub = (s.ops == null ? '지표 없음' : `${STORE_UNIT[s.key] || ''} ${fmt(s.ops)}/초`) + (s.note ? ` · ${s.note}` : '');
    const lv = !live || s.ops == null ? 'idle' : s.level === 'CRIT' ? 'crit' : s.level === 'WARN' ? 'warn' : 'ok';
    circle(L.nodes['s' + i], lv, s.name, L.narrow ? (s.ops == null ? '—' : fmt(s.ops)) : sub, 'right');
  });
}

function Tip({ hover, flow, groups, stores }) {
  let title = '', rows = [];
  if (hover.id === 'user') {
    title = '사용자 → 서비스';
    rows = [['초당 요청', fmt(groups.reduce((s, g) => s + g.rps, 0))]];
  } else if (hover.id === 'app') {
    title = '앱 서버';
    rows = [['응답하는 앱', `${flow?.instancesUp ?? 0} / ${flow?.instancesTotal ?? 0}대`], ['메시지 처리', `${fmt(flow?.kafkaConsumed)}/초`]];
  } else if (hover.id.startsWith('g')) {
    const g = groups[+hover.id.slice(1)];
    title = g.name;
    rows = [['초당 요청', fmt(g.rps)], ['평균 응답', `${fmt(g.avgMs)}ms`], ['서버 오류', `${fmt(g.err5)}/초`], ['요청 제한', `${fmt(g.rejected)}/초`], ['좌석 겹침', `${fmt(g.conflict)}/초`], ['사용자 쪽 오류', `${fmt(g.err4)}/초`]];
    if (hover.id === 'g0') rows.push(['줄 선 사람', fmt(flow?.queueWaiting)], ['입장한 사람', fmt(flow?.queueActive)]);
  } else {
    const s = stores[+hover.id.slice(1)];
    title = s.name;
    rows = [[`${STORE_UNIT[s.key] || '작업'}/초`, fmt(s.ops)]];
    if (s.note) rows.push(['상태', s.note]);
  }
  return (
    <div className={'tr-tip' + (hover.y < 200 ? ' down' : '')} style={{ left: hover.x, top: hover.y }} role="tooltip">
      <b>{title}</b>
      {rows.map(([k, v]) => (
        <div key={k}><span>{k}</span><span className="num">{v}</span></div>
      ))}
    </div>
  );
}

function FlowTable({ flow, groups, stores }) {
  return (
    <table>
      <thead>
        <tr><th>구간</th><th className="n">초당</th><th className="n">평균 응답</th><th className="n">서버 오류/초</th><th className="n">요청 제한/초</th><th className="n">좌석 겹침/초</th><th className="n">사용자 오류/초</th></tr>
      </thead>
      <tbody>
        {groups.map((g) => (
          <tr key={g.name}>
            <td>사용자 → {g.name}</td><td className="n">{fmt(g.rps)}</td><td className="n">{fmt(g.avgMs)}ms</td>
            <td className="n">{fmt(g.err5)}</td><td className="n">{fmt(g.rejected)}</td><td className="n">{fmt(g.conflict)}</td><td className="n">{fmt(g.err4)}</td>
          </tr>
        ))}
        {stores.map((s) => (
          <tr key={s.key}>
            <td>앱 → {s.name} ({STORE_UNIT[s.key]})</td><td className="n">{fmt(s.ops)}</td><td colSpan={5} />
          </tr>
        ))}
        <tr><td>메시지 전달 → 앱 (처리)</td><td className="n">{fmt(flow?.kafkaConsumed)}</td><td colSpan={5} /></tr>
      </tbody>
    </table>
  );
}
