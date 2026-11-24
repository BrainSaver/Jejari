import { useEffect, useState } from 'react';
import { Link, useOutletContext, useParams } from 'react-router';
import { get, post } from '../api/client.js';
import { Who } from './AdminLayout.jsx';
import RequestLog from './RequestLog.jsx';
import { ago } from './Risk.jsx';
import { ConfirmButton, ErrorBox } from './ui.jsx';
import { hasRole } from './useAdminAuth.js';

/**
 * A-11 단체 신청 (concurrency.md §18).
 *   GET  /api/v1/admin/groups?status=           목록. 경고(휴대폰 없음 비율·다른 단체와 번호 겹침)가 있는 신청이 위
 *   GET  /api/v1/admin/groups/{id}              상세. 탑승자 이름·번호는 가려서
 *   POST /api/v1/admin/groups/{id}/approve      승인 (REVIEWER 이상). 자동 승인과 같은 절차라 오픈 직후엔 DEFERRED일 수 있음
 *   POST /api/v1/admin/groups/{id}/reject       반려 {reason} (REVIEWER 이상)
 *
 * 백엔드가 보이게: 자동 승인인지 관리자 승인인지, 예약(reservation) 상태, 배정 결과(outcome)를 그대로 보여 준다.
 * 위험 점수가 결제 차단 기준(80) 이상이면 자동 승인하지 않고 REQUESTED로 남아 여기서 판단한다.
 */
const STATUS = { REQUESTED: 'amber', APPROVED: 'go', REJECTED: 'stop', WITHDRAWN: '' };
const OUTCOME = {
  APPROVED: '승인 — 예약(HOLD)을 만들고 좌석을 배정했어요.',
  DEFERRED: '미룸 — 할당석이 모자라고 대기열에 사람이 있어 일반 좌석을 지금은 못 가져와요. 스캐너가 1분마다 다시 해요.',
  SEAT_SHORT: '좌석 부족 — 일반 좌석까지 모자라요. 신청은 REQUESTED로 남아요.',
};

export function GroupList() {
  const claims = useOutletContext();
  const [status, setStatus] = useState('REQUESTED');
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    get('/api/v1/admin/groups?limit=100' + (status ? `&status=${status}` : '')).then(setData).catch(setError);
  }, [status]);
  return (
    <>
      <div className="head"><h1>단체 신청</h1><span style={{ flex: 1 }} /><Who claims={claims} /></div>
      <ErrorBox error={error} />
      <section className="panel">
        <h2>
          <span className="grow">신청 {data ? `${data.length}건` : ''}</span>
          <select value={status} onChange={(e) => setStatus(e.target.value)} style={{ width: 160, minHeight: 32 }} aria-label="상태">
            <option value="">모든 상태</option>
            {Object.keys(STATUS).map((s) => <option key={s}>{s}</option>)}
          </select>
        </h2>
        <p className="sub">Oracle group_booking + reservation. 경고가 붙은 신청이 위에 와요. 위험 점수 때문에 자동 승인되지 않은 신청도 REQUESTED로 남아 있어요.</p>
        <table>
          <thead><tr><th>신청</th><th>운행</th><th className="n">인원</th><th>경고</th><th>상태</th><th>예약</th><th>승인</th><th>신청 시각</th></tr></thead>
          <tbody>
            {(data || []).map((g) => (
              <tr key={g.groupId}>
                <td><Link className="link" to={`/admin/groups/${g.groupId}`}>G-{g.groupId}</Link></td>
                <td className="num">{g.runId} <span className="faint">{g.fromStationId}→{g.toStationId}</span></td>
                <td className="n num">{g.passengerCount}</td>
                <td><Warns g={g} /></td>
                <td><span className={'st ' + (STATUS[g.status] ?? '')}>{g.status}</span></td>
                <td>{g.reservationId ? <span className="num">R-{g.reservationId} <span className="faint">{g.reservationStatus}</span></span> : '—'}</td>
                <td className="mut">{g.status === 'APPROVED' ? (g.autoApproved ? '자동' : '관리자') : '—'}</td>
                <td className="mut">{ago(g.createdAt)}</td>
              </tr>
            ))}
            {data && !data.length && <tr><td colSpan={8} className="mut">신청이 없어요.</td></tr>}
          </tbody>
        </table>
      </section>
      <RequestLog filter={(r) => r.path.includes('/admin/groups')} rows={4} />
    </>
  );
}

function Warns({ g }) {
  if (!g.warnNoPhone && !g.warnDupPhone) return <span className="faint">—</span>;
  return (
    <>
      {g.warnNoPhone && <span className="st plain amber">휴대폰 없음 많음</span>}
      {g.warnDupPhone && <span className="st plain stop" style={{ marginLeft: 4 }}>번호 겹침</span>}
    </>
  );
}

export default function GroupDetail() {
  const claims = useOutletContext();
  const { groupId } = useParams();
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const [reason, setReason] = useState('');
  const load = () => get(`/api/v1/admin/groups/${groupId}`).then(setD).catch(setError);
  useEffect(() => {
    load();
  }, [groupId]);

  const canAct = hasRole(claims, 'REVIEWER');
  const approve = async () => {
    setError(null);
    try {
      setResult(await post(`/api/v1/admin/groups/${groupId}/approve`));
      load();
    } catch (e) {
      setError(e);
    }
  };
  const reject = async () => {
    setError(null);
    try {
      await post(`/api/v1/admin/groups/${groupId}/reject`, { reason });
      setReason('');
      load();
    } catch (e) {
      setError(e);
    }
  };

  const s = d?.summary;
  const noPhone = d ? d.passengers.filter((p) => p.noPhone).length : 0;
  return (
    <>
      <div className="head"><h1><Link className="link" to="/admin/groups">단체 신청</Link> · G-{groupId}</h1><span style={{ flex: 1 }} /><Who claims={claims} /></div>
      <ErrorBox error={error} onClose={() => setError(null)} />
      {s && (
        <div className="kpis">
          <div className="kpi"><div className="l">상태</div><div className="n" style={{ fontSize: 22, padding: '6px 0' }}><span className={'st ' + (STATUS[s.status] ?? '')} style={{ fontSize: 14 }}>{s.status}</span></div><div className="d">{ago(s.createdAt)} 신청</div></div>
          <div className="kpi"><div className="l">인원</div><div className="n">{s.passengerCount}</div><div className="d">휴대폰 없음 {noPhone}명 ({Math.round((noPhone / s.passengerCount) * 100)}%)</div></div>
          <div className="kpi"><div className="l">운행</div><div className="n" style={{ fontSize: 22, padding: '6px 0' }}>{s.runId}</div><div className="d">{s.fromStationId} → {s.toStationId}</div></div>
          <div className="kpi"><div className="l">예약</div><div className="n" style={{ fontSize: 22, padding: '6px 0' }}>{s.reservationId ? `R-${s.reservationId}` : '—'}</div><div className="d">{s.reservationStatus || '승인 전'}{s.payDeadlineAt ? ` · 기한 ${s.payDeadlineAt.slice(0, 16).replace('T', ' ')}` : ''}</div></div>
          <div className="kpi"><div className="l">할인</div><div className="n" style={{ fontSize: 22, padding: '6px 0' }}>{s.discount ? '어른 10%' : '없음'}</div><div className="d">출발 1개월 안 신청분만</div></div>
          <div className="kpi"><div className="l">경고</div><div className="n" style={{ fontSize: 16, padding: '10px 0' }}><Warns g={s} /></div><div className="d">{s.rosterChangedAt ? '명단 1회 변경함' : '명단 변경 없음'}</div></div>
        </div>
      )}
      <div className="cols">
        <section className="panel">
          <h2><span className="grow">탑승자 명단</span><span className="mut" style={{ fontWeight: 500 }}>이름·번호는 가려서 보여요</span></h2>
          <table>
            <thead><tr><th className="n">#</th><th>이름</th><th>휴대폰</th><th>구분</th><th>좌석</th></tr></thead>
            <tbody>
              {(d?.passengers || []).map((p) => (
                <tr key={p.passengerNo}>
                  <td className="n num">{p.passengerNo}</td>
                  <td>{p.name}{p.rep && <span className="st plain sky" style={{ marginLeft: 6 }}>대표자</span>}</td>
                  <td className="num">{p.noPhone ? <span className="faint">없음 (대표자 번호로)</span> : p.phone}</td>
                  <td>{p.passengerType === 'CHILD' ? '어린이' : '어른'}</td>
                  <td className="num">{p.carNo != null ? `${p.carNo}호차 ${p.seatNo}` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <section className="panel">
          <h2>판단</h2>
          {s?.status === 'REQUESTED' ? (
            canAct ? (
              <>
                <p className="sub">승인하면 할당석부터 같은 객차에 붙여 배정하고, 모자라면 일반 좌석에서 이어 받아요. 대기열에 사람이 있으면 미뤄질 수 있어요.</p>
                <ConfirmButton className="btn" confirm="한 번 더 누르면 승인" onConfirm={approve}>승인</ConfirmButton>
                <hr className="sep" />
                <div className="field">
                  <label htmlFor="why">반려 사유 (100자 이내)</label>
                  <input id="why" value={reason} maxLength={100} placeholder="예: 다른 단체와 같은 명단으로 반복 신청" onChange={(e) => setReason(e.target.value)} />
                </div>
                <ConfirmButton className="btn ghost" confirm="한 번 더 누르면 반려" disabled={!reason.trim()} onConfirm={reject}>반려</ConfirmButton>
              </>
            ) : <p className="mut">승인·반려는 REVIEWER 이상만 할 수 있어요.</p>
          ) : (
            <p className="mut">
              {s?.status === 'APPROVED' && `${s.autoApproved ? '자동' : '관리자'} 승인됨. 결제·만료·취소는 예약(R-${s.reservationId}) 상태를 따라요.`}
              {s?.status === 'REJECTED' && `반려됨: ${s.cancelReason || ''}`}
              {s?.status === 'WITHDRAWN' && '대표자가 철회했어요.'}
            </p>
          )}
          {result && (
            <div className={'alert ' + (result.outcome === 'APPROVED' ? 'go' : 'amber')} style={{ marginTop: 12 }}>
              <b>{result.outcome}</b> <span>{OUTCOME[result.outcome] || ''}</span>
            </div>
          )}
        </section>
      </div>
      <RequestLog filter={(r) => r.path.includes('/admin/groups')} rows={6} />
    </>
  );
}
