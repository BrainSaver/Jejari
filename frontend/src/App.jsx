import { Navigate, Route, Routes } from 'react-router';
import AdminLayout from './admin/AdminLayout.jsx';
import AdminLogin from './admin/AdminLogin.jsx';
import Dashboard from './admin/Dashboard.jsx';
import Soon from './admin/Soon.jsx';
import Master from './admin/Master.jsx';
import Runs from './admin/Runs.jsx';
import Home from './user/Home.jsx';

// 경로를 추가하면 서버의 SpaForwardController에도 같은 경로를 넣는다 (새로고침 때 index.html을 돌려주게)
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/admin/login" element={<AdminLogin />} />
      <Route path="/admin" element={<AdminLayout />}>
        <Route index element={<Navigate to="dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="risk" element={<Soon id="A-05" title="위험 사용자" owner="강다인" />} />
        <Route path="cases" element={<Soon id="A-06" title="케이스 상세" owner="강다인" />} />
        <Route path="master" element={<Master />} />
        <Route path="runs" element={<Runs />} />
        <Route path="settings" element={<Soon id="A-07" title="운영 설정" owner="조영준" />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
