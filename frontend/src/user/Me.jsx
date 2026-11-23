import { useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { api, get, logout } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { ConfirmButton } from '../admin/ui.jsx';
import { toMs } from './fmt.js';
import { setMyName } from './myName.js';
import { useMember } from './useMember.js';

/**
 * U-09 내 정보 (AUTH-06).
 *   GET    /api/v1/me          이름·휴대폰은 서버가 가린 값(마스킹)으로 준다
 *   POST   /api/v1/me/reveal   {password} 비밀번호를 다시 확인하고 가리지 않은 이름·휴대폰을 받는다
 *   PATCH  /api/v1/me          {currentPassword, name?, phone?, newPassword?} 정보 변경 — 바꿀 것만 보낸다
 *   DELETE /api/v1/me          탈퇴 {password} — 비밀번호를 다시 확인한다. 진행 중인 예약이 있으면 거절
 * 결제 수단 목록·등록(AUTH-07)은 서버 API가 아직 없어, 결제 화면(U-06)에서 가상 카드번호를 바로 받는다.
 */
const MESSAGES = {
  WITHDRAW_ACTIVE_RESERVATION: '결제 대기·확정된 표가 있어 탈퇴할 수 없어요. 먼저 취소해 주세요.',
  AUTH_INVALID_CREDENTIALS: '비밀번호가 맞지 않아요.',
  AUTH_LOCKED: '여러 번 틀려서 잠시 잠겼어요. 잠시 뒤에 다시 해 주세요.',
  PROFILE_NOTHING_TO_CHANGE: '바꿀 내용을 입력해 주세요.',
  PASSWORD_UNCHANGED: '지금 쓰는 비밀번호와 다른 비밀번호를 넣어 주세요.',
};
const REVEAL_SECONDS = 30; // 원본을 보여 주는 시간. 지나면 다시 가린다 (화면을 켜 둔 채 자리를 비워도 안전하게)

export default function Me() {
  const { member, checking } = useMember();
  const location = useLocation();
  const navigate = useNavigate();
  const [me, setMe] = useState(null);
  const [pw, setPw] = useState('');
  const [error, setError] = useState(null);

  // 원본 보기
  const [revealOpen, setRevealOpen] = useState(false);
  const [revealPw, setRevealPw] = useState('');
  const [revealed, setRevealed] = useState(null); // {name, phone}
  const [revealErr, setRevealErr] = useState(null);
  const [left, setLeft] = useState(0);
  const timer = useRef(null);
  const relogin = useRef(false);

  // 정보 변경
  const EMPTY = { name: '', phone: '', newPw: '', newPw2: '', current: '' };
  const [edit, setEdit] = useState(EMPTY);
  const [editOpen, setEditOpen] = useState(false);
  const [editErr, setEditErr] = useState(null);
  const [editOk, setEditOk] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (member) get('/api/v1/me').then(setMe).catch(setError);
  }, [member]);
  useEffect(() => () => clearInterval(timer.current), []);

  if (checking) return null;
  // 비밀번호를 바꿔 로그아웃된 것이면 "다시 로그인해 주세요" 안내(changed)를 달아 보낸다
  if (!member) return <Navigate to={relogin.current ? '/login?changed=1' : `/login?next=${encodeURIComponent(location.pathname)}`} replace />;

  const out = async () => {
    await logout();
    navigate('/', { state: { loggedOut: true } });
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

  const hide = () => {
    clearInterval(timer.current);
    setRevealed(null);
    setLeft(0);
  };
  const reveal = async (e) => {
    e.preventDefault();
    setRevealErr(null);
    setBusy(true);
    try {
      const v = await api('POST', '/api/v1/me/reveal', { password: revealPw });
      setRevealPw('');
      setRevealOpen(false);
      setRevealed(v);
      setLeft(REVEAL_SECONDS);
      clearInterval(timer.current);
      timer.current = setInterval(() => setLeft((s) => {
        if (s <= 1) {
          clearInterval(timer.current);
          setRevealed(null);
          return 0;
        }
        return s - 1;
      }), 1000);
    } catch (err) {
      setRevealErr(err);
      setRevealPw('');
    } finally {
      setBusy(false);
    }
  };

  const wantsChange = edit.name.trim() || edit.phone.trim() || edit.newPw;
  const pwMismatch = edit.newPw && edit.newPw !== edit.newPw2;
  const save = async (e) => {
    e.preventDefault();
    setEditErr(null);
    setEditOk(false);
    if (pwMismatch) {
      setEditErr({ message: '새 비밀번호 두 칸이 서로 달라요.' });
      return;
    }
    setBusy(true);
    try {
      const body = { currentPassword: edit.current };
      if (edit.name.trim()) body.name = edit.name;
      if (edit.phone.trim()) body.phone = edit.phone;
      if (edit.newPw) body.newPassword = edit.newPw;
      const res = await api('PATCH', '/api/v1/me', body);
      if (res.reloginRequired) {
        // 비밀번호를 바꾸면 서버가 모든 기기의 로그인을 끊는다 → 새 비밀번호로 다시 로그인
        relogin.current = true; // 로그아웃되면 위의 <Navigate>가 /login?changed=1로 보낸다
        await logout().catch(() => {});
        return;
      }
      setMe(res.me);
      setMyName(member.sub, res.me.name); // 오른쪽 위 이름도 바로 바꾼다
      hide(); // 보고 있던 원본은 옛 값이라 다시 가린다
      setEdit(EMPTY);
      setEditOpen(false);
      setEditOk(true);
    } catch (err) {
      setEditErr(err);
      setEdit((d) => ({ ...d, current: '' }));
    } finally {
      setBusy(false);
    }
  };

  const shownName = revealed ? revealed.name : me?.name;
  const shownPhone = revealed ? revealed.phone : me?.phone;

  return (
    <>
      <header className="top"><h1>내 정보</h1></header>
      <main className="body">
        <p className="guide"><Icon name="info" /><span>내 계정 정보를 보고 고치고, 로그아웃하거나 탈퇴할 수 있어요.</span></p>
        {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{MESSAGES[error.code] || error.message}</span></div>}
        {editOk && <div className="alert go" role="status"><Icon name="info" /><span>정보를 바꿨어요.</span></div>}
        {me && (
          <div className="lay">
            <div className="c1">
              <section className="card" style={{ order: 1 }}>
                <div className="row" style={{ gap: 14 }}>
                  <span style={{ width: 52, height: 52, borderRadius: 12, background: 'var(--sign)', color: 'var(--ink)', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 19, flex: 'none' }}>{(shownName || '?')[0]}</span>
                  <span className="grow"><b style={{ fontSize: 17 }}>{shownName}</b><br /><span className="mut" style={{ fontSize: 13.5 }}>{me.email}<br />{shownPhone}</span></span>
                </div>
                {revealed ? (
                  <div className="row" style={{ marginTop: 12, gap: 10 }}>
                    <span className="mut grow" style={{ fontSize: 13 }}>{left}초 뒤에 다시 가려요.</span>
                    <button type="button" className="btn sm ghost" onClick={hide}>지금 가리기</button>
                  </div>
                ) : revealOpen ? (
                  <form onSubmit={reveal} style={{ marginTop: 12 }}>
                    <p className="mut" style={{ fontSize: 13.5, margin: '0 0 8px' }}>이름과 휴대폰 번호를 모두 보려면 비밀번호를 한 번 더 넣어 주세요.</p>
                    {revealErr && <div className="alert stop" role="alert"><Icon name="info" /><span>{MESSAGES[revealErr.code] || revealErr.message}</span></div>}
                    <div className="row">
                      <input type="password" aria-label="비밀번호 확인" autoComplete="current-password" placeholder="비밀번호" value={revealPw} onChange={(e) => setRevealPw(e.target.value)} style={{ flex: 1 }} autoFocus />
                      <button type="submit" className="btn sm" disabled={!revealPw || busy}>확인</button>
                      <button type="button" className="btn sm ghost" onClick={() => { setRevealOpen(false); setRevealPw(''); setRevealErr(null); }}>취소</button>
                    </div>
                  </form>
                ) : (
                  <button type="button" className="btn sm ghost" style={{ marginTop: 12 }} onClick={() => setRevealOpen(true)}>
                    <Icon name="info" /> 가려진 정보 보기
                  </button>
                )}
              </section>
              {me.identityVerified && (
                <div className="alert go" style={{ order: 1 }}><Icon name="info" /><span>{new Date(toMs(me.joinedAt)).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })}에 본인인증을 마쳤어요</span></div>
              )}

              <div className="sec" style={{ order: 2 }}>정보 변경</div>
              <section className="card" style={{ order: 2 }}>
                {!editOpen ? (
                  <div className="row">
                    <span className="mut grow" style={{ fontSize: 14 }}>이름, 휴대폰 번호, 비밀번호를 바꿀 수 있어요.</span>
                    <button type="button" className="btn sm" onClick={() => { setEditOpen(true); setEditOk(false); }}>바꾸기</button>
                  </div>
                ) : (
                  <form onSubmit={save} className="stack" style={{ gap: 0 }}>
                    <p className="mut" style={{ fontSize: 13.5, margin: '0 0 10px' }}>바꿀 칸만 채우세요. 비워 두면 그대로예요. 마지막에 지금 비밀번호를 넣어야 바뀌어요.</p>
                    {editErr && <div className="alert stop" role="alert"><Icon name="info" /><span>{MESSAGES[editErr.code] || editErr.message}</span></div>}
                    <div className="field">
                      <label htmlFor="ed-name">새 이름</label>
                      <input id="ed-name" autoComplete="name" placeholder="바꾸지 않으면 비워 두세요" maxLength={50} value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} />
                    </div>
                    <div className="field">
                      <label htmlFor="ed-phone">새 휴대폰 번호</label>
                      <input id="ed-phone" type="tel" inputMode="tel" autoComplete="tel" placeholder="010-0000-0000" maxLength={20} value={edit.phone} onChange={(e) => setEdit({ ...edit, phone: e.target.value })} />
                    </div>
                    <div className="field">
                      <label htmlFor="ed-npw">새 비밀번호</label>
                      <input id="ed-npw" type="password" autoComplete="new-password" placeholder="10~64자 (바꾸면 모든 기기에서 로그아웃돼요)" maxLength={64} value={edit.newPw} onChange={(e) => setEdit({ ...edit, newPw: e.target.value })} />
                    </div>
                    {edit.newPw && (
                      <div className="field">
                        <label htmlFor="ed-npw2">새 비밀번호 확인</label>
                        <input id="ed-npw2" type="password" autoComplete="new-password" maxLength={64} value={edit.newPw2} onChange={(e) => setEdit({ ...edit, newPw2: e.target.value })} />
                        {pwMismatch && edit.newPw2 && <span className="faint" style={{ fontSize: 12.5, color: 'var(--stop, #C0392B)' }}>두 칸이 달라요.</span>}
                      </div>
                    )}
                    <div className="field">
                      <label htmlFor="ed-cur">지금 비밀번호</label>
                      <input id="ed-cur" type="password" autoComplete="current-password" placeholder="본인 확인" value={edit.current} onChange={(e) => setEdit({ ...edit, current: e.target.value })} />
                    </div>
                    <div className="row" style={{ gap: 10 }}>
                      <button className="btn" type="submit" disabled={busy || !wantsChange || !edit.current}>{busy ? '바꾸는 중…' : '저장'}</button>
                      <button className="btn ghost" type="button" onClick={() => { setEditOpen(false); setEdit(EMPTY); setEditErr(null); }}>취소</button>
                    </div>
                  </form>
                )}
              </section>

              <div className="sec" style={{ order: 4 }}>계정</div>
              <section className="card" style={{ padding: '4px 16px', order: 5 }}>
                <button type="button" className="row" onClick={out} style={{ width: '100%', border: 0, background: 'none', font: 'inherit', color: 'inherit', cursor: 'pointer', padding: '14px 0', borderBottom: '1px solid var(--line2)' }}>
                  <Icon name="out" /><span className="grow" style={{ textAlign: 'left' }}>로그아웃</span>
                </button>
                <div style={{ padding: '12px 0' }}>
                  <p className="mut" style={{ fontSize: 14, margin: '0 0 8px' }}>탈퇴하면 이름·연락처는 지워지고 대기 신청은 취소돼요. 지난 예매 기록은 이름·연락처 없이 남아요. 비밀번호를 한 번 더 넣어 주세요.</p>
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
