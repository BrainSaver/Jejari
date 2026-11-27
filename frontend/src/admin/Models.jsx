import { useEffect, useState } from 'react';
import { Link, useOutletContext } from 'react-router';
import { get, post } from '../api/client.js';
import { Who } from './AdminLayout.jsx';
import { TYPE } from './ModelOpinion.jsx';
import RequestLog from './RequestLog.jsx';
import { ConfirmButton, ErrorBox } from './ui.jsx';
import { hasRole } from './useAdminAuth.js';
import { ROLE, ko } from './labels.js';

/**
 * A-09 모델 관리 (RISK-05 이어서). 합성 모델 버전을 실데이터 점수로 비교하고, 케이스 화면 "모델 보조 의견"에 보여 줄 버전을 고른다.
 *   GET  /api/v1/admin/models                     버전 목록
 *   GET  /api/v1/admin/models/compare?a=&b=       두 버전이 다르게 본 계정
 *   POST /api/v1/admin/models/{version}/activate  사용 중으로 (SUPERVISOR)
 *   GET  /api/v1/admin/models/audit               무작위 점검 표본 현황
 *   POST /api/v1/admin/models/audit-sample        {count} 무작위 점검 표본을 케이스로 열기 (REVIEWER)
 *
 * 사용자가 많아질수록 똑똑해지는 순환: 매주 일요일 새벽 서버가 실데이터로 사람 기준을 다시 맞추고(①), 관리자 판정(②)이 충분하면
 * 다시 학습해, 자동 시험(③)을 통과한 것만 "꺼진 후보"로 올린다 (deploy/model-retrain.sh). 켜는 것은 여기서 사람이 한다.
 *
 * 학습은 이 화면에서 하지 않는다. 점수는 서버의 매시간 작업이 등록된 버전마다 매겨 둔다 (deploy/model-refresh.sh).
 * 정답이 없는 실데이터라 "맞힘"은 관리자 판정이 있는 계정으로만 센다.
 */
const pct = (n, d) => (d ? `${Math.round((100 * n) / d)}%` : '—');
const when = (t) => (t ? t.replace('T', ' ').slice(0, 16) : '—');
const parse = (p) => {
  try {
    return p ? JSON.parse(p) : {};
  } catch {
    return {}; // 3000자에서 잘린 params 등
  }
};
const p1 = (v) => (v == null ? '—' : `${(v * 100).toFixed(1)}%`);

export default function Models() {
  const claims = useOutletContext();
  const canWrite = hasRole(claims, 'SUPERVISOR');
  const [list, setList] = useState(null);
  const [error, setError] = useState(null);
  const [done, setDone] = useState(null);

  const load = () => get('/api/v1/admin/models').then(setList).catch(setError);
  useEffect(() => {
    load();
  }, []);

  const activate = async (v) => {
    setError(null);
    try {
      setList(await post(`/api/v1/admin/models/${encodeURIComponent(v)}/activate`));
      setDone(v);
    } catch (e) {
      setError(e);
    }
  };

  const active = list?.find((m) => m.active);
  return (
    <>
      <div className="head"><h1>모델 관리</h1><span style={{ flex: 1 }} /><Who claims={claims} /></div>
      <div className="alert sky">
        모델 점수는 <b>보조 의견</b>이에요. 사용 중인 버전이 케이스 화면의 "모델 보조 의견" 카드에 나올 뿐, 대기열 순번·이용 제한·케이스 열기에는 쓰지 않아요.
        점수는 서버가 매시간 등록된 모든 버전으로 매겨 두므로, 켜기 전에 실데이터로 비교할 수 있어요.
        {!canWrite && <div style={{ marginTop: 4 }}>지금 권한({ko(ROLE, claims?.adm)})은 보기만 돼요. 바꾸기는 총괄 관리자만 할 수 있어요.</div>}
      </div>
      <ErrorBox error={error} onClose={() => setError(null)} />
      {done && <div className="alert go">{done}을(를) 사용 중으로 바꿨어요. 케이스 화면에 바로 반영돼요.</div>}

      <Audit claims={claims} />

      <section className="panel">
        <h2><span className="grow">버전 {list?.length ?? ''}개</span>{active && <span className="faint" style={{ fontSize: 12.5 }}>사용 중 {active.version}</span>}</h2>
        {list && !list.length && <p className="mut">등록된 합성 모델이 없어요 (model_run, algorithm = ENSEMBLE).</p>}
        <div style={{ overflowX: 'auto' }}>
          <table className="tight">
            <thead>
              <tr>
                <th>버전</th><th>상태</th><th>학습</th><th className="n">문턱</th><th className="n">점수 계정</th>
                <th className="n">봇 쪽으로 봄</th><th>닮은 유형</th><th title="관리자 판정과 비교: 잡은 수/판정 수">관리자 판정</th><th>마지막 점수</th>
              </tr>
            </thead>
            <tbody>
              {(list || []).map((m) => (
                <tr key={m.version}>
                  <td className="mono" style={{ fontSize: 13, whiteSpace: 'nowrap' }}>{m.version}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    {m.active ? <span className="st go">사용 중</span> : <span className="st">꺼짐</span>}
                    {!m.active && (
                      <div style={{ marginTop: 6 }}>
                        <ConfirmButton confirm="한 번 더 누르면 사용" onConfirm={() => activate(m.version)} disabled={!canWrite || !m.scored}>
                          사용 중으로
                        </ConfirmButton>
                      </div>
                    )}
                  </td>
                  <td className="mut" style={{ fontSize: 12.5, minWidth: 160 }}>
                    {when(m.trainedAt)}
                    {parse(m.params).gate ? <Gate m={m} /> : <div className="faint clamp2" title={m.trainSource}>{m.trainSource}</div>}
                  </td>
                  <td className="n num">{m.threshold ?? '—'}</td>
                  <td className="n num">{m.scored.toLocaleString()}</td>
                  <td className="n num">{m.flagged.toLocaleString()} <span className="faint">({pct(m.flagged, m.scored)})</span></td>
                  <td style={{ fontSize: 12.5 }}>
                    {Object.entries(m.types).sort((a, b) => b[1] - a[1]).map(([t, n]) => (
                      <span key={t} className="st plain" style={{ marginRight: 4 }} title={TYPE[t]?.[1]}>{TYPE[t]?.[0] || t} {n}</span>
                    ))}
                    {!Object.keys(m.types).length && <span className="faint">—</span>}
                  </td>
                  <td className="nw" style={{ fontSize: 12.5 }} title={`봇 판정 ${m.verdicts.bots}명 중 ${m.verdicts.botsFlagged}명 잡음 · 사람 판정 ${m.verdicts.humans}명 중 ${m.verdicts.humansFlagged}명 오탐`}>
                    <div>봇 <b className="num">{m.verdicts.botsFlagged}</b>/{m.verdicts.bots} 잡음</div>
                    <div>사람 <b className="num">{m.verdicts.humansFlagged}</b>/{m.verdicts.humans} 오탐</div>
                  </td>
                  <td className="mut nw" style={{ fontSize: 12.5 }}>{when(m.lastScoredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="faint" style={{ fontSize: 12, marginTop: 6 }}>
          "관리자 판정"은 케이스에서 봇(되팔이·매크로·취소 남용)이나 사람(정상)으로 판정한 계정만 셉니다. 규칙이 연 케이스에만 판정이 있어
          규칙 쪽으로 치우친 숫자예요. 점수가 없는 버전은 켤 수 없어요.
        </p>
      </section>

      {list && list.length >= 2 && <Compare list={list} />}
      <RequestLog filter={(r) => r.path.includes('/admin/models')} rows={5} />
    </>
  );
}

/** 주간 재학습 후보면 자동 시험 결과 (params.gate, deploy/model-retrain.sh) */
function Gate({ m }) {
  const p = parse(m.params);
  if (!p.gate) return null;
  return (
    <div style={{ marginTop: 4 }}>
      <span className={'st plain ' + (p.gate.passed ? 'go' : 'stop')}>자동 시험 {p.gate.passed ? '통과' : '탈락'}</span>
      <div className="faint">주간 재학습 · {p.mode}{p.base ? ` · 기준 ${p.base}` : ''}
        {p.gate.labels ? ` · 판정 학습 ${p.gate.labels.train} / 시험 사람 ${p.gate.labels.test_humans}·봇 ${p.gate.labels.test_bots}` : ''}</div>
      <ul style={{ margin: '4px 0 0', paddingLeft: 16 }}>
        {(p.gate.checks || []).map((c) => (
          <li key={c.name} className={c.ok ? 'faint' : ''}>
            {c.ok ? '✓' : '✗'} {c.name} {p1(c.base)} → {p1(c.cand)}
          </li>
        ))}
      </ul>
      {(m.precision != null || m.recall != null) && (
        <div className="faint"><span title="정밀도·재현율">시험에서 봇이라 한 것 중 맞은 비율 {p1(m.precision)} · 실제 봇 중 잡은 비율 {p1(m.recall)}</span></div>
      )}
    </div>
  );
}

/**
 * ② 무작위 점검 표본: 의심과 상관없이 최근 활동 계정을 골라 케이스로 연다. 그 판정이 치우침 없는 정답이 되어
 * 주간 재학습의 오탐·탐지 시험에 쓰인다 (판정이 규칙·모델이 연 케이스에만 붙으면 "이미 잡던 봇"만 더 잘 잡게 됨)
 */
function Audit({ claims }) {
  const canSample = hasRole(claims, 'REVIEWER');
  const [stats, setStats] = useState(null);
  const [count, setCount] = useState(5);
  const [res, setRes] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    get('/api/v1/admin/models/audit').then(setStats).catch(setError);
  }, []);
  const sample = async () => {
    setError(null);
    try {
      const r = await post('/api/v1/admin/models/audit-sample', { count: Number(count) });
      setRes(r);
      setStats(r.stats);
    } catch (e) {
      setError(e);
    }
  };
  return (
    <section className="panel">
      <h2>무작위 점검 표본</h2>
      <p className="sub" style={{ fontSize: 13 }}>
        최근 7일에 활동한 계정 중 <b>무작위로</b> 골라 케이스로 열어요. 의심 근거가 아니라 모델 학습용 판정을 받기 위한 것이라,
        케이스를 여는 것만으로는 제한·순번에 아무 영향이 없어요. 케이스 화면에서 평소처럼 봇·사람으로 판정해 주세요.
      </p>
      <ErrorBox error={error} onClose={() => setError(null)} />
      {stats && (
        <div className="row" style={{ gap: 14, flexWrap: 'wrap', fontSize: 13.5, marginBottom: 8 }}>
          <span>판정 대기 <b className="num">{stats.open}</b></span>
          <span>판정 끝 <b className="num">{stats.judged}</b> <span className="faint">(봇 {stats.bots} · 사람 {stats.humans})</span></span>
        </div>
      )}
      <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <label className="row" style={{ gap: 6, alignItems: 'center', fontSize: 13 }}>
          계정
          <input type="number" min={1} max={20} value={count} onChange={(e) => setCount(e.target.value)}
            style={{ width: 70, minHeight: 32 }} aria-label="뽑을 계정 수" />
          명
        </label>
        <ConfirmButton confirm={`한 번 더 누르면 ${count}명을 케이스로 열어요`} onConfirm={sample}
          disabled={!canSample || !(count >= 1 && count <= 20)}>
          표본 뽑기
        </ConfirmButton>
        {!canSample && <span className="faint" style={{ fontSize: 12.5 }}>검토자 이상</span>}
      </div>
      {res && (
        <p style={{ fontSize: 13, marginTop: 8 }}>
          케이스 {res.opened.length}건 열림{res.skipped ? ` (이미 열린 케이스 등 ${res.skipped}건 건너뜀)` : ''}:{' '}
          {res.opened.map((id) => <Link key={id} className="link" to={`/admin/cases/${id}`} style={{ marginRight: 6 }}>#{id}</Link>)}
        </p>
      )}
    </section>
  );
}

function Compare({ list }) {
  const active = list.find((m) => m.active) || list[0];
  const other = list.find((m) => m.version !== active.version);
  const [a, setA] = useState(active.version);
  const [b, setB] = useState(other.version);
  const [c, setC] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    setC(null);
    setError(null);
    if (a === b) return;
    get(`/api/v1/admin/models/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`).then(setC).catch(setError);
  }, [a, b]);

  const pick = (value, set) => (
    <select value={value} onChange={(e) => set(e.target.value)} style={{ minHeight: 32, width: 'auto', maxWidth: '100%' }}>
      {list.map((m) => <option key={m.version} value={m.version}>{m.version}{m.active ? ' (사용 중)' : ''}</option>)}
    </select>
  );

  return (
    <section className="panel">
      <h2>두 버전 비교</h2>
      <div className="row" style={{ gap: 6, flexWrap: 'wrap', alignItems: 'center', marginBottom: 10 }}>
        {pick(a, setA)}<span className="faint">vs</span>{pick(b, setB)}
      </div>
      {a === b && <p className="mut">서로 다른 두 버전을 고르세요.</p>}
      <ErrorBox error={error} />
      {c && (
        <>
          <div className="row" style={{ gap: 16, flexWrap: 'wrap', fontSize: 13.5, marginBottom: 8 }}>
            <span>둘 다 봇 쪽으로 봄 <b className="num">{c.both}</b></span>
            <span>{a}만 <b className="num">{c.onlyA}</b></span>
            <span>{b}만 <b className="num">{c.onlyB}</b></span>
          </div>
          <DiffTable title={`${a}만 봇 쪽으로 본 계정`} rows={c.onlyAList} total={c.onlyA} a={a} b={b} />
          <div style={{ height: 12 }} />
          <DiffTable title={`${b}만 봇 쪽으로 본 계정`} rows={c.onlyBList} total={c.onlyB} a={a} b={b} />
        </>
      )}
    </section>
  );
}

const DIFF_PAGE = 10; // 표가 길어지지 않게 10줄씩 넘겨 본다

function DiffTable({ title, rows, total, a, b }) {
  const [page, setPage] = useState(0);
  useEffect(() => setPage(0), [rows]);
  const pages = Math.max(1, Math.ceil(rows.length / DIFF_PAGE));
  const shown = rows.slice(page * DIFF_PAGE, (page + 1) * DIFF_PAGE);
  const verdict = { BOT: <span className="st plain stop">봇 판정</span>, HUMAN: <span className="st plain go">사람 판정</span> };
  return (
    <div style={{ minWidth: 0 }}>
      <h3 style={{ fontSize: 13.5, margin: '4px 0' }}>{title} <span className="faint">{total > rows.length ? `(위 ${rows.length}개 / ${total})` : ''}</span></h3>
      {!rows.length && <p className="mut" style={{ fontSize: 13 }}>없어요.</p>}
      {!!rows.length && (
        <div style={{ overflowX: 'auto' }}>
        <table>
          <thead><tr><th>회원</th><th className="n">{a}</th><th className="n">{b}</th><th>닮은 유형</th><th>판정</th></tr></thead>
          <tbody>
            {shown.map((r) => {
              const t = r.typeA || r.typeB;
              return (
                <tr key={r.memberId}>
                  <td className="num">#{r.memberId}</td>
                  <td className="n num">{r.scoreA == null ? '—' : Math.round(r.scoreA)}</td>
                  <td className="n num">{r.scoreB == null ? '—' : Math.round(r.scoreB)}</td>
                  <td style={{ fontSize: 12.5 }}>{t ? TYPE[t]?.[0] || t : '—'}</td>
                  <td>{verdict[r.verdict] || <span className="faint">없음</span>}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {pages > 1 && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8, marginTop: 8, fontSize: 13 }}>
            <span className="mut">{page * DIFF_PAGE + 1}–{Math.min(rows.length, (page + 1) * DIFF_PAGE)} / {rows.length}</span>
            <button type="button" className="btn ghost sm" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
            <button type="button" className="btn ghost sm" disabled={page >= pages - 1} onClick={() => setPage(page + 1)}>다음</button>
          </div>
        )}
        </div>
      )}
    </div>
  );
}
