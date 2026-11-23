import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { logout } from '../api/client.js';
import Icon, { Seal } from './Icon.jsx';
import { useAdminAuth } from './useAdminAuth.js';

const MENU = [
  ['운영', [['dashboard', 'pulse', '실시간 현황'], ['traffic', 'flow', '트래픽 관제'], ['risk', 'shield', '위험 사용자'], ['cases', 'folder', '케이스'], ['models', 'swap', '모델 관리']]],
  ['기준·설정', [['master', 'train', '기준 데이터'], ['runs', 'calendar', '운행·행사'], ['settings', 'gear', '운영 설정'], ['deploy', 'refresh', '배포']]],
];

export default function AdminLayout() {
  const { claims, checking } = useAdminAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  if (checking) return <div className="admin" />;
  if (!claims) return <Navigate to="/admin/login" replace />;

  const out = async () => {
    await logout();
    navigate('/admin/login');
  };

  return (
    <div className="admin">
      <div className="wrap">
        <nav className="side" aria-label="관리자 메뉴">
          <div className="brand">
            <Seal />
            <div>제자리<small>운영 콘솔</small></div>
          </div>
          {MENU.map(([grp, items]) => (
            <div key={grp}>
              <div className="grp">{grp}</div>
              {items.map(([to, icon, label]) => (
                <NavLink key={to} to={to}>
                  <Icon name={icon} />
                  {label}
                </NavLink>
              ))}
            </div>
          ))}
          <button type="button" className="link out" onClick={out}>
            <Icon name="out" />
            로그아웃
          </button>
        </nav>
        <main className="main">
          <div key={pathname} className="page-enter">
            <Outlet context={claims} />
          </div>
        </main>
      </div>
    </div>
  );
}

/** 화면 머리글 오른쪽의 "누가 어떤 권한으로" */
export function Who({ claims }) {
  return (
    <div className="who">
      <i>{(claims?.adm || '?')[0]}</i>관리자 #{claims?.sub}
      <span style={{ color: 'var(--faint)' }}>{claims?.adm}</span>
    </div>
  );
}
