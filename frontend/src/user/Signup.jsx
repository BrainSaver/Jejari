import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { safeNext } from './Login.jsx';

/**
 * U-02 회원가입·모의 본인인증 (AUTH-01·02). 두 단계:
 *   1. POST /api/v1/auth/verify  {name, phone, provider} → 1회용 verificationToken (가입 한 번에만 쓸 수 있음)
 *   2. POST /api/v1/auth/signup  {verificationToken, email, password} → 로그인 화면으로
 * 서버는 이름·휴대폰으로 CI 해시를 만들어 매수 제한을 사람 단위로 묶는다. 시험용 모의 인증이라 실제 정보를 넣지 않는다.
 * 비밀번호 규칙은 서버(PasswordPolicy)와 같은 것을 입력 중에 미리 보여 준다. 최종 판정은 서버.
 */
const PROVIDERS = [['MOCK_PASS', '모의 PASS'], ['MOCK_PHONE', '모의 휴대폰']];

export default function Signup() {
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const navigate = useNavigate();
  const [step, setStep] = useState(1);
  const [who, setWho] = useState({ name: '', phone: '', provider: 'MOCK_PASS' });
  const [verified, setVerified] = useState(null); // {token, expiresIn}
  const [acc, setAcc] = useState({ email: '', pw1: '', pw2: '' });
  const [error, setError] = useState(null);
  const [emailTaken, setEmailTaken] = useState(false);
  const [busy, setBusy] = useState(false);

  const verify = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await post('/api/v1/auth/verify', who);
      setVerified({ token: r.verificationToken, expiresIn: r.expiresIn });
      setStep(2);
    } catch (err) {
      setError(err.code === 'AUTH_VERIFY_FAILED' ? '본인인증에 실패했어요. 이름(한글·영문 2자 이상)과 휴대폰 번호를 확인해 주세요.' : err.message);
    } finally {
      setBusy(false);
    }
  };

  const rules = pwRules(acc.pw1, acc.email);
  const ok = rules.every((r) => r.ok) && acc.pw1 === acc.pw2;

  const signup = async (e) => {
    e.preventDefault();
    if (!ok) return;
    setBusy(true);
    setError(null);
    setEmailTaken(false);
    try {
      await post('/api/v1/auth/signup', { verificationToken: verified.token, email: acc.email.trim(), password: acc.pw1 });
      navigate(`/login?joined=1&email=${encodeURIComponent(acc.email.trim())}&next=${encodeURIComponent(next)}`, { replace: true });
    } catch (err) {
      if (err.code === 'AUTH_EMAIL_DUPLICATED') setEmailTaken(true);
      else if (err.code === 'AUTH_VERIFY_FAILED') {
        setError('본인인증 시간이 지났어요. 처음부터 다시 해 주세요.');
        setStep(1);
      } else setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="body" style={{ justifyContent: 'flex-start' }}>
      <p className="guide"><Icon name="info" /><span>본인인증을 먼저 하고, 다음 화면에서 이메일과 비밀번호를 정하면 가입이 끝나요.</span></p>
      <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
        {step === 1 ? (
          <>
            <li style={{ borderTop: '3px solid var(--ink)', paddingTop: 8, fontSize: 13, fontWeight: 700 }}>1 본인인증</li>
            <li style={{ borderTop: '3px solid var(--line)', paddingTop: 8, fontSize: 13, color: 'var(--faint)' }}>2 계정 만들기</li>
          </>
        ) : (
          <>
            <li style={{ borderTop: '3px solid var(--go)', paddingTop: 8, fontSize: 13, color: 'var(--go-d)', fontWeight: 600 }}>✓ 본인인증</li>
            <li style={{ borderTop: '3px solid var(--ink)', paddingTop: 8, fontSize: 13, fontWeight: 700 }}>2 계정 만들기</li>
          </>
        )}
      </ol>
      {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{error}</span></div>}

      {step === 1 ? (
        <form className="stack" style={{ gap: 0 }} onSubmit={verify}>
          <h2 style={{ fontSize: 20, margin: '10px 0 4px' }}>본인인증부터 할게요</h2>
          <p className="mut" style={{ margin: '0 0 18px', fontSize: 14 }}>한 사람이 한 계정만 쓰도록 확인해요. 시험용 모의 인증이라 실제 정보는 넣지 마세요.</p>
          <div className="field"><label htmlFor="nm">이름</label><input id="nm" value={who.name} onChange={(e) => setWho({ ...who, name: e.target.value })} required /></div>
          <div className="field">
            <label htmlFor="ph">휴대폰 번호</label>
            <input id="ph" inputMode="tel" placeholder="010-1234-5678" value={who.phone} onChange={(e) => setWho({ ...who, phone: e.target.value })} required />
            <span className="hint">끝이 0000이면 인증이 실패하도록 만든 시험 번호예요.</span>
          </div>
          <div className="field">
            <label htmlFor="mt">인증 수단</label>
            <select id="mt" value={who.provider} onChange={(e) => setWho({ ...who, provider: e.target.value })}>
              {PROVIDERS.map(([v, l]) => <option key={v} value={v}>{l} ({v})</option>)}
            </select>
          </div>
          <button className="btn ink" type="submit" disabled={busy}>{busy ? '인증하는 중…' : '인증하기'}</button>
        </form>
      ) : (
        <form className="stack" style={{ gap: 0 }} onSubmit={signup}>
          <div className="alert go" style={{ margin: '6px 0 16px' }}>
            <Icon name="info" /><span>본인인증을 마쳤어요. {who.name}, {maskPhone(who.phone)}</span>
          </div>
          <div className="field">
            <label htmlFor="em2">이메일</label>
            <input id="em2" type="email" autoComplete="username" value={acc.email} onChange={(e) => { setAcc({ ...acc, email: e.target.value }); setEmailTaken(false); }} required />
            {emailTaken && <span className="hint" style={{ color: 'var(--stop)', fontWeight: 700 }}>이미 가입된 이메일이에요.</span>}
          </div>
          <div className="field">
            <label htmlFor="pw1">비밀번호</label>
            <input id="pw1" type="password" autoComplete="new-password" value={acc.pw1} onChange={(e) => setAcc({ ...acc, pw1: e.target.value })} required />
            <ul style={{ listStyle: 'none', margin: '2px 0 0', padding: 0, fontSize: 12.5 }}>
              {rules.map((r) => (
                <li key={r.label} style={{ color: !acc.pw1 ? 'var(--mut)' : r.ok ? 'var(--go-d)' : 'var(--stop)', fontWeight: 700 }}>
                  {acc.pw1 ? (r.ok ? '✓ ' : '× ') : '· '}{r.label}
                </li>
              ))}
            </ul>
          </div>
          <div className="field">
            <label htmlFor="pw2">비밀번호 확인</label>
            <input id="pw2" type="password" autoComplete="new-password" value={acc.pw2} onChange={(e) => setAcc({ ...acc, pw2: e.target.value })} required />
            {acc.pw2 && acc.pw1 !== acc.pw2 && <span className="hint" style={{ color: 'var(--stop)', fontWeight: 700 }}>비밀번호가 서로 달라요.</span>}
          </div>
          <button className="btn" type="submit" disabled={busy || !ok}>{busy ? '가입하는 중…' : '가입하기'}</button>
        </form>
      )}
      <p className="mut" style={{ textAlign: 'center', margin: '6px 0 0', fontSize: 14 }}>
        이미 계정이 있나요? <Link className="link" to={`/login?next=${encodeURIComponent(next)}`}>로그인</Link>
      </p>
    </main>
  );
}

/**
 * 서버 PasswordPolicy와 같은 규칙 (흔한 비밀번호 목록은 서버만 본다).
 * 화면이 서버보다 엄격하면 서버가 받는 비밀번호도 [가입하기]가 막히므로, 기준을 서버와 똑같이 둔다.
 */
function pwRules(pw, email) {
  const local = (email.trim().split('@')[0] || '').toLowerCase();
  const lower = pw.toLowerCase();
  const len = [...pw].length;
  return [
    { label: '10자 이상 64자 이하', ok: len >= 10 && len <= 64 },
    { label: '서로 다른 문자 4종류 이상', ok: new Set(pw).size >= 4 },
    { label: '앞뒤 공백 없음', ok: pw === pw.trim() },
    { label: '이메일 아이디를 넣지 않기', ok: local.length < 4 || !lower.includes(local) },
    { label: '1234567·qwerty 같은 연속 글자로만 만들지 않기', ok: !isSequence(lower) },
  ];
}

/** 서버 PasswordPolicy.isSequence와 같음: 6자 이상 연속 패턴을 빼고 남는 게 4자 이하면 거절 */
const SEQUENCES = ['01234567890', '09876543210', 'abcdefghijklmnopqrstuvwxyz', 'zyxwvutsrqponmlkjihgfedcba',
  'qwertyuiop', 'poiuytrewq', 'asdfghjkl', 'lkjhgfdsa', 'zxcvbnm', 'mnbvcxz', '1q2w3e4r5t6y7u8i9o0p'];
function isSequence(lower) {
  let rest = lower;
  let removed = true;
  while (removed) {
    removed = false;
    for (const seq of SEQUENCES) {
      const longest = longestCommon(rest, seq);
      if (longest.length >= 6) {
        rest = rest.split(longest).join('');
        removed = true;
      }
    }
  }
  return rest.length < lower.length && [...rest].length <= 4;
}
function longestCommon(a, b) {
  let best = '';
  for (let i = 0; i < a.length; i++) {
    for (let j = a.length; j > i + best.length; j--) {
      const sub = a.slice(i, j);
      if (b.includes(sub)) {
        best = sub;
        break;
      }
    }
  }
  return best;
}

const maskPhone = (p) => {
  const d = p.replace(/\D/g, '');
  return d.length >= 8 ? `${d.slice(0, 3)}-****-${d.slice(-4)}` : p;
};
