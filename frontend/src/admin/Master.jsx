import { useEffect, useMemo, useState } from 'react';
import { useOutletContext, useSearchParams } from 'react-router';
import { get, post, put } from '../api/client.js';
import { Who } from './AdminLayout.jsx';
import RequestLog from './RequestLog.jsx';
import { ErrorBox } from './ui.jsx';
import { hasRole } from './useAdminAuth.js';
import { ROLE, ko } from './labels.js';

/**
 * A-02 기준 데이터 (ADM-01). API: /api/v1/admin/stations · routes · car-types · trains
 *
 * 백엔드가 보이게:
 *  - 탭마다 서버가 지키는 규칙(지우지 않는 참조 데이터, 쓰이는 동안 구조 잠금 IN_USE, ID 대역)을 위에 적는다
 *  - 잠긴 칸은 왜 잠겼는지(열차 N대가 사용 중, 운행 N건) 옆에 적는다
 *  - 열차 저장 전에 만들어질 좌석 수와 seat_id 계산식을 미리 보여 준다
 */
const TABS = [
  ['stations', '역'],
  ['routes', '노선'],
  ['car-types', '차량 유형'],
  ['trains', '열차'],
];

const RULES = {
  stations: '역은 지우지 않는 참조 데이터예요. 삭제 대신 이름·좌표만 고쳐요. 역 코드는 바꿀 수 없어요.',
  routes: '노선을 쓰는 열차가 있으면 역 구성은 잠기고 이름만 고칠 수 있어요. 화면에서 만든 노선 ID는 100부터 (시드는 1~).',
  'car-types': '이 유형을 쓰는 열차가 있으면 좌석 수·등급·열 배치는 잠기고 이름만 고칠 수 있어요. 이름을 바꾸면 좌석 배치도에도 바로 반영돼요.',
  trains: '저장하면 호차 구성대로 좌석이 함께 생겨요. 운행이 붙은 뒤에는 노선·방향·편성이 잠겨요. 화면에서 만든 열차 ID는 1000부터.',
};

export default function Master() {
  const claims = useOutletContext();
  const canWrite = hasRole(claims, 'SUPERVISOR');
  const [params, setParams] = useSearchParams();
  const tab = TABS.some(([k]) => k === params.get('tab')) ? params.get('tab') : 'trains';
  const [error, setError] = useState(null);

  // 네 탭이 서로의 목록을 쓴다 (노선 폼은 역, 열차 폼은 노선·차량 유형). 한 번에 읽고, 저장 뒤 다시 읽는다
  const [data, setData] = useState({ stations: [], routes: [], 'car-types': [], trains: [] });
  const load = async () => {
    try {
      const [stations, routes, carTypes, trains] = await Promise.all(
        TABS.map(([k]) => get('/api/v1/admin/' + k)),
      );
      setData({ stations, routes, 'car-types': carTypes, trains });
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
  }, []);

  /** 저장 공통: 실패는 위에, 성공하면 다시 읽고 저장된 행을 돌려준다 */
  const save = async (fn) => {
    setError(null);
    try {
      const r = await fn();
      await load();
      return r;
    } catch (e) {
      setError(e);
      throw e;
    }
  };

  const Tab = { stations: Stations, routes: Routes, 'car-types': CarTypes, trains: Trains }[tab];

  return (
    <>
      <div className="head">
        <h1>기준 데이터</h1>
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>
      <div className="tabs" role="tablist">
        {TABS.map(([k, label]) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setParams({ tab: k })}>
            {label} <span className="num" style={{ opacity: 0.7, fontWeight: 600 }}>{data[k].length}</span>
          </button>
        ))}
      </div>
      <div className="alert sky">
        {RULES[tab]}
        {!canWrite && <div style={{ marginTop: 4 }}>지금 권한({ko(ROLE, claims?.adm)})은 보기만 돼요. 저장은 총괄 관리자만 할 수 있어요.</div>}
      </div>
      <ErrorBox error={error} onClose={() => setError(null)} />
      <Tab data={data} canWrite={canWrite} save={save} key={tab} />
      <RequestLog filter={(r) => TABS.some(([k]) => r.path.includes('/admin/' + k))} />
    </>
  );
}

// ------------------------------------------------------------ 공통 틀: 왼쪽 목록 + 오른쪽 폼

function Layout({ title, count, search, setSearch, placeholder, onNew, canWrite, list, form }) {
  return (
    <div className="cols" style={{ marginBottom: 16 }}>
      <section className="panel">
        <h2>
          <span className="grow">{title} <span className="mut" style={{ fontWeight: 500 }}>{count}</span></span>
          <input placeholder={placeholder} aria-label={placeholder} value={search} onChange={(e) => setSearch(e.target.value)} style={{ width: 180, minHeight: 32 }} />
          {canWrite && <button type="button" className="btn ghost sm" onClick={onNew}>새로</button>}
        </h2>
        {list}
      </section>
      <section className="panel stick">{form}</section>
    </div>
  );
}

function useSelection(rows, idKey) {
  const [sel, setSel] = useState(null); // null = 새로 등록
  const current = sel == null ? null : rows.find((r) => String(r[idKey]) === String(sel)) || null;
  return [current, (row) => setSel(row ? row[idKey] : null)];
}

const match = (q, ...xs) => !q || xs.some((x) => String(x ?? '').toLowerCase().includes(q.trim().toLowerCase()));

function Field({ label, hint, children }) {
  return (
    <label className="field">
      <span className="lbl">{label}</span>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}

function Locked({ why }) {
  return <span className="st plain amber" style={{ marginLeft: 6 }}>잠김 · {why}</span>;
}

function SaveButton({ busy, label, disabled }) {
  return (
    <button className="btn wide" type="submit" disabled={busy || disabled}>
      {busy ? '저장 중' : label}
    </button>
  );
}

/** 폼 제출 공통: 버튼 잠그고, 성공하면 저장된 행을 고른다 */
function useSubmit(save, select) {
  const [busy, setBusy] = useState(false);
  const run = async (fn) => {
    setBusy(true);
    try {
      const saved = await save(fn);
      select(saved);
    } catch {
      /* 오류는 화면 위 ErrorBox */
    } finally {
      setBusy(false);
    }
  };
  return [busy, run];
}

// ------------------------------------------------------------ 역

function Stations({ data, canWrite, save }) {
  const rows = data.stations;
  const [q, setQ] = useState('');
  const [cur, select] = useSelection(rows, 'stationId');
  const [busy, run] = useSubmit(save, select);
  const [f, setF] = useState({});
  useEffect(() => setF(cur ? { ...cur, latitude: cur.latitude ?? '', longitude: cur.longitude ?? '' } : { stationId: '', stationName: '', latitude: '', longitude: '' }), [cur]);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = (e) => {
    e.preventDefault();
    const body = { stationId: f.stationId.trim(), stationName: f.stationName, latitude: num(f.latitude), longitude: num(f.longitude) };
    run(() => (cur ? put(`/api/v1/admin/stations/${cur.stationId}`, body) : post('/api/v1/admin/stations', body)));
  };

  return (
    <Layout
      title="역" count={`${rows.length}곳`} search={q} setSearch={setQ} placeholder="역 이름·코드 찾기" canWrite={canWrite} onNew={() => select(null)}
      list={
        <table>
          <thead><tr><th>코드</th><th>이름</th><th className="n">위도</th><th className="n">경도</th></tr></thead>
          <tbody>
            {rows.filter((r) => match(q, r.stationId, r.stationName)).map((r) => (
              <tr key={r.stationId} className={cur?.stationId === r.stationId ? 'sel' : ''} onClick={() => select(r)} style={{ cursor: 'pointer' }}>
                <td className="mono">{r.stationId}</td><td><b>{r.stationName}</b></td>
                <td className="n num">{r.latitude ?? '—'}</td><td className="n num">{r.longitude ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
      form={
        <form onSubmit={submit}>
          <h2>{cur ? `${cur.stationName} 수정` : '새 역 등록'}</h2>
          <Field label="역 코드" hint={cur ? '역 코드는 바꿀 수 없어요 (PK)' : '영문·숫자 10자 이내. 공공데이터 역 코드를 그대로 쓰면 좋아요'}>
            <input value={f.stationId || ''} onChange={set('stationId')} disabled={!!cur} required maxLength={10} />
          </Field>
          <Field label="역 이름"><input value={f.stationName || ''} onChange={set('stationName')} required maxLength={50} /></Field>
          <div className="row">
            <Field label="위도 (선택)"><input value={f.latitude ?? ''} onChange={set('latitude')} inputMode="decimal" /></Field>
            <Field label="경도 (선택)"><input value={f.longitude ?? ''} onChange={set('longitude')} inputMode="decimal" /></Field>
          </div>
          <SaveButton busy={busy} label={cur ? '역 저장' : '역 등록'} disabled={!canWrite} />
        </form>
      }
    />
  );
}

// ------------------------------------------------------------ 노선

function Routes({ data, canWrite, save }) {
  const rows = data.routes;
  const [q, setQ] = useState('');
  const [cur, select] = useSelection(rows, 'routeId');
  const [busy, run] = useSubmit(save, select);
  const [name, setName] = useState('');
  const [stops, setStops] = useState([]);
  useEffect(() => {
    setName(cur?.routeName || '');
    setStops(cur ? cur.stations.map((s) => ({ stationId: s.stationId, km: String(s.distanceKm) })) : [{ stationId: '', km: '0' }, { stationId: '', km: '' }]);
  }, [cur]);
  const locked = cur && cur.trainCount > 0;
  const nameOf = useMemo(() => Object.fromEntries(data.stations.map((s) => [s.stationId, s.stationName])), [data.stations]);

  const setStop = (i, k, v) => setStops(stops.map((s, j) => (j === i ? { ...s, [k]: v } : s)));
  const move = (i, d) => {
    const next = [...stops];
    [next[i], next[i + d]] = [next[i + d], next[i]];
    setStops(next);
  };

  const submit = (e) => {
    e.preventDefault();
    // 잠긴 노선은 stations를 빼고 보낸다 → 서버가 이름만 바꾼다
    const body = { routeName: name, ...(locked ? {} : { stations: stops.map((s) => ({ stationId: s.stationId, distanceKm: num(s.km) })) }) };
    run(() => (cur ? put(`/api/v1/admin/routes/${cur.routeId}`, body) : post('/api/v1/admin/routes', body)));
  };

  return (
    <Layout
      title="노선" count={`${rows.length}개`} search={q} setSearch={setQ} placeholder="노선 이름 찾기" canWrite={canWrite} onNew={() => select(null)}
      list={
        <table>
          <thead><tr><th>ID</th><th>이름</th><th>구간</th><th className="n">역</th><th className="n">거리</th><th className="n">열차</th></tr></thead>
          <tbody>
            {rows.filter((r) => match(q, r.routeName, r.routeId)).map((r) => (
              <tr key={r.routeId} className={cur?.routeId === r.routeId ? 'sel' : ''} onClick={() => select(r)} style={{ cursor: 'pointer' }}>
                <td className="mono faint">{r.routeId}</td><td><b>{r.routeName}</b></td>
                <td>{nameOf[r.originStationId] || r.originStationId} → {nameOf[r.destStationId] || r.destStationId}</td>
                <td className="n">{r.stations.length}</td>
                <td className="n num">{r.stations.length ? `${r.stations[r.stations.length - 1].distanceKm}km` : '—'}</td>
                <td className="n">{r.trainCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
      form={
        <form onSubmit={submit}>
          <h2>{cur ? `${cur.routeName} 수정` : '새 노선 등록'}</h2>
          <Field label="노선 이름"><input value={name} onChange={(e) => setName(e.target.value)} required maxLength={50} placeholder="경부선" /></Field>
          <div className="field">
            <span className="lbl">
              역 순서와 누적 거리 (km)
              {locked && <Locked why={`열차 ${cur.trainCount}대가 사용 중`} />}
            </span>
            {stops.map((s, i) => (
              <div className="row" key={i} style={{ marginBottom: 6 }}>
                <span className="num faint" style={{ width: 18 }}>{i + 1}</span>
                <select value={s.stationId} onChange={(e) => setStop(i, 'stationId', e.target.value)} disabled={locked} required style={{ flex: 1 }} aria-label={`${i + 1}번째 역`}>
                  <option value="">역 고르기</option>
                  {data.stations.map((st) => <option key={st.stationId} value={st.stationId}>{st.stationName}</option>)}
                </select>
                <input value={s.km} onChange={(e) => setStop(i, 'km', e.target.value)} disabled={locked || i === 0} inputMode="decimal" style={{ width: 90 }} aria-label={`${i + 1}번째 역 거리`} required />
                {!locked && (
                  <>
                    <button type="button" className="btn ghost sm" onClick={() => move(i, -1)} disabled={i <= 1} aria-label="위로">↑</button>
                    <button type="button" className="btn ghost sm" onClick={() => setStops(stops.filter((_, j) => j !== i))} disabled={stops.length <= 2 || i === 0} aria-label="빼기">−</button>
                  </>
                )}
              </div>
            ))}
            {!locked && (
              <button type="button" className="btn ghost sm" onClick={() => setStops([...stops, { stationId: '', km: '' }])}>역 추가</button>
            )}
            <span className="hint">첫 역은 0km이고 뒤로 갈수록 커져야 해요. 운임(2,800원 + 125원/km)과 정차 시각(2.7km/분)이 이 거리로 계산돼요.</span>
          </div>
          <SaveButton busy={busy} label={cur ? (locked ? '이름만 저장' : '노선 저장') : '노선 등록'} disabled={!canWrite} />
        </form>
      }
    />
  );
}

// ------------------------------------------------------------ 차량 유형

function CarTypes({ data, canWrite, save }) {
  const rows = data['car-types'];
  const [q, setQ] = useState('');
  const [cur, select] = useSelection(rows, 'carTypeCode');
  const [busy, run] = useSubmit(save, select);
  const [f, setF] = useState({});
  useEffect(() => setF(cur ? { ...cur, seatCount: String(cur.seatCount) } : { carTypeCode: '', carTypeName: '', seatCount: '56', seatGrade: 'GENERAL', seatColumns: 'ABCD' }), [cur]);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  const locked = cur && cur.carCount > 0;
  const cols = (f.seatColumns || '').toUpperCase();
  const n = Number(f.seatCount) || 0;
  const lastSeat = cols.length >= 2 && n > 0 ? `${Math.ceil(n / cols.length)}${cols[(n - 1) % cols.length]}` : null;

  const submit = (e) => {
    e.preventDefault();
    const body = { carTypeCode: f.carTypeCode.trim(), carTypeName: f.carTypeName, seatCount: Number(f.seatCount), seatGrade: f.seatGrade, seatColumns: cols };
    run(() => (cur ? put(`/api/v1/admin/car-types/${cur.carTypeCode}`, body) : post('/api/v1/admin/car-types', body)));
  };

  return (
    <Layout
      title="차량 유형" count={`${rows.length}종`} search={q} setSearch={setQ} placeholder="코드·이름 찾기" canWrite={canWrite} onNew={() => select(null)}
      list={
        <table>
          <thead><tr><th>코드</th><th>이름</th><th>등급</th><th className="n">좌석</th><th>열</th><th className="n">쓰는 호차</th></tr></thead>
          <tbody>
            {rows.filter((r) => match(q, r.carTypeCode, r.carTypeName)).map((r) => (
              <tr key={r.carTypeCode} className={cur?.carTypeCode === r.carTypeCode ? 'sel' : ''} onClick={() => select(r)} style={{ cursor: 'pointer' }}>
                <td className="mono">{r.carTypeCode}</td><td><b>{r.carTypeName || '—'}</b></td>
                <td><span className={'st ' + (r.seatGrade === 'FIRST' ? 'amber' : '')}>{grade(r.seatGrade)}</span></td>
                <td className="n">{r.seatCount}</td><td className="mono">{r.seatColumns || '—'}</td><td className="n">{r.carCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
      form={
        <form onSubmit={submit}>
          <h2>{cur ? `${cur.carTypeCode} 수정` : '새 차량 유형'}</h2>
          <Field label="코드" hint={cur ? '코드는 바꿀 수 없어요 (PK)' : '영문·숫자 10자 이내'}>
            <input value={f.carTypeCode || ''} onChange={set('carTypeCode')} disabled={!!cur} required maxLength={10} />
          </Field>
          <Field label="이름"><input value={f.carTypeName || ''} onChange={set('carTypeName')} maxLength={50} placeholder="KTX 일반실" /></Field>
          {locked && <p className="sub" style={{ margin: '0 0 8px' }}><Locked why={`호차 ${cur.carCount}량이 사용 중`} /></p>}
          <div className="row">
            <Field label="등급">
              <select value={f.seatGrade || 'GENERAL'} onChange={set('seatGrade')} disabled={locked}>
                <option value="GENERAL">일반실</option><option value="FIRST">특실</option>
              </select>
            </Field>
            <Field label="좌석 수"><input type="number" min={1} max={999} value={f.seatCount || ''} onChange={set('seatCount')} disabled={locked} required /></Field>
            <Field label="한 줄 열"><input value={f.seatColumns || ''} onChange={set('seatColumns')} disabled={locked} required maxLength={6} placeholder="ABCD" /></Field>
          </div>
          {lastSeat && (
            <div className="alert sky" style={{ margin: '0 0 14px' }}>
              좌석 번호 1{cols[0]} ~ {lastSeat}, 창가는 {cols[0]}·{cols[cols.length - 1]}열 (양 끝 열). 시드와 같은 규칙이에요.
            </div>
          )}
          <SaveButton busy={busy} label={cur ? (locked ? '이름만 저장' : '차량 유형 저장') : '차량 유형 등록'} disabled={!canWrite} />
        </form>
      }
    />
  );
}

// ------------------------------------------------------------ 열차

function Trains({ data, canWrite, save }) {
  const rows = data.trains;
  const types = data['car-types'];
  const [q, setQ] = useState('');
  const [cur, select] = useSelection(rows, 'trainId');
  const [busy, run] = useSubmit(save, select);
  const [f, setF] = useState({});
  useEffect(() => {
    setF(
      cur
        ? { trainNo: cur.trainNo, trainKind: cur.trainKind, routeId: String(cur.routeId), direction: cur.direction, groups: toGroups(cur.cars) }
        : { trainNo: '', trainKind: 'KTX', routeId: data.routes[0] ? String(data.routes[0].routeId) : '', direction: 'DOWN', groups: defaultGroups(types) },
    );
  }, [cur, types, data.routes]);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  const locked = cur && cur.runCount > 0;
  const groups = f.groups || [];
  const typeOf = Object.fromEntries(types.map((t) => [t.carTypeCode, t]));
  const cars = groups.flatMap((g) => Array(Math.max(0, Number(g.count) || 0)).fill(g.code));
  const seats = cars.reduce((s, c) => s + (typeOf[c]?.seatCount || 0), 0);
  const setGroup = (i, k, v) => setF({ ...f, groups: groups.map((g, j) => (j === i ? { ...g, [k]: v } : g)) });

  const submit = (e) => {
    e.preventDefault();
    run(() => {
      const body = { trainNo: f.trainNo, trainKind: f.trainKind, routeId: Number(f.routeId), direction: f.direction, cars };
      return cur ? put(`/api/v1/admin/trains/${cur.trainId}`, body) : post('/api/v1/admin/trains', body);
    });
  };

  return (
    <Layout
      title="열차" count={`${rows.length}대`} search={q} setSearch={setQ} placeholder="열차 번호 찾기" canWrite={canWrite} onNew={() => select(null)}
      list={
        <table>
          <thead><tr><th>ID</th><th>열차</th><th>노선</th><th className="n">호차</th><th className="n">좌석</th><th>구성</th><th className="n">운행</th></tr></thead>
          <tbody>
            {rows.filter((r) => match(q, r.trainNo, r.trainId)).map((r) => (
              <tr key={r.trainId} className={cur?.trainId === r.trainId ? 'sel' : ''} onClick={() => select(r)} style={{ cursor: 'pointer' }}>
                <td className="mono faint">{r.trainId}</td>
                <td className="mono">{r.trainNo} <span className="faint" style={{ fontWeight: 500 }}>{r.trainKind}</span></td>
                <td>{r.routeName} {r.direction === 'DOWN' ? '하행' : '상행'}</td>
                <td className="n">{r.cars.length}</td><td className="n">{r.seatCount}</td>
                <td className="mut">{composition(r.cars)}</td>
                <td className="n">{r.runCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
      form={
        <form onSubmit={submit}>
          <h2>{cur ? `${cur.trainNo} 열차 수정` : '새 열차 등록'}</h2>
          <div className="row">
            <Field label="열차 번호"><input value={f.trainNo || ''} onChange={set('trainNo')} required maxLength={10} placeholder="105" /></Field>
            <Field label="종류" hint="운임표의 열차 종류와 같게"><input value={f.trainKind || ''} onChange={set('trainKind')} required maxLength={20} /></Field>
          </div>
          {locked && <p className="sub" style={{ margin: '0 0 8px' }}><Locked why={`운행 ${cur.runCount}건이 붙어 있음`} /> 번호·종류만 바꿀 수 있어요.</p>}
          <div className="row">
            <Field label="노선">
              <select value={f.routeId || ''} onChange={set('routeId')} disabled={locked} required>
                {data.routes.map((r) => <option key={r.routeId} value={r.routeId}>{r.routeName}</option>)}
              </select>
            </Field>
            <Field label="방향">
              <select value={f.direction || 'DOWN'} onChange={set('direction')} disabled={locked}>
                <option value="DOWN">하행</option><option value="UP">상행</option>
              </select>
            </Field>
          </div>
          <div className="field">
            <span className="lbl">호차 구성 (1호차부터 순서대로)</span>
            {groups.map((g, i) => (
              <div className="row" key={i} style={{ marginBottom: 6 }}>
                <select value={g.code} onChange={(e) => setGroup(i, 'code', e.target.value)} disabled={locked} style={{ flex: 1 }} aria-label={`차량 유형 ${i + 1}`}>
                  {types.map((t) => <option key={t.carTypeCode} value={t.carTypeCode}>{t.carTypeName || t.carTypeCode} · {grade(t.seatGrade)} {t.seatCount}석</option>)}
                </select>
                <input type="number" min={1} max={20} value={g.count} onChange={(e) => setGroup(i, 'count', e.target.value)} disabled={locked} style={{ width: 70 }} aria-label={`${i + 1}번째 구성 량 수`} />
                <span className="mut">량</span>
                {!locked && groups.length > 1 && (
                  <button type="button" className="btn ghost sm" onClick={() => setF({ ...f, groups: groups.filter((_, j) => j !== i) })} aria-label="빼기">−</button>
                )}
              </div>
            ))}
            {!locked && types.length > 0 && (
              <button type="button" className="btn ghost sm" onClick={() => setF({ ...f, groups: [...groups, { code: types[0].carTypeCode, count: 1 }] })}>구성 추가</button>
            )}
          </div>
          <div className="alert sky" style={{ margin: '0 0 14px' }}>
            {cur && !locked && cars.join() !== cur.cars.map((c) => c.carTypeCode).join() ? '저장하면 좌석을 지우고 다시 만들어요: ' : cur ? '지금 좌석: ' : '저장하면 좌석이 함께 만들어져요: '}
            <b>{cars.length}량 {seats.toLocaleString()}석</b>
          </div>
          <SaveButton busy={busy} label={cur ? (locked ? '번호·종류만 저장' : '열차 저장') : '열차 등록'} disabled={!canWrite || !cars.length} />
        </form>
      }
    />
  );
}

// ------------------------------------------------------------ 작은 도우미

const num = (v) => (v === '' || v == null ? null : Number(v));
const grade = (g) => (g === 'FIRST' ? '특실' : '일반실');

/** 이어지는 같은 유형을 묶는다: [F,G,G,G] → [{F,1},{G,3}] */
function toGroups(cars) {
  const out = [];
  for (const c of cars) {
    const last = out[out.length - 1];
    if (last && last.code === c.carTypeCode) last.count++;
    else out.push({ code: c.carTypeCode, count: 1 });
  }
  return out;
}

function defaultGroups(types) {
  const first = types.find((t) => t.seatGrade === 'FIRST');
  const gen = types.find((t) => t.seatGrade === 'GENERAL');
  return [first && { code: first.carTypeCode, count: 1 }, gen && { code: gen.carTypeCode, count: 7 }].filter(Boolean);
}

/** "특실 1, 일반실 9" */
function composition(cars) {
  const by = {};
  cars.forEach((c) => (by[grade(c.seatGrade)] = (by[grade(c.seatGrade)] || 0) + 1));
  return Object.entries(by).map(([g, n]) => `${g} ${n}`).join(', ');
}
