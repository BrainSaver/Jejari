// 가짜 GitHub + wrangler pages dev 위에서 API 흐름 검사
// node site/test/api-test.mjs http://127.0.0.1:8790 http://127.0.0.1:9100
const BASE = process.argv[2] || 'http://127.0.0.1:8790';
const MOCK = process.argv[3] || 'http://127.0.0.1:9100';
let pass = 0, failN = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  ok  ', m); } else { failN++; console.log('  FAIL', m); } };

function client() {
  let cookie = '';
  return async (path, body, method) => {
    const res = await fetch(BASE + '/api' + path, {
      method: method || (body ? 'POST' : 'GET'),
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(cookie ? { Cookie: cookie } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const sc = res.headers.get('set-cookie'); if (sc) cookie = sc.split(';')[0];
    return { status: res.status, data: await res.json() };
  };
}
const mock = (path, body) => fetch(MOCK + path, { method: body ? 'POST' : 'GET', headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }).then((r) => r.json());
const F = 'docs/기능설계서.md';
const secOf = (text, head) => {
  const i = text.indexOf(head); const j = text.indexOf('\n## ', i + 1);
  return text.slice(i, j === -1 ? text.length : j + 1);
};

const dh = client(), dg = client(), anon = client();

console.log('로그인');
ok((await anon('/login', { name: 'Hacker', pin: '1234' })).status === 401, '브랜치 이름이 아니면 거부');
ok((await anon('/login', { name: 'DongHyeon', pin: '12345' })).status === 401, '4자리가 아니면 거부');
let r = await dh('/login', { name: 'DongHyeon', pin: '1234' });
ok(r.status === 200 && r.data.firstSet, '처음 로그인은 PIN 설정');
r = await dh('/me'); ok(r.data.name === 'DongHyeon' && r.data.admin, '세션 유지, 팀장 표시');
r = await dg('/login', { name: 'DongGyun', pin: '0000' }); ok(r.data.firstSet, '동균 PIN 설정');
const bad = client();
for (let i = 0; i < 4; i++) await bad('/login', { name: 'DongGyun', pin: '9999' });
r = await bad('/login', { name: 'DongGyun', pin: '9999' }); ok(r.status === 423, '5번 틀리면 잠김');
r = await bad('/login', { name: 'DongGyun', pin: '0000' }); ok(r.status === 423, '잠긴 동안 맞는 PIN도 거부');
r = await anon('/save', { path: F, mode: 'section', oldText: 'x', newText: 'y', message: 'docs: x' }); ok(r.status === 401, '로그인 없이 저장 거부');
r = await fetch(BASE + '/api/save', { method: 'POST', body: 'a=1', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }); ok(r.status === 415, 'JSON 아닌 POST 거부');

console.log('섹션 저장');
let p = await dh('/prepare', { path: F });
ok(p.status === 200 && p.data.file.content.includes('## 6. 화면 후보'), '내 브랜치 파일 열기');
const s6 = secOf(p.data.file.content, '## 6. 화면 후보');
const s6new = s6.replace('화면설계에서 이 ID를 그대로 씁니다.', '화면설계에서 이 ID를 그대로 씁니다. (사이트 테스트)');
r = await dh('/commit-message', { path: F, heading: '## 6. 화면 후보', before: s6, after: s6new });
ok(r.data.message === 'docs(기능설계): 6. 화면 후보 수정' && !r.data.ai, 'Groq 키 없으면 기본 메시지');
r = await dh('/save', { path: F, mode: 'section', oldText: s6, newText: s6new, message: r.data.message, heading: '## 6. 화면 후보' });
ok(r.status === 200 && r.data.branch === 'DongHyeon', '자기 브랜치에 저장');
r = await anon(`/file?path=${encodeURIComponent(F)}&ref=DongHyeon&fresh=1`);
ok(r.data.content.includes('(사이트 테스트)'), '브랜치에 반영됨');
r = await anon(`/file?path=${encodeURIComponent(F)}&ref=main&fresh=1`);
ok(!r.data.content.includes('(사이트 테스트)'), 'main은 그대로');
r = await dh('/commits?fresh=1');
ok(r.data.some((c) => c.author === 'DongHyeon' && c.message.startsWith('docs(기능설계)')), '기록에 이름으로 남음');

console.log('동시 수정');
r = await dh('/save', { path: F, mode: 'section', oldText: s6, newText: s6 + 'x', message: 'docs(기능설계): 다시' });
ok(r.status === 409 && r.data.error === 'STALE' && r.data.current, '바뀐 섹션을 옛 원문으로 저장하면 STALE');
// 다른 섹션은 그대로 저장 가능
p = await dh('/prepare', { path: F });
const s9 = secOf(p.data.file.content, '## 9. 에러 코드');
r = await dh('/save', { path: F, mode: 'section', oldText: s9, newText: s9.replace('| 코드 | HTTP | 상황 |', '| 코드 | HTTP | 상황  |'), message: 'docs(기능설계): 에러 코드 표 정리' });
ok(r.status === 200, '다른 섹션은 저장됨');

console.log('main 자동 합치기');
await mock('/_test/commit', { branch: 'main', path: 'docs/concurrency.md', content: '# 제자리 동시성 절차\n\nmain에서 바뀜\n', message: 'docs: main 변경', author: 'BrainSaver' });
p = await dh('/prepare', { path: F });
ok(p.data.sync === 'merged', '저장 전 main을 브랜치에 합침');
r = await anon('/file?path=docs/concurrency.md&ref=DongHyeon&fresh=1');
ok(r.data.content.includes('main에서 바뀜'), 'main 변경이 브랜치에 들어옴');
// 충돌: 동균이 README를 고친 뒤 main도 README를 다르게 고침
p = await dg('/prepare', { path: 'README.md' });
const rd = p.data.file.content;
const rs = secOf(rd, '## ') || rd;
r = await dg('/save', { path: 'README.md', mode: 'file', oldText: rd, newText: rd + '\n동균 추가\n', message: 'docs(기획서): 동균 추가' });
ok(r.status === 200, '동균 README 저장');
await mock('/_test/commit', { branch: 'main', path: 'README.md', content: rd + '\nmain 추가\n', message: 'docs: main README' });
r = await dg('/save', { path: 'docs/DB설계서.md', mode: 'section', oldText: '# ', newText: '# ', message: 'docs(DB설계): x' });
ok(r.status === 409 && r.data.error === 'MAIN_CONFLICT' && r.data.files.includes('README.md'), 'main과 충돌이면 저장 멈추고 파일 알려줌');

console.log('되돌리기');
let feed = (await dh('/commits?fresh=2')).data;
const mineC = feed.find((c) => c.author === 'DongHyeon' && c.message.includes('에러 코드 표 정리'));
r = await dg('/revert', { sha: mineC.sha });
ok(r.status === 403, '남의 수정은 되돌릴 수 없음');
const first = feed.find((c) => c.author === 'DongHyeon' && c.message.startsWith('docs(기능설계): 6.'));
r = await dh('/revert', { sha: first.sha });
ok(r.status === 409 && r.data.error === 'LATER_CHANGES', '뒤에 같은 파일이 바뀌었으면 경고');
r = await dh('/revert', { sha: mineC.sha });
ok(r.status === 200, '내 수정 되돌림');
r = await anon(`/file?path=${encodeURIComponent(F)}&ref=DongHyeon&fresh=3`);
ok(r.data.content.includes('| 코드 | HTTP | 상황 |') && r.data.content.includes('(사이트 테스트)'), '그 커밋만 되돌아가고 다른 수정은 남음');

console.log('main에 합치기');
r = await dh('/branches?fresh=4');
const me = r.data.branches.find((b) => b.name === 'DongHyeon');
ok(me.ahead > 0, `브랜치 현황 ahead=${me.ahead}`);
r = await dh('/merge', {});
ok(r.status === 200 && r.data.pr, 'PR 만들고 바로 합침');
r = await anon(`/file?path=${encodeURIComponent(F)}&ref=main&fresh=5`);
ok(r.data.content.includes('(사이트 테스트)'), 'main에 반영됨');
r = await dh('/merge', {});
ok(r.status === 400, '더 합칠 게 없으면 알려줌');

console.log('ERD 내보내기');
r = await dh('/erd-export', { content: '{"project":"제자리","tables":[]}', message: 'db(erd): 테스트 내보내기' });
ok(r.status === 200 && /DongHyeon-\d{8}\.json$/.test(r.data.path), `내보내기 커밋 ${r.data.path}`);
r = await dh('/erd-export', { content: 'not json', message: 'db(erd): x' });
ok(r.status === 400, 'JSON 아니면 거부');

console.log('경로 제한');
r = await dh('/save', { path: 'site/functions/api/x.js', mode: 'file', newText: 'x', message: 'feat: x' });
ok(r.status === 400, '문서가 아닌 파일 저장 거부');
r = await anon('/file?path=.github/workflows/site.yml&ref=main');
ok(r.status === 400, '문서가 아닌 파일 읽기 거부');
r = await anon('/file?path=README.md&ref=claude/x');
ok(r.status === 400, '이름 브랜치가 아닌 ref 거부');

console.log('PIN 초기화');
r = await dg('/admin/reset-pin', { name: 'DongHyeon' }); ok(r.status === 403, '팀장 아니면 거부');
r = await dh('/admin/reset-pin', { name: 'DongGyun' }); ok(r.status === 200, '팀장이 초기화');
r = await anon('/me'); ok(r.data.pins.DongGyun === false, '초기화 후 설정 전 상태');
r = await client()('/login', { name: 'DongGyun', pin: '4321' }); ok(r.data.firstSet, '다시 처음처럼 설정 (잠금도 풀림)');

console.log('그래프');
r = await anon('/graph?fresh=6');
ok(r.data.commits.length > 3 && r.data.heads.main, `커밋 ${r.data.commits.length}개, 머리 ${Object.keys(r.data.heads).length}개`);

console.log(`\n${pass} 통과, ${failN} 실패`);
process.exit(failN ? 1 : 0);
