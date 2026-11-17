import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { adminLogin } from '../api/client.js';
import { Seal } from './Icon.jsx';
import { useAdminAuth } from './useAdminAuth.js';

/** A-01 관리자 로그인 (최소판: 시안의 왼쪽 그림판은 아직 없음) */
export default function AdminLogin() {
  const { claims, checking } = useAdminAuth();
  const navigate = useNavigate();
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (!checking && claims) return <Navigate to="/admin/dashboard" replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await adminLogin(loginId.trim(), password);
      navigate('/admin/dashboard');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="admin">
      <div className="login">
        <form onSubmit={submit}>
          <div className="row" style={{ marginBottom: 16 }}>
            <Seal size={36} />
            <div>
              <h1>운영 콘솔</h1>
              <div className="mut" style={{ fontSize: 13 }}>관리자 계정으로 들어가요</div>
            </div>
          </div>
          {error && (
            <div className="alert stop" role="alert">
              {error.message}
              {error.traceId && <div className="faint" style={{ fontSize: 12 }}>traceId {error.traceId}</div>}
            </div>
          )}
          <div className="field">
            <label htmlFor="lid">관리자 ID</label>
            <input id="lid" autoComplete="username" value={loginId} onChange={(e) => setLoginId(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="pw">비밀번호</label>
            <input id="pw" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </div>
          <button className="btn wide" type="submit" disabled={busy}>{busy ? '확인하는 중' : '들어가기'}</button>
        </form>
      </div>
    </div>
  );
}
