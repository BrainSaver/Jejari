import { useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { memberLogin } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { useMember } from './useMember.js';

/**
 * U-01 로그인 — 최소판. 예매 흐름(U-03 → U-04 → U-05)을 이어 보려고 만든 것이라 시안의 왼쪽 그림판·회원가입 링크는 없다.
 * 담당(강다인·조영준)이 시안 docs/ui/U-01.html대로 다듬는다. ?next= 로 로그인 뒤 돌아갈 곳을 받는다.
 */
export default function Login() {
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const { member, checking } = useMember();
  const navigate = useNavigate();
  const [f, setF] = useState({ email: '', password: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (!checking && member) return <Navigate to={next} replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await memberLogin(f.email.trim(), f.password);
      navigate(next, { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <header className="top narrow"><h1>로그인</h1></header>
      <main className="body narrow">
        <p className="guide"><Icon name="info" /><span>가입할 때 쓴 이메일과 비밀번호를 넣고 [로그인]을 누르세요.</span></p>
        {error && (
          <div className="alert stop" role="alert"><Icon name="info" /><span>{error.message}</span></div>
        )}
        <form className="card" onSubmit={submit}>
          <div className="field">
            <label htmlFor="em">이메일</label>
            <input id="em" type="email" autoComplete="username" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} required />
          </div>
          <div className="field">
            <label htmlFor="pw">비밀번호</label>
            <input id="pw" type="password" autoComplete="current-password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required />
          </div>
          <button className="btn" type="submit" disabled={busy}>{busy ? '확인하는 중' : '로그인'}</button>
        </form>
        <p className="faint" style={{ fontSize: 12.5, textAlign: 'center', margin: 0 }}>최소판 화면이에요 · 시안 docs/ui/U-01.html · 담당 강다인·조영준</p>
      </main>
    </>
  );
}

/** 다른 사이트로 보내는 주소(//evil.com 등)는 받지 않는다 */
const safeNext = (n) => (n && n.startsWith('/') && !n.startsWith('//') ? n : '/');
