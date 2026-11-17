import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { del, get, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { ConfirmButton } from '../admin/ui.jsx';
import { GRADE, hm, shortDate } from './fmt.js';
import RouteLine from './RouteLine.jsx';
import { useMember } from './useMember.js';

/**
 * U-10 취소표 대기 신청·목록 (WL-01·02·03).
 *   POST   /api/v1/waitlists           신청 {runId, fromStationId, toStationId, seatGrade, seatCount} (입장 토큰 필요 없음)
 *   GET    /api/v1/waitlists           내 대기 (몇 번째인지 ahead, 제안이면 offerDeadlineAt·offeredReservationId)
 *   DELETE /api/v1/waitlists/{id}      대기 취소 (WAITING만)
 * 취소표가 나오면 서버가 신청 순서대로 좌석을 잡아(예약 HOLD) 제안한다. 제안받은 건은 "결제하러 가기"로 U-06.
 * 매진 운행의 [취소표 대기 신청](U-03)에서 ?run=&from=&to= 를 들고 들어오면 위쪽에 신청 카드가 나온다.
 */
const STATUS = {
  WAITING: ['sky', '대기 중'],
  OFFERED: ['amber', '좌석 제안'],
  ACCEPTED: ['go', '결제함'],
  EXPIRED: ['', '제안 만료'],
  SKIPPED: ['', '건너뜀'],
  CANCELED: ['', '취소함'],
};
const MESSAGES = {
  WAITLIST_NOT_SOLD_OUT: '아직 남은 좌석이 있어요. 바로 예매해 주세요.',
  WAITLIST_DUPLICATED: '이 열차는 이미 대기 중이에요.',
  WAITLIST_LIMIT: '취소표 대기는 모두 합쳐 3건까지예요.',
  WAITLIST_RESTRICTED: '이용 제한 중이라 취소표 대기를 신청할 수 없어요.',
  WAITLIST_NOT_WAITING: '제안받은 좌석은 예매 내역에서 취소해 주세요.',
};

export default function Waitlist() {
  const [params] = useSearchParams();
  const runId = params.get('run');
  const from = params.get('from') || '';
  const to = params.get('to') || '';
  const { member, checking } = useMember();
  const location = useLocation();
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);

  const load = () => get('/api/v1/waitlists').then(setItems).catch(setError);
  useEffect(() => {
    if (member) load();
  }, [member]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;

  const active = (items || []).filter((w) => w.status === 'WAITING' || w.status === 'OFFERED').length;

  return (
    <>
      <header className="top"><h1>취소표</h1></header>
      <main className="body">
        <p className="guide"><Icon name="info" /><span>매진된 열차는 여기서 대기를 신청해요. 표가 나오면 알림으로 알려 드려요.</span></p>
        {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{MESSAGES[error.code] || error.message}</span></div>}
        <div className="lay">
          <div className="c1">
            <div className="sec d-only">새로 신청</div>
            {runId ? (
              <ApplyCard runId={runId} from={from} to={to} onApplied={load} />
            ) : (
              <div className="card mut" style={{ fontSize: 14.5 }}>
                매진된 열차의 [취소표 대기 신청]을 누르면 여기서 신청할 수 있어요.
                <p style={{ margin: '10px 0 0' }}><Link className="link" to="/">열차 찾기</Link></p>
              </div>
            )}
          </div>
          <div className="c2">
            <div className="row sec"><span className="grow">내 대기</span><span className="faint" style={{ fontWeight: 500 }}>{active} / 3건</span></div>
            {items && !items.length && <p className="mut">신청한 대기가 없어요.</p>}
            {(items || []).map((w) => <WaitCard key={w.waitlistId} w={w} onChanged={load} />)}
          </div>
        </div>
      </main>
    </>
  );
}

function ApplyCard({ runId, from, to, onApplied }) {
  const navigate = useNavigate();
  const [run, setRun] = useState(null);
  const [grade, setGrade] = useState('GENERAL');
  const [count, setCount] = useState(1);
  const [msg, setMsg] = useState(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    get(`/api/v1/runs/${runId}?from=${from}&to=${to}`).then((r) => {
      setRun(r);
      const sold = Object.entries(r.remaining || {}).find(([, n]) => n === 0);
      if (sold) setGrade(sold[0]);
    }).catch(() => {});
  }, [runId, from, to]);

  const apply = async () => {
    setMsg(null);
    try {
      await post('/api/v1/waitlists', { runId: Number(runId), fromStationId: from || null, toStationId: to || null, seatGrade: grade, seatCount: count });
      setDone(true);
      onApplied();
    } catch (e) {
      setMsg(e);
    }
  };

  if (!run) return <p className="mut">열차를 불러오는 중…</p>;
  const dep = run.stops.find((s) => s.stationId === run.fromStationId);
  const arr = run.stops.find((s) => s.stationId === run.toStationId);
  const left = run.remaining?.[grade];

  return (
    <section className="ticket">
      <div className="row">
        <span className="tno grow"><b>{run.trainNo}</b> 열차<span className="dd">{shortDate(run.runDate)}</span></span>
        <span className={'st ' + (left === 0 ? 'stop' : 'go')}>{GRADE[grade]} {left === 0 ? '매진' : left != null ? `${left}석 남음` : ''}</span>
      </div>
      <RouteLine from={{ name: dep?.stationName, time: dep?.departureAt }} to={{ name: arr?.stationName, time: arr?.arrivalAt }} />
      <div className="perf" />
      {Object.keys(run.fares || {}).length > 1 && (
        <div className="row" style={{ marginBottom: 10 }}>
          <span className="grow"><b>등급</b></span>
          <div className="chips" style={{ borderBottom: 0 }}>
            {Object.keys(run.fares).map((g) => (
              <button key={g} type="button" className={'chip' + (grade === g ? ' on' : '')} onClick={() => setGrade(g)}>{GRADE[g]}</button>
            ))}
          </div>
        </div>
      )}
      <div className="row">
        <span className="grow"><b>매수</b></span>
        <div className="qty">
          <button type="button" aria-label="한 장 빼기" disabled={count <= 1} onClick={() => setCount(count - 1)}>−</button>
          <b>{count}</b>
          <button type="button" aria-label="한 장 더하기" disabled={count >= 4} onClick={() => setCount(count + 1)}>+</button>
        </div>
      </div>
      {msg && (
        <div className="alert stop" style={{ marginTop: 12, flexWrap: 'wrap' }}>
          <Icon name="info" /><span className="grow">{MESSAGES[msg.code] || msg.message}</span>
          {msg.code === 'WAITLIST_NOT_SOLD_OUT' && (
            <button type="button" className="btn sm" style={{ marginLeft: 28 }} onClick={() => navigate(`/queue/${runId}?from=${from}&to=${to}`)}>예매하기</button>
          )}
        </div>
      )}
      {done ? (
        <div className="alert go" style={{ marginTop: 14 }}><Icon name="info" /><span>대기를 신청했어요. 오른쪽 "내 대기"에서 순서를 볼 수 있어요.</span></div>
      ) : (
        <button className="btn" type="button" style={{ marginTop: 14 }} onClick={apply}>대기 신청</button>
      )}
      <p className="mut" style={{ fontSize: 12.5, margin: '10px 0 0' }}>
        취소표가 나오면 신청한 순서대로 좌석을 잡아 두고 알려 드려요. 같은 열차는 1건, 모두 합쳐 3건까지 신청할 수 있어요.
      </p>
    </section>
  );
}

function WaitCard({ w, onChanged }) {
  const [cls, label] = STATUS[w.status] || ['', w.status];
  const [msg, setMsg] = useState(null);
  const offered = w.status === 'OFFERED';
  const cancel = async () => {
    try {
      await del(`/api/v1/waitlists/${w.waitlistId}`);
      onChanged();
    } catch (e) {
      setMsg(MESSAGES[e.code] || e.message);
    }
  };
  return (
    <article className="ticket" style={offered ? { boxShadow: '0 0 0 2px var(--amber) inset' } : w.status !== 'WAITING' ? { opacity: 0.7 } : {}}>
      <div className="row">
        <span className="tno grow"><b>{w.trainNo}</b> 열차<span className="dd">{w.fromName} → {w.toName}</span></span>
        <span className={'st ' + cls}>{offered && w.offerDeadlineAt ? `${hm(w.offerDeadlineAt)} 안에 결제` : label}</span>
      </div>
      {offered ? (
        <>
          <p style={{ margin: '8px 0 12px', fontSize: 14.5 }}><b>좌석을 잡아 뒀어요.</b> <span className="mut">{GRADE[w.seatGrade]} {w.seatCount}매, 기한 안에 결제하면 확정돼요.</span></p>
          {w.offeredReservationId && <Link className="btn" to={`/pay/${w.offeredReservationId}`}>결제하러 가기</Link>}
        </>
      ) : (
        <div className="row" style={{ marginTop: 10 }}>
          <span className="grow">
            {w.status === 'WAITING' && w.ahead != null && <><span className="num" style={{ fontSize: 30, fontWeight: 600 }}>{w.ahead + 1}</span><span className="mut" style={{ fontSize: 14 }}>번째</span><br /></>}
            <span className="mut" style={{ fontSize: 13 }}>{shortDate(w.runDate)} {GRADE[w.seatGrade]} {w.seatCount}매</span>
          </span>
          {w.status === 'WAITING' && <ConfirmButton className="btn ghost sm" confirm="한 번 더 누르면 취소" onConfirm={cancel}>대기 취소</ConfirmButton>}
        </div>
      )}
      {msg && <p style={{ color: 'var(--stop)', fontWeight: 700, fontSize: 13.5, margin: '6px 0 0' }}>{msg}</p>}
    </article>
  );
}
