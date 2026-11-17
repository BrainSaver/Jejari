import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useParams } from 'react-router';
import { get } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { seatText, shortDate, won } from './fmt.js';
import RouteLine from './RouteLine.jsx';
import { useMember } from './useMember.js';

/**
 * U-07 결제 결과 (PAY-02·03). GET /api/v1/reservations/{id}
 * PAYMENT_PENDING(PG 응답을 아직 모름)이면 3초마다 다시 읽는다. 서버의 확인 스캐너가 PG에 물어 확정·실패로 정리한다.
 * 1분이 넘어도 그대로면 "늦어지고 있어요"로 바꾸고 계속 기다린다 (화면을 닫아도 서버가 끝까지 정리한다).
 */
export default function PayResult() {
  const { reservationId: id } = useParams();
  const { member, checking } = useMember();
  const location = useLocation();
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  const [slow, setSlow] = useState(false);
  const started = useRef(Date.now());

  useEffect(() => {
    if (!member) return;
    let alive = true;
    let t;
    const load = () =>
      get(`/api/v1/reservations/${id}`)
        .then((r) => {
          if (!alive) return;
          setD(r);
          if (r.summary.status === 'PAYMENT_PENDING' || r.summary.status === 'HOLD') {
            if (Date.now() - started.current > 60_000) setSlow(true);
            t = setTimeout(load, 3000);
          }
        })
        .catch((e) => alive && setError(e));
    load();
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [member, id]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;

  const s = d?.summary;
  const state = !s ? null : s.status === 'CONFIRMED' ? 'ok' : s.status === 'EXPIRED' || s.status === 'CANCELED' ? 'fail' : 'wait';
  const refunded = d?.payment?.status === 'REFUNDED' || d?.cancellation?.refundStatus;
  const ticketNo = d?.seats?.find((x) => x.ticketNo)?.ticketNo;

  const GUIDE = {
    ok: '표가 확정됐어요. 예매 내역에서 언제든 다시 볼 수 있어요.',
    wait: '결제 결과를 확인하고 있어요. 이 화면을 닫아도 괜찮아요.',
    fail: '결제가 되지 않았어요. 다른 열차를 찾아보세요.',
  };

  return (
    <>
      <header className="top narrow"><h1>결제 결과</h1></header>
      <main className="body narrow" style={{ paddingTop: 20 }}>
        {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{error.code === 'RESERVATION_NOT_FOUND' ? '예약을 찾을 수 없어요.' : error.message}</span></div>}
        {state && <p className="guide"><Icon name="info" /><span>{GUIDE[state]}</span></p>}
        <ol className="flow" aria-label="예매 단계">
          <li className="done"><b>✓</b>줄 서기</li><li className="done"><b>✓</b>좌석 고르기</li>
          <li className={state === 'ok' ? 'done' : 'now'}><b>{state === 'ok' ? '✓' : 3}</b>결제</li>
          <li className={state === 'ok' ? 'now' : ''}><b>4</b>확정</li>
        </ol>
        {state === 'ok' && (
          <section className="res">
            <div className="mark" style={{ background: 'var(--go)', color: '#fff' }}><Icon name="ticket" /></div>
            <h2>예매가 확정됐어요</h2><p className="mut" style={{ margin: 0 }}>즐거운 명절 보내세요.</p>
          </section>
        )}
        {state === 'wait' && (
          <section className="res" aria-live="polite">
            <div className="mark" style={{ background: 'var(--amber-t)', color: 'var(--amber-d)' }}><span className="spin" style={{ display: 'grid' }}><Icon name="refresh" /></span></div>
            <h2>결제 결과를 확인하고 있어요</h2>
            <p className="mut" style={{ margin: 0 }}>
              {slow ? '결과가 늦어지고 있어요. 확정되면 예매 내역과 알림에서 알려 드릴게요.' : '카드사 응답을 기다리는 중이에요. 3초마다 다시 확인해요.'}
            </p>
          </section>
        )}
        {state === 'fail' && (
          <section className="res">
            <div className="mark" style={{ background: 'var(--stop-t)', color: 'var(--stop)' }}><Icon name="info" /></div>
            <h2>결제가 승인되지 않았어요</h2>
            <p className="mut" style={{ margin: 0 }}>
              {refunded ? '결제 금액은 환불돼요. ' : '돈은 빠져나가지 않았고, '}잡아 둔 좌석은 다른 분께 돌아갔어요.
            </p>
          </section>
        )}
        {s && state !== 'fail' && (
          <article className="ticket">
            <div className="row">
              <span className="tno grow"><b>{s.trainNo}</b> 열차<span className="dd">{shortDate(s.runDate)}</span></span>
              {state === 'ok' ? <span className="st go">확정</span> : <span className="st amber">확인 중</span>}
            </div>
            <RouteLine from={s.from} to={s.to} />
            <div className="kv" style={{ marginTop: 8 }}><span>좌석</span><span>{seatText(d.seats)}</span></div>
            <div className="kv"><span>결제 금액</span><span>{won(s.totalAmount)}</span></div>
            <div className="perf" />
            <div className="stub">
              <span className="mut" style={{ fontSize: 13 }}>승차권 번호</span>
              {ticketNo ? <span className="code">{ticketNo}</span> : <span className="faint">확정되면 나와요</span>}
            </div>
            {state === 'ok' && <span className="stamp" style={{ position: 'absolute', left: '46%', top: 108, fontSize: 20 }}>확정</span>}
          </article>
        )}
        {state === 'ok' && (
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <button className="btn ghost" type="button" style={{ flex: '1 1 220px' }} onClick={() => window.print()}>큰 글씨로 인쇄하기</button>
          </div>
        )}
        <div className="stack rbtns" style={{ marginTop: 'auto' }}>
          {state === 'fail' ? (
            <Link className="btn" to="/">다른 열차 찾기</Link>
          ) : (
            <>
              <Link className="btn ghost" to="/">처음으로</Link>
              <Link className="btn" to="/reservations">예매 내역 보기</Link>
            </>
          )}
        </div>
      </main>
    </>
  );
}
