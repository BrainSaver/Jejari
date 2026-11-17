import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { get, logout } from '../api/client.js';
import Icon, { Seal } from '../admin/Icon.jsx';
import { AUTH_ART } from './art.js';
import { EaseBar, EaseRoot } from './ease.jsx';
import { useMember } from './useMember.js';

/**
 * 사용자 화면 공통 틀 (시안 docs/ui/U-*.html): 도우미 줄 + PC 위쪽 메뉴 / 모바일 아래 탭.
 * 알림 배지는 읽지 않은 알림 수 (로그인했을 때만, 화면을 옮길 때마다 다시 읽음)
 */
const MENU = [
  ['/', 'search', '검색'],
  ['/reservations', 'ticket', '예매 내역'],
  ['/waitlist', 'clock', '취소표'],
  ['/notifications', 'bell', '알림'],
  ['/me', 'person', '내 정보'],
];

export default function UserLayout() {
  const { pathname } = useLocation();
  const { member } = useMember();
  const navigate = useNavigate();
  const unread = useUnread(member, pathname);

  return (
    <EaseRoot>
      <div className="app">
        <EaseBar />
        <header className="gnb">
          <Link className="logo" to="/"><Seal />제자리</Link>
          <nav aria-label="주요 메뉴">
            {MENU.map(([to, , label]) => (
              <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive ? 'on' : '')}>
                {label}
                {to === '/notifications' && unread > 0 && <span className="cnt">{unread}</span>}
              </NavLink>
            ))}
          </nav>
          {member ? (
            <button type="button" className="me" style={{ border: 0, background: 'none', cursor: 'pointer', font: 'inherit' }}
              onClick={() => logout().then(() => navigate('/'))}>
              <i><Icon name="person" /></i>회원 #{member.sub} · 로그아웃
            </button>
          ) : (
            <Link className="me" to={`/login?next=${encodeURIComponent(pathname)}`}><i><Icon name="person" /></i>로그인</Link>
          )}
        </header>

        {/* 경로가 바뀌면 key가 바뀌어 새로 그려지며 살짝 올라오며 나타난다 */}
        <div key={pathname} className="page-enter" style={{ display: 'flex', flexDirection: 'column', flex: 1, minWidth: 0 }}>
          <Outlet />
        </div>

        <nav className="tabbar" aria-label="주요 메뉴">
          {MENU.map(([to, icon, label]) => (
            <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive ? 'on' : '')}>
              <span className={to === '/notifications' && unread > 0 ? 'dot' : ''}><Icon name={icon} /></span>
              {label}
            </NavLink>
          ))}
        </nav>
      </div>
    </EaseRoot>
  );
}

function useUnread(member, pathname) {
  const [n, setN] = useState(0);
  useEffect(() => {
    if (!member) return setN(0);
    get('/api/v1/notifications?size=1')
      .then((r) => setN(r?.unreadCount || 0))
      .catch(() => {});
  }, [member, pathname]);
  return n;
}

/**
 * 로그인·회원가입 틀 (시안 U-01·U-02의 .app.auth): PC는 왼쪽 남색 그림판 + 오른쪽 폼, 모바일은 폼만
 */
export function AuthLayout() {
  const { pathname } = useLocation();
  return (
    <EaseRoot>
      <div className="app auth">
        <aside className="auth-hero">
          <div className="art" aria-hidden="true" dangerouslySetInnerHTML={{ __html: AUTH_ART }} />
          <div className="row" style={{ gap: 12 }}><Seal size={36} /><b style={{ fontSize: 18 }}>제자리</b></div>
          <div>
            <div className="num" style={{ fontSize: 22, color: 'var(--sign)', fontWeight: 600 }}>명절 승차권 공정 예매</div>
            <h2 style={{ fontSize: 48, lineHeight: 1.25, margin: '14px 0 16px', fontWeight: 800, fontFamily: 'var(--serif)' }}>
              명절 열차,<br />줄 선 순서대로.
            </h2>
            <p style={{ fontSize: 17, color: '#D3D9E6', maxWidth: 440, lineHeight: 1.7, margin: 0 }}>
              먼저 줄 선 사람이 먼저 앉습니다. 대기열 순번, 좌석 선점, 결제 기한을 모두가 같은 규칙으로 지켜요.
            </p>
          </div>
          <p style={{ fontSize: 13, color: '#A9B6CC', margin: 0 }}>매크로·다중 계정 예매는 자동으로 걸러져요.</p>
        </aside>
        <div className="app-side">
          <EaseBar />
          <div key={pathname} className="page-enter" style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
            <Outlet />
          </div>
        </div>
      </div>
    </EaseRoot>
  );
}
