import { createContext, useContext, useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import Icon from '../admin/Icon.jsx';

/**
 * "편안한 화면" 설정 (글자 크기 3단계, 음성 안내). 모든 사용자 화면 위쪽 도우미 줄에서 바꾸고,
 * 다른 화면으로 가도 유지된다 (지금은 이 브라우저에만 저장. 실제로는 회원 설정에 저장할 예정)
 */
const EASE_KEY = 'jejari-ease';
const EaseContext = createContext(null);

function loadEase() {
  try {
    const s = JSON.parse(localStorage.getItem(EASE_KEY) || 'null');
    if (s && [1, 1.15, 1.3].includes(s.z)) return { z: s.z, voice: !!s.voice };
  } catch {
    /* 저장소를 못 쓰는 브라우저 → 기본값 */
  }
  return { z: 1, voice: false };
}

/** 사용자 화면 맨 바깥. 글자 크기(--z)를 걸고, 음성 안내를 켜 두면 화면이 바뀔 때 안내 한 줄(.guide)을 읽는다 */
export function EaseRoot({ children }) {
  const [ease, setEase] = useState(loadEase);
  const { pathname } = useLocation();

  useEffect(() => {
    try {
      localStorage.setItem(EASE_KEY, JSON.stringify(ease));
    } catch {
      /* 저장 못 해도 지금 화면에는 적용됨 */
    }
  }, [ease]);

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

  return (
    <EaseContext.Provider value={[ease, setEase]}>
      <div className="user" style={{ '--z': ease.z }}>{children}</div>
    </EaseContext.Provider>
  );
}

/** 위쪽 도우미 줄 (.sbar) */
export function EaseBar() {
  const [ease, setEase] = useContext(EaseContext);
  const [callNote, setCallNote] = useState(false);
  const toggleVoice = () => {
    if (ease.voice && window.speechSynthesis) speechSynthesis.cancel();
    setEase({ ...ease, voice: !ease.voice });
  };
  return (
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
      {/* 시연용 사이트라 상담 전화가 없다. 예전엔 tel:1588-0000이라 누르면 모르는 번호로 실제 전화가 걸렸다 */}
      <button type="button" className="sb-call" aria-expanded={callNote} aria-controls="sb-call-note" onClick={() => setCallNote((v) => !v)}>
        <Icon name="phone" />전화 도움
      </button>
      {callNote && (
        <span id="sb-call-note" className="sb-note" role="status">
          시연용 사이트라 상담 전화는 없어요. 오른쪽 아래 예매 도우미에게 물어보세요.
        </span>
      )}
    </div>
  );
}
