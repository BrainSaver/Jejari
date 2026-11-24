import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router';
import { get, post, put } from '../api/client.js';
import Icon from '../admin/Icon.jsx';
import { ConfirmButton } from '../admin/ui.jsx';
import { RES_STATUS, hm, shortDate, won } from './fmt.js';
import RouteLine from './RouteLine.jsx';
import { useMember } from './useMember.js';
import { useMyProfile } from './myName.js';

/**
 * U-12 여행사 단체 예매 (코레일 단체승차권 기준, concurrency.md §18).
 *   POST /api/v1/groups                     신청 {runId, fromStationId, toStationId, consent, passengers[]} → 바로 자동 승인 시도
 *   GET  /api/v1/groups/me                  내 신청
 *   GET  /api/v1/groups/{id}                상세 (탑승자 이름·번호는 가려서)
 *   POST /api/v1/groups/{id}/withdraw       승인 전 철회
 *   PUT  /api/v1/groups/{id}/passengers     명단 변경 (승인 뒤 7일 이내 1회)
 * 검색 결과의 [단체 신청]에서 ?run=&from=&to= 를 들고 들어온다. 승인되면 예약 한 건이 생겨 일반 결제 화면(U-06)으로 결제한다.
 */
const MIN = 11;
const MAX = 200;
const NO_PHONE_WARN = 0.3;
const ROSTER_DAYS = 7;

const STATUS = {
  REQUESTED: ['sky', '배정 대기'],
  APPROVED: ['go', '배정됨'],
  REJECTED: ['stop', '반려'],
  WITHDRAWN: ['', '철회함'],
};
const OUTCOME = {
  APPROVED: ['go', '좌석을 붙여서 배정했어요. 결제 기한 안에 대표자가 한 번에 결제해 주세요.'],
  DEFERRED: ['amber', '지금은 개인 예매 대기열이 붐벼서 배정을 잠시 미뤘어요. 대기열이 비면 신청 순서대로 자동으로 배정해요.'],
  SEAT_SHORT: ['amber', '남은 좌석이 모자라요. 신청은 남겨 두고, 좌석이 생기면 자동으로 배정해요.'],
  REVIEW: ['amber', '관리자가 확인한 뒤 배정해요. 결과는 아래 "내 단체 신청"에서 볼 수 있어요.'],
};
const MESSAGES = {
  GROUP_SIZE_OUT_OF_RANGE: `단체는 대표자를 포함해 ${MIN}명부터 ${MAX}명까지예요.`,
  GROUP_CONSENT_REQUIRED: '탑승자 개인정보 제공 동의에 체크해 주세요.',
  GROUP_REP_INVALID: '대표자는 한 명이고, 휴대폰 번호가 있어야 해요.',
  GROUP_REP_PHONE_MISMATCH: '대표자 번호가 가입할 때 본인인증한 번호와 달라요.',
  GROUP_DUPLICATE_PHONE: '같은 휴대폰 번호가 두 번 들어 있어요. 휴대폰이 없는 분은 "휴대폰 없음"에 체크해 주세요.',
  GROUP_NOT_OPEN: '이 열차는 지금 단체 신청을 받지 않아요. 출발 2개월 전 오전 7시부터 신청할 수 있어요.',
  GROUP_ALREADY_REQUESTED: '이 열차에 배정을 기다리는 단체 신청이 이미 있어요.',
  GROUP_ROSTER_LOCKED: `명단은 배정된 뒤 ${ROSTER_DAYS}일 안에 한 번만 바꿀 수 있어요.`,
  GUEST_NOT_ALLOWED: '단체 신청은 회원만 할 수 있어요.',
};
const msgOf = (e) => MESSAGES[e?.code] || e?.message;

const blank = () => ({ name: '', phone: '', noPhone: false, passengerType: 'ADULT' });

export default function Groups() {
  const [params] = useSearchParams();
  const runId = params.get('run');
  const from = params.get('from') || '';
  const to = params.get('to') || '';
  const { member, checking } = useMember();
  const location = useLocation();
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);

  const load = () => get('/api/v1/groups/me').then(setItems).catch(setError);
  useEffect(() => {
    if (member) load();
  }, [member]);

  if (checking) return null;
  if (!member) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;

  return (
    <>
      <header className="top"><h1>단체 예매</h1></header>
      <main className="body">
        <p className="guide"><Icon name="info" /><span>같은 구간을 함께 타는 {MIN}명 이상이면 단체로 신청해요. 대표자 한 분이 명단을 적고 한 번에 결제해요.</span></p>
        {error && <div className="alert stop" role="alert"><Icon name="info" /><span>{msgOf(error)}</span></div>}
        <div className="lay">
          <div className="c1">
            <div className="sec d-only">새로 신청</div>
            {runId ? (
              <ApplyCard runId={runId} from={from} to={to} onApplied={load} />
            ) : (
              <div className="card mut" style={{ fontSize: 14.5 }}>
                열차 검색 결과에서 [단체 신청]을 누르면 여기서 신청할 수 있어요.
                <p style={{ margin: '10px 0 0' }}><Link className="link" to="/">열차 찾기</Link></p>
              </div>
            )}
          </div>
          <div className="c2">
            <div className="sec">내 단체 신청</div>
            {items && !items.length && <p className="mut">신청한 단체가 없어요.</p>}
            {(items || []).map((g) => <GroupCard key={g.groupId} g={g} onChanged={load} />)}
          </div>
        </div>
      </main>
    </>
  );
}

// ------------------------------------------------------------ 신청

function ApplyCard({ runId, from, to, onApplied }) {
  const { member } = useMember();
  const { name: myName } = useMyProfile(member);
  const [run, setRun] = useState(null);
  const [rep, setRep] = useState({ name: '', phone: '' });
  const [rows, setRows] = useState(() => Array.from({ length: MIN - 1 }, blank));
  const [consent, setConsent] = useState(false);
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(null);

  useEffect(() => {
    get(`/api/v1/runs/${runId}?from=${from}&to=${to}`).then(setRun).catch(setMsg);
  }, [runId, from, to]);
  useEffect(() => {
    if (myName && !rep.name) setRep((r) => ({ ...r, name: myName }));
  }, [myName]);

  const total = rows.length + 1;
  const noPhone = rows.filter((r) => r.noPhone).length;
  const fare = run?.fares?.GENERAL;
  const depAt = run?.stops?.find((s) => s.stationId === run.fromStationId)?.departureAt;
  const discount = depAt && Date.parse(depAt) - Date.now() <= 30 * 86_400_000;

  const set = (i, patch) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const add = (n) => setRows([...rows, ...Array.from({ length: Math.min(n, MAX - total) }, blank)]);
  const remove = (i) => setRows(rows.filter((_, j) => j !== i));

  const submit = async () => {
    setMsg(null);
    setBusy(true);
    try {
      const passengers = [
        { name: rep.name, phone: rep.phone, noPhone: false, passengerType: 'ADULT', rep: true },
        ...rows.map((r) => ({ ...r, phone: r.noPhone ? null : r.phone, rep: false })),
      ];
      const res = await post('/api/v1/groups', { runId: Number(runId), fromStationId: from || null, toStationId: to || null, consent, passengers });
      setDone(res);
      onApplied();
    } catch (e) {
      setMsg(e);
    } finally {
      setBusy(false);
    }
  };

  if (!run) return msg ? <div className="alert stop"><Icon name="info" /><span>{msgOf(msg)}</span></div> : <p className="mut">열차를 불러오는 중…</p>;
  const dep = run.stops.find((s) => s.stationId === run.fromStationId);
  const arr = run.stops.find((s) => s.stationId === run.toStationId);

  if (done) {
    const [cls, text] = OUTCOME[done.outcome] || ['sky', '신청을 받았어요.'];
    return (
      <section className="ticket">
        <div className="row"><span className="tno grow"><b>{run.trainNo}</b> 열차<span className="dd">{shortDate(run.runDate)}</span></span><span className={'st ' + (STATUS[done.status]?.[0] || '')}>{STATUS[done.status]?.[1]}</span></div>
        <div className={'alert ' + cls} style={{ marginTop: 12 }}><Icon name="info" /><span>{text}</span></div>
        {done.outcome === 'APPROVED' && (
          <>
            <p style={{ margin: '12px 0', fontSize: 14.5 }}>
              {total}명 · <b>{won(done.totalAmount)}</b>{done.discount && <span className="mut"> (어른 10% 단체 할인)</span>}<br />
              <span className="mut">{shortDate(done.payDeadlineAt)} {hm(done.payDeadlineAt)}까지 결제</span>
            </p>
            <Link className="btn" to={`/pay/${done.reservationId}`}>결제하러 가기</Link>
          </>
        )}
        {(done.warnNoPhone || done.warnDupPhone) && (
          <p className="mut" style={{ fontSize: 12.5, margin: '10px 0 0' }}>
            {done.warnNoPhone && '휴대폰 없는 탑승자가 많아 관리자가 한 번 더 볼 수 있어요. '}
            {done.warnDupPhone && '다른 단체 명단과 겹치는 번호가 있어 관리자가 한 번 더 볼 수 있어요.'}
          </p>
        )}
      </section>
    );
  }

  return (
    <section className="ticket">
      <div className="row">
        <span className="tno grow"><b>{run.trainNo}</b> 열차<span className="dd">{shortDate(run.runDate)}</span></span>
        {fare != null && <span className="st sky">일반실 1인 {won(fare)}</span>}
      </div>
      <RouteLine from={{ name: dep?.stationName, time: dep?.departureAt }} to={{ name: arr?.stationName, time: arr?.arrivalAt }} />
      <div className="perf" />

      <div className="sec" style={{ marginTop: 4, marginBottom: 10 }}>대표자 (본인, 함께 탑승)</div>
      <div className="grp-row">
        <div className="field"><label htmlFor="rep-nm">이름</label><input id="rep-nm" value={rep.name} onChange={(e) => setRep({ ...rep, name: e.target.value })} /></div>
        <div className="field"><label htmlFor="rep-ph">휴대폰 (가입 때 인증한 번호)</label><input id="rep-ph" inputMode="tel" placeholder="010-1234-5678" value={rep.phone} onChange={(e) => setRep({ ...rep, phone: e.target.value })} /></div>
      </div>

      <div className="row sec" style={{ marginTop: 6 }}>
        <span className="grow">탑승자 명단</span>
        <span className={'num ' + (total < MIN ? 'faint' : '')} style={{ fontWeight: 700 }}>{total}명</span>
      </div>
      <ol className="grp-list">
        {rows.map((r, i) => (
          <li key={i} className="grp-row">
            <span className="grp-no num">{i + 2}</span>
            <input aria-label={`${i + 2}번 이름`} placeholder="이름" value={r.name} onChange={(e) => set(i, { name: e.target.value })} />
            <input aria-label={`${i + 2}번 휴대폰`} inputMode="tel" placeholder={r.noPhone ? '대표자 번호로 받아요' : '010-0000-0000'} disabled={r.noPhone}
              value={r.noPhone ? '' : r.phone} onChange={(e) => set(i, { phone: e.target.value })} />
            <label className="grp-chk"><input type="checkbox" checked={r.noPhone} onChange={(e) => set(i, { noPhone: e.target.checked })} />휴대폰 없음</label>
            <select aria-label={`${i + 2}번 구분`} value={r.passengerType} onChange={(e) => set(i, { passengerType: e.target.value })}>
              <option value="ADULT">어른</option>
              <option value="CHILD">어린이</option>
            </select>
            <button type="button" className="btn ghost sm" aria-label={`${i + 2}번 빼기`} disabled={total <= MIN} onClick={() => remove(i)}>빼기</button>
          </li>
        ))}
      </ol>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <button type="button" className="btn soft sm" disabled={total >= MAX} onClick={() => add(1)}>1명 더</button>
        <button type="button" className="btn soft sm" disabled={total >= MAX} onClick={() => add(10)}>10명 더</button>
      </div>
      {noPhone / total > NO_PHONE_WARN && (
        <div className="alert amber" style={{ marginTop: 12 }}><Icon name="info" /><span>휴대폰 없는 분이 {Math.round(NO_PHONE_WARN * 100)}%를 넘어요. 신청은 되지만 관리자가 한 번 더 확인해요.</span></div>
      )}

      <div className="perf" />
      <p style={{ margin: '0 0 10px', fontSize: 14.5 }}>
        예상 금액 <b>{fare != null ? won(fare * total) : '—'}</b>
        {discount ? <span className="mut"> · 출발 1개월 안이라 어른은 10% 단체 할인돼요</span> : <span className="mut"> · 출발 1개월 안에 신청하면 어른 10% 할인</span>}
      </p>
      <label className="grp-chk wrap" style={{ fontSize: 14 }}>
        <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
        탑승자에게 이름·휴대폰 번호를 승차권 전달에 쓰는 것에 동의받았어요
      </label>
      {msg && <div className="alert stop" style={{ marginTop: 12 }}><Icon name="info" /><span>{msgOf(msg)}</span></div>}
      <button className="btn" type="button" style={{ marginTop: 14 }} disabled={busy || total < MIN || !consent} onClick={submit}>
        {busy ? '신청하는 중…' : `${total}명 단체 신청`}
      </button>
      <p className="mut" style={{ fontSize: 12.5, margin: '10px 0 0' }}>
        같은 객차에 붙여서 배정해요. 배정되면 3일 안에 대표자가 한 번에 결제하고, 명단은 배정 뒤 {ROSTER_DAYS}일 안에 한 번 바꿀 수 있어요.
        휴대폰 없는 분의 승차권은 대표자 번호로 보내요.
      </p>
    </section>
  );
}

// ------------------------------------------------------------ 내 신청

function GroupCard({ g, onChanged }) {
  const [cls, label] = STATUS[g.status] || ['', g.status];
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState(null);
  const [msg, setMsg] = useState(null);
  const res = g.reservationStatus ? RES_STATUS[g.reservationStatus] : null;
  const payable = g.reservationId && g.reservationStatus === 'HOLD';

  const loadDetail = () => get(`/api/v1/groups/${g.groupId}`).then(setDetail).catch(setMsg);
  const toggle = () => {
    if (!open && !detail) loadDetail();
    setOpen(!open);
  };
  const withdraw = async () => {
    try {
      await post(`/api/v1/groups/${g.groupId}/withdraw`);
      onChanged();
    } catch (e) {
      setMsg(e);
    }
  };

  return (
    <article className="ticket" style={g.status === 'REJECTED' || g.status === 'WITHDRAWN' ? { opacity: 0.7 } : {}}>
      <div className="row">
        <span className="tno grow"><b style={{ whiteSpace: 'nowrap' }}>단체 {g.passengerCount}명</b><span className="dd">신청 {shortDate(g.createdAt)}</span></span>
        <span className={'st ' + cls}>{label}</span>
        {res && <span className={'st ' + res[0]} style={{ marginLeft: 6 }}>{res[1]}</span>}
      </div>
      {g.status === 'REQUESTED' && <p className="mut" style={{ fontSize: 13.5, margin: '8px 0 0' }}>좌석이 나거나 대기열이 비면 신청 순서대로 자동으로 배정해요.</p>}
      {g.status === 'REJECTED' && g.cancelReason && <p className="mut" style={{ fontSize: 13.5, margin: '8px 0 0' }}>사유: {g.cancelReason}</p>}
      {payable && (
        <div className="row" style={{ marginTop: 10 }}>
          <span className="grow mut" style={{ fontSize: 13.5 }}>{shortDate(g.payDeadlineAt)} {hm(g.payDeadlineAt)}까지 결제{g.discount ? ' · 단체 할인' : ''}</span>
          <Link className="btn sm" to={`/pay/${g.reservationId}`}>결제하러 가기</Link>
        </div>
      )}
      <div className="row" style={{ marginTop: 10, gap: 8 }}>
        <button type="button" className="link grow" style={{ border: 0, background: 'none', padding: 0, cursor: 'pointer', font: 'inherit', fontSize: 14, textAlign: 'left' }} onClick={toggle} aria-expanded={open}>
          {open ? '명단 접기' : '명단 보기'}
        </button>
        {g.status === 'REQUESTED' && <ConfirmButton className="btn ghost sm" confirm="한 번 더 누르면 철회" onConfirm={withdraw}>신청 철회</ConfirmButton>}
      </div>
      {open && detail && <Roster g={g} detail={detail} onChanged={() => { loadDetail(); onChanged(); }} />}
      {msg && <p style={{ color: 'var(--stop)', fontWeight: 700, fontSize: 13.5, margin: '6px 0 0' }}>{msgOf(msg)}</p>}
    </article>
  );
}

function canChange(g) {
  if (g.status !== 'APPROVED' || g.rosterChangedAt || !g.approvedAt) return false;
  if (!['HOLD', 'PAYMENT_PENDING', 'CONFIRMED'].includes(g.reservationStatus)) return false;
  return Date.now() - Date.parse(g.approvedAt) <= ROSTER_DAYS * 86_400_000;
}

function Roster({ g, detail, onChanged }) {
  const [edit, setEdit] = useState({}); // passengerNo → {name, phone, noPhone}
  const [editing, setEditing] = useState(false);
  const [msg, setMsg] = useState(null);
  const changeable = canChange(g);

  const save = async () => {
    setMsg(null);
    const changes = Object.entries(edit).map(([no, v]) => ({ passengerNo: Number(no), name: v.name, phone: v.noPhone ? null : v.phone, noPhone: v.noPhone }));
    if (!changes.length) return setEditing(false);
    try {
      await put(`/api/v1/groups/${g.groupId}/passengers`, { changes });
      setEditing(false);
      setEdit({});
      onChanged();
    } catch (e) {
      setMsg(e);
    }
  };

  return (
    <div style={{ marginTop: 10 }}>
      <ol className="grp-list">
        {detail.passengers.map((p) => {
          const e = edit[p.passengerNo];
          return (
            <li key={p.passengerNo} className="grp-row">
              <span className="grp-no num">{p.passengerNo}</span>
              {editing && !p.rep ? (
                e ? (
                  <>
                    <input aria-label="새 이름" placeholder="새 이름" value={e.name} onChange={(ev) => setEdit({ ...edit, [p.passengerNo]: { ...e, name: ev.target.value } })} />
                    <input aria-label="새 휴대폰" inputMode="tel" placeholder="010-0000-0000" disabled={e.noPhone} value={e.noPhone ? '' : e.phone}
                      onChange={(ev) => setEdit({ ...edit, [p.passengerNo]: { ...e, phone: ev.target.value } })} />
                    <label className="grp-chk"><input type="checkbox" checked={e.noPhone} onChange={(ev) => setEdit({ ...edit, [p.passengerNo]: { ...e, noPhone: ev.target.checked } })} />휴대폰 없음</label>
                  </>
                ) : (
                  <>
                    <span className="grow">{p.name} <span className="faint">{p.noPhone ? '휴대폰 없음' : p.phone}</span></span>
                    <button type="button" className="btn ghost sm" onClick={() => setEdit({ ...edit, [p.passengerNo]: { name: '', phone: '', noPhone: false } })}>바꾸기</button>
                  </>
                )
              ) : (
                <span className="grow">
                  {p.name}{p.rep && <span className="st sky" style={{ marginLeft: 6 }}>대표자</span>}
                  <span className="faint"> {p.noPhone ? '휴대폰 없음' : p.phone} · {p.passengerType === 'CHILD' ? '어린이' : '어른'}</span>
                </span>
              )}
              {p.carNo != null && <span className="mut num" style={{ fontSize: 13 }}>{p.carNo}호차 {p.seatNo}</span>}
            </li>
          );
        })}
      </ol>
      {g.rosterChangedAt && <p className="mut" style={{ fontSize: 12.5 }}>명단은 {shortDate(g.rosterChangedAt)}에 한 번 바꿨어요.</p>}
      {changeable && !editing && <button type="button" className="btn soft sm" onClick={() => setEditing(true)}>명단 바꾸기 (1회)</button>}
      {editing && (
        <div className="row" style={{ gap: 8 }}>
          <span className="grow mut" style={{ fontSize: 12.5 }}>인원·좌석·어른/어린이는 그대로예요. 대표자는 바꿀 수 없어요. 저장하면 다시 바꿀 수 없어요.</span>
          <button type="button" className="btn ghost sm" onClick={() => { setEditing(false); setEdit({}); }}>그만두기</button>
          <ConfirmButton className="btn sm" confirm="한 번 더 누르면 저장" onConfirm={save}>저장</ConfirmButton>
        </div>
      )}
      {msg && <p style={{ color: 'var(--stop)', fontWeight: 700, fontSize: 13.5, margin: '6px 0 0' }}>{msgOf(msg)}</p>}
    </div>
  );
}
