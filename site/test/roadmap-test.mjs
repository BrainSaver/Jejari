// 로드맵 보드 API 흐름 검사: node test/roadmap-test.mjs http://127.0.0.1:8790  (pages dev에 --d1 AI_DB 필요)
const BASE = process.argv[2] || 'http://127.0.0.1:8790';
let pass = 0, failN = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  ok  ', m); } else { failN++; console.log('  FAIL', m); } };
function client() {
  let cookie = '';
  return async (path, body) => {
    const r = await fetch(BASE + '/api' + path, { method: body ? 'POST' : 'GET', headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(cookie ? { Cookie: cookie } : {}) }, body: body ? JSON.stringify(body) : undefined });
    const sc = r.headers.get('set-cookie'); if (sc) cookie = sc.split(';')[0];
    const ct = r.headers.get('content-type') || '';
    return { status: r.status, data: ct.includes('json') ? await r.json() : await r.arrayBuffer(), ct };
  };
}
const dh = client(), dg = client(), anon = client();
await dh('/login', { name: 'DongHyeon', pin: '1234' });
await dg('/login', { name: 'DongGyun', pin: '5678' });

let r = await anon('/roadmap');
ok(r.status === 200 && r.data.items.length > 50 && r.data.areas.length > 10, `로그인 없이 보기 (카드 ${r.data.items.length}장, 영역 ${r.data.areas.length}개)`);
ok(r.data.items.every((x) => x.area && x.roles && Object.keys(x.roles).length && !('status' in x)), '모든 카드에 영역과 파트 칸');
const evt = r.data.items.find((x) => x.code === 'EVT-04');
ok(evt && evt.area === 'event' && evt.roles.C && evt.roles.B, 'EVT-04는 이벤트 영역, C·B 칸');
const test1 = r.data.items.find((x) => x.code === 'TEST-01');
ok(Object.keys(test1.roles).length === 5, '공통 작업은 A~E 다섯 칸');

r = await anon('/roadmap/check', { id: evt.id, part: 'C', done: true }); ok(r.status === 401, '로그인 안 하면 체크 불가');
// 두 사람이 같은 카드의 다른 칸을 거의 동시에 체크해도 둘 다 남는다
const [c1, c2] = await Promise.all([dh('/roadmap/check', { id: evt.id, part: 'C', done: true }), dg('/roadmap/check', { id: evt.id, part: 'B', done: true })]);
ok(c1.status === 200 && c2.status === 200, '동시 체크 둘 다 성공');
r = await anon('/roadmap');
let e2 = r.data.items.find((x) => x.id === evt.id);
ok(e2.roles.C.done && e2.roles.C.by === 'DongHyeon' && e2.roles.B.done && e2.roles.B.by === 'DongGyun', '누가 체크했는지 칸마다 기록');
r = await dh('/roadmap/check', { id: evt.id, part: 'A', done: true }); ok(r.status === 400, '맡지 않은 파트는 체크 불가');
r = await dh('/roadmap/check', { id: evt.id, part: 'B', done: false });
ok(r.status === 200 && !r.data.item.roles.B.done && r.data.item.roles.B.by === null, '체크 풀기');

r = await dh('/roadmap/save', { item: { ...e2, title: '옛 버전' }, v: e2.v - 1 }); ok(r.status === 409, '옛 버전으로 고치면 충돌');
e2 = r.data.current;
r = await dh('/roadmap/save', { item: { ...e2, roles: { ...e2.roles, D: { done: false } }, blocked: true }, v: e2.v });
ok(r.status === 200 && r.data.item.roles.D && r.data.item.roles.C.by === 'DongHyeon' && r.data.item.blocked, '파트 추가·막힘 표시, 기존 체크 기록 유지');

r = await dh('/roadmap/save', { item: { title: '파트 없음', area: 'seat', roles: {} } }); ok(r.status === 400, '파트 없는 카드 거부');
r = await dh('/roadmap/save', { item: { code: 'rsv-06', title: '매수 제한 Redis 키 정리', area: 'seat', roles: { B: { done: false }, A: { done: true } } } });
ok(r.status === 200 && r.data.item.code === 'RSV-06' && r.data.item.roles.A.by === 'DongHyeon', '할 일 추가 (처음부터 체크한 칸도 기록)');
const nw = r.data.item;

const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
r = await dh('/roadmap/note', { id: nw.id, text: '키 이름 purchase:{member} 로 맞춰 주세요', img: png, to: 'B' }); ok(r.status === 200, 'B에게 남길 말 + 사진');
r = await dh('/roadmap/note', { id: nw.id, text: 'x', to: 'Z' }); ok(r.status === 400, '없는 파트에게는 못 남김');
r = await anon('/roadmap');
ok(r.data.msgs.some((m) => m.to === 'B' && m.item === nw.id) && r.data.items.find((x) => x.id === nw.id).notesTo.B === 1, 'B 받은 말 목록·카드 말풍선 수');
r = await dh('/roadmap/item-log?id=' + nw.id);
const note = r.data.log.find((e) => e.act === 'note');
ok(note && note.to === 'B' && note.img, '카드 기록에 받는 파트');
r = await anon('/roadmap/img?id=' + note.img); ok(r.status === 401, '사진은 로그인해야 봄');
r = await dg('/roadmap/img?id=' + note.img); ok(r.status === 200 && r.ct === 'image/png', '사진 받기');
r = await dg('/roadmap/note-delete', { seq: note.seq }); ok(r.status === 403, '남의 말은 못 지움');
r = await dh('/roadmap/note-delete', { seq: note.seq }); ok(r.status === 200, '내 말 지우기');

r = await dh('/roadmap/delete', { id: nw.id }); ok(r.status === 200, '카드 삭제');
r = await anon('/roadmap');
ok(!r.data.items.some((x) => x.id === nw.id), '삭제 반영');
ok(r.data.log.some((e) => e.act === 'check' && e.who === 'DongGyun'), '최근 변경에 누가 체크했는지');

console.log(`\n${pass} ok, ${failN} fail`);
process.exit(failN ? 1 : 0);
