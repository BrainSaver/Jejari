import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { ApiError, del, get, post } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { clearPass, loadPass, savePass } from '../lib/pass.js';
import { useMember } from './useMember.js';

/**
 * U-05 좌석 선택 (RSV-01·02). 입장 토큰을 가진 회원이 좌석을 고르고 선점한다.
 *   GET  /api/v1/runs/{runId}?from=&to=          열차·운임 (누구나)
 *   GET  /api/v1/runs/{runId}/seats?from=&to=    배치도 (헤더 X-Queue-Token)
 *   POST /api/v1/reservations                    선점 (헤더 X-Queue-Token, Idempotency-Key)
 *
 * 핵심
 *  - 여러 좌석은 모두 잡히거나 하나도 안 잡힌다 (서버 Redis Lua 한 번). 화면은 결과만 받는다
 *  - 멱등 키: "이 좌석 잡기"를 누를 때 하나 만들고, 응답을 못 받아 다시 보낼 때는 같은 키를 쓴다.
 *    고른 좌석이 바뀌면 새 키. 그래서 버튼을 두 번 눌러도, 네트워크가 끊겨 다시 보내도 예약은 하나다
 *  - 202 REQUEST_IN_PROGRESS(같은 키를 서버가 처리 중)면 1초 뒤 같은 키로 다시 묻는다
 *  - 배치도는 짧은 캐시라 조금 늦을 수 있다. 최종 판정은 선점 때 서버가 한다
 */
const MAX_PEOPLE = 9; // 화면의 상한. 실제 한도(1회·열차당·1일·명절)는 서버 정책이 판정하고 LIMIT_* 로 알려 준다
const TYPES = [['ADULT', '어른'], ['CHILD', '어린이'], ['SENIOR', '경로']];
const GRADE = { GENERAL: '일반실', FIRST: '특실' };
const MESSAGES = {
  LIMIT_PER_RESERVATION: '한 번에 예매할 수 있는 매수를 넘었어요. 인원을 줄여 주세요.',
  LIMIT_PER_RUN: '이 열차에서 살 수 있는 매수를 넘었어요 (본인인증 기준).',
  LIMIT_PER_DAY: '오늘 살 수 있는 매수를 넘었어요.',
  LIMIT_PER_HOLIDAY: '이번 명절에 살 수 있는 매수를 넘었어요.',
};

export default function Seats() {
  const { runId } = useParams();
  const [params] = useSearchParams();
  const from = params.get('from') || '';
  const to = params.get('to') || '';
  const { member, checking } = useMember();
  const navigate = useNavigate();
  const location = useLocation();

  const [pass, setPass] = useState(() => loadPass(runId));
  const left = useCountdown(pass?.expiresAt);
  const expired = !pass || left <= 0;

  // 좌석 화면을 나가면(다른 메뉴·뒤로 가기·챗봇 버튼 등) 입장을 끝내고 줄에서 뺀다. 다시 예매하면 맨 뒤에 새로 줄을 서고
  // 차례가 오면 좌석 고르는 시간도 새로 받는다. 나가도 토큰을 살려 두면 돌아왔을 때 줄 없이 들어오면서 시간은 이어지고,
  // 반대로 줄 없이 시간만 새로 주면 나갔다 들어오기로 시간을 끝없이 늘릴 수 있어서다.
  // 좌석을 잡아 결제로 갈 때도 뺀다: 결제 화면은 입장 토큰을 쓰지 않는데, 남겨 두면 토큰이 끝날 때까지(최대 10분) 다른 열차 줄을
  // 못 선다 ("이미 다른 열차를 기다리고 있어요"). 새로고침은 화면이 내려가지 않아(언마운트 없음) 그대로 이어진다.
  // StrictMode(개발)는 붙였다 떼었다 다시 붙이므로, 바로 다시 붙으면 취소되게 한 틱 미룬다.
  useEffect(() => {
    clearTimeout(pendingLeave[runId]);
    return () => {
      pendingLeave[runId] = setTimeout(() => {
        clearPass(runId);
        del(`/api/v1/runs/${runId}/queue/me`).catch(() => {});
      }, 0);
    };
  }, [runId]);

  const [run, setRun] = useState(null);
  const [map, setMap] = useState(null);
  const [carNo, setCarNo] = useState(null);
  // 검색 화면에서 고른 유형별 인원 (없으면 어른 1명)
  const want = Object.fromEntries(['adult', 'child', 'senior'].map((k) => [k, Math.max(0, Math.min(MAX_PEOPLE, Math.floor(Number(params.get(k))) || 0))]));
  if (want.adult + want.child + want.senior < 1 || want.adult + want.child + want.senior > MAX_PEOPLE) Object.assign(want, { adult: 1, child: 0, senior: 0 });
  const wantGrade = params.get('grade') || '';
  const [people, setPeople] = useState({ ADULT: want.adult, CHILD: want.child, SENIOR: want.senior });
  const [picked, setPicked] = useState([]); // [{seatId, seatNo, carNo, grade}]
  const [notice, setNotice] = useState(null); // {kind:'taken'|'error'|'expired', text}
  // 좌석 조회·선점이 너무 잦아 서버가 429(SEATMAP_RATE_LIMITED)로 쉬라고 한 때: {sec: 남은 초, what: 'map'|'hold'}.
  // 자동으로 다시 보내지 않는다 (바로 다시 부르면 제한이 의미 없고 더 오래 쉬게 된다). 시간이 지나면 사람이 [다시 불러오기]를 누른다
  const [rest, setRest] = useState(null);
  useEffect(() => {
    if (!rest || rest.sec <= 0) return undefined;
    const t = setTimeout(() => setRest((r) => (r ? { ...r, sec: r.sec - 1 } : r)), 1000);
    return () => clearTimeout(t);
  }, [rest]);
  const [busy, setBusy] = useState(false);
  const [lost, setLost] = useState([]); // 남이 먼저 잡은 좌석: 잠깐 흔들린다
  const idemKey = useRef(null);
  const [extending, setExtending] = useState(false);

  const total = people.ADULT + people.CHILD + people.SENIOR;
  const passHeader = pass ? { 'X-Queue-Token': pass.token } : undefined;

  useEffect(() => {
    get(`/api/v1/runs/${runId}?from=${from}&to=${to}`).then(setRun).catch(() => {});
  }, [runId, from, to]);

  // auto = 사람이 누르지 않았는데 화면이 스스로 다시 부름 (좌석을 뺏긴 직후 새로고침). 행동 로그에서 구분해
  // 위험 점수 특징(간격)에서 뺀다 — 안 빼면 봇에게 좌석을 놓친 사람이 "0.2초 만에 다시 조회"로 봇처럼 보인다
  const loadMap = useCallback(async (auto = false) => {
    if (!pass) return;
    try {
      const m = await get(`/api/v1/runs/${runId}/seats?from=${from}&to=${to}`, auto ? { ...passHeader, 'X-Search-Auto': '1' } : passHeader);
      setMap(m);
      setCarNo((c) => c ?? firstFreeCar(m, wantGrade));
    } catch (e) {
      handleError(e);
    }
  }, [runId, from, to, pass]);

  useEffect(() => {
    if (member) loadMap(false);
  }, [member, loadMap]);

  // 고른 좌석·인원이 바뀌면 다른 요청 → 새 멱등 키
  useEffect(() => {
    idemKey.current = null;
  }, [picked, people]);

  function handleError(e, what = 'map') {
    const code = e instanceof ApiError ? e.code : '';
    if (code === 'SEATMAP_RATE_LIMITED') {
      setNotice(null);
      setRest({ sec: Math.max(1, Math.ceil(Number(e.retryAfter) || 5)), what });
    } else if (code === 'QUEUE_TOKEN_INVALID' || code === 'QUEUE_TOKEN_REQUIRED') {
      clearPass(runId);
      setNotice({ kind: 'expired' });
    } else if (code === 'SEAT_ALREADY_TAKEN') {
      setNotice({ kind: 'taken', text: `방금 다른 분이 ${picked.map((p) => p.seatNo).join(', ')} 중 일부를 먼저 잡았어요. 다른 좌석을 고르거나, 나란히 빈 좌석을 대신 골라 드릴게요.` });
      setLost(picked.map((p) => p.seatId));
      setTimeout(() => setLost([]), 1300);
      setPicked([]);
      loadMap(true);
    } else {
      setNotice({ kind: 'error', text: MESSAGES[code] || e.message || '좌석을 잡지 못했어요.' });
    }
  }

  // 좌석 고르는 시간 늘리기: 서버가 1회만 허용한다 (QUEUE_EXTEND_USED). 성공하면 새 만료 시각으로 바꾼다
  const extend = async () => {
    if (extending || !pass || pass.extended) return;
    setExtending(true);
    try {
      const r = await post(`/api/v1/runs/${runId}/queue/me/extend`);
      savePass(runId, r.passToken, r.passExpiresAt, true);
      setPass({ token: r.passToken, expiresAt: r.passExpiresAt, extended: true });
      setNotice(null);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'QUEUE_EXTEND_USED') {
        savePass(runId, pass.token, pass.expiresAt, true);
        setPass({ ...pass, extended: true });
      } else {
        handleError(e);
      }
    } finally {
      setExtending(false);
    }
  };

  const car = map?.cars.find((c) => c.carNo === carNo);
  const fareOf = (grade) => run?.fares?.[grade] ?? 0;
  const sum = picked.reduce((s, p) => s + fareOf(p.grade), 0);

  const toggle = (c, s) => {
    setNotice(null);
    if (picked.some((p) => p.seatId === s.seatId)) {
      setPicked(picked.filter((p) => p.seatId !== s.seatId));
    } else if (picked.length < total) {
      setPicked([...picked, { seatId: s.seatId, seatNo: s.seatNo, carNo: c.carNo, grade: c.grade }]);
    } else {
      setNotice({ kind: 'error', text: `${total}명이라 ${total}석까지 고를 수 있어요. 바꾸려면 고른 좌석을 다시 눌러 빼 주세요.` });
    }
  };

  const autoPick = () => {
    if (!car) return;
    const seats = pickTogether(car, total);
    if (!seats) {
      setNotice({ kind: 'error', text: `${car.carNo}호차에는 빈 좌석이 ${total}석 없어요. 다른 호차를 골라 주세요.` });
      return;
    }
    setNotice(null);
    setPicked(seats.map((s) => ({ seatId: s.seatId, seatNo: s.seatNo, carNo: car.carNo, grade: car.grade })));
  };

  const hold = async () => {
    if (picked.length !== total || busy) return;
    setBusy(true);
    setNotice(null);
    idemKey.current = idemKey.current || crypto.randomUUID();
    const types = TYPES.flatMap(([t]) => Array(people[t]).fill(t));
    const body = {
      runId: Number(runId),
      fromStationId: from || undefined,
      toStationId: to || undefined,
      seats: picked.map((p, i) => ({ seatId: p.seatId, passengerType: types[i] })),
      channel: 'WEB',
    };
    try {
      for (let attempt = 0; attempt < 6; attempt++) {
        const r = await post('/api/v1/reservations', body, { ...passHeader, 'Idempotency-Key': idemKey.current });
        if (r && r.code === 'REQUEST_IN_PROGRESS') {
          await sleep(1000); // 202: 서버가 같은 키를 처리 중 → 같은 키로 다시
          continue;
        }
        clearPass(runId); // 좌석을 잡으면 입장 토큰은 다 쓴 것
        navigate(`/pay/${r.reservationId}`, { state: { hold: r, picked, run, from, to } });
        return;
      }
      setNotice({ kind: 'error', text: '처리가 오래 걸려요. 예매 내역에서 확인해 주세요.' });
    } catch (e) {
      handleError(e, 'hold');
    } finally {
      setBusy(false);
    }
  };

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;

  const stopName = (id) => run?.stops?.find((s) => s.stationId === id);
  const dep = stopName(run?.fromStationId);
  const arr = stopName(run?.toStationId);

  return (
    <>
      <header className="top">
        <Link className="back wl" to="/" state={{ q: { from, to, date: run?.runDate || '' } }}><span>열차 다시 고르기</span></Link>
        <h1>좌석 선택</h1>
      </header>
      <main className="body">
        <p className="guide">
          <Icon name="info" />
          <span>흰색 빈 좌석을 인원 수만큼 누른 뒤 [이 좌석 잡기]를 누르세요. 어려우면 [나란히 빈 좌석 골라 주기]를 누르세요.</span>
        </p>
        <ol className="flow" aria-label="예매 단계">
          <li className="done"><b>✓</b>줄 서기</li>
          <li className="now" aria-current="step"><b>2</b>좌석 고르기</li>
          <li><b>3</b>결제</li>
          <li><b>4</b>확정</li>
        </ol>

        <div className="lay">
          <div className="c1">
            {(expired || notice?.kind === 'expired') ? (
              <div className="alert stop" style={{ flexWrap: 'wrap', order: 4 }}>
                <Icon name="info" />
                <span className="grow">{pass ? '좌석 고르는 시간이 지나 순서가 끝났어요. 다시 줄을 서야 해요.' : '줄 서기를 먼저 해야 좌석을 고를 수 있어요.'}</span>
                <Link className="btn sm" to={`/queue/${runId}?from=${from}&to=${to}`} style={{ marginLeft: 28 }}>다시 줄 서기</Link>
              </div>
            ) : (
              <>
                <div className="chips" role="tablist" aria-label="호차" style={{ order: 3 }}>
                  {(map?.cars || []).map((c) => (
                    <button key={c.carNo} type="button" role="tab" aria-selected={c.carNo === carNo} className={'chip' + (c.carNo === carNo ? ' on' : '')} onClick={() => setCarNo(c.carNo)}>
                      {c.carNo}호차 {c.grade === 'FIRST' && <span className="faint">특실</span>}
                      <span className="faint" style={{ fontWeight: 600, marginLeft: 4 }}>{c.available}</span>
                    </button>
                  ))}
                </div>
                {rest && (
                  <div className="alert amber" role="status" aria-live="polite" style={{ flexWrap: 'wrap', order: 4 }}>
                    <Icon name="clock" />
                    <span className="grow">
                      {rest.sec > 0
                        ? <>좌석을 너무 자주 {rest.what === 'hold' ? '잡으려 해서' : '불러서'} <b>{rest.sec}초</b> 쉬어요.</>
                        : <>이제 다시 할 수 있어요.</>}
                      <span className="faint" style={{ display: 'block', fontSize: 12.5 }}>빈 좌석이 나기를 기다린다면 회원은 [취소표]에서 대기를 신청하는 게 더 빨라요.</span>
                    </span>
                    <button type="button" className="btn sm" style={{ marginLeft: 28 }} disabled={rest.sec > 0}
                      onClick={() => { const w = rest.what; setRest(null); if (w === 'hold') hold(); else loadMap(false); }}>
                      {rest.what === 'hold' ? '다시 잡기' : '다시 불러오기'}
                    </button>
                  </div>
                )}
                {notice && notice.kind !== 'expired' && (
                  <div key={notice.text} className="alert stop shake" role="alert" style={{ flexWrap: 'wrap', order: 4 }}>
                    <Icon name="info" />
                    <span className="grow">{notice.text}</span>
                    {notice.kind === 'taken' && (
                      <button type="button" className="btn sm" style={{ marginLeft: 28 }} onClick={autoPick}>나란히 빈 좌석 골라 주기</button>
                    )}
                  </div>
                )}
                {!map && !rest && <p className="mut" style={{ order: 5 }}>좌석 배치를 불러오는 중…</p>}
                {car && <SeatMap car={car} picked={picked} lost={lost} onToggle={(s) => toggle(car, s)} />}
                {car && (
                  <button type="button" className="btn ghost" style={{ order: 5 }} onClick={autoPick}>나란히 빈 좌석 골라 주기</button>
                )}
              </>
            )}
          </div>

          <aside className="c2 stick">
            {pass && !expired && (
              <div className={'timer' + (left < 60 ? ' late' : '')} style={{ order: 1 }}>
                <Icon name="clock" />
                <span className="grow">좌석을 고를 수 있는 시간</span>
                <b>{mmss(left)}</b>
              </div>
            )}
            {pass && !expired && (
              <p className="mut" style={{ order: 1, margin: 0, fontSize: 13 }}>다른 화면으로 나가면 입장이 끝나요. 다시 예매하면 맨 뒤에 새로 줄을 서고, 차례가 되면 시간을 새로 받아요.</p>
            )}
            {pass && !expired && (
              <button type="button" className={'btn sm' + (pass.extended ? ' off' : ' soft')} style={{ order: 1 }}
                disabled={pass.extended || extending} onClick={extend}>
                {extending ? <><span className="spinner" />늘리는 중…</> : pass.extended ? '시간을 이미 늘렸어요 (1회)' : '5분 더 쓰기 (1회만 가능)'}
              </button>
            )}
            <div className="card" style={{ padding: '14px 16px', order: 2 }}>
              <div className="row">
                <span className="tno grow"><b>{run?.trainNo || '…'}</b> 열차<span className="dd">{run ? shortDate(run.runDate) : ''}</span></span>
              </div>
              {dep && arr && (
                <>
                  <div className="d-only">
                    <div className="route sm">
                      <div><div className="t">{hm(dep.departureAt)}</div><div className="s">{dep.stationName}</div></div>
                      <div className="mid"><i /><span>{duration(dep.departureAt, arr.arrivalAt)}</span></div>
                      <div className="end"><div className="t">{hm(arr.arrivalAt)}</div><div className="s">{arr.stationName}</div></div>
                    </div>
                  </div>
                  <div className="m-only" style={{ fontSize: 14, marginTop: 2 }}>{dep.stationName} {hm(dep.departureAt)} → {arr.stationName} {hm(arr.arrivalAt)}</div>
                </>
              )}
              {TYPES.map(([t, label]) => (
                <div key={t} className="row" style={{ marginTop: 10, paddingTop: 10, borderTop: '1px solid var(--line2)' }}>
                  <span className="grow"><b>{label}</b></span>
                  <div className="qty">
                    <button type="button" aria-label={`${label} 한 명 빼기`} disabled={people[t] === 0 || total === 1} onClick={() => setPeople({ ...people, [t]: people[t] - 1 })}>−</button>
                    <b>{people[t]}</b>
                    <button type="button" aria-label={`${label} 한 명 더하기`} disabled={total >= MAX_PEOPLE} onClick={() => setPeople({ ...people, [t]: people[t] + 1 })}>+</button>
                  </div>
                </div>
              ))}
              <p className="faint" style={{ fontSize: 12.5, margin: '8px 0 0' }}>어린이·경로 할인은 아직 없어 어른 운임으로 계산해요.</p>
            </div>
            <div className="dock" style={{ order: 6 }}>
              <div className="row">
                <span className="grow">
                  <span className="mut" style={{ fontSize: 13 }}>{picked.length}/{total}석</span>{' '}
                  <b>{picked.length ? picked.map((p) => `${p.carNo}-${p.seatNo}`).join(', ') : '좌석을 골라 주세요'}</b>
                </span>
                <b className="num" style={{ fontSize: 22 }}>{sum.toLocaleString()}원</b>
              </div>
              <button type="button" className={'btn' + (picked.length === total && !expired ? '' : ' off')} disabled={picked.length !== total || busy || expired} onClick={hold}>
                {busy ? <><span className="spinner" />좌석을 잡는 중…</> : picked.length === total ? '이 좌석 잡기' : `${total - picked.length}석 더 골라 주세요`}
              </button>
              <p className="d-only mut" style={{ fontSize: 12.5, margin: 0 }}>잡은 좌석은 결제 기한까지 나만 결제할 수 있어요. 여러 좌석은 모두 잡히거나 하나도 안 잡혀요.</p>
            </div>
          </aside>
        </div>
      </main>
    </>
  );
}

// ------------------------------------------------------------ 배치도

/** 좌석 번호 "12A" → 행 12, 열 A. 열 배치(seatColumns)의 가운데에 통로 */
function grid(car) {
  const cols = (car.seatColumns || 'ABCD').split('');
  const aisleAfter = Math.ceil(cols.length / 2) - 1; // ABCD → B 뒤, ABC → B 뒤
  const rows = new Map();
  for (const s of car.seats) {
    const m = /^(\d+)([A-Z])$/.exec(s.seatNo);
    if (!m) continue;
    const r = Number(m[1]);
    if (!rows.has(r)) rows.set(r, {});
    rows.get(r)[m[2]] = s;
  }
  return { cols, aisleAfter, rows: [...rows.entries()].sort((a, b) => a[0] - b[0]) };
}

function SeatMap({ car, picked, lost = [], onToggle }) {
  const { cols, aisleAfter, rows } = useMemo(() => grid(car), [car]);
  const mine = new Set(picked.map((p) => p.seatId));
  const windows = [cols[0], cols[cols.length - 1]].join('·');
  const aisles = cols.slice(1, -1).join('·');

  const seatBtn = (s, label) => {
    if (!s) return <span />;
    const isMine = mine.has(s.seatId);
    const cls = (isMine ? 'mine' : s.state === 'EMPTY' ? 'free' : s.state === 'HELD' ? 'hold' : 'taken') + (lost.includes(s.seatId) ? ' lost' : '');
    const state = isMine ? '내가 고른 좌석' : s.state === 'EMPTY' ? '빈 좌석' : s.state === 'HELD' ? '다른 분이 고르는 중' : '판매됨';
    return (
      <button
        key={s.seatId} type="button" className={'seat ' + cls} data-rec={'seat-' + s.seatId}
        disabled={!isMine && s.state !== 'EMPTY'} aria-pressed={isMine}
        aria-label={`${s.seatNo} ${state}${s.window ? ', 창가' : ''}`}
        onClick={() => onToggle(s)}
      >
        {label}
      </button>
    );
  };

  // 모바일: 세로 (행이 아래로, 열이 옆으로)
  const vCols = `20px ${cols.map((_, i) => (i === aisleAfter ? '1fr 22px' : '1fr')).join(' ')}`;
  // PC: 가로 (열이 위아래, 행이 옆으로) — 시안 .hcar
  const hCols = `20px repeat(${rows.length}, minmax(0,1fr))`;

  return (
    <>
      <div className="car m-only" style={{ order: 5 }}>
        {rows.map(([r, byCol]) => (
          <div key={r} className="srow" style={{ gridTemplateColumns: vCols }}>
            <span className="rn">{r}</span>
            {cols.flatMap((c, i) => [seatBtn(byCol[c], c), i === aisleAfter ? <span key={'a' + i} className="aisle" /> : null])}
          </div>
        ))}
        <p className="mut" style={{ fontSize: 13, margin: '0 0 8px', textAlign: 'center' }}>{windows}는 창가, {aisles}는 통로 쪽 좌석이에요.</p>
        <Legend />
      </div>
      <div className="hcar d-only">
        <p className="mut" style={{ fontSize: 13, margin: '0 0 10px' }}>
          {car.carNo}호차 {GRADE[car.grade]} · {windows}는 창가, {aisles}는 통로 쪽 좌석이에요 · 빈 좌석 {car.available}석
        </p>
        <div className="hgrid" style={{ gridTemplateColumns: hCols }}>
          {cols.map((c, i) => (
            <FragmentRow key={c}>
              <span className="rn">{c}</span>
              {rows.map(([r, byCol]) => <span key={r} style={{ display: 'contents' }}>{seatBtn(byCol[c], `${r}${c}`)}</span>)}
              {i === aisleAfter && <span className="haisle" />}
            </FragmentRow>
          ))}
        </div>
        <Legend />
      </div>
    </>
  );
}

const FragmentRow = ({ children }) => <>{children}</>;

function Legend() {
  return (
    <div className="legend">
      <span><i />빈 좌석</span>
      <span><i style={{ background: 'var(--ink)', borderColor: 'var(--ink)' }} />내 선택</span>
      <span><i style={{ background: 'var(--amber-t)', borderColor: 'var(--sign)' }} />다른 분이 고르는 중</span>
      <span><i style={{ background: 'var(--line2)', borderColor: 'var(--line2)' }} />판매됨</span>
    </div>
  );
}

/**
 * 나란히 빈 좌석: 같은 행, 통로를 건너지 않는 연속 빈 좌석 n개를 앞 행부터 찾는다.
 * 없으면 같은 행 아무 자리 n개, 그것도 없으면 호차 안 아무 빈 좌석 n개. 그래도 모자라면 null
 */
function pickTogether(car, n) {
  const { cols, aisleAfter, rows } = grid(car);
  const free = (s) => s && s.state === 'EMPTY';
  const sides = [cols.slice(0, aisleAfter + 1), cols.slice(aisleAfter + 1)];
  for (const [, byCol] of rows) {
    for (const side of sides) {
      for (let i = 0; i + n <= side.length; i++) {
        const run = side.slice(i, i + n).map((c) => byCol[c]);
        if (run.every(free)) return run;
      }
    }
  }
  for (const [, byCol] of rows) {
    const inRow = cols.map((c) => byCol[c]).filter(free);
    if (inRow.length >= n) return inRow.slice(0, n);
  }
  const all = car.seats.filter(free);
  return all.length >= n ? all.slice(0, n) : null;
}

const firstFreeCar = (m, grade) => (m.cars.find((c) => c.grade === (grade || 'GENERAL') && c.available > 0) || m.cars.find((c) => c.available > 0) || m.cars[0])?.carNo ?? null;

// ------------------------------------------------------------ 시간

const pendingLeave = {}; // runId → 미뤄 둔 이탈 타이머 (StrictMode 재마운트 때 취소)

function useCountdown(expiresAt) {
  const calc = () => (expiresAt ? Math.max(0, Math.floor((Date.parse(expiresAt) - Date.now()) / 1000)) : 0);
  const [left, setLeft] = useState(calc);
  useEffect(() => {
    const t = setInterval(() => setLeft(calc()), 1000);
    return () => clearInterval(t);
  }, [expiresAt]);
  return left;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const mmss = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
const hm = (t) => (t ? t.slice(11, 16) : '--:--');
const DAYS = '일월화수목금토';
const shortDate = (ymd) => `${+ymd.slice(5, 7)}월 ${+ymd.slice(8, 10)}일(${DAYS[new Date(ymd + 'T00:00:00Z').getUTCDay()]})`;
function duration(a, b) {
  if (!a || !b) return '';
  const m = Math.round((Date.parse(b) - Date.parse(a)) / 60000);
  return m >= 60 ? `${Math.floor(m / 60)}시간 ${m % 60 ? `${m % 60}분` : ''}`.trim() : `${m}분`;
}
