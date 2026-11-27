import { App as AntApp, ConfigProvider } from 'antd';
import koKR from 'antd/locale/ko_KR';
import { Navigate, Route, Routes } from 'react-router';
import AdminLayout from './AdminLayout.jsx';
import AdminLogin from './AdminLogin.jsx';
import CaseDetail, { CaseList } from './Cases.jsx';
import Contests from './Contests.jsx';
import Dashboard from './Dashboard.jsx';
import Deploy from './Deploy.jsx';
import GroupDetail, { GroupList } from './Groups.jsx';
import Master from './Master.jsx';
import Models from './Models.jsx';
import Risk from './Risk.jsx';
import Runs from './Runs.jsx';
import Settings from './Settings.jsx';
import { theme } from './theme.js';
import '../styles/admin-antd.css';
import Traffic from './Traffic.jsx';

/**
 * 관리자 화면 묶음 (/admin/*). App.jsx가 lazy로 불러와서 Ant Design은 관리자 화면에서만 받는다.
 * 색은 사이트 토큰(styles/tokens.css)을 그대로 옮긴 theme.js를 쓴다.
 */
export default function AdminApp() {
  return (
    <ConfigProvider locale={koKR} theme={theme}>
      <AntApp>
        <Routes>
          <Route path="login" element={<AdminLogin />} />
          <Route element={<AdminLayout />}>
            <Route index element={<Navigate to="/admin/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="risk" element={<Risk />} />
            <Route path="cases" element={<CaseList />} />
            <Route path="cases/:caseId" element={<CaseDetail />} />
            <Route path="groups" element={<GroupList />} />
            <Route path="groups/:groupId" element={<GroupDetail />} />
            <Route path="master" element={<Master />} />
            <Route path="runs" element={<Runs />} />
            <Route path="settings" element={<Settings />} />
            <Route path="deploy" element={<Deploy />} />
            <Route path="models" element={<Models />} />
            <Route path="traffic" element={<Traffic />} />
            <Route path="contests" element={<Contests />} />
            <Route path="*" element={<Navigate to="/admin/dashboard" replace />} />
          </Route>
        </Routes>
      </AntApp>
    </ConfigProvider>
  );
}
