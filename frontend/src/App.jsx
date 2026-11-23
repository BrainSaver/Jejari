import { Navigate, Route, Routes } from 'react-router';
import AdminLayout from './admin/AdminLayout.jsx';
import AdminLogin from './admin/AdminLogin.jsx';
import Dashboard from './admin/Dashboard.jsx';
import Master from './admin/Master.jsx';
import Runs from './admin/Runs.jsx';
import Risk from './admin/Risk.jsx';
import Settings from './admin/Settings.jsx';
import Deploy from './admin/Deploy.jsx';
import Models from './admin/Models.jsx';
import Traffic from './admin/Traffic.jsx';
import CaseDetail, { CaseList } from './admin/Cases.jsx';
import Login from './user/Login.jsx';
import Pay from './user/Pay.jsx';
import PayResult from './user/PayResult.jsx';
import Queue from './user/Queue.jsx';
import Me from './user/Me.jsx';
import Notifications from './user/Notifications.jsx';
import Reservations from './user/Reservations.jsx';
import Search from './user/Search.jsx';
import Guest from './user/Guest.jsx';
import GuestLookup from './user/GuestLookup.jsx';
import Waitlist from './user/Waitlist.jsx';
import Seats from './user/Seats.jsx';
import Signup from './user/Signup.jsx';
import UserLayout, { AuthLayout } from './user/UserLayout.jsx';

// 경로를 추가하면 서버의 SpaForwardController에도 같은 경로를 넣는다 (새로고침 때 index.html을 돌려주게)
export default function App() {
  return (
    <Routes>
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<Login />} />
        <Route path="/signup" element={<Signup />} />
        <Route path="/guest" element={<Guest />} />
        <Route path="/guest/lookup" element={<GuestLookup />} />
      </Route>
      <Route element={<UserLayout />}>
        <Route path="/" element={<Search />} />
        <Route path="/queue/:runId" element={<Queue />} />
        <Route path="/runs/:runId/seats" element={<Seats />} />
        <Route path="/pay/:reservationId" element={<Pay />} />
        <Route path="/pay/:reservationId/result" element={<PayResult />} />
        <Route path="/reservations" element={<Reservations />} />
        <Route path="/waitlist" element={<Waitlist />} />
        <Route path="/notifications" element={<Notifications />} />
        <Route path="/me" element={<Me />} />
      </Route>
      <Route path="/admin/login" element={<AdminLogin />} />
      <Route path="/admin" element={<AdminLayout />}>
        <Route index element={<Navigate to="dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="risk" element={<Risk />} />
        <Route path="cases" element={<CaseList />} />
        <Route path="cases/:caseId" element={<CaseDetail />} />
        <Route path="master" element={<Master />} />
        <Route path="runs" element={<Runs />} />
        <Route path="settings" element={<Settings />} />
        <Route path="deploy" element={<Deploy />} />
        <Route path="models" element={<Models />} />
        <Route path="traffic" element={<Traffic />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
