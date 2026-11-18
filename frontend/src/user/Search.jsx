import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { get } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { seoulHm, seoulNowLocal, seoulToday } from '../lib/time.js';
import { MiniTrain } from './RouteLine.jsx';

/**
 * U-03 메인·운행 검색 (TRN-01~03). 비회원도 볼 수 있다.
 *   GET /api/v1/stations                      역 목록
 *   GET /api/v1/runs?from=&to=&date=          검색 (등급별 운임·남은 좌석)
 *   GET /api/v1/runs/{runId}?from=&to=        카드를 펼칠 때 정차역
 *
 * 검색 조건은 주소(?from=&to=&date=)에 둔다 → 새로고침·뒤로 가기·링크 공유에도 같은 결과.
 */
const GRADES = [['GENERAL', '일반실'], ['FIRST', '특실']];
const LOW = 5; // 이만큼 이하면 "얼마 안 남음" 색

export default function Search() {
  const [params, setParams] = useSearchParams();
  const [stations, setStations] = useState([]);
  const [form, setForm] = useState({ from: params.get('from') || '', to: params.get('to') || '', date: params.get('date') || seoulToday(), time: params.get('time') || defaultTime(params.get('date') || seoulToday()) });
  const [state, setState] = useState({ runs: null, loading: false, error: null });
  const [sort, setSort] = useState('dep');
  const [spun, setSpun] = useState(false); // 바꾸기 버튼이 반 바퀴씩 돈다

  // 역 목록. 주소에 조건이 없으면 서울 → 부산(없으면 앞의 두 역)을 기본으로
  useEffect(() => {
    get('/api/v1/stations')
      .then((list) => {
        setStations(list);
        setForm((f) => {
          if (f.from && f.to) return f;
          const id = (name) => list.find((s) => s.stationName === name)?.stationId;
          return { ...f, from: id('서울') || list[0]?.stationId || '', to: id('부산') || list[1]?.stationId || '' };
        });
      })
      .catch((error) => setState((s) => ({ ...s, error })));
  }, []);

  // 주소의 조건이 바뀌면 검색
  const q = { from: params.get('from'), to: params.get('to'), date: params.get('date') };
  const qTime = params.get('time') || defaultTime(q.date);
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
  }, [q.from, q.to, q.date]);

  const submit = (e) => {
    e.preventDefault();
    if (form.from === form.to) {
      setState({ runs: null, loading: false, error: { message: '출발역과 도착역이 같아요. 다른 역을 골라 주세요.' } });
      return;
    }
    setParams({ from: form.from, to: form.to, date: form.date, time: form.time });
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
      return r.bookable || r.saleStatus === 'SCHEDULED';
    });
    return sort === 'dep'
      ? list.sort((a, b) => (a.departureAt || '').localeCompare(b.departureAt || ''))
      : list.sort((a, b) => a.durationMin - b.durationMin);
  }, [state.runs, sort, q.date, qTime]);

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
            <div className="dtime" style={{ display: 'flex', gap: 16 }}>
              <label className="row dt" style={{ position: 'relative', flex: 1.4, minWidth: 0 }}>
                <Icon name="calendar" />
                <span className="grow">
                  <span className="mut" style={{ fontSize: 12.5, display: 'block' }}>운행일</span>
                  <b>{longDate(form.date)}</b>
                </span>
                {form.date === seoulToday() && <span className="st amber">오늘</span>}
                {/* 날짜 칸은 보이지 않게 덮어 두고, 어디를 눌러도 브라우저 달력이 열린다 */}
                <input
                  type="date" aria-label="운행일" value={form.date} min={seoulToday()} required
                  onClick={openPicker}
                  onChange={(e) => e.target.value && setForm({ ...form, date: e.target.value, time: e.target.value === seoulToday() ? maxTime(form.time, seoulHm()) : form.time })}
                  style={overlay}
                />
              </label>
              <label className="row dt" style={{ position: 'relative', flex: 1, minWidth: 0 }}>
                <Icon name="clock" />
                <span className="grow">
                  <span className="mut" style={{ fontSize: 12.5, display: 'block' }}>출발 시각 이후</span>
                  <b>{timeText(form.time)}</b>
                </span>
                <input
                  type="time" aria-label="출발 시각" value={form.time} required
                  min={form.date === seoulToday() ? seoulHm() : undefined}
                  onClick={openPicker}
                  onChange={(e) => e.target.value && setForm({ ...form, time: e.target.value })}
                  style={overlay}
                />
              </label>
            </div>
            <button className="btn ink" type="submit" disabled={!stations.length}>열차 찾기</button>
          </form>
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
                {shortDate(q.date)} {timeText(qTime)} 이후 {name(q.from)}에서 {name(q.to)}까지 열차 {sorted.length}개
              </b>
              <div className="sort">
                <button type="button" className={sort === 'dep' ? 'on' : ''} onClick={() => setSort('dep')}>출발 빠른 순</button>
                <button type="button" className={sort === 'dur' ? 'on' : ''} onClick={() => setSort('dur')}>소요 짧은 순</button>
              </div>
            </div>
            {/* 카드가 차례로 올라오고, 노선 선 위로 기차가 출발역에서 도착역까지 달린다 (정렬을 바꾸면 다시) */}
            <div className="stagger" key={sort} style={{ display: 'flex', flexDirection: 'column', gap: 'inherit' }}>
              {sorted.map((r, i) => <RunCard key={r.runId} r={r} i={i} from={q.from} to={q.to} name={name} />)}
            </div>
            {!sorted.length && (
              <div className="card" style={{ textAlign: 'center' }}>
                <b>이 날짜·시각 이후에 탈 수 있는 열차가 없어요.</b>
                <p className="mut" style={{ margin: '6px 0 0' }}>시각을 앞당기거나 날짜·역을 바꿔 다시 찾아보세요.</p>
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
const maxTime = (a, b) => (a > b ? a : b);
const timeText = (t) => {
  const [h, m] = (t || '00:00').split(':').map(Number);
  return `${h < 12 ? '오전' : '오후'} ${h % 12 === 0 ? 12 : h % 12}시${m ? ` ${m}분` : ''}`;
};

// ------------------------------------------------------------ 운행 카드

function RunCard({ r, i, from, to, name }) {
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
          <button type="button" className="btn" style={{ marginTop: 10 }} onClick={() => navigate(`/queue/${r.runId}?from=${from}&to=${to}`)}>예매하기</button>
        )}
        {view.action === 'wait' && (
          <button type="button" className="btn soft" style={{ marginTop: 10 }} onClick={() => navigate(`/waitlist?run=${r.runId}&from=${from}&to=${to}`)}>취소표 대기 신청</button>
        )}
        {view.action === 'off' && <span className="btn off" style={{ marginTop: 10 }} aria-disabled="true">{view.off}</span>}
      </div>
    </article>
  );
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

