import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { del, get, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { savePass } from '../lib/pass.js';
import { QUEUE_ART } from './art.js';
import { useMember } from './useMember.js';

/**
 * U-04 대기열 (QUE-01·02·03·05). 남색 판 위에 큰 번호표와 기차 진행 막대 (시안 docs/ui/U-04.html).
 *   POST   /api/v1/runs/{runId}/queue      줄 서기 (이미 서 있으면 같은 번호표 → 새로고침해도 순번 그대로)
 *   GET    /api/v1/runs/{runId}/queue/me   서버가 정한 간격(pollIntervalSec)마다 내 순서. 입장하면 passToken
 *   DELETE /api/v1/runs/{runId}/queue/me   대기 그만두기
 * SSE·웹소켓 대신 폴링이다. 간격을 서버가 정하므로 몰릴 때 서버가 부하를 조절할 수 있다.
 */
const MESSAGES = {
  RUN_NOT_ON_SALE: '아직 예매가 열리지 않았어요.',
  QUEUE_DUPLICATED: '이미 다른 열차를 기다리고 있어요. 그 줄을 그만둔 뒤 다시 서 주세요.',
};

export default function Queue() {
  const { runId } = useParams();
  const [params] = useSearchParams();
  const from = params.get('from') || '';
  const to = params.get('to') || '';
  // 검색 화면에서 고른 인원·좌석 종류를 좌석 화면까지 이어 준다
  const carry = ['people', 'grade'].filter((k) => params.get(k)).map((k) => `&${k}=${encodeURIComponent(params.get(k))}`).join('');
  const { member, checking } = useMember();
  const navigate = useNavigate();
  const location = useLocation();
  const [st, setSt] = useState(null);
  const [first, setFirst] = useState(null); // 처음 받은 앞사람 수 (진행 막대 기준)
  const [joinedAt, setJoinedAt] = useState(null);
  const [run, setRun] = useState(null);
  const [error, setError] = useState(null);
  const timer = useRef(null);
  const backoff = useRef(1);
  const [going, setGoing] = useState(false); // 입장: 기차가 끝까지 달려 나가는 중
  const [tick, setTick] = useState(0); // 앞사람 수가 줄면 숫자가 톡
  const lastAhead = useRef(null);

  useEffect(() => {
    get(`/api/v1/runs/${runId}?from=${from}&to=${to}`).then(setRun).catch(() => {});
  }, [runId, from, to]);

  useEffect(() => {
    if (!member) return;
    let alive = true;
    const handle = (s) => {
      if (!alive) return;
      backoff.current = 1;
      setSt(s);
      setFirst((f) => (f == null ? Math.max(1, s.ahead ?? 1) : f));
      setJoinedAt((t) => t || new Date());
      if (s.ahead != null && lastAhead.current != null && s.ahead < lastAhead.current) setTick((n) => n + 1);
      lastAhead.current = s.ahead ?? lastAhead.current;
      if (s.state === 'ADMITTED' && s.passToken) {
        savePass(runId, s.passToken, s.passExpiresAt);
        // 차례가 왔다: 기차가 끝까지 달려 나간 뒤 좌석 화면으로 (움직임 줄이기면 바로)
        setGoing(true);
        const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
        timer.current = setTimeout(() => navigate(`/runs/${runId}/seats?from=${from}&to=${to}${carry}`, { replace: true }), reduce ? 0 : 1000);
        return;
      }
      timer.current = setTimeout(poll, Math.max(1, s.pollIntervalSec || 2) * 1000);
    };
    const fail = (e) => {
      if (!alive) return;
      if (e.code === 'RATE_LIMITED') {
        // 너무 자주 물었다 → 간격을 늘려 계속
        backoff.current = Math.min(backoff.current * 2, 10);
        timer.current = setTimeout(poll, backoff.current * 2000);
        return;
      }
      setError(e);
    };
    const poll = () => get(`/api/v1/runs/${runId}/queue/me`).then(handle).catch(fail);
    post(`/api/v1/runs/${runId}/queue`).then(handle).catch(fail);
    return () => {
      alive = false;
      clearTimeout(timer.current);
    };
  }, [member, runId]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;

  const leave = async () => {
    clearTimeout(timer.current);
    try {
      await del(`/api/v1/runs/${runId}/queue/me`);
    } catch {
      /* 이미 빠졌으면 그만 */
    }
    navigate(`/?from=${from}&to=${to}&date=${run?.runDate || ''}`);
  };

  const ahead = st?.ahead ?? 0;
  const pct = going ? 100 : first ? Math.min(96, Math.max(4, Math.round(((first - ahead) / first) * 100))) : 4;
  const dep = run?.stops?.find((s) => s.stationId === run.fromStationId);
  const arr = run?.stops?.find((s) => s.stationId === run.toStationId);

  return (
    <div className="pg-queue">
      <div className="art" aria-hidden="true" dangerouslySetInnerHTML={{ __html: QUEUE_ART }} />
      <header className="top m-only" style={{ position: 'relative', zIndex: 1 }}>
        <button type="button" className="back" aria-label="뒤로" onClick={leave} style={{ border: 0, background: 'none', color: 'inherit', cursor: 'pointer' }}>←</button>
        <h1>대기열</h1>
      </header>
      <main className="body narrow qbody" style={{ gap: 16, position: 'relative', zIndex: 1 }}>
        <p className="guide"><Icon name="info" /><span>이 화면을 닫지 말고 기다리세요. 차례가 되면 좌석 고르는 화면으로 저절로 넘어가요.</span></p>
        <ol className="flow" aria-label="예매 단계">
          <li className="now" aria-current="step"><b>1</b>줄 서기</li><li><b>2</b>좌석 고르기</li><li><b>3</b>결제</li><li><b>4</b>확정</li>
        </ol>
        {run && (
          <div style={{ fontSize: 14, color: '#B9C1D3' }}>
            <b style={{ color: '#fff' }}>{run.trainNo} 열차</b>&nbsp; {dep?.stationName} {dep?.departureAt?.slice(11, 16)} → {arr?.stationName} {arr?.arrivalAt?.slice(11, 16)}&nbsp; {shortDate(run.runDate)}
          </div>
        )}

        {error ? (
          <div className="alert stop" role="alert" style={{ flexWrap: 'wrap' }}>
            <Icon name="info" />
            <span className="grow">{MESSAGES[error.code] || error.message}</span>
            <Link className="btn sm" to={`/?from=${from}&to=${to}&date=${run?.runDate || ''}`} style={{ marginLeft: 28 }}>열차 다시 찾기</Link>
          </div>
        ) : (
          <>
            <section style={{ padding: '22px 0 6px' }} aria-live="polite">
              <div style={{ fontSize: 15, color: '#B9C1D3' }}>내 순번</div>
              <div key={tick} className={'q-num' + (tick ? ' tick' : '')}>{st ? (st.rank ?? st.ticketNo).toLocaleString() : '…'}</div>
              <div style={{ marginTop: 12, fontSize: 16 }}>
                {st?.state === 'ADMITTED'
                  ? '차례가 됐어요! 좌석 고르는 화면으로 넘어가요.'
                  : st
                    ? <>앞에 <b>{ahead.toLocaleString()}</b>명이 있어요.{st.estimatedWaitSec != null && <> 예상 대기 <b>{wait(st.estimatedWaitSec)}</b></>}</>
                    : '번호표를 받고 있어요…'}
              </div>
            </section>
            {/* 기차: 앞사람이 줄면 앞으로 가고(left 전환), 늘 바퀴가 돌고 연기가 난다. 입장하면 끝까지 달려 나간다 */}
            <div className={'track' + (going ? ' go' : '')} aria-hidden="true">
              <div className="rail" /><div className="done" style={{ width: pct + '%' }} />
              <span className={'stn end' + (going ? ' admit-flash' : '')} />
              <div className="train" style={{ left: pct + '%' }}>
                <span className="puff" style={{ left: 30 }} /><span className="puff" style={{ left: 30 }} /><span className="puff" style={{ left: 30 }} />
                <svg viewBox="0 0 44 32" width="44" height="32">
                  <g className="body">
                    <rect x="2" y="4" width="40" height="22" rx="8" fill="var(--sign)" />
                    <rect x="8" y="9" width="10" height="7" rx="2" fill="var(--ink)" />
                    <rect x="22" y="9" width="10" height="7" rx="2" fill="var(--ink)" />
                    <rect x="34" y="1" width="5" height="5" rx="1" fill="var(--sign)" />
                  </g>
                  <g className="wheel"><circle cx="12" cy="28" r="3.4" fill="#fff" /><path d="M12 25.2v5.6" stroke="var(--ink)" strokeWidth="1.2" /></g>
                  <g className="wheel"><circle cx="32" cy="28" r="3.4" fill="#fff" /><path d="M32 25.2v5.6" stroke="var(--ink)" strokeWidth="1.2" /></g>
                </svg>
              </div>
            </div>
            <div className="qlbl">
              <span>번호표 {st ? `${st.ticketNo.toLocaleString()}번` : ''}{joinedAt ? ` 받음 ${joinedAt.toTimeString().slice(0, 8)}` : ''}</span>
              <span>좌석 선택</span>
            </div>
          </>
        )}
        <div className="qcard" style={{ fontSize: 14, lineHeight: 1.6, color: '#D3D9E6' }}>
          새로고침하거나 창을 닫았다 열어도 순번은 그대로예요.<br />차례가 오면 자동으로 좌석 선택으로 넘어가고, 그때부터 5분 안에 좌석을 골라야 해요.
        </div>
        <div className="stack qbtns" style={{ marginTop: 'auto' }}>
          <button type="button" className="btn ghost" onClick={leave}>대기 그만두기</button>
        </div>
      </main>
    </div>
  );
}

const wait = (s) => (s >= 60 ? `${Math.floor(s / 60)}분 ${s % 60}초` : `${s}초`);
const DAYS = '일월화수목금토';
const shortDate = (ymd) => `${+ymd.slice(5, 7)}월 ${+ymd.slice(8, 10)}일(${DAYS[new Date(ymd + 'T00:00:00Z').getUTCDay()]})`;
