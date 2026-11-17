import { useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { api, get, logout } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { ConfirmButton } from '../admin/ui.jsx';
import { toMs } from './fmt.js';
import { useMember } from './useMember.js';

/**
 * U-09 내 정보 (AUTH-06).
 *   GET    /api/v1/me      이름·휴대폰은 서버가 가린 값(마스킹)으로 준다
 *   DELETE /api/v1/me      탈퇴 {password} — 비밀번호를 다시 확인한다. 진행 중인 예약이 있으면 거절
 * 결제 수단 목록·등록(AUTH-07)은 서버 API가 아직 없어, 결제 화면(U-06)에서 가상 카드번호를 바로 받는다.
 */
const MESSAGES = {
  WITHDRAW_ACTIVE_RESERVATION: '결제 대기·확정된 표가 있어 탈퇴할 수 없어요. 먼저 취소해 주세요.',
  AUTH_INVALID_CREDENTIALS: '비밀번호가 맞지 않아요.',
  AUTH_LOCKED: '여러 번 틀려서 잠시 잠겼어요.',
};

export default function Me() {
  const { member, checking } = useMember();
  const location = useLocation();
  const navigate = useNavigate();
  const [me, setMe] = useState(null);
  const [pw, setPw] = useState('');
  const [error, setError] = useState(null);

  useEffect(() => {
    if (member) get('/api/v1/me').then(setMe).catch(setError);
  }, [member]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;

  const out = async () => {
    await logout();
    navigate('/login');
  };
  const withdraw = async () => {
    setError(null);
    try {
      await api('DELETE', '/api/v1/me', { password: pw });
      await logout().catch(() => {});
      navigate('/login');
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <header className="top"><h1>내 정보</h1></header>
      <main className="body">
        <p className="guide"><Icon name="info" /><span>내 계정 정보를 보고, 로그아웃하거나 탈퇴할 수 있어요.</span></p>
        {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{MESSAGES[error.code] || error.message}</span></div>}
        {me && (
          <div className="lay">
            <div className="c1">
              <section className="card row" style={{ gap: 14, order: 1 }}>
                <span style={{ width: 52, height: 52, borderRadius: 12, background: 'var(--sign)', color: 'var(--ink)', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 19, flex: 'none' }}>{(me.name || '?')[0]}</span>
                <span className="grow"><b style={{ fontSize: 17 }}>{me.name}</b><br /><span className="mut" style={{ fontSize: 13.5 }}>{me.email}<br />{me.phone}</span></span>
              </section>
              {me.identityVerified && (
                <div className="alert go" style={{ order: 1 }}><Icon name="info" /><span>{new Date(toMs(me.joinedAt)).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })}에 본인인증을 마쳤어요</span></div>
              )}
              <div className="sec" style={{ order: 4 }}>계정</div>
              <section className="card" style={{ padding: '4px 16px', order: 5 }}>
                <button type="button" className="row" onClick={out} style={{ width: '100%', border: 0, background: 'none', font: 'inherit', color: 'inherit', cursor: 'pointer', padding: '14px 0', borderBottom: '1px solid var(--line2)' }}>
                  <Icon name="out" /><span className="grow" style={{ textAlign: 'left' }}>로그아웃</span>
                </button>
                <div style={{ padding: '12px 0' }}>
                  <p className="mut" style={{ fontSize: 14, margin: '0 0 8px' }}>탈퇴하면 예매 내역과 대기 신청이 모두 사라져요. 비밀번호를 한 번 더 넣어 주세요.</p>
                  <div className="row">
                    <input type="password" aria-label="비밀번호 확인" placeholder="비밀번호" value={pw} onChange={(e) => setPw(e.target.value)} style={{ flex: 1 }} />
                    <ConfirmButton className="btn warn sm" confirm="한 번 더 누르면 탈퇴" onConfirm={withdraw} disabled={!pw}>탈퇴</ConfirmButton>
                  </div>
                </div>
              </section>
            </div>
            <div className="c2">
              <div className="sec" style={{ order: 2 }}>결제 수단</div>
              <section className="card" style={{ order: 3 }}>
                <p className="mut" style={{ margin: 0, fontSize: 14 }}>
                  카드 등록은 준비 중이에요. 지금은 결제할 때 가상 카드번호를 바로 넣어요. 실제 카드번호는 넣지 마세요.
                </p>
              </section>
            </div>
          </div>
        )}
      </main>
    </>
  );
}
