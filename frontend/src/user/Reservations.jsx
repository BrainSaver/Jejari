import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { get, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { ConfirmButton } from '../admin/ui.jsx';
import { hm, RES_STATUS, seatText, shortDate, toMs, won } from './fmt.js';
import RouteLine from './RouteLine.jsx';
import { useMember } from './useMember.js';

/**
 * U-08 예매 내역·상세·취소 (RSV-04, CNL-01·02).
 *   GET  /api/v1/reservations                      목록 (최근 것 먼저)
 *   GET  /api/v1/reservations/{id}                 상세 (좌석·승차권 번호·취소 가능 여부)
 *   GET  /api/v1/reservations/{id}/cancel-quote    취소 시트: 수수료·환불 예정액 (서버가 계산)
 *   POST /api/v1/reservations/{id}/cancel          취소 확정 (HOLD면 수수료 없이 선점 해제, 확정이면 수수료 뒤 환불)
 * 수수료는 화면이 계산하지 않는다. 서버가 출발까지 남은 시간으로 정책을 골라 계산한 값만 보여 준다.
 */
const CHIPS = [
  ['all', '전체', () => true],
  ['hold', '결제 대기', (s) => s === 'HOLD' || s === 'PAYMENT_PENDING'],
  ['ok', '확정', (s) => s === 'CONFIRMED'],
  ['done', '취소·만료', (s) => s === 'CANCELED' || s === 'EXPIRED'],
];
/** 서버 코드는 화면에 그대로 보이지 않게 한글로 */
const PAY_STATUS = { REQUESTED: '결제 요청', APPROVED: '결제 완료', FAILED: '결제 실패', REFUNDED: '환불 완료' };
const REFUND_STATUS = {
  DONE: '환불 완료',
  REQUESTED: '환불 진행 중',
  FAILED: '환불이 늦어지고 있어요. 자동으로 다시 시도해요',
};
const CANCEL_MSG = {
  CANCEL_NOT_ALLOWED: '지금은 취소할 수 없는 예약이에요 (출발 뒤 일정 시간이 지났어요).',
  PAYMENT_IN_PROGRESS: '결제 중이라 지금은 취소할 수 없어요.',
};

export default function Reservations() {
  const { member, checking } = useMember();
  const location = useLocation();
  const [items, setItems] = useState(null);
  const [chip, setChip] = useState('all');
  const [params] = useSearchParams();
  const [open, setOpen] = useState(Number(params.get('open')) || null); // 펼친 예약 id (알림에서 오면 그 표)
  const [sheet, setSheet] = useState(null); // 취소 시트를 연 예약 id
  const [error, setError] = useState(null);

  const load = () => get('/api/v1/reservations?size=50').then((p) => setItems(p.items)).catch(setError);
  // 알림에서 왔으면(?open=) 목록이 그려진 뒤 그 표로 내려간다
  useEffect(() => {
    const id = params.get('open');
    if (items && id) document.getElementById('res-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [items]);
  useEffect(() => {
    if (member) load();
  }, [member]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;

  const count = (f) => (items || []).filter((r) => f(r.status)).length;
  const shown = (items || []).filter((r) => CHIPS.find((c) => c[0] === chip)[2](r.status));

  return (
    <>
      <header className="top narrow"><h1>예매 내역</h1></header>
      <main className="body narrow">
        <p className="guide"><Icon name="info" /><span>결제하지 않은 표는 [결제하기]를 누르세요. 표를 취소하려면 [취소]를 누르세요.</span></p>
        {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{error.message}</span></div>}
        <div className="chips" role="tablist">
          {CHIPS.map(([k, label, f]) => (
            <button key={k} type="button" role="tab" aria-selected={chip === k} className={'chip' + (chip === k ? ' on' : '')} onClick={() => setChip(k)}>
              {label} {items ? count(f) : ''}
            </button>
          ))}
        </div>
        {items && !shown.length && (
          <div className="card" style={{ textAlign: 'center' }}>
            <b>{items.length ? '이 조건의 표가 없어요.' : '아직 예매한 표가 없어요.'}</b>
            <p style={{ margin: '10px 0 0' }}><Link className="link" to="/">열차 찾기</Link></p>
          </div>
        )}
        <div className="stagger" key={chip} style={{ display: 'flex', flexDirection: 'column', gap: 'inherit' }}>
        {shown.map((r, i) => (
          <ResCard key={r.reservationId} i={i} r={r} open={open === r.reservationId} onToggle={() => setOpen(open === r.reservationId ? null : r.reservationId)} onCancel={() => setSheet(r.reservationId)} onChanged={load} />
        ))}
        </div>
      </main>
      {sheet && <CancelSheet id={sheet} onClose={() => setSheet(null)} onDone={() => { setSheet(null); load(); }} />}
    </>
  );
}

function ResCard({ r, i, open, onToggle, onCancel, onChanged }) {
  const navigate = useNavigate();
  const [d, setD] = useState(null);
  const [msg, setMsg] = useState(null);
  const [cls, label] = RES_STATUS[r.status] || ['', r.status];
  const hold = r.status === 'HOLD';
  const dim = r.status === 'CANCELED' || r.status === 'EXPIRED';

  useEffect(() => {
    if (open || r.status === 'CONFIRMED' || r.status === 'CANCELED' || hold) get(`/api/v1/reservations/${r.reservationId}`).then(setD).catch(() => {});
  }, [open, r.reservationId, r.status]);

  const releaseHold = async () => {
    try {
      await post(`/api/v1/reservations/${r.reservationId}/cancel`);
      onChanged();
    } catch (e) {
      setMsg(CANCEL_MSG[e.code] || e.message);
    }
  };

  const ticket = d?.seats?.find((s) => s.ticketNo)?.ticketNo;
  const leftMin = r.from?.time ? Math.round((toMs(r.from.time) - Date.now()) / 60000) : null;

  return (
    <article id={'res-' + r.reservationId} className="ticket h" style={{ '--i': i, ...(hold ? { boxShadow: '0 0 0 2px var(--amber) inset' } : {}), ...(dim ? { opacity: 0.7 } : {}) }}>
      <div>
        <div className="row">
          <span className="tno grow"><b>{r.trainNo}</b> 열차<span className="dd">{shortDate(r.runDate)}</span></span>
          <span className={'st ' + cls}>{hold && r.payDeadlineAt ? `결제 기한 ${hm(r.payDeadlineAt)}` : label}</span>
        </div>
        <RouteLine from={r.from} to={r.to} />
        <button type="button" className="link" onClick={onToggle} aria-expanded={open} style={{ border: 0, background: 'none', padding: '10px 0 0', cursor: 'pointer', font: 'inherit', fontSize: 14 }}>
          {open ? '자세히 접기' : '자세히 보기'}
        </button>
        {open && d && (
          <div style={{ marginTop: 8 }}>
            <div className="kv"><span>예약 번호</span><span className="num">{r.reservationId}</span></div>
            {d.seats.map((s) => (
              <div className="kv" key={s.carNo + s.seatNo}><span>{s.carNo}호차 {s.seatNo}</span><span>{won(s.amount)}{s.ticketNo ? ` · ${s.ticketNo}` : ''}</span></div>
            ))}
            {d.payment && <div className="kv"><span>결제</span><span>{PAY_STATUS[d.payment.status] || d.payment.status} · {won(d.payment.amount)}</span></div>}
            {d.cancellation && (
              <div className="kv"><span>{d.cancellation.cancelType === 'ADMIN' ? '운영 취소' : '취소'}</span><span>수수료 {won(d.cancellation.fee)} · 환불 {won(d.cancellation.refundAmount)}{d.cancellation.refundStatus ? ` (${REFUND_STATUS[d.cancellation.refundStatus] || d.cancellation.refundStatus})` : ''}</span></div>
            )}
          </div>
        )}
      </div>
      <div className="perf" />
      <div className="rt">
        <div className="kv"><span>좌석</span><span>{d ? seatText(d.seats) : `${r.seatCount}석`}</span></div>
        <div className="kv"><span>금액</span><span>{won(r.totalAmount)}</span></div>
        {ticket && <div className="kv"><span>승차권</span><span className="num" style={{ fontSize: 16, fontWeight: 600 }}>{ticket}</span></div>}
        {msg && <p style={{ color: 'var(--stop)', fontWeight: 700, fontSize: 13.5, margin: '6px 0 0' }}>{msg}</p>}
        {hold && (
          <>
            <button className="btn" type="button" style={{ marginTop: 10 }} onClick={() => navigate(`/pay/${r.reservationId}`)}>결제하기</button>
            <ConfirmButton className="btn ghost sm" confirm="한 번 더 누르면 좌석을 놓아요" onConfirm={releaseHold}>선점 취소</ConfirmButton>
          </>
        )}
        {r.status === 'PAYMENT_PENDING' && (
          <Link className="btn ghost" to={`/pay/${r.reservationId}/result`} style={{ marginTop: 10 }}>결제 결과 보기</Link>
        )}
        {r.status === 'CONFIRMED' && (
          <div className="row" style={{ marginTop: 8 }}>
            <span className="grow mut" style={{ fontSize: 12.5 }}>
              {d?.cancel?.cancelable === false ? '지금은 취소할 수 없어요' : leftMin != null ? `출발 ${leftText(leftMin)}` : ''}
              {d?.cancel?.cancelable && d.cancel.feeRate != null ? <><br />지금 취소하면 수수료 {Math.round(d.cancel.feeRate * 100)}%</> : null}
            </span>
            <button className="btn ghost sm" type="button" onClick={onCancel} disabled={d?.cancel?.cancelable === false}>취소</button>
          </div>
        )}
        {r.status === 'EXPIRED' && <p className="mut" style={{ fontSize: 13, margin: 0 }}>결제 기한이 지나 좌석이 풀렸어요.</p>}
        {r.status === 'CANCELED' && (
          <p className="mut" style={{ fontSize: 13, margin: 0 }}>
            {d?.cancellation?.cancelType === 'ADMIN' ? '운영 사유로 취소됐어요. 수수료 없이 전액 환불해 드려요.' : '취소된 표예요.'}
          </p>
        )}
      </div>
    </article>
  );
}

/** 취소 시트: 서버가 계산한 수수료·환불 예정액을 보여 주고 두 번 눌러 확정 */
function CancelSheet({ id, onClose, onDone }) {
  const [q, setQ] = useState(null);
  const [error, setError] = useState(null);
  const [done, setDone] = useState(null);
  useEffect(() => {
    get(`/api/v1/reservations/${id}/cancel-quote`).then(setQ).catch(setError);
  }, [id]);
  const confirm = async () => {
    try {
      setDone(await post(`/api/v1/reservations/${id}/cancel`));
    } catch (e) {
      setError(e);
    }
  };
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label="예매 취소">
        <h2>표를 취소할까요?</h2>
        {error && <div className="alert stop"><Icon name="info" /><span>{CANCEL_MSG[error.code] || error.message}</span></div>}
        {done ? (
          <>
            <div className="alert go"><Icon name="info" /><span>취소했어요. {won(done.refundAmount)}을 돌려드려요.{done.refundStatus ? ` ${REFUND_STATUS[done.refundStatus] || done.refundStatus}.` : ''}</span></div>
            <button className="btn" type="button" style={{ marginTop: 12 }} onClick={onDone}>확인</button>
          </>
        ) : q ? (
          <>
            <div className="kv"><span>결제 금액</span><span>{won(q.paidAmount)}</span></div>
            <div className="kv"><span>출발까지</span><span>{leftText(q.minutesBeforeDeparture)}</span></div>
            <div className="kv"><span>수수료</span><span>{won(q.fee)}{q.feeRate != null ? ` (${Math.round(Number(q.feeRate) * 100)}%)` : ''}</span></div>
            <div className="kv total"><span>돌려받는 돈</span><span className="num">{won(q.refund)}</span></div>
            <div className="stack" style={{ marginTop: 14 }}>
              <ConfirmButton className="btn warn" confirm="한 번 더 누르면 취소돼요" onConfirm={confirm}>취소하기</ConfirmButton>
              <button className="btn ghost" type="button" onClick={onClose}>그만두기</button>
            </div>
          </>
        ) : !error && <p className="mut">수수료를 계산하는 중…</p>}
      </div>
    </div>
  );
}

function leftText(min) {
  if (min == null) return '';
  if (min < 0) return `${-min}분 지남`;
  if (min >= 1440) return `${Math.floor(min / 1440)}일 ${Math.floor((min % 1440) / 60)}시간 전`;
  if (min >= 60) return `${Math.floor(min / 60)}시간 ${min % 60}분 전`;
  return `${min}분 전`;
}
