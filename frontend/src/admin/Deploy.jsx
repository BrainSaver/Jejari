import { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router';
import { get, post } from '../api/client.js';
import { Who } from './AdminLayout.jsx';
import { ConfirmButton, ErrorBox } from './ui.jsx';

/**
 * A-08 공용 서버 배포. 버튼은 서버의 자동 배포 장치(jejari-autodeploy)를 지금 한 번 실행시킨다.
 *   GET  /api/v1/admin/deploy   지금 서버 버전 · 마지막 판단 · 진행 중 여부
 *   POST /api/v1/admin/deploy   시작만 걸고 바로 돌아옴 (SUPERVISOR 중 서버 .env에 적힌 관리자만)
 * 배포 중에는 앱이 재시작돼 이 화면의 요청이 잠깐 느리거나 실패할 수 있다 → 몇 초마다 다시 읽는다.
 * BLOCKED면 아래 BlockedPanel에서 막힌 파일별 할 일을 처리하고 승인한다 (DeployApprovalService).
 * 프로젝트가 끝나면 지운다.
 */
const STATE = {
  OK: ['go', '정상', '서버가 자동 검사를 통과한 최신 코드 버전이에요.'],
  WAITING_CI: ['amber', '자동 검사 기다림', '새 코드 버전의 자동 검사가 아직 안 끝났어요. 끝나면 다시 누르거나 다음 자동 주기(10분)를 기다리세요.'],
  CI_FAILED: ['stop', '자동 검사 실패', '최근 코드 버전들이 자동 검사를 통과하지 못해 배포하지 않았어요.'],
  BLOCKED: ['stop', '멈춤 (DB·서버 설정 변경)', 'DB 구조·기본 데이터·서버 설정이 바뀐 코드 버전이에요. 아래 "막힌 버전 승인"에서 할 일을 처리하고 승인하면 배포돼요.'],
  FAILED: ['stop', '실패', '빌드나 앱 기동이 실패했어요. 서버 로그를 확인하세요 (journalctl -u jejari-autodeploy).'],
};

export default function Deploy() {
  const claims = useOutletContext();
  const [s, setS] = useState(null);
  const [error, setError] = useState(null);
  const [started, setStarted] = useState(false);

  const load = () => get('/api/v1/admin/deploy').then((r) => { setS(r); setError(null); }).catch((e) => {
    // 배포 중 앱 재시작으로 생기는 잠깐의 실패는 보여 주지 않는다
    if (!started) setError(e);
  });
  useEffect(() => {
    load();
  }, []);
  // 진행 중이거나 방금 눌렀으면 5초마다 다시 읽기
  useEffect(() => {
    if (!(s?.running || started)) return undefined;
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [s?.running, started]);
  useEffect(() => {
    if (started && s && !s.running) {
      const t = setTimeout(() => setStarted(false), 15000);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [started, s?.running]);

  const run = async () => {
    setError(null);
    try {
      setS(await post('/api/v1/admin/deploy'));
      setStarted(true);
    } catch (e) {
      setError(e);
    }
  };

  const [tone, label, help] = STATE[s?.lastState] || ['plain', s?.lastState || '-', ''];

  return (
    <>
      <div className="head"><h1>배포</h1><span style={{ flex: 1 }} /><Who claims={claims} /></div>
      <div className="alert sky">
        버튼을 누르면 서버의 자동 배포 장치를 <b>지금 한 번</b> 실행해요. 자동으로는 10분마다 돌아요.
        <b>자동 검사를 통과한 최신 코드 버전</b>만 배포해요 (보통 2~4분). 앱이 1대라 재시작하는 <b>40초~1분 동안 사이트가 느려져요</b> (요청은 앱이 뜰 때까지 기다렸다 처리). 시연 중에는 누르지 마세요.
      </div>
      <ErrorBox error={error} onClose={() => setError(null)} />
      {s && !s.enabled && <div className="alert amber">이 서버에서는 수동 배포가 꺼져 있어요 (공용 서버에서만 켜요).</div>}
      {s?.enabled && (
        <div className="cols">
          <section className="panel">
            <h2>지금 서버</h2>
            <table>
              <tbody>
                <tr><th style={{ width: 120 }}>배포된 코드 버전</th><td><code>{s.deployedSha || '-'}</code> <span className="mut">{s.deployedSubject}</span></td></tr>
                <tr><th>마지막 판단</th><td><span className={`st ${tone}`}>{label}</span> <span className="faint num">{s.lastStateAt}</span></td></tr>
                <tr><th>내용</th><td className="mut" style={{ fontSize: 13 }}>{s.lastMessage}</td></tr>
                <tr><th>마지막 수동 요청</th><td className="faint num">{s.lastRequest || '-'}</td></tr>
              </tbody>
            </table>
            {help && <p className="sub" style={{ marginTop: 10 }}>{help}</p>}
          </section>
          <section className="panel">
            <h2>수동 배포</h2>
            {s.running ? (
              <div className="alert amber">배포 중이에요… 끝나면 위 "마지막 판단"이 바뀌어요. 이 화면은 5초마다 다시 읽어요.</div>
            ) : started ? (
              <div className="alert sky">방금 끝났어요. 위 결과를 확인하세요.</div>
            ) : null}
            <ConfirmButton className="btn" confirm="한 번 더 누르면 배포 시작" onConfirm={run} disabled={!s.canTrigger || s.running}>
              지금 배포
            </ConfirmButton>
            {!s.canTrigger && <p className="sub" style={{ marginTop: 8 }}>이 계정은 보기만 돼요. 누를 수 있는 관리자는 서버 설정에 적힌 사람뿐이에요.</p>}
          </section>
        </div>
      )}
      {s?.enabled && s.lastState === 'BLOCKED' && <BlockedPanel onChanged={load} />}
    </>
  );
}

const KIND = {
  PATCH: ['amber', 'DB 구조 변경'],
  INFO: ['go', '할 일 없음'],
  MANUAL: ['stop', '서버에서 직접'],
};

/**
 * BLOCKED 승인 (DeployApprovalService). 막힌 파일마다 할 일을 보여 준다.
 *   DB 패치 → [적용]으로 Oracle에 넣음 / 문서·시드 → 자동 통과 / 그 밖 → 서버에서 직접 하고 "처리함" 체크
 * 모두 끝나야 [승인]이 켜지고, 승인하면 자동 배포가 그 커밋 하나만 BLOCKED를 건너뛰고 배포한다.
 * 프로젝트가 끝나면 지운다.
 */
function BlockedPanel({ onChanged }) {
  const [b, setB] = useState(null);
  const [error, setError] = useState(null);
  const [checked, setChecked] = useState({});
  const [results, setResults] = useState({});
  const [open, setOpen] = useState({});

  const load = () => get('/api/v1/admin/deploy/blocked').then((r) => { setB(r); setError(null); }).catch(setError);
  useEffect(() => {
    load();
  }, []);

  if (error) return <ErrorBox error={error} onClose={() => setError(null)} />;
  if (!b) return null;
  if (!b.blocked) {
    return (
      <section className="panel" style={{ marginTop: 16 }}>
        <h2>막힌 버전 승인</h2>
        <p className="sub">막힌 파일 목록이 아직 없어요. 자동 배포 장치가 한 번 더 돌면 생겨요 (최대 10분, 위 "지금 배포"를 누르면 바로).</p>
      </section>
    );
  }

  const apply = async (path) => {
    setError(null);
    try {
      const r = await post('/api/v1/admin/deploy/blocked/patches', { sha: b.sha, path });
      setResults((x) => ({ ...x, [path]: r }));
      await load();
    } catch (e) {
      setError(e);
    }
  };
  const show = async (path) => {
    if (open[path] !== undefined) {
      setOpen((x) => { const y = { ...x }; delete y[path]; return y; });
      return;
    }
    try {
      const r = await get(`/api/v1/admin/deploy/blocked/file?sha=${b.sha}&path=${encodeURIComponent(path)}`);
      setOpen((x) => ({ ...x, [path]: r.content }));
    } catch (e) {
      setError(e);
    }
  };
  const approve = async () => {
    setError(null);
    try {
      const confirmed = b.items.filter((i) => i.kind === 'MANUAL' && checked[i.path]).map((i) => i.path);
      setB(await post('/api/v1/admin/deploy/blocked/approve', { sha: b.sha, confirmed }));
      onChanged();
    } catch (e) {
      setError(e);
    }
  };

  const manualLeft = b.items.filter((i) => i.kind === 'MANUAL' && !checked[i.path]).length;
  const patchLeft = b.items.filter((i) => i.kind === 'PATCH' && !i.done).length;

  return (
    <section className="panel" style={{ marginTop: 16 }}>
      <h2>막힌 버전 승인</h2>
      <p className="sub">
        <code>{b.shortSha}</code> <span className="mut">{b.subject}</span>
      </p>
      <div className="alert sky">
        DB 구조 변경은 <b>[적용]</b>을 누르면 DB에 반영돼요. "서버에서 직접"은 적힌 명령을 운영자가 서버에서 실행한 뒤(또는 배포 직후 하기로 하고) 체크하세요.
        모두 끝나면 <b>승인</b>할 수 있고, 승인하면 이 버전 하나만 멈춤을 건너뛰고 배포해요.
      </div>
      <table>
        <thead>
          <tr><th>파일</th><th style={{ width: 110 }}>종류</th><th>할 일</th><th style={{ width: 150 }} /></tr>
        </thead>
        <tbody>
          {b.items.map((i) => {
            const [tone, label] = KIND[i.kind];
            const r = results[i.path];
            return (
              <tr key={i.path}>
                <td>
                  <span className="nw">
                    <code>{i.path}</code>
                    {b.canAct && (
                      <button type="button" className="btn ghost sm" style={{ marginLeft: 6, minHeight: 24 }} onClick={() => show(i.path)}>
                        {open[i.path] !== undefined ? '닫기' : '내용'}
                      </button>
                    )}
                  </span>
                  {open[i.path] !== undefined && (
                    <pre style={{ maxHeight: 260, overflow: 'auto', fontSize: 12, marginTop: 6, whiteSpace: 'pre-wrap' }}>{open[i.path]}</pre>
                  )}
                  {r && (
                    <div className={`alert ${r.ok ? 'go' : 'stop'}`} style={{ marginTop: 6 }}>
                      {r.ok ? '적용했어요' : '실패했어요 (ORA-/SP2- 오류)'}
                      <pre style={{ maxHeight: 200, overflow: 'auto', fontSize: 12, whiteSpace: 'pre-wrap' }}>{r.output}</pre>
                    </div>
                  )}
                </td>
                <td><span className={`st ${i.done ? 'go' : tone}`}>{i.done ? (i.kind === 'PATCH' ? '적용함' : label) : label}</span></td>
                <td className="mut" style={{ fontSize: 13 }}>{i.kind === 'MANUAL' ? <code>{i.how}</code> : i.how}</td>
                <td>
                  {i.kind === 'PATCH' && b.canAct && (
                    <ConfirmButton confirm="한 번 더 누르면 적용" onConfirm={() => apply(i.path)}>
                      {i.done ? '다시 적용' : '적용'}
                    </ConfirmButton>
                  )}
                  {i.kind === 'MANUAL' && b.canAct && (
                    <label style={{ fontSize: 13 }}>
                      <input type="checkbox" checked={!!checked[i.path]} onChange={(e) => setChecked((x) => ({ ...x, [i.path]: e.target.checked }))} /> 처리함
                    </label>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div style={{ marginTop: 12 }}>
        {b.approvedAt ? (
          <div className="alert go">승인됨 ({b.approvedAt}). 자동 배포가 이 버전을 배포해요. 위 "마지막 판단"이 바뀔 때까지 기다려 주세요.</div>
        ) : (
          <>
            <ConfirmButton className="btn" confirm="한 번 더 누르면 승인·배포" onConfirm={approve} disabled={!b.canAct || patchLeft > 0 || manualLeft > 0}>
              승인하고 배포
            </ConfirmButton>
            {b.canAct && (patchLeft > 0 || manualLeft > 0) && (
              <p className="sub" style={{ marginTop: 8 }}>남은 일: DB 구조 변경 {patchLeft}개, 서버 작업 체크 {manualLeft}개</p>
            )}
            {!b.canAct && <p className="sub" style={{ marginTop: 8 }}>이 계정은 보기만 돼요.</p>}
          </>
        )}
      </div>
    </section>
  );
}
