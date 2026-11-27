import { useEffect, useRef, useState } from 'react';
import { Link, useOutletContext } from 'react-router';
import { del, get, post, put } from '../api/client.js';
import { usePolling } from '../hooks/usePolling.js';
import { addDays, md, mdhm, seoulToday, toInput } from '../lib/time.js';
import { Who } from './AdminLayout.jsx';
import RequestLog from './RequestLog.jsx';
import { ConfirmButton, ErrorBox, RunStatus } from './ui.jsx';
import { hasRole } from './useAdminAuth.js';

/**
 * A-03 운행·명절 행사 (ADM-02). API: /api/v1/admin/sale-events, /api/v1/admin/runs
 *
 * 백엔드가 보이게:
 *  - 운행 상태를 바꾸는 주체(판매 오픈 스케줄러)와 규칙을 화면 위에 적는다
 *  - 일괄 생성 결과(만든 운행 번호·건너뛴 날짜)를 서버 응답 그대로 보여 준다
 *  - 실패는 오류 코드(RUN_NOT_SCHEDULED, IN_USE …)와 traceId까지 보여 준다
 */
const POLL_MS = 10_000;
const WEEK = ['월', '화', '수', '목', '금', '토', '일']; // 서버: 1=월 … 7=일
const LIMIT_LABEL = { PER_RESERVATION: '1회', PER_RUN: '열차당', PER_DAY: '1일', PER_HOLIDAY: '명절 전체' };
const DEFAULT_LIMITS = { PER_RESERVATION: 4, PER_RUN: 4, PER_DAY: 20, PER_HOLIDAY: 8 };

const PAGE = 50;

export default function Runs() {
  const claims = useOutletContext();
  const canWrite = hasRole(claims, 'SUPERVISOR');
  const [error, setError] = useState(null);

  // ---- 명절 행사
  const [events, setEvents] = useState([]);
  const [eventForm, setEventForm] = useState(null); // null | {mode:'new'|'edit', ...}
  const loadEvents = () => get('/api/v1/admin/sale-events').then(setEvents).catch(setError);
  useEffect(() => {
    loadEvents();
  }, []);

  // ---- 운행 필터
  const today = seoulToday();
  const [filter, setFilter] = useState({ eventId: '', status: '', trainId: '', from: today, to: addDays(today, 62) });
  const pickEvent = (ev) =>
    setFilter((f) =>
      ev && String(ev.saleEventId) !== f.eventId
        ? { ...f, eventId: String(ev.saleEventId), from: ev.holidayFrom, to: ev.holidayTo }
        : { ...f, eventId: '', from: today, to: addDays(today, 62) },
    );

  const [trains, setTrains] = useState([]);
  useEffect(() => {
    get('/api/v1/admin/trains').then(setTrains).catch(setError);
  }, []);

  const query = new URLSearchParams({ from: filter.from, to: filter.to });
  if (filter.status) query.set('status', filter.status);
  if (filter.trainId) query.set('trainId', filter.trainId);
  if (filter.eventId) query.set('saleEventId', filter.eventId);
  const runs = usePolling(() => get('/api/v1/admin/runs?' + query), POLL_MS, [query.toString()]);

  const [bulkOpen, setBulkOpen] = useState(false);
  // 운행이 수백 건이라 한 번에 다 그리면 화면이 수만 px로 길어진다. 50건씩 더 보기
  const [shownN, setShownN] = useState(PAGE);
  useEffect(() => setShownN(PAGE), [query.toString()]);
  const selectedEvent = events.find((e) => String(e.saleEventId) === filter.eventId);

  /** 바꾸는 요청 공통: 실패를 화면 위에 띄우고, 성공하면 목록을 다시 읽는다 */
  const act = async (fn) => {
    setError(null);
    try {
      const r = await fn();
      runs.refreshNow();
      loadEvents();
      return r;
    } catch (e) {
      setError(e);
      throw e;
    }
  };

  return (
    <>
      <div className="head">
        <h1>운행·명절 행사</h1>
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>

      <div className="alert sky">
        <b>상태는 서버의 판매 오픈 스케줄러가 바꿔요.</b> <span className="num">SCHEDULED</span> → 판매 오픈 시각이 지나면{' '}
        <span className="num">ON_SALE</span> → 판매 마감 시각이 지나면 <span className="num">CLOSED</span>. 주기는 app_policy{' '}
        <code>sale.open_interval_sec</code>, 조건부 UPDATE라 앱이 여러 대여도 한 번만 바뀌어요. 이 화면은 10초마다 다시 읽어요.
        {!canWrite && <div style={{ marginTop: 4 }}>지금 권한({claims?.adm})은 보기만 돼요. 저장은 SUPERVISOR.</div>}
      </div>

      <ErrorBox error={error} onClose={() => setError(null)} />

      <section className="panel">
        <h2>
          <span className="grow">명절 행사</span>
          {canWrite && (
            <button type="button" className="btn ghost sm" onClick={() => setEventForm(eventForm ? null : { mode: 'new' })}>
              {eventForm?.mode === 'new' ? '닫기' : '행사 추가'}
            </button>
          )}
        </h2>
        <p className="sub">행을 누르면 아래 운행 목록이 그 행사로 걸러져요. 다시 누르면 풀려요.</p>
        <table>
          <thead>
            <tr>
              <th>이름</th><th>명절 기간</th><th>판매 오픈</th><th className="n">운행</th><th>1인 매수 제한</th><th>상태</th><th></th>
            </tr>
          </thead>
          <tbody>
            {events.map((ev) => (
              <tr
                key={ev.saleEventId}
                className={String(ev.saleEventId) === filter.eventId ? 'sel' : ''}
                onClick={() => pickEvent(ev)}
                style={{ cursor: 'pointer' }}
              >
                <td><b>{ev.name}</b> <span className="faint">#{ev.saleEventId}</span></td>
                <td>{md(ev.holidayFrom)} ~ {md(ev.holidayTo)}</td>
                <td className="mono">{mdhm(ev.saleOpenAt)}</td>
                <td className="n">{ev.runCount}</td>
                <td>{limitsText(ev.limits)}</td>
                <td><span className={'st ' + ({ OPEN: 'go', PLANNED: '', CLOSED: 'sky' }[ev.status] ?? '')}>{eventStatus(ev.status)}</span></td>
                <td className="n">
                  {canWrite && (
                    <button
                      type="button"
                      className="btn ghost sm"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEventForm({ mode: 'edit', ...ev });
                      }}
                    >
                      수정
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {!events.length && (
              <tr><td colSpan={7} className="mut">등록된 행사가 없어요.</td></tr>
            )}
          </tbody>
        </table>
        {eventForm && (
          <EventForm
            key={eventForm.mode + (eventForm.saleEventId || '')}
            init={eventForm}
            onCancel={() => setEventForm(null)}
            onSave={async (body) => {
              const saved = await act(() =>
                eventForm.mode === 'new'
                  ? post('/api/v1/admin/sale-events', body)
                  : put(`/api/v1/admin/sale-events/${eventForm.saleEventId}`, body),
              );
              setEventForm(null);
              pickEvent(saved);
            }}
          />
        )}
      </section>

      <section className="panel">
        <h2>
          <span className="grow">
            {selectedEvent ? `${selectedEvent.name} 운행` : '운행'}{' '}
            <span className="mut" style={{ fontWeight: 500 }}>{runs.data ? `${runs.data.length}건` : ''}</span>
          </span>
          {canWrite && (
            <button type="button" className="btn sm" onClick={() => setBulkOpen(!bulkOpen)}>
              {bulkOpen ? '닫기' : '운행 일괄 생성'}
            </button>
          )}
        </h2>

        <div className="filters" style={{ marginBottom: 12 }}>
          <Field label="운행일 부터">
            <input type="date" value={filter.from} onChange={(e) => setFilter({ ...filter, from: e.target.value })} />
          </Field>
          <Field label="까지">
            <input type="date" value={filter.to} onChange={(e) => setFilter({ ...filter, to: e.target.value })} />
          </Field>
          <Field label="상태">
            <select value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
              <option value="">모든 상태</option>
              {['SCHEDULED', 'ON_SALE', 'CLOSED', 'DEPARTED'].map((s) => <option key={s}>{s}</option>)}
            </select>
          </Field>
          <Field label="열차">
            <select value={filter.trainId} onChange={(e) => setFilter({ ...filter, trainId: e.target.value })}>
              <option value="">모든 열차</option>
              {trains.map((t) => <option key={t.trainId} value={t.trainId}>{t.trainNo} ({t.direction === 'DOWN' ? '하행' : '상행'})</option>)}
            </select>
          </Field>
        </div>

        {bulkOpen && (
          <BulkForm
            trains={trains}
            events={events}
            event={selectedEvent}
            onCreate={(body) => act(() => post('/api/v1/admin/runs', body))}
          />
        )}

        {runs.error && <ErrorBox error={runs.error} />}
        <table>
          <thead>
            <tr>
              <th>운행</th><th>열차</th><th>운행일</th><th>구간</th><th>출발</th><th>상태</th><th>판매 오픈</th><th className="n">판매 / 좌석</th><th></th>
            </tr>
          </thead>
          <tbody>
            {(runs.data || []).slice(0, shownN).map((r) => (
              <RunRow key={r.runId} r={r} canWrite={canWrite} act={act} />
            ))}
            {runs.data && !runs.data.length && (
              <tr><td colSpan={9} className="mut">이 조건에 맞는 운행이 없어요.</td></tr>
            )}
          </tbody>
        </table>
        {runs.data && runs.data.length > shownN && (
          <button type="button" className="btn ghost wide" style={{ marginTop: 12 }} onClick={() => setShownN((n) => n + PAGE)}>
            더 보기 ({shownN}건 보는 중 · {runs.data.length - shownN}건 남음)
          </button>
        )}
      </section>

      <RequestLog filter={(r) => r.path.includes('/admin/runs') || r.path.includes('/admin/sale-events')} />
    </>
  );
}

// ------------------------------------------------------------ 운행 한 줄

function RunRow({ r, canWrite, act }) {
  const [editing, setEditing] = useState(false);
  // 10초 새로고침 사이에 상태가 바뀌면(판매 오픈 스케줄러 등) 그 줄이 깜빡인다
  const prevStatus = useRef(r.status);
  const [changed, setChanged] = useState(0);
  useEffect(() => {
    if (prevStatus.current !== r.status) setChanged((c) => c + 1);
    prevStatus.current = r.status;
  }, [r.status]);
  const [openAt, setOpenAt] = useState(toInput(r.saleOpenAt));
  const scheduled = r.status === 'SCHEDULED';
  const pct = r.totalSeats ? Math.round((r.soldSeats / r.totalSeats) * 100) : 0;

  return (
    <tr key={changed} className={changed ? 'flash' : ''}>
      <td className="mono faint">#{r.runId}</td>
      <td className="mono">{r.trainNo}</td>
      <td className="nw">{md(r.runDate)}</td>
      <td className="nw">{r.origin && r.dest ? `${r.origin} → ${r.dest}` : '—'}</td>
      <td className="mono">{r.departureAt ? r.departureAt.slice(11, 16) : '—'}</td>
      <td><RunStatus status={r.status} /></td>
      <td className="mono nw">
        {editing ? (
          <span className="row" style={{ gap: 6 }}>
            <input type="datetime-local" value={openAt} onChange={(e) => setOpenAt(e.target.value)} style={{ minHeight: 30, padding: '2px 8px', width: 190 }} />
            <button
              type="button"
              className="btn sm"
              onClick={() =>
                act(() => put(`/api/v1/admin/runs/${r.runId}`, { saleEventId: r.saleEventId, saleOpenAt: openAt, saleCloseAt: r.saleCloseAt }))
                  .then(() => setEditing(false))
                  .catch(() => {})
              }
            >
              저장
            </button>
            <button type="button" className="btn ghost sm" onClick={() => setEditing(false)}>취소</button>
          </span>
        ) : (
          mdhm(r.saleOpenAt)
        )}
      </td>
      <td className={'n num' + (r.soldSeats ? '' : ' faint')}>
        {r.soldSeats} / {r.totalSeats} {r.soldSeats > 0 && <span className="faint">({pct}%)</span>}
      </td>
      <td className="n" style={{ whiteSpace: 'nowrap' }}>
        {r.status === 'ON_SALE' && (
          <Link className="btn ghost sm" to={`/admin/dashboard?run=${r.runId}`}>현황</Link>
        )}
        {scheduled && canWrite && !editing && (
          <>
            <button type="button" className="btn ghost sm" onClick={() => setEditing(true)}>오픈 시각</button>{' '}
            <ConfirmButton confirm="한 번 더 누르면 지금 판매" onConfirm={() => act(() => post(`/api/v1/admin/runs/${r.runId}/open`)).catch(() => {})}>
              지금 판매 열기
            </ConfirmButton>{' '}
            <ConfirmButton className="btn ghost sm" confirm="한 번 더 누르면 삭제" onConfirm={() => act(() => del(`/api/v1/admin/runs/${r.runId}`)).catch(() => {})}>
              삭제
            </ConfirmButton>
          </>
        )}
      </td>
    </tr>
  );
}

// ------------------------------------------------------------ 행사 폼

function EventForm({ init, onSave, onCancel }) {
  const isNew = init.mode === 'new';
  const [f, setF] = useState({
    name: init.name || '',
    holidayFrom: init.holidayFrom || '',
    holidayTo: init.holidayTo || '',
    saleOpenAt: toInput(init.saleOpenAt),
    priorityOpenAt: toInput(init.priorityOpenAt),
    status: init.status || 'PLANNED',
    limits: { ...DEFAULT_LIMITS },
  });
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await onSave({
        name: f.name,
        holidayFrom: f.holidayFrom,
        holidayTo: f.holidayTo,
        saleOpenAt: f.saleOpenAt,
        priorityOpenAt: f.priorityOpenAt || null,
        status: f.status,
        ...(isNew ? { limits: f.limits } : {}),
      });
    } catch {
      /* 오류는 화면 위 ErrorBox */
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} style={{ marginTop: 16, paddingTop: 16, borderTop: '2px solid var(--line2)' }}>
      <h2 style={{ fontSize: 14.5 }}>{isNew ? '새 명절 행사' : `${init.name} 수정`}</h2>
      <div className="filters">
        <Field label="이름"><input value={f.name} onChange={set('name')} required maxLength={50} placeholder="2027 설" /></Field>
        <Field label="명절 시작"><input type="date" value={f.holidayFrom} onChange={set('holidayFrom')} required /></Field>
        <Field label="명절 끝"><input type="date" value={f.holidayTo} onChange={set('holidayTo')} required /></Field>
        <Field label="판매 오픈"><input type="datetime-local" value={f.saleOpenAt} onChange={set('saleOpenAt')} required /></Field>
        <Field label="우선 예매 (선택)"><input type="datetime-local" value={f.priorityOpenAt} onChange={set('priorityOpenAt')} /></Field>
        <Field label="상태">
          <select value={f.status} onChange={set('status')}>
            <option value="PLANNED">준비 (PLANNED)</option>
            <option value="OPEN">판매 중 (OPEN)</option>
            <option value="CLOSED">끝 (CLOSED)</option>
          </select>
        </Field>
      </div>
      {isNew ? (
        <div className="filters" style={{ marginTop: 12 }}>
          {Object.keys(DEFAULT_LIMITS).map((k) => (
            <Field key={k} label={`매수 제한 · ${LIMIT_LABEL[k]}`}>
              <input
                type="number" min={1} max={99} value={f.limits[k]} style={{ width: 110 }}
                onChange={(e) => setF({ ...f, limits: { ...f.limits, [k]: Number(e.target.value) } })}
              />
            </Field>
          ))}
        </div>
      ) : (
        <p className="sub" style={{ margin: '10px 0 0' }}>
          판매 오픈을 바꾸면 이 행사의 <b>판매 전(SCHEDULED)</b> 운행도 같이 바뀌어요. 이미 열린 운행은 그대로예요. 매수 제한 수정은 아직 없어요.
        </p>
      )}
      <p className="sub" style={{ margin: '10px 0' }}>
        {isNew && '행사와 행사 전용 매수 제한 정책 4개가 한 트랜잭션으로 생겨요. 정책이 없으면 상시 정책만 걸려 명절 전체 한도가 빠져요 (동시성 §10.6).'}
      </p>
      <div className="row">
        <button className="btn sm" type="submit" disabled={busy}>{busy ? '저장 중' : '저장'}</button>
        <button className="btn ghost sm" type="button" onClick={onCancel}>닫기</button>
      </div>
    </form>
  );
}

// ------------------------------------------------------------ 일괄 생성 폼

function BulkForm({ trains, events, event, onCreate }) {
  const today = seoulToday();
  const [f, setF] = useState({
    trainId: trains[0]?.trainId ? String(trains[0].trainId) : '',
    from: event?.holidayFrom || addDays(today, 1),
    to: event?.holidayTo || addDays(today, 7),
    weekdays: [],
    departTime: '',
    saleEventId: event ? String(event.saleEventId) : '',
  });
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const train = trains.find((t) => String(t.trainId) === f.trainId);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  const toggleDay = (d) =>
    setF({ ...f, weekdays: f.weekdays.includes(d) ? f.weekdays.filter((x) => x !== d) : [...f.weekdays, d].sort() });

  useEffect(() => {
    if (!f.trainId && trains.length) setF((x) => ({ ...x, trainId: String(trains[0].trainId) }));
  }, [trains, f.trainId]);

  const pickEvent = (e) => {
    const ev = events.find((x) => String(x.saleEventId) === e.target.value);
    setF({ ...f, saleEventId: e.target.value, ...(ev ? { from: ev.holidayFrom, to: ev.holidayTo } : {}) });
  };

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setResult(null);
    try {
      const r = await onCreate({
        trainId: Number(f.trainId),
        from: f.from,
        to: f.to,
        weekdays: f.weekdays,
        departTime: f.departTime || null,
        saleEventId: f.saleEventId ? Number(f.saleEventId) : null,
      });
      setResult(r);
    } catch {
      /* 오류는 화면 위 ErrorBox */
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} style={{ background: 'var(--paper)', border: '1px solid var(--line)', borderRadius: 12, padding: 16, marginBottom: 16 }}>
      <div className="filters">
        <Field label="열차">
          <select value={f.trainId} onChange={set('trainId')} required>
            {trains.map((t) => (
              <option key={t.trainId} value={t.trainId}>
                {t.trainNo} · {t.routeName} {t.direction === 'DOWN' ? '하행' : '상행'} · {t.seatCount}석
              </option>
            ))}
          </select>
        </Field>
        <Field label="명절 행사">
          <select value={f.saleEventId} onChange={pickEvent}>
            <option value="">없음 (평시)</option>
            {events.map((ev) => <option key={ev.saleEventId} value={ev.saleEventId}>{ev.name}</option>)}
          </select>
        </Field>
        <Field label="부터"><input type="date" value={f.from} onChange={set('from')} required /></Field>
        <Field label="까지"><input type="date" value={f.to} onChange={set('to')} required /></Field>
        <Field label="첫 역 출발 (선택)" hint={train ? `비우면 ${train.direction === 'DOWN' ? '09:00' : '15:00'}` : ''}>
          <input type="time" value={f.departTime} onChange={set('departTime')} />
        </Field>
      </div>
      <div className="row" style={{ margin: '12px 0', flexWrap: 'wrap' }}>
        <span style={{ fontSize: 12.5, fontWeight: 800 }}>요일</span>
        {WEEK.map((w, i) => (
          <label key={w} className="row" style={{ gap: 4, fontWeight: 700 }}>
            <input type="checkbox" style={{ width: 18, minHeight: 18 }} checked={f.weekdays.includes(i + 1)} onChange={() => toggleDay(i + 1)} />
            {w}
          </label>
        ))}
        <span className="faint" style={{ fontSize: 12 }}>아무것도 안 고르면 매일</span>
      </div>
      <p className="sub" style={{ margin: '0 0 12px' }}>
        판매 오픈은 {f.saleEventId ? '행사 판매 오픈 시각을 그대로 복사' : '출발 2개월 전 07:00 (Oracle ADD_MONTHS)'}, 마감은 운행일 다음 날 00:00.
        정차 시각은 누적 거리 ÷ 2.7km/분 + 중간 정차 2분. 같은 날 운행이 이미 있으면 건너뛰고, 전부 한 트랜잭션이에요 (최대 92일).
      </p>
      <button className="btn sm" type="submit" disabled={busy || !f.trainId}>{busy ? '만드는 중' : '운행 만들기'}</button>
      {result && (
        <div className="alert sky" style={{ marginTop: 12, marginBottom: 0 }}>
          <b>{result.created.length}건 만들었어요</b>
          {result.created.length > 0 && <> (운행 #{result.created[0]}{result.created.length > 1 ? ` ~ #${result.created[result.created.length - 1]}` : ''})</>}
          {result.skipped.length > 0 && (
            <div>같은 날 운행이 있어 건너뛴 날짜 {result.skipped.length}개: {result.skipped.map(md).join(', ')}</div>
          )}
        </div>
      )}
    </form>
  );
}

// ------------------------------------------------------------ 작은 조각

function Field({ label, hint, children }) {
  return (
    // label로 감싸 입력칸과 이름을 묶는다 (화면 읽기 프로그램이 이름을 읽게)
    <label className="field">
      <span className="lbl">{label}</span>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}

const eventStatus = (s) => ({ PLANNED: '준비', OPEN: '판매 중', CLOSED: '끝' }[s] || s);
const limitsText = (l) =>
  l && Object.keys(l).length
    ? Object.keys(LIMIT_LABEL).filter((k) => l[k] != null).map((k) => `${LIMIT_LABEL[k]} ${l[k]}`).join(' · ')
    : '상시 정책';

