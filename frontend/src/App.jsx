import { lazy, Suspense, useEffect } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigationType } from 'react-router';
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
import Groups from './user/Groups.jsx';
import Seats from './user/Seats.jsx';
import Signup from './user/Signup.jsx';
import NotFound from './user/NotFound.jsx';
import UserLayout, { AuthLayout } from './user/UserLayout.jsx';

// 관리자 화면은 Ant Design을 써서 무겁다. 회원 화면이 같이 받지 않게 /admin에 들어올 때만 따로 불러온다
const AdminApp = lazy(() => import('./admin/AdminApp.jsx'));

/**
 * 화면을 옮기면 맨 위부터 보이게 한다. 없으면 검색 결과를 내려 본 채 [예매하기]를 눌렀을 때
 * 좌석 화면이 404px 내려간 채 열려 안내·남은 시간이 안 보였다 (2026-11-26 점검).
 * 뒤로 가기(POP)는 브라우저가 원래 위치로 돌려주고, #주소는 그 위치로 가야 하니 건드리지 않는다.
 */
function ScrollToTop() {
  const { pathname, hash } = useLocation();
  const type = useNavigationType();
  useEffect(() => {
    if (type !== 'POP' && !hash) window.scrollTo(0, 0);
  }, [pathname]); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

// 경로를 추가하면 서버의 SpaForwardController에도 같은 경로를 넣는다 (빠지면 새로고침 때 화면은 뜨지만 상태가 404)
export default function App() {
  return (
    <>
    <ScrollToTop />
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
        <Route path="/groups" element={<Groups />} />
        <Route path="/notifications" element={<Notifications />} />
        <Route path="/me" element={<Me />} />
        <Route path="*" element={<NotFound />} />
      </Route>
      <Route
        path="/admin/*"
        element={
          <Suspense fallback={null}>
            <AdminApp />
          </Suspense>
        }
      />
    </Routes>
    </>
  );
}
