import { useEffect, useState } from 'react';
import { useOutletContext, useSearchParams } from 'react-router';
import { get } from '../api/client.js';
import { Who } from './AdminLayout.jsx';
import { ErrorBox } from './ui.jsx';
import { ciText } from './stats.js';
import { RUN_STATUS, ko } from './labels.js';

/**
 * 봇 대회 결과 (읽기 전용). GET /api/v1/admin/contests · /api/v1/admin/contests/{tag}?runId=
 *
 * 대회 진행(봇 실행·평가)은 집계자 PC의 진행 화면(loadtest/contest/web)이 하고, 이 화면은 그 결과를 DB에서 다시 읽기만 한다.
 * 숫자는 loadtest/tools/eval_contest.sh와 같은 규칙:
 *  - 탐지 = 그 계정에 연결된 대상(CI·결제수단·기기)에 의심 케이스가 있음. 사람이 탐지되면 오탐
 *  - 막힌 층 = 매수제한 · 규칙(케이스) · 순번조정(최고 점수 × 점당 ms) · 관리자판정. 하나도 없으면 "뚫림"
 *  - 참가자 종류는 이메일(lt{태그}.{종류}.g…)에서 읽는다. 서버는 이 라벨을 탐지에 쓰지 않는다
 */
const SUBJECT = { CI: '명의', PAYMENT: '결제수단', DEVICE: '기기' };

const ci = ciText;

export default function Contests() {
  const claims = useOutletContext();
  const [params, setParams] = useSearchParams();
  const tag = params.get('tag') || '';
  const runId = params.get('runId') || '';
  const [list, setList] = useState(null);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [n, setN] = useState(0); // 다시 읽기

  useEffect(() => {
    get('/api/v1/admin/contests').then(setList).catch(setError);
  }, [n]);

  // 태그를 고르지 않았으면 가장 최근 대회
  useEffect(() => {
    if (!tag && list?.length) setParams({ tag: list[0].tag }, { replace: true });
  }, [list, tag]);

  useEffect(() => {
    if (!tag) return;
    setLoading(true);
    setError(null);
    get(`/api/v1/admin/contests/${encodeURIComponent(tag)}${runId ? `?runId=${encodeURIComponent(runId)}` : ''}`)
      .then(setData)
      .catch((e) => { setData(null); setError(e); })
      .finally(() => setLoading(false));
  }, [tag, runId, n]);

  const r = data?.result;
  const t = r?.totals;
  const bots = r?.kinds.filter((k) => !k.human) || [];
  const humans = r?.kinds.filter((k) => k.human) || [];

  return (
    <>
      <div className="head">
        <h1>봇 대회 결과</h1>
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>
      <div className="alert sky">
        대회는 진행자 PC의 진행 화면이 열고, 이 화면은 그 결과를 DB에서 다시 읽기만 해요. 아무것도 실행하거나 바꾸지 않아요.
        참가자는 이메일 모양(<code>lt태그.종류.g묶음.번호@…</code>)으로 가려내고, 숫자는 평가 스크립트(<code>eval_contest.sh</code>)와 같은 규칙으로 셉니다.
      </div>

      <section className="panel">
        <div className="filters">
          <label className="field">
            <span className="lbl">대회 (태그)</span>
            <select value={tag} onChange={(e) => setParams({ tag: e.target.value })}>
              {!list?.length && <option value="">대회 기록 없음</option>}
              {(list || []).map((c) => (
                <option key={c.tag} value={c.tag}>
                  {c.tag} · 봇 {c.bots}계정 · 사람 {c.humans}계정{c.firstJoinedAt ? ` · ${c.firstJoinedAt.slice(0, 10)}` : ''}
                </option>
              ))}
            </select>
          </label>
          {data?.runs?.length > 1 && (
            <label className="field">
              <span className="lbl">운행</span>
              <select value={runId || String(data.run?.runId ?? '')} onChange={(e) => setParams({ tag, runId: e.target.value })}>
                {data.runs.map((x) => <option key={x.runId} value={x.runId}>{x.runId} (예약 {x.reservations}건)</option>)}
              </select>
            </label>
          )}
          <span style={{ flex: 1 }} />
          <button type="button" className="btn ghost sm" onClick={() => setN(n + 1)} disabled={loading}>{loading ? '읽는 중' : '다시 읽기'}</button>
        </div>
        {data?.run && (
          <p className="mut" style={{ fontSize: 13, margin: '10px 0 0' }}>
            운행 <b>{data.run.runId}</b>{data.run.trainNo ? ` · 열차 ${data.run.trainNo}` : ''}{data.run.runDate ? ` · ${data.run.runDate}` : ''}
            {data.run.saleEventName ? ` · ${data.run.saleEventName}` : ''}{data.run.status ? ` · ${ko(RUN_STATUS, data.run.status)}` : ''}
            {' '}· 점수 1점 = 대기열 {data.penaltyMsPerPoint / 1000}초
          </p>
        )}
        {data && !data.behaviorAvailable && (
          <p className="faint" style={{ fontSize: 13 }}>행동 기록을 읽지 못해 선점 시도·매수 제한 거절·기기 연결은 빠졌어요.</p>
        )}
      </section>

      <ErrorBox error={error} onClose={() => setError(null)} />

      {t && (
        <div className="kpis k4">
          <div className="kpi">
            <div className="l">봇 탐지 (계정)</div>
            <div className="n">{t.botDetected}<span className="mut" style={{ fontSize: 18 }}> / {t.botMembers}</span></div>
            <div className="d">의심 케이스가 연결된 봇 계정 · {ci(t.botDetected, t.botMembers)}</div>
          </div>
          <div className="kpi">
            <div className="l">봇이 결제한 좌석</div>
            <div className="n">{t.botPaidSeats}</div>
            <div className="d">탐지 수보다 이게 실제 피해예요</div>
          </div>
          <div className={'kpi' + (t.humanFalsePositive > 0 ? ' alarm' : '')}>
            <div className="l">사람 오탐</div>
            <div className="n">{t.humanFalsePositive}<span style={{ fontSize: 18, opacity: 0.7 }}> / {t.humanMembers}</span></div>
            <div className="d">{t.humanMembers ? `${Math.round((t.humanFalsePositive / t.humanMembers) * 1000) / 10}% · ${ci(t.humanFalsePositive, t.humanMembers)} · 목표 5% 이하` : '사람 참가자 없음 — 오탐은 판정 보류'}</div>
          </div>
          <div className="kpi">
            <div className="l">사람이 결제한 좌석</div>
            <div className="n">{t.humanPaidSeats}</div>
            <div className="d">사람이 끝까지 샀는지 (막히면 0)</div>
          </div>
        </div>
      )}

      {r && (
        <section className="panel">
          <h2>종류별 결과</h2>
          <p className="sub">
            봇은 막힌 층, 사람은 오탐을 봅니다. 순번 몇 초는 사람도 새 계정이면 받는 정도라, 사람 줄과 비교해 읽어요.
            구간은 계정을 서로 독립으로 본 값이에요 — 한 사람·한 봇이 계정을 여러 개 만들었으면 실제 불확실성은 더 커요.
            기준값을 이 대회 데이터로 정했다면, 이 대회로는 그 기준을 평가할 수 없어요 (새 데이터로 다시 재야 해요).
          </p>
          <table>
            <thead>
              <tr>
                <th>종류</th><th className="n">계정</th><th className="n">명의</th><th className="n">결제한 좌석</th><th className="n">잡고 안 낸 좌석</th>
                <th className="n">선점 시도</th><th className="n">제한 거절</th><th className="n">탐지</th><th className="n">최고 점수</th><th className="n">순번 -초</th><th>막힌 층 / 오탐</th>
              </tr>
            </thead>
            <tbody>
              {[...bots, ...humans].map((k) => (
                <tr key={k.kind}>
                  <td><b>{k.kind}</b></td>
                  <td className="n">{k.members}</td>
                  <td className="n">{k.identities}</td>
                  <td className="n">{k.paidSeats}</td>
                  <td className="n">{k.heldSeats}</td>
                  <td className="n">{data.behaviorAvailable ? k.holdTries : '—'}</td>
                  <td className="n">{data.behaviorAvailable ? k.limitRejects : '—'}</td>
                  <td className="n">{k.detected}/{k.members}</td>
                  <td className="n">{Math.round(k.maxScore)}</td>
                  <td className="n">{k.penaltySec}</td>
                  <td>
                    {k.human
                      ? <span className={'st ' + (k.detected ? 'stop' : 'go')}>{k.detected ? `오탐 ${k.detected}명` : '오탐 없음'}</span>
                      : k.layers.length
                        ? k.layers.map((l) => <span key={l} className="st plain" style={{ marginRight: 4 }}>{l}</span>)
                        : <span className="st stop">뚫림</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {r && r.kinds.some((k) => k.rules.length) && (
        <section className="panel">
          <h2>걸린 규칙</h2>
          <p className="sub">그 규칙의 케이스가 연결된 계정 수. 사람 줄에 규칙이 있으면 그 규칙이 오탐을 낸 거예요.</p>
          <table>
            <thead><tr><th>종류</th><th>대상</th><th>규칙</th><th className="n">계정</th></tr></thead>
            <tbody>
              {[...bots, ...humans].flatMap((k) => k.rules.map((x) => (
                <tr key={k.kind + x.subjectType + x.rule}>
                  <td>{k.kind}</td>
                  <td>{SUBJECT[x.subjectType] || x.subjectType}</td>
                  <td><span className={'st plain ' + (k.human ? 'stop' : '')}>{x.rule}</span></td>
                  <td className="n">{x.members}</td>
                </tr>
              )))}
            </tbody>
          </table>
        </section>
      )}

      {list && !list.length && (
        <section className="panel">
          <p className="mut" style={{ fontSize: 14, margin: 0 }}>
            아직 대회 기록이 없어요. 진행 화면(<code>node loadtest/contest/web/server.js</code>)으로 대회를 열면 여기에 나타나요.
          </p>
        </section>
      )}
    </>
  );
}
