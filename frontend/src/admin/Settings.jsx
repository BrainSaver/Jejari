import { useEffect, useMemo, useState } from 'react';
import { useOutletContext } from 'react-router';
import { del, get, post, put } from '../api/client.js';
import { Who } from './AdminLayout.jsx';
import RequestLog from './RequestLog.jsx';
import { ConfirmButton, ErrorBox } from './ui.jsx';
import { hasRole } from './useAdminAuth.js';

/**
 * A-07 운영 설정 (ADM-08). app_policy 값을 묶음(queue·reservation·risk …)별로 보고 바꾼다.
 *   GET /api/v1/admin/policies                목록 (DB 값 + Redis policy:cache 값)
 *   GET /api/v1/admin/policies/{key}/history  최근 변경 20건
 *   PUT /api/v1/admin/policies/{key}          {value, version} — SUPERVISOR, 읽은 버전이 옛것이면 409
 *
 * 백엔드가 보이게: 버전(낙관적 잠금), Redis 캐시 값이 DB와 다르면 표시, 저장하면 "커밋 → 캐시 갱신 → 각 앱 1초 안에 반영"을 적는다.
 * TTL·임계값을 코드에 박지 않고 여기서 바꾸는 것이 프로젝트 규칙이다 (CLAUDE.md "app_policy에서 읽습니다").
 *
 * 인증 제한 임시 해제 (ERD v39, docs/강동균/설계안-인증-시도제한-임시해제.md):
 *   GET/POST/DELETE /api/v1/admin/auth-relax — IP는 서버가 해시로 바꿔 넣으므로 아래 표에서 직접 고치지 않는다
 */
export default function Settings() {
  const claims = useOutletContext();
  const canWrite = hasRole(claims, 'SUPERVISOR');
  const [list, setList] = useState(null);
  const [group, setGroup] = useState('');
  const [q, setQ] = useState('');
  const [sel, setSel] = useState(null);
  const [error, setError] = useState(null);

  const load = () => get('/api/v1/admin/policies').then(setList).catch(setError);
  useEffect(() => {
    load();
  }, []);

  const groups = useMemo(() => {
    const c = {};
    (list || []).forEach((p) => (c[p.group] = (c[p.group] || 0) + 1));
    return Object.entries(c).sort();
  }, [list]);
  const shown = (list || []).filter((p) => (!group || p.group === group) && (!q || p.key.includes(q) || (p.description || '').includes(q)));
  const cur = list?.find((p) => p.key === sel) || null;

  return (
    <>
      <div className="head"><h1>운영 설정</h1><span style={{ flex: 1 }} /><Who claims={claims} /></div>
      <div className="alert sky">
        앱은 정책을 Redis <code>policy:cache</code> → Oracle <code>app_policy</code> 순으로 읽어요. 저장하면 DB를 먼저 바꾸고(버전 +1, 이력 기록),
        커밋 뒤 캐시를 바꿔요. 각 앱의 메모리 캐시가 1초라 모든 서버에 1초 안에 반영돼요.
        {!canWrite && <div style={{ marginTop: 4 }}>지금 권한({claims?.adm})은 보기만 돼요. 저장은 SUPERVISOR.</div>}
      </div>
      <ErrorBox error={error} onClose={() => setError(null)} />
      <AuthRelax canWrite={canWrite} onChanged={load} />
      <div className="tabs" role="tablist">
        <button type="button" className={group === '' ? 'on' : ''} onClick={() => setGroup('')}>전체 <span className="num" style={{ opacity: 0.7 }}>{list?.length || ''}</span></button>
        {groups.map(([g, n]) => (
          <button key={g} type="button" className={group === g ? 'on' : ''} onClick={() => setGroup(g)}>{g} <span className="num" style={{ opacity: 0.7 }}>{n}</span></button>
        ))}
      </div>
      <div className="cols">
        <section className="panel">
          <h2>
            <span className="grow">설정 {shown.length}개</span>
            <input placeholder="키·설명 찾기" aria-label="키·설명 찾기" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: 200, minHeight: 32 }} />
          </h2>
          <table>
            <thead><tr><th>키</th><th>값</th><th>형식</th><th className="n">버전</th><th>설명</th></tr></thead>
            <tbody>
              {shown.map((p) => (
                <tr key={p.key} className={sel === p.key ? 'sel' : ''} onClick={() => setSel(p.key)} style={{ cursor: 'pointer' }}>
                  <td className="mono" style={{ fontSize: 13 }}>{p.key}</td>
                  <td>
                    <b className="num">{p.value}</b>
                    {p.cachedValue != null && p.cachedValue !== p.value && (
                      <span className="st plain amber" style={{ marginLeft: 6 }} title="Redis policy:cache 값이 DB와 달라요">캐시 {p.cachedValue}</span>
                    )}
                  </td>
                  <td className="faint">{p.valueType}</td>
                  <td className="n num">{p.version}</td>
                  <td className="mut" style={{ fontSize: 12.5 }}>{p.description}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <section className="panel">
          {cur ? <Editor key={cur.key} p={cur} canWrite={canWrite} onSaved={load} /> : <p className="mut">왼쪽에서 설정을 고르세요.</p>}
        </section>
      </div>
      <RequestLog filter={(r) => r.path.includes('/admin/policies') || r.path.includes('/admin/auth-relax')} rows={5} />
    </>
  );
}

function Editor({ p, canWrite, onSaved }) {
  const [value, setValue] = useState(p.value);
  // 저장 뒤 목록을 다시 읽으면 버전·값이 바뀐다. 칸은 새 값으로 맞추되 "저장했어요" 안내는 남긴다
  useEffect(() => setValue(p.value), [p.version]);
  const [hist, setHist] = useState(null);
  const [error, setError] = useState(null);
  const [saved, setSaved] = useState(null);
  useEffect(() => {
    get(`/api/v1/admin/policies/${p.key}/history`).then(setHist).catch(() => setHist([]));
  }, [p.key, p.version]);

  const save = async () => {
    setError(null);
    try {
      const r = await put(`/api/v1/admin/policies/${p.key}`, { value, version: p.version });
      setSaved(r);
      onSaved();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <h2 className="mono" style={{ fontSize: 15 }}>{p.key}</h2>
      <p className="sub">{p.description}</p>
      {saved && <div className="alert sky">저장했어요. 버전 {saved.version}, 캐시 {saved.cachedValue}. 1초 안에 모든 서버에 반영돼요.</div>}
      <ErrorBox error={error} />
      {error?.code === 'POLICY_VERSION_CONFLICT' && (
        <button type="button" className="btn ghost sm" style={{ marginBottom: 12 }} onClick={onSaved}>다시 읽기</button>
      )}
      <label className="field">
        <span className="lbl">값 ({p.valueType}) · 지금 {p.value}, 버전 {p.version}</span>
        <input value={value} onChange={(e) => setValue(e.target.value)} disabled={!canWrite} />
        <span className="hint">마지막 변경 {p.updatedAt ? p.updatedAt.replace('T', ' ').slice(0, 19) : '—'}{p.updatedBy ? ` · 관리자 #${p.updatedBy}` : ''}</span>
      </label>
      <ConfirmButton className="btn wide" confirm={`한 번 더 누르면 ${p.value} → ${value}`} onConfirm={save} disabled={!canWrite || value === p.value}>
        저장
      </ConfirmButton>
      <hr className="sep" />
      <h2 style={{ fontSize: 14 }}>변경 이력</h2>
      {hist && !hist.length && <p className="mut">아직 바꾼 적이 없어요 (시드 값).</p>}
      <table>
        <tbody>
          {(hist || []).map((h) => (
            <tr key={h.historyId}>
              <td className="num faint">v{h.version}</td>
              <td><span className="num">{h.oldValue ?? '—'}</span> → <b className="num">{h.newValue}</b></td>
              <td className="mut" style={{ fontSize: 12.5 }}>{h.changedAt?.replace('T', ' ').slice(5, 16)}{h.changedBy ? ` · #${h.changedBy}` : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

/**
 * 인증 시도 제한 임시 해제. k6로 공용 서버에 계정을 많이 만들 때 그 PC의 IP만 정한 시각까지 가입·로그인 한도를 높인다.
 * 종료 시각이 지나면 아무도 안 눌러도 평소 한도로 돌아간다 (서버가 읽을 때마다 시각을 비교).
 */
function AuthRelax({ canWrite, onChanged }) {
  const [st, setSt] = useState(null);
  const [ip, setIp] = useState('');
  const [hours, setHours] = useState(3);
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  const load = () => get('/api/v1/admin/auth-relax').then(setSt).catch(setError);
  useEffect(() => {
    load();
    const t = setInterval(load, 60_000); // 남은 시간 표시
    return () => clearInterval(t);
  }, []);
  if (!st) return error ? <ErrorBox error={error} onClose={() => setError(null)} /> : null;

  const run = async (fn) => {
    setError(null);
    try {
      setSt(await fn());
      onChanged();
    } catch (e) {
      setError(e);
    }
  };
  const start = () => run(() => post('/api/v1/admin/auth-relax', { ip: ip.trim() || null, hours: Number(hours), reason }));
  const stop = () => run(() => del('/api/v1/admin/auth-relax'));
  const left = st.remainingMin >= 60 ? `${Math.floor(st.remainingMin / 60)}시간 ${st.remainingMin % 60}분` : `${st.remainingMin}분`;

  return (
    <section className="panel" style={{ marginBottom: 16 }}>
      <h2><span className="grow">인증 제한 임시 해제</span>
        {st.active ? <span className="st amber">해제 중</span> : <span className="st plain">평소</span>}
      </h2>
      {st.active ? (
        <div className="alert amber">
          <b>{st.until?.replace('T', ' ').slice(0, 16)}까지 해제 중</b> (남은 {left}) · IP {st.ipCount}개{st.includesMe ? ' (지금 이 PC 포함)' : ''} · 사유: {st.reason}
          <div style={{ marginTop: 4 }}>
            해제 IP만 가입 시간당 {st.signupPerHour} · 로그인 분당 {st.loginPerMin} (평소 {st.normalSignupPerHour} · {st.normalLoginPerMin}).
            시각이 지나면 저절로 평소로 돌아가요. 관리자 로그인·비회원 조회는 풀리지 않아요.
          </div>
        </div>
      ) : (
        <p className="sub">
          k6로 공용 서버에 계정을 많이 만들 때 그 PC의 IP만 최대 {st.maxHours}시간 동안 가입·로그인 한도를 높여요
          (평소 IP당 가입 시간당 {st.normalSignupPerHour} · 로그인 분당 {st.normalLoginPerMin}). 시각이 지나면 저절로 평소로 돌아가요.
          원본 IP는 저장하지 않고 해시만 남겨요. <b>봇 대회 중에는 켜지 마세요</b> (같은 와이파이의 봇도 같이 풀려요).
        </p>
      )}
      <ErrorBox error={error} onClose={() => setError(null)} />
      {canWrite && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <label className="field" style={{ margin: 0 }}>
            <span className="lbl">IP (비우면 지금 이 PC)</span>
            <input value={ip} onChange={(e) => setIp(e.target.value)} placeholder="예: 203.0.113.7" style={{ width: 170 }} />
          </label>
          <label className="field" style={{ margin: 0 }}>
            <span className="lbl">시간</span>
            <select value={hours} onChange={(e) => setHours(e.target.value)} style={{ width: 100 }}>
              {[1, 3, 6, 12, 24].filter((h) => h <= st.maxHours).map((h) => <option key={h} value={h}>{h}시간</option>)}
            </select>
          </label>
          <label className="field" style={{ margin: 0, flex: 1, minWidth: 200 }}>
            <span className="lbl">사유 (5자 이상)</span>
            <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="예: k6 시나리오 11 공용 서버 측정" />
          </label>
          <ConfirmButton className="btn sm" confirm={`한 번 더 누르면 ${hours}시간 해제`} onConfirm={start} disabled={reason.trim().length < 5}>
            {st.active ? 'IP 추가·연장' : '해제 시작'}
          </ConfirmButton>
          {st.active && <ConfirmButton className="btn ghost sm" confirm="한 번 더 누르면 지금 끝내기" onConfirm={stop}>지금 끝내기</ConfirmButton>}
        </div>
      )}
    </section>
  );
}
