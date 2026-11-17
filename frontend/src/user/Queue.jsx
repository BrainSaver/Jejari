import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { ApiError, del, get, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { savePass } from '../lib/pass.js';
import { useMember } from './useMember.js';

/**
 * U-04 대기열 — 최소판. 번호표를 받고 서버가 알려 준 간격(pollIntervalSec)으로 내 순서를 묻다가,
 * 입장(ADMITTED)하면 입장 토큰을 들고 좌석 선택(U-05)으로 간다.
 * 담당(김성원)이 시안 docs/ui/U-04.html대로 다듬는다 (남색 판, 달·학 그림, 예상 대기 시간 막대 등).
 *   POST   /api/v1/runs/{runId}/queue      줄 서기 (이미 서 있으면 같은 번호표)
 *   GET    /api/v1/runs/{runId}/queue/me   내 순서 / 입장하면 passToken
 *   DELETE /api/v1/runs/{runId}/queue/me   줄에서 나가기
 */
export default function Queue() {
  const { runId } = useParams();
  const [params] = useSearchParams();
  const { member, checking } = useMember();
  const navigate = useNavigate();
  const location = useLocation();
  const [st, setSt] = useState(null);
  const [error, setError] = useState(null);
  const timer = useRef(null);
  const seatsUrl = `/runs/${runId}/seats?from=${params.get('from') || ''}&to=${params.get('to') || ''}`;

  useEffect(() => {
    if (!member) return;
    let alive = true;
    const handle = (s) => {
      if (!alive) return;
      setSt(s);
      if (s.state === 'ADMITTED' && s.passToken) {
        savePass(runId, s.passToken, s.passExpiresAt);
        navigate(seatsUrl, { replace: true });
        return;
      }
      timer.current = setTimeout(poll, Math.max(1, s.pollIntervalSec || 2) * 1000);
    };
    const poll = () => get(`/api/v1/runs/${runId}/queue/me`).then(handle).catch(fail);
    const fail = (e) => alive && setError(e);
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
    navigate('/');
  };

  return (
    <>
      <header className="top narrow">
        <Link className="back wl" to="/"><span>열차 다시 고르기</span></Link>
        <h1>줄 서기</h1>
      </header>
      <main className="body narrow">
        <p className="guide"><Icon name="info" /><span>차례가 되면 좌석 고르는 화면으로 저절로 넘어가요. 이 화면을 닫지 마세요.</span></p>
        <ol className="flow" aria-label="예매 단계">
          <li className="now" aria-current="step"><b>1</b>줄 서기</li><li><b>2</b>좌석 고르기</li><li><b>3</b>결제</li><li><b>4</b>확정</li>
        </ol>
        {error && (
          <div className="alert stop" role="alert">
            <Icon name="info" />
            <span>{error instanceof ApiError ? error.message : '줄 서기에 실패했어요.'}</span>
          </div>
        )}
        {st && st.state === 'WAITING' && (
          <div className="card" style={{ textAlign: 'center' }}>
            <div className="mut">내 번호표</div>
            <div className="num" style={{ fontSize: 44, fontWeight: 900 }}>{st.ticketNo.toLocaleString()}</div>
            <div style={{ fontSize: 18, fontWeight: 800, marginTop: 6 }}>
              내 앞에 <span className="num">{(st.ahead ?? 0).toLocaleString()}</span>명
            </div>
            {st.estimatedWaitSec != null && <div className="mut">예상 {wait(st.estimatedWaitSec)}</div>}
          </div>
        )}
        {!st && !error && <p className="mut" style={{ textAlign: 'center' }}>번호표를 받는 중…</p>}
        <button type="button" className="btn ghost" onClick={leave}>줄에서 나가기</button>
        <p className="faint" style={{ fontSize: 12.5, textAlign: 'center', margin: 0 }}>최소판 화면이에요 · 시안 docs/ui/U-04.html · 담당 김성원</p>
      </main>
    </>
  );
}

const wait = (s) => (s >= 60 ? `${Math.floor(s / 60)}분 ${s % 60}초` : `${s}초`);
