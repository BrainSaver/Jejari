import { useEffect, useState } from 'react';
import { get } from '../api/client.js';

/**
 * RISK-05 모델 보조 의견 (A-06). GET /api/v1/admin/cases/{id}/model
 * 오프라인 합성 모델(부스팅 + Isolation Forest + GRU)이 계정마다 매긴 점수. 판정 참고용이고 순번·제한에는 쓰지 않는다.
 * 규칙 점수와 모델이 다르게 볼 때 한 번 더 보라는 뜻으로 나란히 보여 준다.
 */
const BY = { GBM: '부스팅', IF: 'Isolation Forest', GRU: '행동 순서(GRU)' };

export default function ModelOpinion({ caseId }) {
  const [m, setM] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    get(`/api/v1/admin/cases/${caseId}/model`).then(setM).catch(setError);
  }, [caseId]);

  if (error) return null; // 보조 의견이라 실패해도 케이스 화면은 그대로
  if (!m) return <p className="mut" style={{ fontSize: 13 }}>모델 의견을 불러오는 중…</p>;

  return (
    <div>
      <h2 style={{ fontSize: 14 }}>
        모델 보조 의견 <span className="mut" style={{ fontWeight: 500 }}>판정 참고용 · 순번·제한에는 안 씀</span>
      </h2>
      {!m.shown && <p className="mut" style={{ fontSize: 13 }}>{m.reason}</p>}
      {m.shown && !m.accounts.length && (
        <p className="mut" style={{ fontSize: 13 }}>이 케이스의 계정은 모델 점수가 아직 없어요 ({m.modelVersion}).</p>
      )}
      {m.shown && m.accounts.map((a) => (
        <div key={a.memberId} style={{ padding: '6px 0', borderBottom: '1px solid var(--line2)' }}>
          <div className="row" style={{ fontSize: 13 }}>
            <b>{a.label}</b>
            <span className={'score ' + (a.overThreshold ? 'hi' : '')} style={{ marginLeft: 6 }}>{Math.round(a.score)}</span>
            <span className={'st plain ' + (a.overThreshold ? 'stop' : 'go')}>{a.overThreshold ? '봇 쪽으로 봄' : '사람 쪽으로 봄'}</span>
            <span className="grow" />
            <span className="faint" style={{ fontSize: 12 }}>{BY[a.by] || a.by}가 가장 높게 봄</span>
          </div>
          {a.reasons.length > 0 && (
            <ul style={{ margin: '4px 0 0', paddingLeft: 18, fontSize: 12.5 }} className="mut">
              {a.reasons.map((r) => <li key={r}>{r}</li>)}
            </ul>
          )}
          {a.by === 'GRU' && <p className="faint" style={{ fontSize: 12, margin: '4px 0 0' }}>행동 순서로 판단해 숫자 근거가 약해요 → 아래 화면 녹화 재생으로 확인하세요.</p>}
        </div>
      ))}
      {m.shown && (
        <p className="faint" style={{ fontSize: 12, marginTop: 6 }}>
          {m.modelVersion} · 문턱 {m.threshold}점 (사람 오탐 5% 기준){m.unscored ? ` · 점수 없는 계정 ${m.unscored}개` : ''}
        </p>
      )}
    </div>
  );
}
