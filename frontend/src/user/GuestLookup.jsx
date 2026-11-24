import { useState } from 'react';
import { formatPhone } from '../lib/format.js';
import { Link } from 'react-router';
import { post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { RES_STATUS, hm, shortDate, won } from './fmt.js';

/**
 * 비회원 예약 조회 (로그인 없이). POST /api/v1/auth/guest/lookup {reservationId, phone}
 * 예약 번호가 순번이라 하나씩 넣어 보는 것을 막으려고 서버가 IP당 시간당 시도를 제한한다 (429).
 * 번호·휴대폰이 맞지 않거나 비회원 예약이 아니면 모두 같은 "맞지 않아요"로 알려 준다 (예약이 있는지 알려 주지 않는다).
 * 취소·결제는 여기서 못 한다 → 같은 본인인증으로 비회원 입장 뒤 예매 내역에서.
 */
const MESSAGES = {
  GUEST_LOOKUP_NOT_FOUND: '예약 번호나 휴대폰 번호가 맞지 않아요. 다시 확인해 주세요.',
  GUEST_DISABLED: '지금은 비회원 예약 조회를 쓸 수 없어요.',
  RATE_LIMITED: '조회를 너무 많이 시도했어요. 잠시 뒤에 다시 해 주세요.',
};

export default function GuestLookup() {
  const [form, setForm] = useState({ reservationId: '', phone: '' });
  const [res, setRes] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setRes(null);
    try {
      setRes(await post('/api/v1/auth/guest/lookup', { reservationId: Number(form.reservationId), phone: form.phone }));
    } catch (err) {
      setError(MESSAGES[err.code] || err.message);
    } finally {
      setBusy(false);
    }
  };

  const [tone, label] = res ? RES_STATUS[res.status] || ['', res.status] : ['', ''];
  return (
    <main className="body" style={{ paddingTop: 40 }}>
      <p className="guide"><Icon name="info" /><span>비회원으로 예매했다면 예약 번호와 예매할 때 쓴 휴대폰 번호로 조회할 수 있어요.</span></p>
      <h1 style={{ fontSize: 26, margin: '0 0 12px' }}>비회원 예약 조회</h1>
      {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{error}</span></div>}
      <form className="stack" style={{ gap: 0 }} onSubmit={submit}>
        <div className="field">
          <label htmlFor="gr">예약 번호</label>
          <input id="gr" inputMode="numeric" pattern="[0-9]*" value={form.reservationId} onChange={(e) => setForm({ ...form, reservationId: e.target.value.replace(/\D/g, '') })} required />
        </div>
        <div className="field">
          <label htmlFor="gp">휴대폰 번호</label>
          <input id="gp" inputMode="tel" autoComplete="tel" maxLength={13} placeholder="010-1234-5678" value={form.phone} onChange={(e) => setForm({ ...form, phone: formatPhone(e.target.value) })} required />
        </div>
        <button className="btn ink" type="submit" disabled={busy || !form.reservationId || !form.phone}>{busy ? '찾는 중…' : '조회'}</button>
      </form>

      {res && (
        <article className="ticket" style={{ marginTop: 16 }} aria-live="polite">
          <div className="row">
            <span className="tno grow"><b>{res.fromStation}</b> → <b>{res.toStation}</b></span>
            <span className={'st ' + tone}>{label}</span>
          </div>
          <div className="kv" style={{ marginTop: 8 }}><span>예약 번호</span><span className="num">{res.reservationId}</span></div>
          {res.departureAt && <div className="kv"><span>출발</span><span>{shortDate(res.departureAt)} {hm(res.departureAt)}</span></div>}
          <div className="kv"><span>좌석 수</span><span>{res.seatCount}석</span></div>
          <div className="kv"><span>금액</span><span>{won(res.totalAmount)}</span></div>
          {res.status === 'HOLD' && res.payDeadlineAt && <div className="kv"><span>결제 마감</span><span>{shortDate(res.payDeadlineAt)} {hm(res.payDeadlineAt)}</span></div>}
          <p className="mut" style={{ fontSize: 13, margin: '10px 0 0' }}>
            결제·취소는 <Link className="link" to="/guest?next=/reservations">비회원으로 다시 본인인증</Link>한 뒤 예매 내역에서 할 수 있어요.
          </p>
        </article>
      )}
      <p className="mut" style={{ textAlign: 'center', margin: '14px 0 0', fontSize: 14 }}>
        <Link className="link" to="/">열차 찾기로 돌아가기</Link>
      </p>
    </main>
  );
}
