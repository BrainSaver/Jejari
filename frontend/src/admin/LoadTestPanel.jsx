import { useEffect, useMemo, useState } from 'react';
import { get, post } from '../api/client.js';
import { ConfirmButton, ErrorBox } from './ui.jsx';

/**
 * 공용 서버 부하 시험 (k6 시나리오 11: 로그인 → 조회 → 대기열 → 좌석 배치도 → 선점 → 결제).
 *
 * k6는 서버가 아니라 <b>관리자가 이 화면을 연 PC</b>에서 돈다 (서버에서 쏘면 앱과 CPU를 나눠 써 수치가 틀어지고,
 * 관리자 계정이 털리면 서버를 공격하는 버튼이 된다). 이 화면은
 *   1) 게이지로 인원·도착 시간·대기 한도를 정하고
 *   2) "해제 + 명령 만들기"로 이 PC의 인증 시도 제한을 임시 해제(POST /api/v1/admin/auth-relax)한 뒤
 *   3) 같은 PC 터미널에 붙여 넣을 명령(loadtest/tools/server_perf.sh)을 만든다.
 * 명령은 저장소 루트에서 Git Bash·WSL·macOS 터미널로 돌린다. k6가 없으면 스크립트가 Docker의 grafana/k6를 쓴다.
 */

// 해제 한도의 상한 (AuthPolicy: 가입 1~5000, 로그인 1~10000)
const MAX_SIGNUP = 5000;
const MAX_LOGIN = 10000;

// 시나리오 11 상한은 2000명 (loadtest/scenarios/11_user_journey.js)
const LIMITS = { USERS: [1, 2000, 10], SPREAD_SEC: [0, 300, 5], MAX_WAIT_SEC: [10, 600, 10] };

// 실제 명절 예매는 오픈 직후 몇 초 안에 좌석의 수십~수백 배가 몰린다 (2026 추석 코레일은 웹 서버 11 → 60대 증설).
// 우리 서버는 VM 한 대라 사람 수는 줄이고 "좌석보다 몇 배 많은 사람이 오픈 직후 한꺼번에"라는 모양을 맞춘다.
const PRESETS = [
  { id: 'try', label: '연습', note: '흐름이 끝까지 도는지만 확인', v: { USERS: 30, SPREAD_SEC: 20, MAX_WAIT_SEC: 120 } },
  { id: 'weekend', label: '주말 오전', note: '1분에 걸쳐 300명, 좌석이 남음', v: { USERS: 300, SPREAD_SEC: 60, MAX_WAIT_SEC: 180 } },
  { id: 'holiday', label: '명절 오픈', note: '10초 안에 1,500명 — 좌석의 약 3배가 오픈 직후 몰림', v: { USERS: 1500, SPREAD_SEC: 10, MAX_WAIT_SEC: 600 } },
  { id: 'peak', label: '명절 최대', note: '5초 안에 2,000명 (시나리오 상한), 매진까지', v: { USERS: 2000, SPREAD_SEC: 5, MAX_WAIT_SEC: 600 } },
];

const LABELS = {
  USERS: ['예매하려는 사람', '명'],
  SPREAD_SEC: ['도착을 흩뜨리는 시간 (0 = 한 순간에)', '초'],
  MAX_WAIT_SEC: ['대기열에서 최대 기다리는 시간', '초'],
};

const seoulBusan = (r) => (r.origin?.includes('서울') && r.dest?.includes('부산') ? 1 : 0);

function todayTag() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `h${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}`; // 영문 소문자·숫자 (예: h112514)
}

export default function LoadTestPanel({ canWrite, relax, onRelaxChanged }) {
  const [runs, setRuns] = useState(null);
  const [runId, setRunId] = useState('');
  const [v, setV] = useState(PRESETS[0].v);
  const [preset, setPreset] = useState(PRESETS[0].id);
  const [tag, setTag] = useState(todayTag);
  const [hours, setHours] = useState(3);
  const [made, setMade] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    get('/api/v1/admin/runs?status=ON_SALE')
      .then((list) => {
        // 시나리오는 서울(3900023) → 부산(3900114)으로 예매한다. 그 구간 운행을 먼저, 남은 좌석 많은 순
        const open = list.filter((r) => r.totalSeats - r.soldSeats > 0)
          .sort((a, b) => (seoulBusan(b) - seoulBusan(a)) || ((b.totalSeats - b.soldSeats) - (a.totalSeats - a.soldSeats)));
        setRuns(open);
        if (open.length) setRunId(String(open[0].runId));
      })
      .catch(setError);
  }, []);

  const run = runs?.find((r) => String(r.runId) === runId) || null;
  const left = run ? run.totalSeats - run.soldSeats : 0;
  const calc = useMemo(() => {
    const perSec = v.SPREAD_SEC > 0 ? v.USERS / v.SPREAD_SEC : v.USERS;
    return {
      perSec,
      ratio: left ? v.USERS / left : 0,
      // 계정 하나에 본인인증 + 가입 두 번을 센다 (SignupService). 로그인은 1분 창에 몰리는 만큼
      signupNeed: v.USERS * 2,
      loginNeed: v.SPREAD_SEC >= 60 ? Math.ceil((v.USERS * 60) / v.SPREAD_SEC) : v.USERS,
    };
  }, [v, left]);

  // 해제할 때 게이지에 맞춰 한도를 올린다 (지금 값보다 낮추지는 않음, 상한까지)
  const signupSet = Math.min(MAX_SIGNUP, Math.max(calc.signupNeed, relax?.signupPerHour ?? 0));
  const loginSet = Math.min(MAX_LOGIN, Math.max(calc.loginNeed, relax?.loginPerMin ?? 0));
  const tagOk = /^[a-z0-9]{1,12}$/.test(tag);
  const base = window.location.origin;
  const env = `BASE_URL=${base} USERS=${v.USERS} RUN_TAG=${tag}`;
  const cmds = [
    { t: '1. 계정 준비 (한 번만, 끊겨도 같은 명령으로 이어서)', c: `${env} loadtest/tools/server_perf.sh accounts` },
    { t: '2. 측정 (끝나면 표로 요약)', c: `${env} SPREAD_SEC=${v.SPREAD_SEC} loadtest/tools/server_perf.sh run ${runId} -e MAX_WAIT_SEC=${v.MAX_WAIT_SEC}` },
  ];

  const pick = (p) => {
    setPreset(p.id);
    setV(p.v);
    setMade(null);
  };
  const change = (k, n) => {
    setPreset('');
    setV((o) => ({ ...o, [k]: Number(n) }));
    setMade(null);
  };

  const start = async () => {
    setError(null);
    try {
      await post('/api/v1/admin/auth-relax', {
        ip: null, // 이 화면을 연 PC = k6를 돌릴 PC
        hours: Number(hours),
        reason: `k6 시나리오 11 ${v.USERS}명 운행 ${runId} 태그 ${tag}`,
        signupPerHour: signupSet,
        loginPerMin: loginSet,
      });
      setMade({ at: new Date() });
      onRelaxChanged();
    } catch (e) {
      setError(e);
    }
  };

  const copy = (text) => navigator.clipboard?.writeText(text).catch(() => {});

  return (
    <section className="panel" style={{ marginBottom: 16 }}>
      <h2><span className="grow">접속 몰림 시험</span><span className="st plain">이 PC에서 실행</span></h2>
      <p className="sub">
        게이지로 인원을 정하고 <b>해제 + 명령 만들기</b>를 누르면, 이 PC의 가입·로그인 제한을 잠시 풀고 터미널에 붙여 넣을 명령을 만들어요.
        명령은 저장소 폴더에서 Git Bash·WSL로 돌려요. <b>봇 대회 중에는 돌리지 마세요.</b>
      </p>
      <ErrorBox error={error} onClose={() => setError(null)} />

      <div className="tabs" role="tablist" style={{ marginBottom: 8 }}>
        {PRESETS.map((p) => (
          <button key={p.id} type="button" className={preset === p.id ? 'on' : ''} onClick={() => pick(p)} title={p.note}>{p.label}</button>
        ))}
      </div>
      {preset && <p className="mut" style={{ marginTop: 0 }}>{PRESETS.find((p) => p.id === preset)?.note}</p>}

      {Object.keys(LIMITS).map((k) => {
        const [min, max, step] = LIMITS[k];
        return (
          <label key={k} className="field">
            <span className="lbl">{LABELS[k][0]} <b className="num">{v[k].toLocaleString()}</b>{LABELS[k][1]}</span>
            <input type="range" min={min} max={max} step={step} value={v[k]} onChange={(e) => change(k, e.target.value)} aria-label={LABELS[k][0]} />
          </label>
        );
      })}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <label className="field" style={{ margin: 0, flex: 1, minWidth: 240 }}>
          <span className="lbl">운행 (판매 중, 남은 좌석 많은 순)</span>
          <select value={runId} onChange={(e) => { setRunId(e.target.value); setMade(null); }}>
            {(runs || []).map((r) => (
              <option key={r.runId} value={r.runId}>
                {r.runId} · {r.trainNo} · {r.departureAt?.replace('T', ' ').slice(0, 16)} {r.origin}→{r.dest} · 남은 {r.totalSeats - r.soldSeats}석
              </option>
            ))}
          </select>
        </label>
        <label className="field" style={{ margin: 0 }}>
          <span className="lbl">실행 태그 (같은 태그 = 같은 계정)</span>
          <input value={tag} onChange={(e) => { setTag(e.target.value.toLowerCase()); setMade(null); }} style={{ width: 140 }} />
        </label>
        <label className="field" style={{ margin: 0 }}>
          <span className="lbl">해제 시간</span>
          <select value={hours} onChange={(e) => setHours(e.target.value)} style={{ width: 100 }}>
            {[1, 3, 6, 12].map((h) => <option key={h} value={h}>{h}시간</option>)}
          </select>
        </label>
      </div>

      <table style={{ marginTop: 8 }}>
        <tbody>
          <tr><td className="mut">초당 도착</td><td className="num">약 {Math.round(calc.perSec).toLocaleString()}명/초</td></tr>
          <tr><td className="mut">남은 좌석 대비</td><td className="num">{run ? `${calc.ratio.toFixed(1)}배 (남은 ${left}석)` : '운행을 고르세요'}</td></tr>
          <tr>
            <td className="mut">필요한 가입 한도</td>
            <td className="num">시간당 {calc.signupNeed.toLocaleString()} (계정 하나에 본인인증 + 가입) → 해제 때 {signupSet.toLocaleString()}로 맞춤
              {calc.signupNeed > MAX_SIGNUP && <span className="st plain amber" style={{ marginLeft: 6 }}>상한 {MAX_SIGNUP} 넘음 → 계정 준비를 1시간 뒤 같은 명령으로 이어서</span>}
            </td>
          </tr>
          <tr>
            <td className="mut">필요한 로그인 한도</td>
            <td className="num">분당 {calc.loginNeed.toLocaleString()} → 해제 때 {loginSet.toLocaleString()}로 맞춤</td>
          </tr>
        </tbody>
      </table>
      {run && !seoulBusan(run) && <p className="mut"><span className="st plain amber">이 운행은 서울→부산이 아니에요</span> 시나리오가 서울→부산으로 예매해서 멈춰요. 서울→부산 운행을 고르세요.</p>}
      {run && calc.ratio > 1 && <p className="mut">좌석보다 사람이 많아 일부는 매진으로 끝나요 (명절과 같은 모양). 결과표의 "매진으로 끝남"은 실패가 아니에요.</p>}

      {canWrite ? (
        <ConfirmButton className="btn wide" confirm={`한 번 더 누르면 이 PC를 ${hours}시간 해제하고 명령을 만들어요`} onConfirm={start}
          disabled={!runId || !tagOk}>
          해제 + 명령 만들기
        </ConfirmButton>
      ) : (
        <p className="mut">해제는 총괄 관리자만 할 수 있어요.</p>
      )}
      {!tagOk && <p className="mut">실행 태그는 영문 소문자·숫자 12자 이내예요.</p>}

      {made && (
        <div style={{ marginTop: 12 }}>
          <div className="alert sky">이 PC를 해제했어요. 같은 PC 터미널에서 저장소 폴더로 이동한 뒤 아래 순서대로 붙여 넣으세요.</div>
          {cmds.map((x) => (
            <div key={x.t} style={{ marginBottom: 8 }}>
              <div className="lbl">{x.t}</div>
              <div style={{ display: 'flex', gap: 6 }}>
                <code className="mono" style={{ flex: 1, padding: 8, overflowX: 'auto', whiteSpace: 'nowrap' }}>{x.c}</code>
                <button type="button" className="btn ghost sm" onClick={() => copy(x.c)}>복사</button>
              </div>
            </div>
          ))}
          <p className="mut">
            끝나면 위 "인증 제한 임시 해제" 칸에서 <b>지금 끝내기</b>를 누르세요 (안 눌러도 {hours}시간 뒤 저절로 돌아가요).
            결제는 모두 같은 시험용 카드를 써요 — 운영 설정 payment.max_ci_per_card가 0(끔)인지 확인하세요.
          </p>
        </div>
      )}
    </section>
  );
}
