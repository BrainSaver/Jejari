import { Alert, Badge, Button, Card, Checkbox, Col, Empty, Form, Input, Popconfirm, Row, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useOutletContext, useSearchParams } from 'react-router';
import { get, post, subscribeRequestLog } from '../api/client.js';
import { addDays, md, seoulMs, seoulToday } from '../lib/time.js';
import { usePolling } from '../hooks/usePolling.js';
import Icon from './Icon.jsx';
import RequestLogPanel from './RequestLogPanel.jsx';
import { ApiError } from './ui-antd.jsx';
import { ROLE, RUN_STATUS, ko } from './labels.js';
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
      <div className="adm-title">
        <h1>실시간 예매 현황</h1>
        <Select
          aria-label="운행 고르기"
          style={{ minWidth: 340 }}
          value={runId || undefined}
          placeholder="판매 중인 운행 없음"
          disabled={!runs.length}
          onChange={(v) => setParams({ run: v })}
          options={runs.map((r) => ({ value: String(r.runId), label: r.label }))}
        />
        {data && <Badge status={data.status === 'ON_SALE' ? 'success' : 'warning'} text={ko(RUN_STATUS, data.status)} />}
        <span style={{ flex: 1 }} />
        <Button onClick={refreshNow} icon={<Icon name="refresh" />}>지금 다시 읽기</Button>
        {GRAFANA && (
          <Button href={GRAFANA} target="_blank" rel="noreferrer" icon={<Icon name="external" />}>자세한 그래프</Button>
        )}
        <div style={{ flexBasis: '100%' }}>
          <LiveInfo data={data} error={error} lastReq={lastReq} />
        </div>
      </div>

      {runsError && <Alert type="error" showIcon style={{ marginBottom: 16 }} message={`운행 목록을 못 읽었어요: ${runsError.message}`} />}
      {!runId && !runsError && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="판매 중(ON_SALE)인 운행이 없어요. 운행·행사 화면에서 판매를 열거나, 시드의 운행 9001·9002를 쓰세요." />
      )}
      {error && <ApiError error={error} style={{ marginBottom: 16 }} />}

      {data && <Kpis d={data} />}

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <RateChart data={data} />
        </Col>
        <Col xs={24} xl={8}>
          <ForceCancel claims={claims} onDone={refreshNow} unknown={data?.payments.unknown} />
        </Col>
      </Row>

      <RequestLogPanel />
    </>
  );
}

/**
 * ADM-07 예약 강제 취소 (강다인 API). POST /api/v1/admin/reservations/{id}/cancel {"memo"}.
 * 확정 예약만, 수수료 0·전액 환불, admin_action에 사유가 남는다. 총괄 관리자만 — 서버가 막고 화면은 버튼만 끈다.
 */
const CANCEL_HINT = {
  ACTION_REASON_REQUIRED: '사유를 5자 이상 적어 주세요. 아무것도 바뀌지 않았어요.',
  ADMIN_FORBIDDEN: '총괄 관리자만 강제 취소할 수 있어요.',
  RESERVATION_NOT_FOUND: '없는 예약 번호예요.',
  RESERVATION_NOT_CONFIRMED: '확정된 예약이 아니거나 이미 취소됐어요. 결제 전 예약은 기한이 지나면 스스로 풀려요.',
  CANCEL_NOT_ALLOWED: '지금은 수수료를 계산할 수 없는 때예요(출발 뒤 한도·도착 뒤). 위약금 면제로 다시 취소해 주세요.',
};

function ForceCancel({ claims, onDone, unknown }) {
  const can = hasRole(claims, 'SUPERVISOR');
  const [rv, setRv] = useState('');
  const [memo, setMemo] = useState('');
  const [waive, setWaive] = useState(true); // 기본 면제 (오탐·운영 사유). 매크로로 판정했을 때만 끔
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  // 시안의 "R-501233"처럼 붙여 넣어도 되게 숫자만 남긴다 (API 경로는 숫자 ID)
  const id = rv.replace(/\D/g, '');
  const ready = can && id && memo.trim().length >= 5;

  const go = async () => {
    setError(null);
    setResult(null);
    setBusy(true);
    try {
      setResult(await post(`/api/v1/admin/reservations/${id}/cancel`, { memo: memo.trim(), waiveFee: waive }));
      setMemo('');
      setWaive(true);
      onDone?.();
    } catch (e) {
      const hint = CANCEL_HINT[e.code];
      setError(hint ? Object.assign(e, { message: hint }) : e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="adm-work" title="예약 강제 취소" extra={<Typography.Text type="secondary">결과 모름 {unknown ?? '—'}건</Typography.Text>}>
      {!can && <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={`지금 권한(${ko(ROLE, claims?.adm)})으로는 강제 취소할 수 없어요. 총괄 관리자만 가능해요.`} />}
      {error && <ApiError error={error} onClose={() => setError(null)} style={{ marginBottom: 12 }} />}
      {result && (
        <Alert
          type={result.cancel.refundStatus === 'FAILED' ? 'error' : 'success'}
          showIcon
          style={{ marginBottom: 12 }}
          message={`예약 ${result.cancel.reservationId}을 취소했어요 · ${result.cancel.seatCount}석 · 환불 ${Number(result.cancel.refundAmount).toLocaleString()}원 (${result.cancel.refundStatus}) · 수수료 ${Number(result.cancel.cancelFee).toLocaleString()}원 · admin_action #${result.actionId}`}
          description={result.cancel.refundStatus === 'FAILED' ? '환불이 실패했어요. 결제 담당(길동현)에게 예약 번호를 알려 주세요.' : null}
        />
      )}
      <Form layout="vertical" disabled={!can} style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
        <Form.Item label="예약 번호" style={{ marginBottom: 12 }}>
          <Input value={rv} onChange={(e) => setRv(e.target.value)} inputMode="numeric" placeholder="예: 501233" />
        </Form.Item>
        <Form.Item label="사유" extra={`5자 이상 (${memo.trim().length}자)`} style={{ marginBottom: 12 }}>
          <Input.TextArea rows={3} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="예: 되팔이 확정 케이스 C-204 조치" />
        </Form.Item>
        <Form.Item style={{ marginBottom: 12 }}>
          <Checkbox checked={waive} onChange={(e) => setWaive(e.target.checked)}>수수료·위약금 면제 (오탐·운영 사유)</Checkbox>
        </Form.Item>
        <span style={{ flex: 1 }} />
        <Popconfirm
          title={`예약 ${id || ''}을 강제 취소할까요?`}
          description={waive ? '수수료 없이 전액 환불돼요.' : '사용자 취소와 같은 수수료를 받아요.'}
          okText="강제 취소"
          okButtonProps={{ danger: true }}
          cancelText="그만두기"
          onConfirm={go}
          disabled={!ready}
        >
          <Button danger type="primary" block disabled={!ready} loading={busy}>강제 취소</Button>
        </Popconfirm>
      </Form>
    </Card>
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

function LiveInfo({ data, error, lastReq }) {
  const now = useNow(1000);
  const age = data ? Math.max(0, Math.round((now - seoulMs(data.asOf)) / 1000)) : null;
  return (
    <Space size={10} wrap style={{ fontSize: 13 }} aria-live="polite">
      {error ? <Typography.Text type="danger">응답 실패</Typography.Text> : <Typography.Text type="secondary">10초마다 갱신</Typography.Text>}
      {data && (
        <Typography.Text type="secondary">
          서버 시각 <span className="adm-num">{data.asOf.slice(11, 19)}</span> ({age}초 전)
        </Typography.Text>
      )}
      {lastReq && (
        <Typography.Text type="secondary">
          응답 <span className="adm-num">{lastReq.ms}ms</span>
          {lastReq.traceId && (
            <Typography.Text className="adm-mono" type="secondary" copyable={{ text: lastReq.traceId }} style={{ marginLeft: 6 }} title="오류 번호 (문제가 생기면 개발자에게 알려 주세요)">
              {lastReq.traceId.slice(0, 12)}…
            </Typography.Text>
          )}
        </Typography.Text>
      )}
    </Space>
  );
}

function Kpis({ d }) {
  const { seats, queue, payments } = d;
  const pct = seats.total ? Math.round((seats.sold / seats.total) * 100) : 0;
  const drainSec = queue.admitPerSec > 0 ? Math.ceil(queue.waiting / queue.admitPerSec) : null;
  const items = [
    { label: '판매', n: seats.sold, d: `${seats.total.toLocaleString()}석 중 ${pct}%`, src: 'Redis', tip: 'Redis seatmap 캐시 · 1초 이하 늦음' },
    { label: '선점 중', n: seats.held, d: seats.groupQuota ? `결제 대기 · 단체 할당 ${seats.groupQuota}석 따로` : '결제 기다리는 좌석', src: 'Redis', tip: 'seat:hold 키가 살아 있는 좌석' },
    { label: '남은 좌석', n: seats.remaining, d: seats.groupQuota ? '전체 − 판매 − 선점 − 단체' : '전체 − 판매 − 선점', src: '계산' },
    { label: '대기열', n: queue.waiting, d: drainSec != null ? `입장 중 ${queue.active} · 소진 ${fmtSec(drainSec)}` : `입장 중 ${queue.active}`, src: 'Redis', tip: `ZSET queue:${d.runId}` },
    { label: '입장 속도', n: queue.admitPerSec, unit: '/초', d: '1초마다 기록', src: 'Redis', tip: `queue:throughput:${d.runId}` },
    { label: '결제 결과 모름', n: payments.unknown, d: '확인 시각 지난 결제 · 전체 운행', src: 'Oracle', tip: 'payment', alarm: payments.unknown > 0 },
  ];
  return (
    <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
      {items.map((k) => (
        <Col key={k.label} xs={12} md={8} xl={4} style={{ display: 'flex' }}>
          <Kpi {...k} />
        </Col>
      ))}
    </Row>
  );
}

/** 숫자 카드. 출처(Redis·Oracle)는 이름에 마우스를 올리면 보인다 */
function Kpi({ label, n, unit, d, src, tip, alarm }) {
  return (
    <Card size="small" style={{ flex: 1 }} styles={{ body: { padding: '12px 16px' } }}>
      <Typography.Text type="secondary" style={{ fontSize: 13 }} title={tip ? `${src} · ${tip}` : src}>{label}</Typography.Text>
      <div className="adm-num" style={{ fontSize: 26, fontWeight: 500, lineHeight: 1.3, marginTop: 2 }}>
        <Typography.Text type={alarm ? 'danger' : undefined} style={{ fontSize: 'inherit' }}>
          {Number(n).toLocaleString()}
          {unit && <span style={{ fontSize: 14 }}>{unit}</span>}
        </Typography.Text>
      </div>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>{d}</Typography.Text>
    </Card>
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

/**
 * 분 경계로 자른 막대(서버 /minutes). 카드 높이(작업 칸 600px)를 그래프가 끝까지 채운다:
 * 그림 영역의 실제 폭·높이를 재서 그 크기로 그린다 (글자가 늘어나지 않게 viewBox를 늘리지 않음).
 */
function RateChart({ data }) {
  const box = useRef(null);
  const [size, setSize] = useState({ w: 800, h: 400 });
  useEffect(() => {
    const ro = new ResizeObserver(([e]) => setSize({ w: Math.max(300, Math.round(e.contentRect.width)), h: Math.max(200, Math.round(e.contentRect.height)) }));
    ro.observe(box.current);
    return () => ro.disconnect();
  }, []);
  const rows = data?.history?.minutes || [];
  const rates = data?.rates || {};
  const windowSec = data?.windowSec ?? 60;
  const { w: W, h: Hbox } = size;
  const L = 36, B = 26, T = 8;
  const H = Hbox - B;
  const max = Math.max(10, ...rows.flatMap((h) => SERIES.map(([k]) => h[k])));
  const top = niceCeil(max);
  const slot = (W - L) / Math.max(rows.length, 1);
  const bw = Math.max(2, (slot - 6) / SERIES.length);
  const y = (v) => T + (H - T) - (v / top) * (H - T);
  const total = rows.reduce((a, h) => a + SERIES.reduce((b, [k]) => b + h[k], 0), 0);

  return (
    <Card
      className="adm-work"
      title={<span title={`막대 한 칸 = 1분 · 마지막 흐린 칸은 진행 중인 분 · 범례 숫자는 최근 ${windowSec}초 · 만료는 결제로 이어지지 못하고 풀린 선점`}>분당 예약·결제·취소·만료</span>}
      extra={
        <Space size={14} wrap style={{ fontSize: 12.5 }}>
          {SERIES.map(([k, name, color, hatch]) => (
            <span key={k}>
              <i style={{ display: 'inline-block', width: 10, height: 10, marginRight: 5, verticalAlign: -1, background: hatch ? 'repeating-linear-gradient(135deg, var(--stop) 0 2px, #fff 2px 3.5px)' : color }} />
              {name} <span className="adm-num">{rates[k] ?? '—'}</span>
            </span>
          ))}
        </Space>
      }
    >
      <div ref={box} style={{ flex: 1, minHeight: 0, position: 'relative' }}>
        {!data ? null : !rows.length ? (
          <Empty style={{ marginTop: 80 }} description="분당 이력을 못 읽었어요. 숫자 카드는 그대로 갱신돼요." />
        ) : (
          <svg width={W} height={Hbox} style={{ display: 'block' }} role="img" aria-label={`최근 ${rows.length}분 분당 예약·결제·취소·만료`}>
            <defs>
              <pattern id="hatch-stop" width="5" height="5" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                <rect width="5" height="5" fill="var(--stop)" />
                <line x1="0" y1="0" x2="0" y2="5" stroke="#fff" strokeWidth="1.6" />
              </pattern>
            </defs>
            {[0, 0.2, 0.4, 0.6, 0.8, 1].map((f) => (
              <g key={f}>
                <line x1={L} x2={W} y1={y(top * f)} y2={y(top * f)} stroke="#F0F0F0" />
                <text x={L - 8} y={y(top * f) + 4} textAnchor="end" fontSize="11" fill="var(--faint)">{Math.round(top * f)}</text>
              </g>
            ))}
            {rows.map((h, i) => {
              const x0 = L + 4 + i * slot;
              const live = i === rows.length - 1;
              return (
                <g key={h.minute} opacity={live ? 0.5 : 1}>
                  <title>{`${h.minute.slice(11, 16)}${live ? ' (진행 중)' : ''} · 예약 ${h.reservations} · 결제 ${h.payments} · 취소 ${h.cancels} · 만료 ${h.expires}`}</title>
                  {SERIES.map(([k, , color, hatch], j) => (
                    <rect key={k} x={x0 + j * bw} y={y(h[k])} width={bw - 1} height={Math.max(h[k] ? 1 : 0, H - y(h[k]))} fill={hatch ? 'url(#hatch-stop)' : color} />
                  ))}
                  {((Number(h.minute.slice(14, 16)) % 5 === 0 && i < rows.length - 2) || live) && (
                    <text x={x0 + (bw * SERIES.length) / 2} y={H + 18} textAnchor="middle" fontSize="12" fill="var(--mut)">{h.minute.slice(11, 16)}</text>
                  )}
                </g>
              );
            })}
            {total === 0 && (
              <text x={(W + L) / 2} y={H / 2} textAnchor="middle" fontSize="13" fill="var(--faint)">최근 {rows.length}분 동안 예약·결제·취소·만료가 없어요</text>
            )}
          </svg>
        )}
      </div>
    </Card>
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
