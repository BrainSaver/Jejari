import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { api, get } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { seoulHm, seoulNowLocal, seoulToday } from '../lib/time.js';
import { MiniTrain } from './RouteLine.jsx';

/**
 * U-03 메인·운행 검색 (TRN-01~03). 비회원도 볼 수 있다.
 *   GET /api/v1/stations                      역 목록
 *   GET /api/v1/runs?from=&to=&date=          검색 (등급별 운임·남은 좌석)
 *   GET /api/v1/runs/{runId}?from=&to=        카드를 펼칠 때 정차역
 *
 * 검색 조건은 주소창이 아니라 브라우저 기록(history state)에 둔다 → 주소에는 아무것도 드러나지 않고,
 * 새로고침·뒤로 가기에는 같은 결과가 나온다. 처음 들어오면 서울 → 부산·지금 시각이 채워져 있지만 [열차 찾기]를 눌러야 검색한다.
 */
const GRADES = [['GENERAL', '일반실'], ['FIRST', '특실']];
const GRADE_KEYS = GRADES.map(([g]) => g);
const MAX_PEOPLE = 9; // 한 번에 예매할 수 있는 화면 상한 (실제 한도는 서버 정책)
// 서버가 받는 승객 유형은 ADULT / CHILD / SENIOR 세 가지 (reservation_seat.passenger_type)
const KINDS = [['adult', '어른', '13세 이상'], ['child', '어린이', '6 ~ 12세'], ['senior', '경로', '65세 이상']];
const LOW = 5; // 이만큼 이하면 "얼마 안 남음" 색

export default function Search() {
  const location = useLocation();
  const navigate = useNavigate();
  // 검색 조건. 예전처럼 params.get('from')으로 읽도록 URLSearchParams 모양으로 둔다 (값은 history state의 q)
  const params = useMemo(() => new URLSearchParams(location.state?.q || {}), [location.state]);
  const setParams = (q) => navigate(location.pathname, { state: { q } });
  // 예전 링크(?from=&to=&date=)로 들어오면 주소의 조건은 버리고 깨끗한 주소로 바꾼다
  useEffect(() => {
    if (location.search) navigate(location.pathname, { replace: true, state: location.state });
  }, []);
  const [stations, setStations] = useState([]);
  const [form, setForm] = useState({ from: params.get('from') || '', to: params.get('to') || '', date: params.get('date') || seoulToday(), time: params.get('time') || defaultTime(params.get('date') || seoulToday()), timeSet: false, ...peopleOf(params), grade: GRADE_KEYS.includes(params.get('grade')) ? params.get('grade') : '' });
  const [state, setState] = useState({ runs: null, loading: false, error: null });
  const [sort, setSort] = useState('dep');
  const [spun, setSpun] = useState(false); // 바꾸기 버튼이 반 바퀴씩 돈다

  // 역 목록. 조건이 없으면 서울 → 부산(없으면 앞의 두 역)을 채워 둔다. 검색은 [열차 찾기]를 눌러야 한다
  useEffect(() => {
    let alive = true;
    get('/api/v1/stations')
      .then((list) => {
        if (!alive) return;
        setStations(list);
        if (params.get('from') && params.get('to')) return;
        const id = (name) => list.find((s) => s.stationName === name)?.stationId;
        setForm((f) => ({ ...f, from: id('서울') || list[0]?.stationId || '', to: id('부산') || list[1]?.stationId || '' }));
      })
      .catch((error) => alive && setState((s) => ({ ...s, error })));
    return () => {
      alive = false;
    };
  }, []);

  // 검색 조건이 생기거나 [열차 찾기]를 다시 누르면 검색
  const q = { from: params.get('from'), to: params.get('to'), date: params.get('date') };
  const qTime = params.get('time') || defaultTime(q.date);
  const qParty = peopleOf(params);
  const qPeople = total(qParty);
  const [peopleOpen, setPeopleOpen] = useState(false);
  const qGrade = GRADE_KEYS.includes(params.get('grade')) ? params.get('grade') : '';
  useEffect(() => {
    if (!q.from || !q.to || !q.date) return;
    let alive = true;
    setState({ runs: null, loading: true, error: null });
    get(`/api/v1/runs?${new URLSearchParams(q)}`)
      .then((runs) => alive && setState({ runs, loading: false, error: null }))
      .catch((error) => alive && setState({ runs: null, loading: false, error }));
    return () => {
      alive = false;
    };
  }, [q.from, q.to, q.date, location.key]);

  const submit = (e) => {
    e.preventDefault();
    if (form.from === form.to) {
      setState({ runs: null, loading: false, error: { message: '출발역과 도착역이 같아요. 다른 역을 골라 주세요.' } });
      return;
    }
    // 이전 검색의 시각은 같은 날짜를 다시 찾을 때만 유지한다
    const keepTime = form.timeSet || (params.get('time') === form.time && params.get('date') === form.date);
    // 시각을 직접 고르지 않았으면 조건에 넣지 않는다. 넣으면 "검색한 순간의 지금 시각"이 굳어, 나중에 날짜만 바꿀 때 열차가 걸러진다
    // 고르지 않은 시각은 [열차 찾기]를 누른 지금으로 다시 맞춘다 (화면을 오래 열어 두면 처음 연 시각이 남아 있음)
    if (!keepTime) setForm((f) => ({ ...f, time: defaultTime(f.date) }));
    setParams({ from: form.from, to: form.to, date: form.date, ...(keepTime ? { time: form.time } : {}), adult: String(form.adult), child: String(form.child), senior: String(form.senior), ...(form.grade ? { grade: form.grade } : {}) });
  };

  const name = (id) => stations.find((s) => s.stationId === id)?.stationName || id;
  const sorted = useMemo(() => {
    if (!state.runs) return null;
    // 고른 날짜·시각 이후에 출발하고, 지금 기준으로 이미 떠났거나 예매가 끝난 열차는 뺀다
    const since = `${q.date}T${qTime}`;
    const now = seoulNowLocal();
    const list = state.runs.filter((r) => {
      const dep = r.departureAt || '';
      if (dep && (dep < since || dep <= now)) return false;
      return matches(r, qPeople, qGrade);
    });
    return sort === 'dep'
      ? list.sort((a, b) => (a.departureAt || '').localeCompare(b.departureAt || ''))
      : list.sort((a, b) => a.durationMin - b.durationMin);
  }, [state.runs, sort, q.date, qTime, qPeople, qGrade]);

  return (
    <>
      <section className="hero">
        <header className="top m-only" style={{ background: 'transparent', position: 'relative', zIndex: 1, paddingBottom: 0 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1 }}><b style={{ fontSize: 17 }}>제자리</b></span>
        </header>
        <div className="hero-in">
          <p className="guide">
            <Icon name="info" />
            <span>출발역, 도착역, 날짜를 확인하고 [열차 찾기]를 누르세요. 타실 열차의 [예매하기]를 누르면 줄을 서요.</span>
          </p>
          <span className="badge">명절 승차권 공정 예매</span>
          <h1 className="hello">어디로 가세요?</h1>
          <p className="hsub">먼저 줄 선 순서대로 좌석을 드려요.</p>

          <form className="card search" onSubmit={submit}>
            <div className="s-main">
            <div className="od">
              <label style={{ display: 'block' }}>
                <span className="mut" style={{ fontSize: 12.5 }}>출발</span>
                <select aria-label="출발역" value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })}>
                  {stations.map((s) => <option key={s.stationId} value={s.stationId}>{s.stationName}</option>)}
                </select>
              </label>
              <button type="button" className={'swap' + (spun ? ' spun' : '')} aria-label="출발·도착 바꾸기" onClick={() => { setSpun(!spun); setForm({ ...form, from: form.to, to: form.from }); }}>
                <Icon name="swap" />
              </button>
              <label style={{ display: 'block', textAlign: 'right' }}>
                <span className="mut" style={{ fontSize: 12.5 }}>도착</span>
                <select aria-label="도착역" value={form.to} onChange={(e) => setForm({ ...form, to: e.target.value })} style={{ textAlign: 'right', textAlignLast: 'right' }}>
                  {stations.map((s) => <option key={s.stationId} value={s.stationId}>{s.stationName}</option>)}
                </select>
              </label>
            </div>
            <div className="s-fields">
              <label className="fld">
                <span className="k"><Icon name="calendar" />운행일</span>
                <span className="v">{shortDate(form.date)}{form.date === seoulToday() && <span className="st amber">오늘</span>}</span>
                {/* 칸 전체를 보이지 않는 입력이 덮고 있어서, 어디를 눌러도 브라우저 달력이 열린다 */}
                <input
                  type="date" aria-label="운행일" value={form.date} min={seoulToday()} required
                  onClick={openPicker}
                  onChange={(e) => e.target.value && setForm({ ...form, date: e.target.value, time: timeForDate(form, e.target.value) })}
                  style={overlay}
                />
              </label>
              <label className="fld">
                <span className="k"><Icon name="clock" />출발 시각 이후</span>
                <span className="v">{timeShort(form.time)}</span>
                <input
                  type="time" aria-label="출발 시각" value={form.time} required
                  onClick={openPicker}
                  onChange={(e) => e.target.value && setForm({ ...form, time: e.target.value, timeSet: true })}
                  style={overlay}
                />
              </label>
              <button type="button" className="fld" aria-haspopup="dialog" onClick={() => setPeopleOpen(true)}>
                <span className="k"><Icon name="person" />인원</span>
                <span className="v">{partyText(form)}<i className="chev" aria-hidden="true" /></span>
              </button>
              <label className="fld">
                <span className="k"><Icon name="ticket" />좌석 종류</span>
                <select aria-label="좌석 종류" value={form.grade} onChange={(e) => setForm({ ...form, grade: e.target.value })}>
                  <option value="">전체</option>
                  {GRADES.map(([g, label]) => <option key={g} value={g}>{label}</option>)}
                </select>
              </label>
            </div>
          </div>
            <button className="btn ink" type="submit" disabled={!stations.length}>열차 찾기</button>
          </form>
          {peopleOpen && (
            <PeopleDialog
              value={form}
              onClose={() => setPeopleOpen(false)}
              onApply={(v) => { setForm({ ...form, ...v }); setPeopleOpen(false); }}
            />
          )}
        </div>
      </section>

      <main className="body" style={{ paddingTop: 20 }}>
        {state.error && (
          <div className="alert stop" role="alert">
            <Icon name="info" />
            <span>
              {state.error.message}
              {state.error.traceId && <span className="faint" style={{ display: 'block', fontSize: 12.5 }}>계속 안 되면 1588-0000에 이 번호를 알려 주세요: {state.error.traceId.slice(0, 12)}</span>}
            </span>
          </div>
        )}
        {state.loading && <p className="mut" style={{ textAlign: 'center' }}>열차를 찾고 있어요…</p>}

        {sorted && (
          <>
            <div className="row" style={{ margin: '10px 2px 0' }}>
              <b className="grow" style={{ fontSize: 16 }}>
                {shortDate(q.date)} {timeText(qTime)} 이후 {name(q.from)}에서 {name(q.to)}까지 {partyText(qParty)}{qGrade ? ` ${GRADES.find(([g]) => g === qGrade)[1]}` : ''} 열차 {sorted.length}개
              </b>
              <div className="sort">
                <button type="button" className={sort === 'dep' ? 'on' : ''} onClick={() => setSort('dep')}>출발 빠른 순</button>
                <button type="button" className={sort === 'dur' ? 'on' : ''} onClick={() => setSort('dur')}>소요 짧은 순</button>
              </div>
            </div>
            {/* 카드가 차례로 올라오고, 노선 선 위로 기차가 출발역에서 도착역까지 달린다 (정렬을 바꾸면 다시) */}
            <div className="stagger" key={sort} style={{ display: 'flex', flexDirection: 'column', gap: 'inherit' }}>
              {sorted.map((r, i) => <RunCard key={r.runId} r={r} i={i} from={q.from} to={q.to} name={name} party={qParty} grade={qGrade} />)}
            </div>
            {!sorted.length && (
              <div className="card" style={{ textAlign: 'center' }}>
                <b>조건에 맞는 열차가 없어요.</b>
                <p className="mut" style={{ margin: '6px 0 0' }}>시각을 앞당기거나 인원·좌석 종류, 날짜·역을 바꿔 다시 찾아보세요.</p>
              </div>
            )}
            {sorted.length > 0 && <p className="faint" style={{ fontSize: 12.5, textAlign: 'center', margin: 0 }}>남은 좌석은 몇 초 늦게 바뀔 수 있어요.</p>}
          </>
        )}
      </main>
    </>
  );
}

const overlay = { position: 'absolute', inset: 0, opacity: 0, cursor: 'pointer', minHeight: 0, width: '100%' };
/** 글자 영역을 눌러도 달력·시각 선택창이 열리게 한다 (브라우저는 보통 작은 아이콘만 연다) */
const openPicker = (e) => {
  try {
    e.currentTarget.showPicker?.();
  } catch {
    /* 이미 열려 있거나 지원하지 않으면 브라우저 기본 동작 */
  }
};
/** 오늘이면 지금 시각, 다른 날이면 0시 */
const defaultTime = (date) => (date === seoulToday() ? seoulHm() : '00:00');
const num = (v) => Math.max(0, Math.min(MAX_PEOPLE, Math.floor(Number(v)) || 0));
const total = (p) => p.adult + p.child + p.senior;
/** 주소에서 유형별 인원을 읽는다. 아무것도 없으면 어른 1명. 옛 주소(?people=3)는 어른 3명 */
function peopleOf(params) {
  const p = { adult: num(params.get('adult') ?? params.get('people')), child: num(params.get('child')), senior: num(params.get('senior')) };
  if (total(p) < 1) return { adult: 1, child: 0, senior: 0 };
  if (total(p) > MAX_PEOPLE) return { adult: Math.min(p.adult, MAX_PEOPLE), child: 0, senior: 0 };
  return p;
}
/** "어른 1명" / "어른 2 · 어린이 1" */
function partyText(p) {
  const parts = KINDS.filter(([k]) => p[k] > 0).map(([k, label]) => `${label} ${p[k]}`);
  return parts.length === 1 ? `${parts[0]}명` : parts.join(' · ');
}
const maxTime = (a, b) => (a > b ? a : b);
/** 날짜를 바꿨을 때의 시각. 이 화면에서 직접 고른 시각은 지키고(오늘이면 지금 이후로만), 아니면 그 날짜의 기본값(오늘 = 지금, 다른 날 = 0시) */
const timeForDate = (form, date) => {
  if (!form.timeSet) return defaultTime(date);
  return date === seoulToday() ? maxTime(form.time, seoulHm()) : form.time;
};
/** "13:30" → "오후 1:30" (칸이 좁아 "시·분"을 줄여 쓴다) */
const timeShort = (t) => {
  const [h, m] = (t || '00:00').split(':').map(Number);
  return `${h < 12 ? '오전' : '오후'} ${h % 12 === 0 ? 12 : h % 12}:${String(m).padStart(2, '0')}`;
};
const timeText = (t) => {
  const [h, m] = (t || '00:00').split(':').map(Number);
  return `${h < 12 ? '오전' : '오후'} ${h % 12 === 0 ? 12 : h % 12}시${m ? ` ${m}분` : ''}`;
};

// ------------------------------------------------------------ 인원 선택

/** 인원 선택 대화상자. [적용]을 눌러야 검색 조건에 반영되고, [취소]·닫기·Esc·바깥을 누르면 버린다 */
function PeopleDialog({ value, onClose, onApply }) {
  const [d, setD] = useState({ adult: value.adult, child: value.child, senior: value.senior });
  const sum = total(d);
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [onClose]);
  return (
    <div className="dlg-back" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dlg" role="dialog" aria-modal="true" aria-labelledby="pp-title">
        <header>
          <h2 id="pp-title">인원 선택</h2>
          <button type="button" className="x" aria-label="닫기" onClick={onClose}>×</button>
        </header>
        <ul>
          {KINDS.map(([k, label, age]) => (
            <li key={k}>
              <span>{label}<span className="age">({age})</span></span>
              <span className="step">
                <button type="button" aria-label={`${label} 한 명 빼기`} disabled={d[k] === 0 || sum <= 1} onClick={() => setD({ ...d, [k]: d[k] - 1 })}>−</button>
                <b>{d[k]}</b>
                <button type="button" aria-label={`${label} 한 명 더하기`} disabled={sum >= MAX_PEOPLE} onClick={() => setD({ ...d, [k]: d[k] + 1 })}>+</button>
              </span>
            </li>
          ))}
        </ul>
        <p className="note">한 번에 최대 {MAX_PEOPLE}명까지 고를 수 있어요. 어린이·경로 할인은 아직 없어 어른 운임으로 계산해요.</p>
        <footer>
          <button type="button" className="pill" onClick={onClose}>취소</button>
          <button type="button" className="pill on" onClick={() => onApply(d)}>적용</button>
        </footer>
      </div>
    </div>
  );
}

// ------------------------------------------------------------ 운행 카드

function RunCard({ r, i, from, to, name, party, grade }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [stops, setStops] = useState(null);
  const view = viewOf(r);

  const toggle = async () => {
    setOpen(!open);
    if (!stops) {
      try {
        const d = await get(`/api/v1/runs/${r.runId}?from=${from}&to=${to}`);
        setStops(d.stops);
      } catch {
        setStops([]);
      }
    }
  };

  return (
    <article className="ticket h" style={{ '--i': i }}>
      <div>
        <div className="row">
          <span className="tno grow"><b>{r.trainNo}</b> 열차 <span className="faint" style={{ fontWeight: 600 }}>{r.trainKind}</span></span>
          <span className={'st ' + view.cls}>{view.label}</span>
        </div>
        <div className="route">
          <div><div className="t">{hm(r.departureAt)}</div><div className="s">{name(from)}</div></div>
          <div className="mid"><i><MiniTrain replay={view.action === 'book'} /></i><span>{duration(r.durationMin)}</span></div>
          <div className="end"><div className="t">{hm(r.arrivalAt)}</div><div className="s">{name(to)}</div></div>
        </div>
        <button type="button" className="link" onClick={toggle} style={{ border: 0, background: 'none', padding: '10px 0 0', cursor: 'pointer', font: 'inherit', fontSize: 14 }} aria-expanded={open}>
          {open ? '정차역 접기' : '정차역 보기'}
        </button>
        {open && <Stops stops={stops} from={from} to={to} />}
      </div>
      <div className="perf" />
      <div className="fares">
        {GRADES.filter(([g]) => r.fares?.[g] != null).map(([g, label]) => (
          <div className="fare" key={g}>
            <span className="k">{label}</span>
            <span className="p">{r.fares[g].toLocaleString()}원</span>
            <Left n={r.remaining?.[g]} bookable={r.bookable} />
          </div>
        ))}
        {!Object.keys(r.fares || {}).length && <p className="mut" style={{ margin: '8px 0' }}>운임이 아직 등록되지 않았어요.</p>}
        {view.action === 'book' && (
          <button type="button" className="btn" style={{ marginTop: 10 }} onClick={() => navigate(`/queue/${r.runId}?from=${from}&to=${to}&adult=${party.adult}&child=${party.child}&senior=${party.senior}${grade ? `&grade=${grade}` : ''}`)}>예매하기</button>
        )}
        {view.action === 'wait' && (
          <button type="button" className="btn soft" style={{ marginTop: 10 }} onClick={() => navigate(`/waitlist?run=${r.runId}&from=${from}&to=${to}`)}>취소표 대기 신청</button>
        )}
        {view.action === 'off' && <span className="btn off" style={{ marginTop: 10 }} aria-disabled="true">{view.off}</span>}
      </div>
    </article>
  );
}

/**
 * 검색 조건에 맞는 열차인가: 판매 중이고, 고른 좌석 종류(전체면 아무 종류)에 운임이 있으며 남은 좌석이 인원 이상.
 * 오픈 전 열차는 남은 좌석을 모르니 그 종류의 운임만 있으면 보여 준다. 마감은 뺀다.
 */
function matches(r, people, grade) {
  const kinds = grade ? [grade] : GRADE_KEYS;
  if (!kinds.some((g) => r.fares?.[g] != null)) return false;
  if (r.saleStatus === 'SCHEDULED' && !r.bookable) return true;
  if (!r.bookable) return false;
  return kinds.some((g) => r.fares?.[g] != null && (r.remaining?.[g] ?? 0) >= people);
}

/** 판매 상태 → 배지·버튼. 색만이 아니라 글자로도 구분 (DESIGN.md) */
function viewOf(r) {
  const left = Object.values(r.remaining || {});
  const soldOut = r.bookable && left.length > 0 && left.every((n) => n === 0);
  if (soldOut) return { cls: 'stop', label: '매진', action: 'wait' };
  if (r.bookable) return { cls: 'go', label: '판매 중', action: 'book' };
  if (r.saleStatus === 'SCHEDULED') return { cls: 'amber', label: '오픈 전', action: 'off', off: openText(r.saleOpenAt) };
  return { cls: 'sky', label: '마감', action: 'off', off: '예매가 끝났어요' };
}

function Left({ n, bookable }) {
  if (!bookable || n == null) return null;
  if (n === 0) return <span className="left none">매진 (표 없음)</span>;
  return <span className={'left' + (n <= LOW ? ' low' : '')}>{n}석 남음</span>;
}

function Stops({ stops, from, to }) {
  if (!stops) return <p className="mut" style={{ fontSize: 14 }}>정차역을 불러오는 중…</p>;
  if (!stops.length) return <p className="mut" style={{ fontSize: 14 }}>정차역을 불러오지 못했어요.</p>;
  const a = stops.findIndex((s) => s.stationId === from);
  const b = stops.findIndex((s) => s.stationId === to);
  return (
    <ol style={{ listStyle: 'none', margin: '8px 0 0', padding: 0, fontSize: 14.5 }}>
      {stops.map((s, i) => {
        const mine = i >= a && i <= b;
        return (
          <li key={s.stationId} className="row" style={{ padding: '3px 0', fontWeight: mine ? 800 : 500, color: mine ? 'var(--ink)' : 'var(--faint)' }}>
            <span className="num" style={{ width: 48 }}>{hm(s.departureAt || s.arrivalAt)}</span>
            <span className="grow">{s.stationName}</span>
            {i === a && <span className="st go">타는 곳</span>}
            {i === b && <span className="st sky">내리는 곳</span>}
          </li>
        );
      })}
    </ol>
  );
}

// ------------------------------------------------------------ 글자 만들기

const DAYS = '일월화수목금토';
const dayOf = (ymd) => new Date(ymd + 'T00:00:00Z').getUTCDay();
/** "2027-02-06" → "2027년 2월 6일 토요일" */
const longDate = (ymd) => (ymd ? `${+ymd.slice(0, 4)}년 ${+ymd.slice(5, 7)}월 ${+ymd.slice(8, 10)}일 ${DAYS[dayOf(ymd)]}요일` : '');
/** "2027-02-06" → "2월 6일(토)" */
const shortDate = (ymd) => (ymd ? `${+ymd.slice(5, 7)}월 ${+ymd.slice(8, 10)}일(${DAYS[dayOf(ymd)]})` : '');
const hm = (t) => (t ? t.slice(11, 16) : '--:--');
const duration = (m) => (m >= 60 ? `${Math.floor(m / 60)}시간 ${m % 60 ? `${m % 60}분` : ''}`.trim() : `${m}분`);
/** "2027-01-16T07:00:00" → "1월 16일 오전 7시 오픈" */
function openText(t) {
  if (!t) return '예매 오픈 전';
  const h = +t.slice(11, 13);
  const m = +t.slice(14, 16);
  const ampm = h < 12 ? '오전' : '오후';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${+t.slice(5, 7)}월 ${+t.slice(8, 10)}일 ${ampm} ${h12}시${m ? ` ${m}분` : ''} 오픈`;
}

