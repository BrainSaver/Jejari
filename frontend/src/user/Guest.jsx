import { useEffect, useState } from 'react';
import { formatPhone } from '../lib/format.js';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { get, guestEnter, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { BUSY_MESSAGE, isBusy } from '../lib/busy.js';
import { safeNext } from './Login.jsx';
import { useMember } from './useMember.js';

/**
 * 비회원 예매 입장. 회원가입 없이 본인인증만 하고 예매한다 (ERD v33, 설계: docs/김성원/팀-확인-요청-비회원-예매.md).
 *   1. POST /api/v1/auth/verify  {name, phone, provider} → 1회용 verificationToken
 *   2. POST /api/v1/auth/guest   {verificationToken}     → 로그인과 같은 토큰 (비밀번호 없음)
 * 본인인증을 받는 이유: 한 사람이 살 수 있는 표 수와 위험 점수를 회원과 똑같이 지키려고. 시험용 모의 인증이라 실제 정보는 넣지 않는다.
 */
const PROVIDERS = [['MOCK_PASS', '모의 PASS'], ['MOCK_PHONE', '모의 휴대폰']];

const MESSAGES = {
  GUEST_DISABLED: '지금은 비회원 예매를 쓸 수 없어요. 로그인해 주세요.',
  AUTH_BANNED: '이용이 정지된 사람이라 예매할 수 없어요.',
  AUTH_VERIFY_FAILED: '본인인증에 실패했어요. 이름(한글·영문 2자 이상)과 휴대폰 번호를 확인해 주세요.',
  GUEST_BUSY: '같은 본인인증으로 처리 중이에요. 잠시 뒤 다시 눌러 주세요.',
};

export default function Guest() {
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const navigate = useNavigate();
  const { member, checking } = useMember();
  const [enabled, setEnabled] = useState(null);
  const [who, setWho] = useState({ name: '', phone: '', provider: 'MOCK_PASS' });
  const [error, setError] = useState(null);
  const [isMember, setIsMember] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    get('/api/v1/auth/guest/enabled').then((r) => setEnabled(!!r.enabled)).catch(() => setEnabled(false));
  }, []);

  if (!checking && member) return <Navigate to={next} replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setIsMember(false);
    try {
      const v = await post('/api/v1/auth/verify', who);
      await guestEnter(v.verificationToken);
      navigate(next, { replace: true });
    } catch (err) {
      if (err.code === 'GUEST_IS_MEMBER') setIsMember(true);
      else setError(isBusy(err) ? BUSY_MESSAGE : MESSAGES[err.code] || err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="body" style={{ paddingTop: 40 }}>
      <p className="guide"><Icon name="info" /><span>가입 없이 본인인증만 하면 바로 예매할 수 있어요. 이름과 휴대폰 번호를 넣고 [본인인증하고 계속]을 누르세요.</span></p>
      <h1 style={{ fontSize: 26, margin: '0 0 4px' }}>비회원으로 예매</h1>
      <p className="mut" style={{ margin: '0 0 14px', fontSize: 14 }}>
        한 사람이 한 번에 살 수 있는 표 수를 모두에게 똑같이 지키려고 본인인증은 꼭 해요. 시험용 모의 인증이라 실제 정보는 넣지 마세요.
      </p>
      {enabled === false && (
        <div className="alert stop" role="alert"><Icon name="info" /><span>지금은 비회원 예매를 쓸 수 없어요. <Link className="link" to={`/login?next=${encodeURIComponent(next)}`}>로그인</Link>해 주세요.</span></div>
      )}
      {isMember && (
        <div className="alert amber" role="alert">
          <Icon name="info" />
          <span>이미 가입된 회원이에요. <Link className="link" to={`/login?next=${encodeURIComponent(next)}`}><b>로그인</b></Link>하고 이어서 예매해 주세요.</span>
        </div>
      )}
      {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{error}</span></div>}
      <form className="stack" style={{ gap: 0 }} onSubmit={submit}>
        <div className="field"><label htmlFor="gnm">이름</label><input id="gnm" autoComplete="name" value={who.name} onChange={(e) => setWho({ ...who, name: e.target.value })} required /></div>
        <div className="field">
          <label htmlFor="gph">휴대폰 번호</label>
          <input id="gph" inputMode="tel" autoComplete="tel" maxLength={13} placeholder="010-1234-5678" value={who.phone} onChange={(e) => setWho({ ...who, phone: formatPhone(e.target.value) })} required />
          <span className="hint">끝이 0000이면 인증이 실패하도록 만든 시험 번호예요.</span>
        </div>
        <div className="field">
          <label htmlFor="gmt">인증 수단</label>
          <select id="gmt" value={who.provider} onChange={(e) => setWho({ ...who, provider: e.target.value })}>
            {PROVIDERS.map(([v, l]) => <option key={v} value={v}>{l} ({v})</option>)}
          </select>
        </div>
        <button className="btn ink" type="submit" disabled={busy || enabled === false}>{busy ? '인증하는 중…' : '본인인증하고 계속'}</button>
      </form>
      <ul className="mut" style={{ fontSize: 13, margin: '14px 0 0', paddingLeft: 18 }}>
        <li>취소표 대기, 내 정보 변경, 탈퇴는 회원만 쓸 수 있어요.</li>
        <li>예약 번호를 꼭 적어 두세요. 로그인 없이 예약 번호와 휴대폰 번호로 조회할 수 있어요.</li>
        <li>표를 탄 뒤 일정 기간이 지나면 이름·연락처가 지워져요.</li>
      </ul>
      <p className="mut" style={{ textAlign: 'center', margin: '14px 0 0', fontSize: 14 }}>
        회원이신가요? <Link className="link" to={`/login?next=${encodeURIComponent(next)}`}>로그인</Link>
        {' · '}
        <Link className="link" to="/guest/lookup">비회원 예약 조회</Link>
      </p>
    </main>
  );
}
