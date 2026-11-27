import { Button, Layout, Menu, Space, Typography } from 'antd';
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router';
import { logout } from '../api/client.js';
import Icon, { Seal } from './Icon.jsx';
import { C } from './theme.js';
import { useAdminAuth } from './useAdminAuth.js';

const MENU = [
  ['운영', [['dashboard', 'pulse', '실시간 현황'], ['traffic', 'flow', '트래픽 관제'], ['risk', 'shield', '위험 사용자'], ['cases', 'folder', '케이스'], ['groups', 'person', '단체 신청'], ['models', 'swap', '모델 관리'], ['contests', 'flag', '봇 대회 결과']]],
  ['기준·설정', [['master', 'train', '기준 데이터'], ['runs', 'calendar', '운행·행사'], ['settings', 'gear', '운영 설정'], ['deploy', 'refresh', '배포']]],
];

// Ant Design으로 다시 만든 화면. 나머지는 예전 CSS(.admin …)를 그대로 쓰므로 .admin 안에 넣는다
const RENEWED = new Set(['/admin/dashboard', '/admin/settings', '/admin/cases']);

export default function AdminLayout() {
  const { claims, checking } = useAdminAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  if (checking) return <div className="adm-shell" />;
  if (!claims) return <Navigate to="/admin/login" replace />;

  const out = async () => {
    await logout();
    navigate('/admin/login');
  };
  const key = pathname.split('/')[2] || 'dashboard';
  const here = MENU.flatMap(([g, items]) => items.map(([k, , label]) => ({ g, k, label }))).find((m) => m.k === key);
  const renewed = RENEWED.has(pathname);

  const items = MENU.map(([grp, list]) => ({
    type: 'group',
    label: grp,
    children: list.map(([k, icon, label]) => ({ key: k, icon: <span className="anticon"><Icon name={icon} /></span>, label })),
  }));

  return (
    <Layout className="adm-shell" style={{ minHeight: '100vh' }}>
      <Layout.Sider width={232} breakpoint="lg" collapsedWidth={0} className="adm-sider">
        <div className="adm-brand">
          <Seal />
          <div>
            제자리<small>운영 콘솔</small>
          </div>
        </div>
        <Menu theme="dark" mode="inline" selectedKeys={[key]} items={items} onClick={({ key: k }) => navigate(`/admin/${k}`)} />
      </Layout.Sider>
      <Layout>
        <Layout.Header className="adm-header">
          <Typography.Text type="secondary" style={{ fontWeight: 700 }}>
            {here ? here.g : '관리자'} / <span style={{ color: C.ink }}>{here ? here.label : ''}</span>
          </Typography.Text>
          <span style={{ flex: 1 }} />
          <Space size={12}>
            <Who claims={claims} />
            <Button size="small" onClick={out} icon={<Icon name="out" />}>로그아웃</Button>
          </Space>
        </Layout.Header>
        <Layout.Content>
          <div key={pathname} className={renewed ? 'adm-page page-enter' : 'admin legacy page-enter'}>
            {renewed ? <Outlet context={claims} /> : <main className="main"><Outlet context={claims} /></main>}
          </div>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}

/** "누가 어떤 권한으로". 상단 바에 한 번 보이고, 예전 화면의 머리글에 있던 것은 CSS로 숨긴다 */
export function Who({ claims }) {
  return (
    <div className="who">
      <i>{(claims?.adm || '?')[0]}</i>관리자 #{claims?.sub}
      <span style={{ color: 'var(--faint)' }}>{claims?.adm}</span>
    </div>
  );
}
