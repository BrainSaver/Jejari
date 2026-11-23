import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router';
import { get, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { ConfirmButton } from '../admin/ui.jsx';
import { GRADE, hm, mmss, people, RES_STATUS, seatText, shortDate, useLeft, won } from './fmt.js';
import RouteLine from './RouteLine.jsx';
import { useMember } from './useMember.js';

/**
 * U-06 결제 (PAY-01, RSV-05, WL-05).
 *   GET  /api/v1/reservations/{id}            예약·좌석·결제 기한
 *   POST /api/v1/reservations/{id}/payments   결제하기 (Idempotency-Key, 본문 cardNumber)
 *   POST /api/v1/reservations/{id}/cancel     잡은 좌석 취소 (HOLD만, 수수료 없음)
 *
 * - 남은 시간은 서버의 결제 기한(pay_deadline_at) 기준. 결제를 시작하면 서버가 기한을 한 번 늘리고 새 기한을 돌려준다
 * - 결제 수단 등록 API가 아직 없어 이 화면에서 가상 카드번호를 받는다 (기능설계서 1.1). 서버는 해시·끝 4자리만 저장
 * - Mock PG는 카드 끝자리로 결과를 정한다: 0 거절 · 9 결과 모름 · 8 늦은 승인 · 나머지 승인 (시연용 안내를 둔다)
 * - 멱등 키는 결제하기 한 번에 하나. 응답을 못 받아 다시 누르면 같은 키 → 결제는 한 번만
 */
const TEST_CARDS = [
  ['4111111111111111', '바로 승인'],
  ['4111111111111118', '늦게 승인 (확인 중 → 확정)'],
  ['4111111111111119', '결과 모름 (확인 중)'],
  ['4111111111111110', '거절'],
];
const MESSAGES = {
  RESERVATION_EXPIRED: '결제 기한이 지나 잡아 둔 좌석이 풀렸어요. 결제는 되지 않았어요.',
  SEAT_LOST: '좌석을 잃었어요. 다시 예매해 주세요.',
  PAYMENT_IN_PROGRESS: '결제 중이라 지금은 취소할 수 없어요.',
  PAYMENT_RISK_BLOCKED: '이상 이용이 감지되어 결제가 제한되었어요. 잡아 둔 좌석은 풀렸고 결제는 되지 않았어요. 문의는 고객센터로 해 주세요.',
};

export default function Pay() {
  const { reservationId: id } = useParams();
  const { member, checking } = useMember();
  const navigate = useNavigate();
  const location = useLocation();
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  const [card, setCard] = useState(TEST_CARDS[0][0]);
  const [busy, setBusy] = useState(false);
  const key = useRef(null);
  const left = useLeft(d?.summary.payDeadlineAt);

  const load = () => get(`/api/v1/reservations/${id}`).then(setD).catch(setError);
  useEffect(() => {
    if (member) load();
  }, [member, id]);

  // 이미 결제가 시작·끝난 예약이면 결과 화면으로
  useEffect(() => {
    if (d && d.summary.status !== 'HOLD') navigate(`/pay/${id}/result`, { replace: true });
  }, [d]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;

  const s = d?.summary;
  const expired = s && left <= 0;
  const digits = card.replace(/\D/g, '');

  const pay = async () => {
    if (busy || expired) return;
    setBusy(true);
    setError(null);
    key.current = key.current || crypto.randomUUID();
    try {
      await post(`/api/v1/reservations/${id}/payments`, { cardNumber: digits }, { 'Idempotency-Key': key.current });
      navigate(`/pay/${id}/result`);
    } catch (e) {
      if (e.code === 'PAYMENT_DUPLICATED') navigate(`/pay/${id}/result`);
      else if (e.code === 'RESERVATION_NOT_HOLD') load();
      else if (e.code === 'PAYMENT_RISK_BLOCKED') { setError(e); load(); } // 좌석이 풀려 예약이 취소 상태로 바뀜
      else setError(e);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    try {
      await post(`/api/v1/reservations/${id}/cancel`);
      navigate('/reservations');
    } catch (e) {
      setError(e);
    }
  };

  const fare = d?.seats?.[0]?.amount;
  const sameFare = d?.seats?.every((x) => x.amount === fare);

  return (
    <>
      <header className="top"><h1>결제</h1></header>
      <main className="body">
        <p className="guide"><Icon name="info" /><span>좌석과 금액을 확인하고 [결제하기]를 누르세요. 남은 시간 안에 눌러야 표가 확정돼요.</span></p>
        <ol className="flow" aria-label="예매 단계">
          <li className="done"><b>✓</b>줄 서기</li><li className="done"><b>✓</b>좌석 고르기</li>
          <li className="now" aria-current="step"><b>3</b>결제</li><li><b>4</b>확정</li>
        </ol>
        {!d && !error && <p className="mut">예약을 불러오는 중…</p>}
        {d && (
          <div className="lay">
            <div className="c1">
              {(expired || error) && (
                <div className="alert stop" role="alert" style={{ order: 1, flexWrap: 'wrap' }}>
                  <Icon name="info" />
                  <span className="grow">{expired ? MESSAGES.RESERVATION_EXPIRED : MESSAGES[error.code] || error.message}</span>
                  {(expired || error?.code === 'RESERVATION_EXPIRED' || error?.code === 'SEAT_LOST') && (
                    <Link className="btn sm" to="/" style={{ marginLeft: 28 }}>다시 열차 찾기</Link>
                  )}
                </div>
              )}
              <article className="ticket" style={{ order: 3 }}>
                <div className="row">
                  <span className="tno grow"><b>{s.trainNo}</b> 열차<span className="dd">{shortDate(s.runDate)}</span></span>
                  <span className={'st ' + RES_STATUS[s.status][0]}>{RES_STATUS[s.status][1]}</span>
                </div>
                <RouteLine from={s.from} to={s.to} ride />
                <div className="perf" />
                <div className="kv"><span>좌석</span><span>{seatText(d.seats)} · {GRADE[d.seats[0]?.grade]}</span></div>
                <div className="kv"><span>인원</span><span>{people(d.seats)}</span></div>
                <div className="kv"><span>운임</span><span>{sameFare ? `${won(fare)} × ${d.seats.length}` : d.seats.map((x) => won(x.amount)).join(' + ')}</span></div>
                <div className="kv total"><span>결제 금액</span><span className="num" style={{ fontSize: 20 }}>{won(s.totalAmount)}</span></div>
              </article>
              <section className="card" style={{ order: 4 }}>
                <div className="row" style={{ marginBottom: 10 }}><b className="grow">결제 수단 · 가상 카드</b></div>
                <div className="pms stack">
                  {TEST_CARDS.map(([no, label]) => (
                    <label key={no} className={'pm' + (digits === no ? ' on' : '')}>
                      <input type="radio" name="card" checked={digits === no} onChange={() => setCard(no)} style={{ width: 18, minHeight: 18 }} />
                      <span className="grow"><b>시험 카드 ···· {no.slice(-4)}</b><br /><span className="mut" style={{ fontSize: 13 }}>{label}</span></span>
                    </label>
                  ))}
                </div>
                <div className="field" style={{ margin: '12px 0 0' }}>
                  <label htmlFor="cn">가상 카드번호 직접 넣기</label>
                  <input id="cn" inputMode="numeric" value={card} onChange={(e) => setCard(e.target.value)} />
                  <span className="hint">실제 카드번호는 넣지 마세요. 12~19자리 숫자, 끝자리로 시험 결과가 정해져요 (0 거절 · 9 결과 모름 · 8 늦은 승인).</span>
                </div>
              </section>
            </div>
            <aside className="c2 stick">
              <section className={'clock' + (left < 120 ? ' late' : '')} style={{ order: 2 }}>
                <div className="row" style={{ fontSize: 13.5, color: '#B9C1D3' }}><span className="grow">결제 기한</span><span>{hm(s.payDeadlineAt)}까지</span></div>
                <div className="big" role="timer" aria-live="off">{mmss(left)}</div>
                <div className="drain"><i style={{ width: `${Math.min(100, (left / 600) * 100)}%` }} /></div>
                <div style={{ fontSize: 13, color: '#B9C1D3', marginTop: 10 }}>시간이 지나면 좌석이 다음 분께 넘어가요.</div>
              </section>
              <div className="stack" style={{ order: 5 }}>
                <button className={'btn' + (expired ? ' off' : '')} type="button" disabled={busy || expired || !/^\d{12,19}$/.test(digits)} onClick={pay}>
                  {busy ? <><span className="spinner" />결제하는 중…</> : `${won(s.totalAmount)} 결제하기`}
                </button>
                <ConfirmButton className="btn ghost" confirm="한 번 더 누르면 좌석을 놓아요" onConfirm={cancel} disabled={expired}>
                  잡은 좌석 취소
                </ConfirmButton>
              </div>
            </aside>
          </div>
        )}
      </main>
    </>
  );
}
