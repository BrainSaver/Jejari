// 로드맵 API 흐름 검사: node test/roadmap-test.mjs http://127.0.0.1:8790  (pages dev에 --d1 AI_DB 필요)
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
ok(r.status === 200 && r.data.items.length > 50, `로그인 없이 보기 (항목 ${r.data.items.length}개, 기능설계서에서 시작)`);
const rsv = r.data.items.find((x) => x.code === 'RSV-02');
const que = r.data.items.find((x) => x.code === 'QUE-04');
ok(rsv && rsv.deps.includes(que.id), 'RSV-02가 QUE-04를 기다림 (파트 간 의존)');
r = await anon('/roadmap/save', { item: { ...rsv, status: 'doing' }, v: rsv.v }); ok(r.status === 401, '로그인 안 하면 수정 불가');

r = await dg('/roadmap/save', { item: { ...rsv, status: 'doing' }, v: rsv.v });
ok(r.status === 200 && r.data.item.status === 'doing' && r.data.item.v === rsv.v + 1, '동균이 진행 중으로');
r = await dh('/roadmap/save', { item: { ...rsv, title: '옛 버전으로 고침' }, v: rsv.v });
ok(r.status === 409 && r.data.error === 'STALE' && r.data.current.status === 'doing', '같은 항목을 옛 버전으로 고치면 충돌');
const cur = r.data.current;
r = await dh('/roadmap/save', { item: { ...cur, status: 'done' }, v: cur.v });
ok(r.status === 200 && r.data.item.doneBy === 'DongHyeon' && r.data.item.doneAt, '완료하면 완료한 사람·시각 기록');
r = await dh('/roadmap/save', { item: { ...r.data.item, doneBy: 'DongGyun' }, v: r.data.item.v });
ok(r.status === 200 && r.data.item.doneBy === 'DongGyun' && r.data.item.status === 'done', '완료한 사람 바꾸기');
r = await dh('/roadmap/save', { item: { ...r.data.item, status: 'todo' }, v: r.data.item.v });
ok(r.status === 200 && r.data.item.doneBy === null, '완료를 풀면 완료자 지움');

r = await dh('/roadmap/save', { item: { code: 'x y', title: 't', part: 'C', status: 'todo' } }); ok(r.status === 400, '코드에 띄어쓰기 거부');
r = await dh('/roadmap/save', { item: { code: 'pay-06', title: '새 작업', part: 'C', owner: 'DongHyeon', status: 'todo', deps: [rsv.id, 'nope'] } });
ok(r.status === 200 && r.data.item.code === 'PAY-06' && r.data.item.deps.length === 1, '새 항목 추가 (없는 선행 작업은 버림)');
const nw = r.data.item;

const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
r = await dh('/roadmap/note', { id: nw.id, text: '결제 시작 API 초안 올림', img: png }); ok(r.status === 200, '진행 메모 + 사진');
r = await dh('/roadmap/note', { id: nw.id, img: 'data:text/html;base64,AAAA' }); ok(r.status === 400, '사진 아닌 것 거부');
r = await dh('/roadmap/item-log?id=' + nw.id);
const note = r.data.log.find((e) => e.act === 'note');
ok(note && note.img && note.text.includes('초안'), '항목 기록에 메모');
r = await anon('/roadmap/img?id=' + note.img); ok(r.status === 401, '사진은 로그인해야 봄');
r = await dg('/roadmap/img?id=' + note.img); ok(r.status === 200 && r.ct === 'image/png' && r.data.byteLength > 50, '사진 받기');
r = await dg('/roadmap/note-delete', { seq: note.seq }); ok(r.status === 403, '남의 메모는 못 지움');
const before = (await anon('/roadmap/rev')).data.rev;
r = await dh('/roadmap/note-delete', { seq: note.seq }); ok(r.status === 200, '내 메모 지우기');
ok((await anon('/roadmap/rev')).data.rev !== before, '지운 것도 rev가 바뀌어 다른 화면이 새로 불러옴');

r = await dh('/roadmap/delete', { id: rsv.id }); ok(r.status === 200, '항목 삭제');
r = await anon('/roadmap');
ok(!r.data.items.some((x) => x.id === rsv.id) && !r.data.items.some((x) => (x.deps || []).includes(rsv.id)), '삭제하면 다른 항목의 선행 작업에서도 빠짐');
ok(r.data.log.some((e) => e.act === 'delete' && e.who === 'DongHyeon') && r.data.log.some((e) => e.act === 'status' && e.who === 'DongGyun'), '최근 변경에 누가 무엇을');
ok(r.data.items.find((x) => x.id === nw.id).notes === 0, '메모 수 집계');

console.log(`\n${pass} ok, ${failN} fail`);
process.exit(failN ? 1 : 0);
