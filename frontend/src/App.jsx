import { Navigate, Route, Routes } from 'react-router';
import AdminLayout from './admin/AdminLayout.jsx';
import AdminLogin from './admin/AdminLogin.jsx';
import Dashboard from './admin/Dashboard.jsx';
import Soon from './admin/Soon.jsx';
import Master from './admin/Master.jsx';
import Runs from './admin/Runs.jsx';
import Search from './user/Search.jsx';
import UserLayout from './user/UserLayout.jsx';
import UserSoon from './user/UserSoon.jsx';

// 경로를 추가하면 서버의 SpaForwardController에도 같은 경로를 넣는다 (새로고침 때 index.html을 돌려주게)
export default function App() {
  return (
    <Routes>
      <Route element={<UserLayout />}>
        <Route path="/" element={<Search />} />
        <Route path="/queue/:runId" element={<UserSoon id="U-04" title="줄 서기" owner="김성원" />} />
        <Route path="/reservations" element={<UserSoon id="U-08" title="예매 내역" owner="강다인" />} />
        <Route path="/waitlist" element={<UserSoon id="U-10" title="취소표" owner="강다인" />} />
        <Route path="/notifications" element={<UserSoon id="U-11" title="알림" owner="길동현" />} />
        <Route path="/me" element={<UserSoon id="U-09" title="내 정보" owner="강다인 · 길동현" />} />
      </Route>
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
