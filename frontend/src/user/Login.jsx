import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { get, memberLogin } from '../api/client.js';
import Icon, { Seal } from '../admin/Icon.jsx';
import { useMember } from './useMember.js';
import { BUSY_MESSAGE, isBusy } from '../lib/busy.js';

/**
 * U-01 로그인 (AUTH-03). POST /api/v1/auth/login → 액세스 토큰(메모리) + 리프레시 토큰(httpOnly 쿠키)
 * ?next= 로 로그인 뒤 돌아갈 곳(예: 줄 서기)을 받는다. 가입 직후에는 ?joined=1 로 와서 안내를 띄운다.
 * 실패하면 비밀번호 칸만 비운다. 어느 쪽이 틀렸는지는 말하지 않는다 (계정 존재 여부를 흘리지 않게).
 */
const MESSAGES = {
  AUTH_INVALID_CREDENTIALS: '이메일 또는 비밀번호가 맞지 않아요. 다시 입력해 주세요.',
  AUTH_LOCKED: '여러 번 틀려서 잠시 잠겼어요. 잠시 후 다시 시도해 주세요.', // 잠금 시간은 app_policy(auth.lock_minutes)라 화면에 박지 않음
  AUTH_RATE_LIMITED: '시도가 너무 많아요. 잠시 후 다시 시도해 주세요.',
  AUTH_BANNED: '이용이 정지된 계정이에요. 1588-0000으로 문의해 주세요.',
};

export default function Login() {
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const { member, checking } = useMember();
  const navigate = useNavigate();
  const [email, setEmail] = useState(params.get('email') || '');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(false); // 공용 PC를 생각해 기본은 끔
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [wait, setWait] = useState(null); // 로그인 대기실이 가득 차 자동으로 다시 보낼 때까지 남은 초
  const pwRef = useRef(null);
  const timer = useRef(null);
  useEffect(() => () => clearInterval(timer.current), []);
  // 비회원 예매를 켜 둔 때만 "비회원으로 예매"를 보여 준다 (app_policy guest.enabled)
  const [guestOn, setGuestOn] = useState(false);
  useEffect(() => {
    get('/api/v1/auth/guest/enabled').then((r) => setGuestOn(!!r.enabled)).catch(() => {});
  }, []);

  if (!checking && member) return <Navigate to={next} replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await memberLogin(email.trim(), password, remember, (sec) => {
        // 서버가 "N초 뒤 다시"라고 하면 남은 시간을 보여 주고 자동으로 다시 보낸다 (memberLogin이 기다림)
        clearInterval(timer.current);
        setWait(sec);
        timer.current = setInterval(() => setWait((w) => (w > 1 ? w - 1 : w)), 1000);
      });
      navigate(next, { replace: true });
    } catch (err) {
      if (isBusy(err)) {
        setError(BUSY_MESSAGE); // 서버가 바쁜 것 → 비밀번호를 다시 치게 하지 않는다
      } else {
        setError(MESSAGES[err.code] || err.message);
        setPassword('');
        pwRef.current?.focus();
      }
    } finally {
      clearInterval(timer.current);
      setWait(null);
      setBusy(false);
    }
  };

  return (
    <main className="body" style={{ paddingTop: 56 }}>
      <p className="guide"><Icon name="info" /><span>이메일과 비밀번호를 넣고 [로그인]을 누르세요. 처음이면 아래 [회원가입]을 누르세요.</span></p>
      <div className="m-only" style={{ padding: '0 4px 18px' }}>
        <Seal size={40} />
        <h1 style={{ fontSize: 30, lineHeight: 1.3, margin: '18px 0 6px', fontWeight: 800, fontFamily: 'var(--serif)' }}>명절 열차,<br />줄 선 순서대로.</h1>
        <p className="mut" style={{ margin: 0 }}>제자리에 로그인하고 예매를 이어가세요.</p>
      </div>
      <h1 className="d-only" style={{ fontSize: 26, margin: '0 0 4px' }}>로그인</h1>
      {params.get('joined') && !error && (
        <div className="alert go"><Icon name="info" /><span>가입했어요. 방금 정한 비밀번호로 로그인해 주세요.</span></div>
      )}
      {params.get('changed') && !error && (
        <div className="alert go"><Icon name="info" /><span>비밀번호를 바꿨어요. 보안을 위해 모든 기기에서 로그아웃됐으니 새 비밀번호로 다시 로그인해 주세요.</span></div>
      )}
      {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{error}</span></div>}
      {wait != null && (
        <div className="alert amber" role="status" aria-live="polite">
          <Icon name="clock" />
          <span>지금 로그인하는 사람이 많아 줄을 서는 중이에요. <b>{wait}초 뒤 자동으로 다시 시도해요.</b> 창을 닫거나 다시 누르지 않아도 돼요.</span>
        </div>
      )}
      <form onSubmit={submit} className="stack" style={{ gap: 0 }}>
        <div className="field">
          <label htmlFor="em">이메일</label>
          <input id="em" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div className="field">
          <label htmlFor="pw">비밀번호</label>
          <input id="pw" ref={pwRef} type="password" autoComplete="current-password" placeholder="비밀번호" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </div>
        <label className="row" style={{ gap: 8, fontSize: 14, margin: '0 0 12px', cursor: 'pointer' }}>
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} style={{ width: 18, minHeight: 18 }} />
          <span>로그인 상태 유지 <span className="mut" style={{ fontSize: 13 }}>(내 기기에서만. 끄면 브라우저를 닫을 때 로그아웃)</span></span>
        </label>
        <button className="btn" type="submit" disabled={busy}>{wait != null ? '줄 서는 중…' : busy ? '확인하는 중…' : '로그인'}</button>
      </form>
      <p className="mut" style={{ textAlign: 'center', margin: '6px 0 0', fontSize: 14 }}>
        처음이신가요? <Link className="link" to={`/signup?next=${encodeURIComponent(next)}`}>회원가입</Link>
      </p>
      {guestOn && (
        <div className="card" style={{ margin: '10px 0 0' }}>
          <b>가입 없이 예매하시나요?</b>
          <p className="mut" style={{ fontSize: 13.5, margin: '4px 0 10px' }}>본인인증만 하면 바로 예매할 수 있어요. 취소표 대기와 내 정보 변경은 회원만 쓸 수 있어요.</p>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <Link className="btn ghost" style={{ flex: '1 1 160px' }} to={`/guest?next=${encodeURIComponent(next)}`}>비회원으로 예매</Link>
            <Link className="btn ghost" style={{ flex: '1 1 160px' }} to="/guest/lookup">비회원 예약 조회</Link>
          </div>
        </div>
      )}
      <p style={{ textAlign: 'center', margin: 0 }}><Link className="link" to="/" style={{ fontSize: 14 }}>로그인 없이 열차 찾기</Link></p>
    </main>
  );
}

/** 다른 사이트로 보내는 주소(//evil.com 등)는 받지 않는다 */
export const safeNext = (n) => (n && n.startsWith('/') && !n.startsWith('//') ? n : '/');
