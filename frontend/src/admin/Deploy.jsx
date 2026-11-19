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
 * 프로젝트가 끝나면 지운다.
 */
const STATE = {
  OK: ['go', '정상', '서버가 main의 최신(CI 통과) 버전이에요.'],
  WAITING_CI: ['amber', 'CI 기다림', '새 커밋의 GitHub CI가 아직 안 끝났어요. 끝나면 다시 누르거나 다음 자동 주기(20분)를 기다리세요.'],
  CI_FAILED: ['stop', 'CI 실패', '최근 커밋들이 CI를 통과하지 못해 배포하지 않았어요.'],
  BLOCKED: ['stop', '멈춤 (DB·인프라 변경)', 'ERD·DB 패치·시드·서버 설정이 바뀐 커밋이에요. 버튼으로는 못 풀어요 → 운영자가 서버에서 DB 패치를 적용한 뒤 배포 (deploy/README.md "BLOCKED 풀기").'],
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
        버튼을 누르면 서버의 자동 배포 장치를 <b>지금 한 번</b> 실행해요. 자동으로는 20분마다 돌아요.
        GitHub main에서 <b>CI를 통과한 최신 커밋</b>만 배포해요 (보통 2~4분). 앱이 1대라 재시작하는 <b>40초~1분 동안 사이트가 느려져요</b> (요청은 앱이 뜰 때까지 기다렸다 처리). 시연 중에는 누르지 마세요.
      </div>
      <ErrorBox error={error} onClose={() => setError(null)} />
      {s && !s.enabled && <div className="alert amber">이 서버에서는 수동 배포가 꺼져 있어요 (공용 서버에서만 켜요).</div>}
      {s?.enabled && (
        <div className="cols">
          <section className="panel">
            <h2>지금 서버</h2>
            <table>
              <tbody>
                <tr><th style={{ width: 120 }}>배포된 커밋</th><td><code>{s.deployedSha || '-'}</code> <span className="mut">{s.deployedSubject}</span></td></tr>
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
    </>
  );
}
