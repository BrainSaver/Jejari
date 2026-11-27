import { useEffect, useRef, useState } from 'react';
import { formatCard } from '../lib/format.js';
import { uuid } from '../lib/uuid.js';
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router';
import { get, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { ConfirmButton } from '../admin/ui.jsx';
import { GRADE, hm, mmss, people, RES_STATUS, seatText, shortDate, toMs, useLeft, won } from './fmt.js';
import { BUSY_RETRY_MAX, busyWaitSec } from './payBusy.js';
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
 * - 결제 요청이 몰리면(503 PAYMENT_BUSY + Retry-After) 그만큼 기다렸다 같은 키로 자동으로 다시 보낸다 (payBusy.js).
 *   서버가 결제 행을 만들기 전에 거절하고 멱등 키를 지우므로 두 번 결제되지 않고 좌석도 그대로다
 */
/**
 * 시험 카드는 회원마다 번호가 다르다 (4111 + 회원 ID 11자리 + 끝자리). 모두 같은 번호를 쓰면 카드 한 장에 붙는
 * 명의 수 상한(payment.max_ci_per_card, concurrency.md §4.3)을 켰을 때 네 번째 사람부터 결제가 막힌다.
 * 같은 회원은 늘 같은 번호라 카드 하나로 센다. 결과는 끝자리만 보므로(Mock PG) 가운데 숫자는 무엇이든 된다
 */
const testCards = (memberId) => {
  const mid = String(Number(memberId) % 1e11 || 1).padStart(11, '0');
  return [
    [`4111${mid}1`, '바로 승인'],
    [`4111${mid}8`, '늦게 승인 (확인 중 → 확정)'],
    [`4111${mid}9`, '결과 모름 (확인 중)'],
    [`4111${mid}0`, '거절'],
  ];
};
const MESSAGES = {
  RESERVATION_EXPIRED: '결제 기한이 지나 잡아 둔 좌석이 풀렸어요. 결제는 되지 않았어요.',
  SEAT_LOST: '좌석을 잃었어요. 다시 예매해 주세요.',
  PAYMENT_IN_PROGRESS: '결제 중이라 지금은 취소할 수 없어요.',
  PAYMENT_RISK_BLOCKED: '이상 이용이 감지되어 결제가 제한되었어요. 잡아 둔 좌석은 풀렸고 결제는 되지 않았어요. 문의는 고객센터로 해 주세요.',
  // 도용 카드 상한 (concurrency.md §4.3). 좌석은 그대로라 다른 카드로 바로 다시 결제할 수 있다
  PAYMENT_BUSY: '결제 요청이 몰려 아직 처리하지 못했어요. 좌석은 그대로 잡혀 있으니 잠시 후 [결제하기]를 다시 눌러 주세요.',
  PAYMENT_CARD_LINK_LIMIT: '이 카드는 여러 사람이 이미 쓰고 있어 결제할 수 없어요. 잡아 둔 좌석은 그대로니 다른 카드로 결제해 주세요.',
};

export default function Pay() {
  const { reservationId: id } = useParams();
  const { member, checking } = useMember();
  const navigate = useNavigate();
  const location = useLocation();
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  const cards = testCards(member?.sub);
  const [card, setCard] = useState(formatCard(cards[0][0])); // 4자리씩 띄워 보여 줌 (보낼 때는 숫자만)
  const [busy, setBusy] = useState(false);
  const key = useRef(null);
  const [wait, setWait] = useState(null); // 자동 재시도 대기 {until: ms, attempt}
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []); // 화면을 떠나면 재시도를 멈춘다
  const left = useLeft(d?.summary.payDeadlineAt);

  const load = () => get(`/api/v1/reservations/${id}`).then(setD).catch(setError);
  useEffect(() => {
    if (member) load();
  }, [member, id]);

  // 이미 결제가 시작·끝난 예약이면 결과 화면으로
  useEffect(() => {
    if (d && d.summary.status !== 'HOLD') navigate(`/pay/${id}/result`, { replace: true });
  }, [d]);

  // 처음 그릴 때 로그인 확인 전이면 회원 ID가 없어 임시 번호다 → 회원이 정해지면 그 회원의 시험 카드로
  useEffect(() => {
    if (member) setCard(formatCard(testCards(member.sub)[0][0]));
  }, [member?.sub]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;

  const s = d?.summary;
  const expired = s && left <= 0;
  const digits = card.replace(/\D/g, '');

  const pay = async () => {
    if (busy || expired) return;
    setBusy(true);
    setError(null);
    key.current = key.current || uuid();
    const deadline = toMs(s.payDeadlineAt);
    try {
      for (let attempt = 0; ; attempt++) {
        try {
          await post(`/api/v1/reservations/${id}/payments`, { cardNumber: digits }, { 'Idempotency-Key': key.current });
          if (alive.current) navigate(`/pay/${id}/result`);
          return;
        } catch (e) {
          // 몰려서 거절: Retry-After만큼(±20%) 기다렸다 같은 키로 다시. 기한이 모자라거나 횟수를 다 쓰면 사람에게 알린다
          const sec = e.code === 'PAYMENT_BUSY' ? busyWaitSec(e.retryAfter, attempt, (deadline - Date.now()) / 1000) : null;
          if (sec == null || !alive.current) throw e;
          setWait({ until: Date.now() + sec * 1000, attempt: attempt + 1 });
          await new Promise((ok) => setTimeout(ok, sec * 1000));
          setWait(null);
          if (!alive.current) return;
        }
      }
    } catch (e) {
      if (!alive.current) return;
      if (e.code === 'PAYMENT_DUPLICATED') navigate(`/pay/${id}/result`);
      else if (e.code === 'RESERVATION_NOT_HOLD') load();
      else if (e.code === 'PAYMENT_RISK_BLOCKED') { setError(e); load(); } // 좌석이 풀려 예약이 취소 상태로 바뀜
      else setError(e);
    } finally {
      setWait(null);
      if (alive.current) setBusy(false);
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
              {wait && !expired && (
                <div className="alert amber" role="status" aria-live="polite" style={{ order: 1 }}>
                  <Icon name="info" />
                  <span className="grow">
                    결제 요청이 몰려 있어요. 좌석은 그대로 잡혀 있어요.{' '}
                    <b className="num">{Math.max(0, Math.ceil((wait.until - Date.now()) / 1000))}초</b> 뒤 자동으로 다시 결제해요
                    ({wait.attempt}/{BUSY_RETRY_MAX}). 다시 누르지 않아도 돼요.
                  </span>
                </div>
              )}
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
                  {cards.map(([no, label]) => (
                    <label key={no} className={'pm' + (digits === no ? ' on' : '')}>
                      <input type="radio" name="card" checked={digits === no} onChange={() => setCard(formatCard(no))} style={{ width: 18, minHeight: 18 }} />
                      <span className="grow"><b>시험 카드 ···· {no.slice(-4)}</b><br /><span className="mut" style={{ fontSize: 13 }}>{label}</span></span>
                    </label>
                  ))}
                </div>
                <div className="field" style={{ margin: '12px 0 0' }}>
                  <label htmlFor="cn">가상 카드번호 직접 넣기</label>
                  <input id="cn" inputMode="numeric" autoComplete="off" placeholder={formatCard(cards[0][0])} value={card} onChange={(e) => setCard(formatCard(e.target.value))} />
                  <span className="hint">실제 카드번호는 넣지 마세요. 12~19자리 숫자, 끝자리로 시험 결과가 정해져요 (0 거절 · 9 결과 모름 · 8 늦은 승인).</span>
                </div>
              </section>
            </div>
            <aside className="c2 stick">
              <section className={'clock' + (left < 120 ? ' late' : '')} style={{ order: 2 }}>
                <div className="row" style={{ fontSize: 13.5, color: '#B9C1D3' }}><span className="grow">남은 시간</span><span>{/* 큰 남은 시간(09:49)과 같은 모양이면 헷갈려 "9시 46분까지"로 */}{hm(s.payDeadlineAt).replace(/^0?(\d+):(\d+)$/, '$1시 $2분')}까지</span></div>
                <div className="big" role="timer" aria-live="off">{mmss(left)}</div>
                <div className="drain"><i style={{ width: `${Math.min(100, (left / 600) * 100)}%` }} /></div>
                <div style={{ fontSize: 13, color: '#B9C1D3', marginTop: 10 }}>시간이 지나면 좌석이 다음 분께 넘어가요.</div>
              </section>
              <div className="stack" style={{ order: 5 }}>
                <button className={'btn' + (expired ? ' off' : '')} type="button" disabled={busy || expired || !/^\d{12,19}$/.test(digits)} onClick={pay}>
                  {busy ? <><span className="spinner" />{wait ? `몰려서 ${Math.max(0, Math.ceil((wait.until - Date.now()) / 1000))}초 뒤 자동으로 다시 (${wait.attempt}/${BUSY_RETRY_MAX})` : '결제하는 중…'}</> : `${won(s.totalAmount)} 결제하기`}
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
