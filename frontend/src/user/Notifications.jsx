import { useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { api, get } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { toMs } from './fmt.js';
import { useMember } from './useMember.js';

/**
 * U-11 알림함 (EVT-06, WL-04).
 *   GET   /api/v1/notifications?before=&size=     최신순 + 읽지 않은 수 (다음 쪽은 nextBefore)
 *   PATCH /api/v1/notifications/{id}/read         누르면 읽음 표시 후 관련 화면으로
 * 알림 행에는 종류(type)와 가리키는 대상(refType·refId)만 있다. 문구는 화면이 종류별로 만든다.
 * 알림은 Kafka 이벤트를 소비자가 받아 쓰는 것이라(Outbox → Kafka → 알림 소비자) 몇 초 늦게 생길 수 있다.
 */
const KIND = {
  WAITLIST_OFFER: { icon: 'ticket', bg: 'var(--amber-t)', fg: 'var(--amber-d)', title: '취소표가 나왔어요', body: '좌석을 잡아 뒀어요. 기한 안에 결제하면 확정돼요.' },
  PAY_REMINDER: { icon: 'clock', bg: 'var(--stop-t)', fg: 'var(--stop)', title: '결제 기한이 얼마 안 남았어요', body: '잡아 둔 좌석의 결제 기한이 곧 끝나요.' },
  EXPIRED: { icon: 'info', bg: 'var(--sky-t)', fg: 'var(--ink)', title: '예약이 만료됐어요', body: '결제 기한이 지나 좌석이 풀렸어요.' },
  WAITLIST_SKIPPED: { icon: 'info', bg: 'var(--sky-t)', fg: 'var(--ink)', title: '취소표 차례가 넘어갔어요', body: '제안 기한 안에 결제하지 않아 다음 분께 넘어갔어요.' },
  REFUNDED: { icon: 'card', bg: 'var(--go-t)', fg: 'var(--go-d)', title: '환불이 끝났어요', body: '취소한 표의 금액을 돌려드렸어요.' },
};

export default function Notifications() {
  const { member, checking } = useMember();
  const location = useLocation();
  const navigate = useNavigate();
  const [page, setPage] = useState(null);
  const [more, setMore] = useState([]);

  useEffect(() => {
    if (member) get('/api/v1/notifications?size=20').then(setPage).catch(() => setPage({ items: [], unreadCount: 0 }));
  }, [member]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;

  const items = [...(page?.items || []), ...more];
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
  const isToday = (n) => new Date(toMs(n.createdAt)).toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' }) === today;

  const open = async (n) => {
    if (!n.readAt) await api('PATCH', `/api/v1/notifications/${n.notificationId}/read`).catch(() => {});
    if (n.refType === 'RESERVATION') {
      navigate(n.type === 'WAITLIST_OFFER' || n.type === 'PAY_REMINDER' ? `/pay/${n.refId}` : '/reservations');
    } else if (n.refType === 'WAITLIST') navigate('/waitlist');
    else navigate('/reservations');
  };

  const loadMore = () => get(`/api/v1/notifications?size=20&before=${page.nextBefore}`).then((p) => {
    setMore([...more, ...p.items]);
    setPage({ ...page, nextBefore: p.nextBefore });
  });

  const group = (title, list) => list.length > 0 && (
    <>
      <div className="sec" style={{ margin: '12px 16px 8px' }}>{title}</div>
      <div>
        {list.map((n) => {
          const k = KIND[n.type] || { icon: 'bell', bg: 'var(--line2)', fg: 'var(--ink)', title: n.type, body: '' };
          return (
            <button key={n.notificationId} type="button" className="row" onClick={() => open(n)}
              style={{ width: '100%', textAlign: 'left', font: 'inherit', color: 'inherit', cursor: 'pointer', border: 0, borderBottom: '1px solid var(--line)', alignItems: 'flex-start', gap: 12, padding: '14px 16px', background: n.readAt ? 'transparent' : 'var(--card)' }}>
              <span style={{ width: 38, height: 38, borderRadius: 8, display: 'grid', placeItems: 'center', flex: 'none', background: k.bg, color: k.fg }}><Icon name={k.icon} /></span>
              <span className="grow">
                <b style={{ fontSize: 15 }}>{k.title}</b><br />
                <span className="mut" style={{ fontSize: 13.5 }}>{k.body}{n.refType === 'RESERVATION' ? ` (예약 ${n.refId})` : ''}</span>
              </span>
              <span className="faint" style={{ fontSize: 12, whiteSpace: 'nowrap', textAlign: 'right' }}>
                {ago(n.createdAt)}
                {!n.readAt && <><br /><span style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: 'var(--stop)', marginTop: 6 }} aria-label="안 읽음" /></>}
              </span>
            </button>
          );
        })}
      </div>
    </>
  );

  return (
    <>
      <header className="top narrow"><h1>알림 {page?.unreadCount ? <span className="st stop" style={{ verticalAlign: 'middle' }}>{page.unreadCount}</span> : null}</h1></header>
      <main className="body narrow" style={{ paddingLeft: 0, paddingRight: 0, gap: 0 }}>
        <p className="guide" style={{ margin: '0 16px' }}><Icon name="info" /><span>알림을 누르면 그 표가 있는 화면으로 가요.</span></p>
        {page && !items.length && <p className="mut" style={{ textAlign: 'center', marginTop: 24 }}>받은 알림이 없어요.</p>}
        {group('오늘', items.filter(isToday))}
        {group('이전', items.filter((n) => !isToday(n)))}
        {page?.nextBefore && <button type="button" className="btn ghost" style={{ margin: 16, width: 'auto' }} onClick={loadMore}>더 보기</button>}
      </main>
    </>
  );
}

function ago(t) {
  const m = Math.round((Date.now() - toMs(t)) / 60000);
  if (m < 1) return '방금';
  if (m < 60) return `${m}분 전`;
  if (m < 1440) return `${Math.floor(m / 60)}시간 전`;
  return `${Math.floor(m / 1440)}일 전`;
}
