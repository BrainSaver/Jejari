import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link, useLocation, useNavigate } from 'react-router';
import { api, get } from '../api/client.js';
import Icon, { Seal } from '../admin/Icon.jsx';
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
  // 예전 링크(?from=&to=&date=…)로 들어오면 그 조건을 한 번 읽어 쓴다 (봇·북마크·외부 링크가 이 주소로 들어옴)
  const legacy = useMemo(() => (location.state?.q ? null : legacyQuery(location.search)), []);
  const params = useMemo(() => new URLSearchParams(location.state?.q || legacy || {}), [location.state]);
  const setParams = (q) => navigate(location.pathname, { state: { q } });
  // 읽은 조건은 history state로 옮기고 주소는 깨끗하게 바꾼다 (이후 검색은 주소에 조건을 남기지 않는다)
  useEffect(() => {
    if (location.search) navigate(location.pathname, { replace: true, state: location.state?.q ? location.state : legacy ? { q: legacy } : location.state });
  }, []);
  const [stations, setStations] = useState([]);
  const [form, setForm] = useState({ from: params.get('from') || '', to: params.get('to') || '', date: params.get('date') || seoulToday(), time: toHour(params.get('time')) || defaultTime(params.get('date') || seoulToday()), timeSet: false, ...peopleOf(params), grade: GRADE_KEYS.includes(params.get('grade')) ? params.get('grade') : '' });
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
  const qTime = toHour(params.get('time')) || defaultTime(q.date);
  const qParty = peopleOf(params);
  const qPeople = total(qParty);
  const [openDlg, setOpenDlg] = useState(null); // 'date' | 'time' | 'people' | 'grade' — 열린 선택 창
  const closeDlg = () => setOpenDlg(null);
  const qGrade = GRADE_KEYS.includes(params.get('grade')) ? params.get('grade') : '';
  useEffect(() => {
    // 주소에 옛 쿼리가 남아 있는 첫 렌더는 건너뛴다. 위에서 주소를 정리하면 다시 그려지며 그때 한 번만 검색한다 (검색이 두 번 나가지 않게)
    if (location.search) return;
    // 검색 조건이 없으면(로고를 눌러 처음 화면으로 돌아옴) 이전 결과를 비워 아무것도 안 보이게 한다
    if (!q.from || !q.to || !q.date) {
      setState({ runs: null, loading: false, error: null });
      return;
    }
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
    // 화면을 오래 열어 둬 지난 시가 됐으면 지금 시로 (state를 직접 바꾸지 않고 지역 변수로)
    const time = form.date === seoulToday() && form.time < hourNow() ? hourNow() : form.time;
    const keepTime = form.timeSet || (params.get('time') === time && params.get('date') === form.date);
    // 시각을 직접 고르지 않았으면 조건에 넣지 않는다. 넣으면 "검색한 순간의 지금 시각"이 굳어, 나중에 날짜만 바꿀 때 열차가 걸러진다
    // 고르지 않은 시각은 [열차 찾기]를 누른 지금으로 다시 맞춘다 (화면을 오래 열어 두면 처음 연 시각이 남아 있음)
    if (!keepTime) setForm((f) => ({ ...f, time: defaultTime(f.date) }));
    setParams({ from: form.from, to: form.to, date: form.date, ...(keepTime ? { time } : {}), adult: String(form.adult), child: String(form.child), senior: String(form.senior), ...(form.grade ? { grade: form.grade } : {}) });
  };

  const name = (id) => stations.find((s) => s.stationId === id)?.stationName || id;
  const sorted = useMemo(() => {
    if (!state.runs || !q.date) return null;
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
          <span style={{ flex: 1 }}><Link className="m-logo" to="/"><Seal size={26} />제자리</Link></span>
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
              <button type="button" className="fld" aria-haspopup="dialog" onClick={() => setOpenDlg('date')}>
                <span className="k"><Icon name="calendar" />운행일</span>
                <span className="v">{shortDate(form.date)}{form.date === seoulToday() && <span className="st amber">오늘</span>}<i className="chev" aria-hidden="true" /></span>
              </button>
              <button type="button" className="fld" aria-haspopup="dialog" onClick={() => setOpenDlg('time')}>
                <span className="k"><Icon name="clock" />출발 시각 이후</span>
                <span className="v">{timeText(form.time)}<i className="chev" aria-hidden="true" /></span>
              </button>
              <button type="button" className="fld" aria-haspopup="dialog" onClick={() => setOpenDlg('people')}>
                <span className="k"><Icon name="person" />인원</span>
                <span className="v">{partyText(form)}<i className="chev" aria-hidden="true" /></span>
              </button>
              <button type="button" className="fld" aria-haspopup="dialog" onClick={() => setOpenDlg('grade')}>
                <span className="k"><Icon name="ticket" />좌석 종류</span>
                <span className="v">{form.grade ? GRADES.find(([g]) => g === form.grade)[1] : '전체'}<i className="chev" aria-hidden="true" /></span>
              </button>
            </div>
          </div>
            <button className="btn ink" type="submit" disabled={!stations.length}>열차 찾기</button>
          </form>
          {openDlg === 'date' && (
            <DateDialog value={form.date} onClose={closeDlg}
              onApply={(date) => { setForm({ ...form, date, time: timeForDate(form, date) }); closeDlg(); }} />
          )}
          {openDlg === 'time' && (
            <TimeDialog value={form.time} date={form.date} onClose={closeDlg}
              onApply={(time) => { setForm({ ...form, time, timeSet: true }); closeDlg(); }} />
          )}
          {openDlg === 'people' && (
            <PeopleDialog value={form} onClose={closeDlg}
              onApply={(v) => { setForm({ ...form, ...v }); closeDlg(); }} />
          )}
          {openDlg === 'grade' && (
            <GradeDialog value={form.grade} onClose={closeDlg}
              onApply={(grade) => { setForm({ ...form, grade }); closeDlg(); }} />
          )}
        </div>
      </section>

      <main className="body" style={{ paddingTop: 20 }}>
        {state.error && (
          <div className="alert stop" role="alert">
            <Icon name="info" />
            <span>
              {state.error.message}
              {state.error.traceId && <span className="faint" style={{ display: 'block', fontSize: 12.5 }}>계속 안 되면 문의할 때 이 번호를 알려 주세요: {state.error.traceId.slice(0, 12)}</span>}
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

/** 오늘이면 지금 시각, 다른 날이면 0시 */
const defaultTime = (date) => (date === seoulToday() ? hourNow() : '00:00');
/** 시(0~23)만 고른다: "00:00" ~ "23:00" */
const HOURS = Array.from({ length: 24 }, (_, h) => `${String(h).padStart(2, '0')}:00`);
/** 지금이 속한 시 (10:48 → "10:00"). 이보다 앞선 시는 이미 지난 시각이라 고를 수 없다 */
const hourNow = () => `${seoulHm().slice(0, 2)}:00`;
/** 예전에 저장된 "10:48" 같은 값도 시 단위로 맞춘다 */
const toHour = (t) => (t && /^\d{2}:\d{2}$/.test(t) ? `${t.slice(0, 2)}:00` : '');
/** 옛 주소 ?from=&to=&date=(&time=&adult=…)의 검색 조건. 출발·도착·날짜가 모두 있고 형식이 맞아야 한다. 아니면 null */
function legacyQuery(search) {
  if (!search) return null;
  const sp = new URLSearchParams(search);
  const [from, to, date] = [sp.get('from'), sp.get('to'), sp.get('date')];
  const id = /^[A-Za-z0-9_-]{1,20}$/;
  if (!from || !to || !date || !id.test(from) || !id.test(to) || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;
  const q = { from, to, date };
  for (const k of ['time', 'adult', 'child', 'senior', 'people', 'grade']) {
    const v = sp.get(k);
    if (v && /^[A-Za-z0-9:_-]{1,12}$/.test(v)) q[k] = v;
  }
  return q;
}
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
  return date === seoulToday() ? maxTime(form.time, hourNow()) : form.time;
};
const timeText = (t) => {
  const [h, m] = (t || '00:00').split(':').map(Number);
  return `${h < 12 ? '오전' : '오후'} ${h % 12 === 0 ? 12 : h % 12}시${m ? ` ${m}분` : ''}`;
};

// ------------------------------------------------------------ 선택 창 (날짜·시각·인원·좌석 종류)

/**
 * 선택 창 공통 틀. [적용]을 눌러야 검색 조건에 반영되고, [취소]·닫기·Esc·바깥을 누르면 버린다.
 * 페이지 전환 애니메이션(.page-enter)의 transform이 남아 있으면 position:fixed가 화면이 아니라 그 요소 기준이 돼,
 * 열차 목록이 길어진 뒤에는 창이 화면 아래로 밀려난다. 그래서 .user 바로 아래(화면 기준)에 그린다
 */
function Dialog({ id, title, onClose, onApply, children, note }) {
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
  const box = (
    <div className="dlg-back" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dlg" role="dialog" aria-modal="true" aria-labelledby={id}>
        <header>
          <h2 id={id}>{title}</h2>
          <button type="button" className="x" aria-label="닫기" onClick={onClose}>×</button>
        </header>
        {children}
        {note && <p className="note">{note}</p>}
        <footer>
          <button type="button" className="pill" onClick={onClose}>취소</button>
          <button type="button" className="pill on" onClick={onApply}>적용</button>
        </footer>
      </div>
    </div>
  );
  return createPortal(box, document.querySelector('.user') || document.body);
}

function PeopleDialog({ value, onClose, onApply }) {
  const [d, setD] = useState({ adult: value.adult, child: value.child, senior: value.senior });
  const sum = total(d);
  return (
    <Dialog id="pp-title" title="인원 선택" onClose={onClose} onApply={() => onApply(d)}
      note={`한 번에 최대 ${MAX_PEOPLE}명까지 고를 수 있어요. 어린이·경로 할인은 아직 없어 어른 운임으로 계산해요.`}>
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
    </Dialog>
  );
}

/** 운행일: 달 단위 달력. 오늘 이전은 고를 수 없다 */
function DateDialog({ value, onClose, onApply }) {
  const today = seoulToday();
  const [pick, setPick] = useState(value);
  const [ym, setYm] = useState(value.slice(0, 7)); // "2026-11"
  const [y, m] = ym.split('-').map(Number);
  const first = new Date(Date.UTC(y, m - 1, 1)).getUTCDay(); // 1일의 요일 (일=0)
  const days = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const iso = (d) => `${ym}-${String(d).padStart(2, '0')}`;
  const move = (n) => {
    const t = new Date(Date.UTC(y, m - 1 + n, 1));
    setYm(`${t.getUTCFullYear()}-${String(t.getUTCMonth() + 1).padStart(2, '0')}`);
  };
  const canPrev = ym > today.slice(0, 7);
  return (
    <Dialog id="dt-title" title="운행일 선택" onClose={onClose} onApply={() => onApply(pick)}>
      <div className="cal">
        <div className="cal-nav">
          <button type="button" aria-label="이전 달" disabled={!canPrev} onClick={() => move(-1)}>‹</button>
          <b>{y}년 {m}월</b>
          <button type="button" aria-label="다음 달" onClick={() => move(1)}>›</button>
        </div>
        <div className="cal-grid">
          {['일', '월', '화', '수', '목', '금', '토'].map((w, i) => <span key={w} className={'w' + (i === 0 ? ' sun' : i === 6 ? ' sat' : '')}>{w}</span>)}
          {Array.from({ length: first }, (_, i) => <span key={'b' + i} />)}
          {Array.from({ length: days }, (_, i) => {
            const d = i + 1;
            const v = iso(d);
            return (
              <button key={v} type="button" className={'d' + (v === pick ? ' on' : '') + (v === today ? ' today' : '')}
                disabled={v < today} aria-pressed={v === pick} aria-label={`${m}월 ${d}일`} onClick={() => setPick(v)}>{d}</button>
            );
          })}
        </div>
      </div>
    </Dialog>
  );
}

/** 출발 시각: 0~23시를 눌러 고른다. 오늘이면 이미 지난 시는 비활성 */
function TimeDialog({ value, date, onClose, onApply }) {
  const [pick, setPick] = useState(value);
  const now = hourNow();
  return (
    <Dialog id="tm-title" title="출발 시각 선택" onClose={onClose} onApply={() => onApply(pick)}
      note="고른 시(時) 이후에 출발하는 열차를 보여 줘요.">
      <div className="opt-grid">
        {HOURS.map((h) => (
          <button key={h} type="button" className={h === pick ? 'on' : ''} aria-pressed={h === pick}
            disabled={date === seoulToday() && h < now} onClick={() => setPick(h)}>{timeText(h)}</button>
        ))}
      </div>
    </Dialog>
  );
}

/** 좌석 종류: 전체 또는 한 가지 */
function GradeDialog({ value, onClose, onApply }) {
  const [pick, setPick] = useState(value);
  return (
    <Dialog id="gr-title" title="좌석 종류 선택" onClose={onClose} onApply={() => onApply(pick)}>
      <ul className="opt-list" role="radiogroup" aria-label="좌석 종류">
        {[['', '전체'], ...GRADES].map(([g, label]) => (
          <li key={g || 'all'}>
            <button type="button" role="radio" aria-checked={g === pick} className={g === pick ? 'on' : ''} onClick={() => setPick(g)}>
              <span>{label}</span><i aria-hidden="true">{g === pick ? '✓' : ''}</i>
            </button>
          </li>
        ))}
      </ul>
    </Dialog>
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
        {view.action === 'book' && r.fares?.GENERAL != null && (
          <Link className="link" style={{ display: 'block', marginTop: 8, fontSize: 13.5 }} to={`/groups?run=${r.runId}&from=${from}&to=${to}`}>11명 이상 단체라면 단체 신청</Link>
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

