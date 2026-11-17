import { useEffect, useMemo, useState } from 'react';
import { useOutletContext } from 'react-router';
import { get, put } from '../api/client.js';
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
      <RequestLog filter={(r) => r.path.includes('/admin/policies')} rows={5} />
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

