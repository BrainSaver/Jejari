import { useState } from 'react';
import { Link, useOutletContext } from 'react-router';
import { get } from '../api/client.js';
import { usePolling } from '../hooks/usePolling.js';
import { Who } from './AdminLayout.jsx';
import RequestLog from './RequestLog.jsx';
import { BY } from './ModelOpinion.jsx';
import { ErrorBox } from './ui.jsx';

/**
 * A-05 위험 사용자 (ADM-04). GET /api/v1/admin/risks?minScore=&status=&type=&page=
 *
 * 백엔드가 보이게:
 *  - 점수는 1분 배치(RISK-04)가 risk_score에 쓴 값. 언제 계산했는지(calculatedAt)를 같이 보여 준다
 *  - 묶음 키(대상)는 CI·결제수단·기기의 HMAC 해시. 개인정보는 서버가 가린 값만 내려온다 (해제는 A-06에서 사유를 남기고)
 *  - 근거 마커는 탐지 규칙 코드 그대로 (MULTI_ACCOUNT_SYNC, FAST_CLICK …)
 *  - 모델 의견(RISK-05): 대상에 묶인 계정 중 가장 높은 모델 점수. 보조 의견이라 필터·정렬은 규칙 점수로만.
 *    숨김 설정(jejari.model.show=false)·사용 중인 모델 없음이면 칸을 빼고 이유만 한 줄
 */
const TYPE = { CI: 'CI', PAYMENT: '결제수단', DEVICE: '기기' };
const REVIEW = { NONE: ['', '검토 전'], PENDING: ['amber', '검토 중'], CONFIRMED: ['stop', '확정'], CLEARED: ['go', '해제'] };
const CASE = { OPEN: 'amber', REVIEWING: 'sky', CONFIRMED: 'stop', DISMISSED: 'go' };
// 위험 점수 등급 (서버 RiskGrade: 경고 risk.rule.warning_threshold 30 · 위험 risk.rule.case_threshold 80 — 케이스·결제 차단과 같은 기준)
const GRADE = { DANGER: ['stop', '위험'], WARNING: ['amber', '경고'], NORMAL: ['', '정상'] };

export default function Risk() {
  const claims = useOutletContext();
  const [f, setF] = useState({ minScore: '60', status: '', type: '' });
  const [page, setPage] = useState(0);
  const q = new URLSearchParams({ page: String(page), size: '20' });
  if (f.minScore) q.set('minScore', f.minScore);
  if (f.status) q.set('status', f.status);
  if (f.type) q.set('type', f.type);
  const { data, error } = usePolling(() => get('/api/v1/admin/risks?' + q), 30_000, [q.toString()]);
  const set = (k) => (e) => {
    setPage(0);
    setF({ ...f, [k]: e.target.value });
  };

  const model = data?.model;
  const cols = model?.shown ? 8 : 7;

  return (
    <>
      <div className="head">
        <h1>위험 사용자</h1>
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>
      <div className="alert sky">
        점수는 서버의 1분 배치가 행동 로그(Kafka → MongoDB)와 예매 기록을 묶음 키(CI·결제수단·기기 해시)별로 모아 계산해요.
        이 화면은 30초마다 다시 읽어요. 개인정보는 서버가 가린 값이에요.
      </div>
      <section className="panel">
        <div className="filters">
          <Field label="점수">
            <select value={f.minScore} onChange={set('minScore')}>
              <option value="80">위험 (80 이상)</option><option value="60">60 이상</option><option value="30">경고 이상 (30 이상)</option><option value="">전체</option>
            </select>
          </Field>
          <Field label="검토 상태">
            <select value={f.status} onChange={set('status')}>
              <option value="">전체</option>
              {Object.entries(REVIEW).map(([k, [, l]]) => <option key={k} value={k}>{l} ({k})</option>)}
            </select>
          </Field>
          <Field label="대상">
            <select value={f.type} onChange={set('type')}>
              <option value="">전체</option>
              {Object.entries(TYPE).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
            </select>
          </Field>
          <span style={{ flex: 1 }} />
          <span className="mut" style={{ fontSize: 13, paddingBottom: 9 }}>가림 해제는 케이스 화면에서 사유를 남기고 해요.</span>
        </div>
      </section>
      <ErrorBox error={error} />
      <section className="panel">
        <h2>{data ? `${data.items.length}건` : '…'} <span className="mut" style={{ fontWeight: 500 }}>점수 높은 순</span></h2>
        {data && !data.items.length && page === 0 && (f.minScore || f.status || f.type) && (
          <p className="mut" style={{ fontSize: 14 }}>
            이 조건에 맞는 대상이 없어요.{f.minScore ? ` ${f.minScore}점 이상이 없으면 점수 필터를 낮춰 보세요.` : ' 필터를 바꿔 보세요.'}
          </p>
        )}
        {model && !model.shown && <p className="faint" style={{ fontSize: 13 }}>모델 의견 칸 없음: {model.reason}</p>}
        <table>
          <thead>
            <tr>
              <th>대상</th><th>점수</th><th>근거</th>
              {model?.shown && (
                <th title={`${model.modelVersion} · 문턱 ${model.threshold} 이상이면 봇 쪽 · 판정 참고용, 순번·제한에는 안 씀`}>모델 의견</th>
              )}
              <th className="n">연결 계정</th><th>검토</th><th>케이스</th><th>계산 시각</th>
            </tr>
          </thead>
          <tbody>
            {(data?.items || []).map((r) => {
              const s = Number(r.finalScore);
              const [rc, rl] = REVIEW[r.reviewStatus] || ['', r.reviewStatus];
              return (
                <tr key={r.subjectType + r.subjectHash}>
                  <td>
                    <b>{TYPE[r.subjectType] || r.subjectType} {who(r)}</b>{' '}
                    <span className="faint num" title={r.subjectHash}>{r.subjectHash.slice(0, 4)}…</span>
                  </td>
                  <td>
                    <span className={'score ' + (r.grade === 'DANGER' ? 'hi' : r.grade === 'WARNING' ? 'mid' : '')}>{Math.round(s)}</span>
                    {r.grade && r.grade !== 'NORMAL' && <span className={'st plain ' + GRADE[r.grade][0]} style={{ marginLeft: 4 }}>{GRADE[r.grade][1]}</span>}
                    <span className="meter"><i style={{ width: `${Math.min(100, s)}%`, background: r.grade === 'DANGER' ? 'var(--stop)' : 'var(--amber)' }} /></span>
                  </td>
                  <td>{(r.markers || []).map((m) => <span key={m} className="st plain stop" style={{ marginRight: 4 }}>{m}</span>)}</td>
                  {model?.shown && <td><ModelCell m={r.model} linked={r.linkedAccounts} /></td>}
                  <td className="n">{r.linkedAccounts}</td>
                  <td><span className={'st ' + rc}>{rl}</span></td>
                  <td>
                    {r.latestCase ? (
                      <Link className="link" to={`/admin/cases/${r.latestCase.caseId}`}>
                        C-{r.latestCase.caseId} <span className={'st ' + (CASE[r.latestCase.status] || '')}>{r.latestCase.status}</span>
                      </Link>
                    ) : <span className="faint">—</span>}
                  </td>
                  <td className="mut">{ago(r.calculatedAt)}</td>
                </tr>
              );
            })}
            {data && !data.items.length && <tr><td colSpan={cols} className="mut">이 조건의 위험 대상이 없어요.</td></tr>}
          </tbody>
        </table>
        {(page > 0 || data?.hasNext) && (
          <div className="row" style={{ marginTop: 12 }}>
            <button type="button" className="btn ghost sm" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
            <span className="mut">{page + 1}쪽</span>
            <button type="button" className="btn ghost sm" disabled={!data?.hasNext} onClick={() => setPage(page + 1)}>다음</button>
          </div>
        )}
      </section>
      <RequestLog filter={(r) => r.path.includes('/admin/risks')} rows={5} />
    </>
  );
}

/** 대상에 묶인 계정 중 가장 높은 모델 점수. 규칙 점수와 다르게 볼 때 눈에 띄게 (봇 쪽이면 빨강) */
function ModelCell({ m, linked }) {
  if (!m) return <span className="faint" title="연결 계정에 모델 점수가 아직 없어요">—</span>;
  const part = m.scoredAccounts < linked ? ` · ${m.scoredAccounts}/${linked}계정` : '';
  return (
    <span title={`가장 높게 본 모델: ${BY[m.by] || m.by || '—'}${part}`}>
      <span className={'st plain ' + (m.overThreshold ? 'stop' : 'go')}>
        {Math.round(m.score)} {m.overThreshold ? '봇 쪽' : '사람 쪽'}
      </span>
      {part && <span className="faint" style={{ fontSize: 12 }}>{part}</span>}
    </span>
  );
}

function Field({ label, children }) {
  return <label className="field"><span className="lbl">{label}</span>{children}</label>;
}

const who = (r) => r.holder?.name || (r.holder?.cardLast4 ? `****${r.holder.cardLast4}` : '');

export function ago(t) {
  if (!t) return '—';
  const m = Math.round((Date.now() - Date.parse(t)) / 60000);
  if (m < 1) return '방금';
  if (m < 60) return `${m}분 전`;
  if (m < 1440) return `${Math.floor(m / 60)}시간 전`;
  return `${Math.floor(m / 1440)}일 전`;
}
