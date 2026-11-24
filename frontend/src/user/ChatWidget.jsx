import { useEffect, useRef, useState } from 'react';
import { get, post } from '../api/client.js';
import basic from './bot/basic.png';
import happy from './bot/happy.png';
import wink from './bot/wink.png';
import surprised from './bot/surprised.png';
import angry from './bot/angry.png';
import sad from './bot/sad.png';

// 마스코트 표정: 인사=happy, 평소 답=basic, 찾는 중=surprised, 기본 환영 화면=wink, 속도 제한=angry, 실패=sad
const MOOD = { basic, happy, wink, surprised, angry, sad };
const Face = ({ mood = 'basic', size = 36 }) => (
  <img src={MOOD[mood] || basic} alt="" width={size} height={Math.round(size * 0.78)} style={{ objectFit: 'contain', flex: 'none' }} />
);

/**
 * 예매 안내 챗봇 (POST /api/v1/chat). 안내·조회만 하고 예매는 대신하지 않는다.
 * 서버가 키(GROQ_API_KEY)를 갖고 있어 화면에는 키가 없다. 서버에서 꺼져 있으면(/chat/status) 버튼을 아예 숨긴다.
 * 대화는 이 화면 안에만 두고 저장하지 않는다 (새로고침하면 사라짐). 서버에는 최근 12개까지만 보낸다.
 */
const HELLO = { role: 'assistant', mood: 'happy', content: '안녕하세요! 열차 시간표·운임·잔여석을 찾아 드려요. 예: "11월 28일 서울에서 부산 가는 열차 알려줘"' };
const KEEP = 12; // 서버가 받는 최대 메시지 수 (ChatService.MAX_MESSAGES)

export default function ChatWidget() {
  const [on, setOn] = useState(false);
  const [open, setOpen] = useState(false);
  const [msgs, setMsgs] = useState([HELLO]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const end = useRef(null);

  useEffect(() => {
    get('/api/v1/chat/status').then((r) => setOn(!!r?.enabled)).catch(() => setOn(false));
  }, []);
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }); }, [msgs, open, busy]);

  if (!on) return null;

  const send = async (e) => {
    e.preventDefault();
    const q = text.trim();
    if (!q || busy) return;
    const next = [...msgs, { role: 'user', content: q }];
    setMsgs(next);
    setText('');
    setBusy(true);
    try {
      // 인사말(HELLO)은 보내지 않는다. 마지막 KEEP개만
      const r = await post('/api/v1/chat', { messages: next.filter((m) => m !== HELLO).slice(-KEEP) });
      setMsgs([...next, { role: 'assistant', mood: 'basic', content: r.reply }]);
    } catch (err) {
      setMsgs([...next, { role: 'assistant', mood: err.code === 'RATE_LIMITED' ? 'angry' : 'sad', content: err.message || '지금은 답하지 못해요. 잠시 후 다시 물어봐 주세요.', error: true }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {open && (
        <section aria-label="예매 안내 챗봇" style={{
          position: 'fixed', right: 16, bottom: 'calc(84px + env(safe-area-inset-bottom))', zIndex: 20,
          width: 'min(360px, calc(100vw - 32px))', height: 'min(480px, 70vh)', display: 'flex', flexDirection: 'column',
          background: 'var(--card)', border: '2px solid var(--ink)', borderRadius: 14, boxShadow: '0 8px 24px rgba(0,0,0,.25)', overflow: 'hidden',
        }}>
          <header style={{ background: 'var(--ink)', color: '#fff', padding: '10px 14px', fontWeight: 900, display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Face mood="wink" size={28} />예매 도우미</span>
            <button type="button" aria-label="닫기" onClick={() => setOpen(false)}
              style={{ background: 'none', border: 0, color: '#fff', font: 'inherit', cursor: 'pointer' }}>✕</button>
          </header>
          <div style={{ flex: 1, overflowY: 'auto', padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {msgs.map((m, i) => (
              <div key={i} style={{ display: 'flex', gap: 6, alignItems: 'flex-end', alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '92%' }}>
                {m.role === 'assistant' && <Face mood={m.mood} />}
                <div style={{
                maxWidth: '100%', padding: '8px 12px', borderRadius: 12,
                whiteSpace: 'pre-wrap', lineHeight: 1.5, fontSize: 14.5,
                background: m.role === 'user' ? 'var(--ink)' : '#fff', color: m.role === 'user' ? '#fff' : m.error ? 'var(--stop)' : 'var(--ink)',
                border: m.role === 'user' ? 0 : '1px solid var(--line)',
                }}>{m.content}</div>
              </div>
            ))}
            {busy && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, alignSelf: 'flex-start', color: 'var(--ink)', opacity: .7, fontSize: 13 }}>
                <Face mood="surprised" />찾는 중…
              </div>
            )}
            <div ref={end} />
          </div>
          <form onSubmit={send} style={{ display: 'flex', gap: 6, padding: 10, borderTop: '1px solid var(--line)' }}>
            <input value={text} onChange={(e) => setText(e.target.value)} maxLength={500} placeholder="무엇이 궁금하세요?"
              aria-label="질문" style={{ flex: 1, minWidth: 0, padding: '10px 12px', border: '1px solid var(--line)', borderRadius: 10, font: 'inherit' }} />
            <button type="submit" disabled={busy || !text.trim()}
              style={{ padding: '0 14px', border: 0, borderRadius: 10, background: 'var(--ink)', color: '#fff', fontWeight: 900, cursor: 'pointer', opacity: busy || !text.trim() ? .5 : 1 }}>
              보내기
            </button>
          </form>
        </section>
      )}
      <button type="button" aria-label={open ? '예매 도우미 닫기' : '예매 도우미 열기'} onClick={() => setOpen((v) => !v)} style={{
        position: 'fixed', right: 16, bottom: 'calc(20px + env(safe-area-inset-bottom))', zIndex: 20, padding: 0,
        border: 0, background: 'none', cursor: 'pointer', filter: 'drop-shadow(0 4px 6px rgba(0,0,0,.35))',
      }}><Face mood={open ? 'wink' : 'happy'} size={72} /></button>
    </>
  );
}
