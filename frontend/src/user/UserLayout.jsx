import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { logout } from '../api/client.js';
import Icon, { Seal } from '../admin/Icon.jsx';
import { useMember } from './useMember.js';

/**
 * 사용자 화면 공통 틀 (시안 docs/ui/U-*.html): 위쪽 "편안한 화면" 도우미 줄 + PC 위쪽 메뉴 / 모바일 아래 탭.
 * 글자 크기·음성 안내는 다른 화면으로 가도 유지된다 (지금은 이 브라우저에만 저장. 실제로는 회원 설정에 저장할 예정)
 */
const EASE_KEY = 'jejari-ease';
const MENU = [
  ['/', 'search', '검색'],
  ['/reservations', 'ticket', '예매 내역'],
  ['/waitlist', 'clock', '취소표'],
  ['/notifications', 'bell', '알림'],
  ['/me', 'person', '내 정보'],
];

function loadEase() {
  try {
    const s = JSON.parse(localStorage.getItem(EASE_KEY) || 'null');
    if (s && [1, 1.15, 1.3].includes(s.z)) return { z: s.z, voice: !!s.voice };
  } catch {
    /* 저장소를 못 쓰는 브라우저 → 기본값 */
  }
  return { z: 1, voice: false };
}

export default function UserLayout() {
  const [ease, setEase] = useState(loadEase);
  const { pathname } = useLocation();
  const { member } = useMember();
  const navigate = useNavigate();

  useEffect(() => {
    try {
      localStorage.setItem(EASE_KEY, JSON.stringify(ease));
    } catch {
      /* 저장 못 해도 지금 화면에는 적용됨 */
    }
  }, [ease]);

  // 음성 안내: 켜 두면 화면이 바뀔 때마다 그 화면의 안내 한 줄(.guide)을 읽는다
  useEffect(() => {
    if (!ease.voice || !window.speechSynthesis) return;
    const t = setTimeout(() => {
      const g = document.querySelector('.user .guide span');
      if (!g) return;
      try {
        speechSynthesis.cancel();
        const u = new SpeechSynthesisUtterance(g.textContent);
        u.lang = 'ko-KR';
        u.rate = 0.9;
        speechSynthesis.speak(u);
      } catch {
        /* 음성 합성이 없는 환경 */
      }
    }, 300);
    return () => clearTimeout(t);
  }, [ease.voice, pathname]);

  const toggleVoice = () => {
    if (ease.voice && window.speechSynthesis) speechSynthesis.cancel();
    setEase({ ...ease, voice: !ease.voice });
  };

  return (
    <div className="user" style={{ '--z': ease.z }}>
      <div className="app">
        <div className="sbar" role="region" aria-label="편안한 화면 도우미">
          <span className="sb-t"><i />편안한 화면<span> · 큼직한 글씨와 쉬운 안내</span></span>
          <span className="sb-seg" role="group" aria-label="글자 크기">
            {[[1, '보통'], [1.15, '크게'], [1.3, '아주 크게']].map(([z, label]) => (
              <button key={z} type="button" className={ease.z === z ? 'on' : ''} aria-pressed={ease.z === z} onClick={() => setEase({ ...ease, z })}>
                {label}
              </button>
            ))}
          </span>
          <button type="button" className="sb-btn" aria-pressed={ease.voice} onClick={toggleVoice}>
            {ease.voice ? '음성 안내 켜짐' : '음성 안내 듣기'}
          </button>
          <a className="sb-call" href="tel:1588-0000"><Icon name="phone" />전화 도움 1588-0000</a>
        </div>

        <header className="gnb">
          <Link className="logo" to="/"><Seal />제자리</Link>
          <nav aria-label="주요 메뉴">
            {MENU.map(([to, , label]) => (
              <NavLink key={to} to={to} end className={({ isActive }) => (isActive ? 'on' : '')}>{label}</NavLink>
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

        <Outlet />

        <nav className="tabbar" aria-label="주요 메뉴">
          {MENU.map(([to, icon, label]) => (
            <NavLink key={to} to={to} end className={({ isActive }) => (isActive ? 'on' : '')}>
              <span><Icon name={icon} /></span>
              {label}
            </NavLink>
          ))}
        </nav>
      </div>
    </div>
  );
}
