#!/usr/bin/env node
// 봇 대회 · botc (강동균) — "규칙을 아는 공격자" 부류. 브라우저 봇 (Playwright).
//
// 수법은 대회가 끝난 뒤 README에 공개한다. 그 전에는 다른 공격자가 이 파일을 보지 않는 것이 규칙이다.
//
// 실행 (Node 18+, Playwright 필요):
//   RUN_ID=<대회 운행> BASE_URL=https://… node loadtest/contest/botc/bot.js
// 환경 변수:
//   MODE       all(기본: 가입 후 구매) · signup(명의만 미리 만들기) · buy(이미 만든 명의로 구매만)
//              가입은 IP당 1시간에 명의 5개까지라(rate:signup), 대회 전날 MODE=signup으로 미리 만들어 둔다
//   RUN_ID     대회 운행 ID (구매할 때 필수)
//   BASE_URL   기본 http://localhost:8080
//   FROM, TO   구간 역 코드 (기본 서울 3900023 → 부산 3900114)
//   TAG        실행 태그 (영문 소문자·숫자, 기본 무작위). 평가: loadtest/tools/eval_contest.sh <TAG> <RUN_ID>
//   ACCOUNTS   명의 수 (기본 6 = 대회 상한)
//   SEATS      명의당 좌석 (기본 4 = 열차당 한도)
//   SPREAD_MIN 계정들이 들어오는 시간 폭(분, 기본 10)
//   HEADLESS   0이면 창을 띄움
//   SHOT_DIR   실패한 화면을 저장할 폴더 (원인 찾기용)
const { chromium } = require('playwright');

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const RUN_ID = Number(process.env.RUN_ID);
const FROM = process.env.FROM || '3900023';
const TO = process.env.TO || '3900114';
const TAG = process.env.TAG || Math.random().toString(36).slice(2, 6);
const ACCOUNTS = Math.min(6, Math.max(1, Number(process.env.ACCOUNTS || 6)));
const SEATS = Math.min(4, Math.max(1, Number(process.env.SEATS || 4)));
const SPREAD_MIN = Math.max(0, Number(process.env.SPREAD_MIN ?? 10));
const KIND = 'botc';
const MODE = process.env.MODE || 'all';
const DOMAIN = 'loadtest.example.com';
const HANGUL = ['가', '나', '다', '라', '마', '바', '사', '아', '자', '차'];

if (!['all', 'signup', 'buy'].includes(MODE)) {
  console.error('MODE는 all · signup · buy');
  process.exit(1);
}
if (MODE !== 'signup' && (!Number.isInteger(RUN_ID) || RUN_ID <= 0)) {
  console.error('RUN_ID가 필요합니다 (loadtest/contest/setup.sh가 출력한 대회 운행 ID)');
  process.exit(1);
}
if (!/^[a-z0-9]{1,8}$/.test(TAG)) {
  console.error('TAG는 영문 소문자·숫자 1~8자');
  process.exit(1);
}

// ------------------------------------------------------------ 도우미

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rand = (a, b) => a + Math.random() * (b - a);
/** 사람의 쉬는 시간은 짧은 쪽에 몰리고 가끔 길다 (로그 정규 분포 비슷하게) */
const think = (minMs, maxMs) => sleep(Math.min(maxMs, minMs + Math.exp(rand(0, Math.log(maxMs - minMs + 1)))));
const log = (who, msg) => console.log(`${new Date().toISOString().slice(11, 19)} [${who}] ${msg}`);

/** 실행·번호 → 가짜 신원 (loadtest/lib/auth.js identity와 같은 모양, 번호 대역만 다르게) */
function identity(i) {
  const t = Number.parseInt(TAG, 36) % 1000;
  const eight = String(t).padStart(3, '0') + String(7000 + i).padStart(4, '0') + '1';
  return {
    name: '테스트' + [...eight].map((d) => HANGUL[Number(d)]).join(''),
    phone: `010${eight}`,
    email: `lt${TAG}.${KIND}.g0.${i}@${DOMAIN}`,
    password: `Zq9!${TAG}Rt#${i}vL2m`,
    // 명의마다 다른 가상 카드. 끝자리 1 = 승인 (0 거절 · 9 결과 모름 · 8 늦은 승인)
    card: `5${String(t).padStart(3, '0')}${String(Date.now() % 1e6).padStart(6, '0')}${String(i).padStart(5, '0')}1`,
  };
}

async function api(path, body) {
  const res = await fetch(BASE_URL + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 본문 없음 */
  }
  if (!res.ok) {
    const e = new Error(`${path} → ${res.status} ${json?.code || text.slice(0, 120)}`);
    e.status = res.status;
    throw e;
  }
  return json;
}

/** 모의 본인인증 → 가입. 명의 하나 = 계정 하나 */
async function createAccount(id) {
  // 이미 만든 명의면 건너뛴다 (로그인이 되면 있는 것). 같은 TAG로 다시 돌려도 안전하게
  try {
    await api('/api/v1/auth/login', { email: id.email, password: id.password });
    return 'exists';
  } catch {
    /* 없음 → 만든다 */
  }
  try {
    const v = await api('/api/v1/auth/verify', { name: id.name, phone: id.phone, provider: 'MOCK_PASS' });
    await api('/api/v1/auth/signup', { verificationToken: v.verificationToken, email: id.email, password: id.password });
    return 'created';
  } catch (e) {
    if (e.status === 429) {
      throw new Error('가입 속도 제한 (IP당 1시간에 명의 5개). 한 시간 뒤 같은 TAG로 MODE=signup을 다시 돌리면 이어서 만든다');
    }
    throw e;
  }
}

/** 곡선으로 다가가 누른다. 직선·순간이동 대신 중간점 두 개를 지나는 베지어 곡선 + 가감속 */
async function humanClick(page, locator) {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  if (!box) throw new Error('누를 요소가 화면에 없음');
  const to = { x: box.x + box.width * rand(0.3, 0.7), y: box.y + box.height * rand(0.3, 0.7) };
  const from = page.__mouse || { x: rand(100, 600), y: rand(100, 500) };
  const c1 = { x: from.x + (to.x - from.x) * 0.3 + rand(-80, 80), y: from.y + (to.y - from.y) * 0.3 + rand(-80, 80) };
  const c2 = { x: from.x + (to.x - from.x) * 0.7 + rand(-40, 40), y: from.y + (to.y - from.y) * 0.7 + rand(-40, 40) };
  const steps = Math.round(rand(18, 40));
  for (let s = 1; s <= steps; s++) {
    const t = s / steps;
    const e = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2; // 가속 → 감속
    const u = 1 - e;
    const x = u ** 3 * from.x + 3 * u * u * e * c1.x + 3 * u * e * e * c2.x + e ** 3 * to.x;
    const y = u ** 3 * from.y + 3 * u * u * e * c1.y + 3 * u * e * e * c2.y + e ** 3 * to.y;
    await page.mouse.move(x, y);
    await sleep(rand(4, 16));
  }
  page.__mouse = to;
  await sleep(rand(60, 220));
  await page.mouse.down();
  await sleep(rand(40, 120));
  await page.mouse.up();
}

async function humanType(page, locator, text) {
  await humanClick(page, locator);
  for (const ch of text) {
    await page.keyboard.type(ch);
    await sleep(rand(60, 260));
  }
}

// ------------------------------------------------------------ 한 명의 흐름

async function oneAccount(browser, id, i) {
  const who = `${KIND}#${i}`;
  const ctx = await browser.newContext({
    viewport: { width: Math.round(rand(1280, 1600)), height: Math.round(rand(800, 1000)) },
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
  });
  const page = await ctx.newPage();
  const result = { account: i, seats: 0, error: null };
  try {
    // 1. 로그인
    await page.goto(`${BASE_URL}/login`);
    await think(800, 4000);
    await humanType(page, page.locator('#em'), id.email);
    await think(300, 1500);
    await humanType(page, page.locator('#pw'), id.password);
    await think(300, 1200);
    await humanClick(page, page.getByRole('button', { name: '로그인' }));
    await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 30_000 });
    log(who, '로그인');

    // 2. 운행 화면을 잠깐 보고 (사람처럼) 대기열로
    await page.goto(`${BASE_URL}/?from=${FROM}&to=${TO}`);
    await think(1500, 7000);
    await page.goto(`${BASE_URL}/queue/${RUN_ID}?from=${FROM}&to=${TO}`);
    log(who, '대기열 진입');
    await page.waitForURL(new RegExp(`/runs/${RUN_ID}/seats`), { timeout: 20 * 60_000 });
    log(who, '입장');

    // 3. 좌석: 인원 → 호차 하나를 골라 흩어진 빈 좌석 → 잡기. 실패하면 다른 좌석으로 한 번만 더
    await page.locator('button.seat:visible').first().waitFor({ timeout: 30_000 });
    await think(1200, 5000);
    for (let n = 1; n < SEATS; n++) {
      await humanClick(page, page.getByRole('button', { name: '어른 한 명 더하기' }));
      await think(250, 1200);
    }
    let held = false;
    for (let attempt = 0; attempt < 2 && !held; attempt++) {
      const tabs = page.getByRole('tab');
      const nTabs = await tabs.count();
      if (nTabs > 1) {
        await humanClick(page, tabs.nth(1 + Math.floor(Math.random() * (nTabs - 1)))); // 1호차(특실) 말고 아무 호차
        await think(800, 3500);
      }
      const free = page.locator('button.seat.free:visible');
      const total = await free.count();
      if (total < SEATS) continue;
      const picks = new Set();
      while (picks.size < SEATS) picks.add(Math.floor(Math.random() * total));
      for (const k of [...picks].sort((a, b) => a - b)) {
        await humanClick(page, free.nth(k));
        await think(300, 2500);
      }
      await think(500, 3000);
      await humanClick(page, page.getByRole('button', { name: '이 좌석 잡기' }));
      try {
        await page.waitForURL(/\/pay\//, { timeout: 15_000 });
        held = true;
      } catch {
        log(who, `좌석 잡기 실패 (${attempt + 1}번째) — 다른 좌석으로`);
        const picked = page.locator('button.seat.mine:visible');
        for (let k = (await picked.count()) - 1; k >= 0; k--) await humanClick(page, picked.nth(k));
        await think(1500, 6000);
      }
    }
    if (!held) throw new Error('좌석을 잡지 못함');
    log(who, '좌석 선점');

    // 4. 결제: 명의마다 다른 카드
    await think(2000, 8000);
    const cn = page.locator('#cn');
    await cn.fill('');
    await humanType(page, cn, id.card);
    await think(600, 3000);
    await humanClick(page, page.getByRole('button', { name: /결제하기$/ }));
    await page.waitForURL(/\/result/, { timeout: 60_000 });
    result.seats = SEATS;
    log(who, `결제 완료 ${SEATS}석`);
  } catch (e) {
    result.error = e.message.split('\n')[0];
    log(who, `중단: ${result.error}`);
    if (process.env.SHOT_DIR) {
      await page.screenshot({ path: `${process.env.SHOT_DIR}/${KIND}-${TAG}-${i}.png`, fullPage: true }).catch(() => {});
    }
  } finally {
    await ctx.close();
  }
  return result;
}

// ------------------------------------------------------------ 실행

(async () => {
  console.log(`botc · ${MODE} · 태그 ${TAG} · 운행 ${RUN_ID || '-'} · 명의 ${ACCOUNTS} × ${SEATS}석 · ${SPREAD_MIN}분에 걸쳐`);
  const ids = Array.from({ length: ACCOUNTS }, (_, i) => identity(i));

  if (MODE !== 'buy') {
    for (const [i, id] of ids.entries()) {
      const r = await createAccount(id);
      log(`${KIND}#${i}`, r === 'exists' ? '이미 있음' : '가입');
      if (r === 'created') await think(2000, 9000);
    }
    if (MODE === 'signup') {
      console.log(`\n명의 ${ACCOUNTS}개 준비 끝. 대회 날: MODE=buy TAG=${TAG} RUN_ID=<운행> node loadtest/contest/botc/bot.js`);
      return;
    }
  }

  // 계정마다 들어오는 시각을 흩는다. 서로 최소 75초 떨어지게 (몇 초 안에 몰리지 않게)
  const span = SPREAD_MIN * 60_000;
  const gap = ACCOUNTS > 1 ? Math.max(75_000, span / ACCOUNTS) : 0;
  const starts = ids.map((_, i) => Math.round(i * gap + rand(0, Math.max(0, gap - 75_000))));

  const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
  const results = await Promise.all(
    ids.map(async (id, i) => {
      await sleep(starts[i]);
      return oneAccount(browser, id, i);
    }),
  );
  await browser.close();

  const seats = results.reduce((a, r) => a + r.seats, 0);
  console.log(`\n끝. 확보 ${seats}석 / 목표 ${ACCOUNTS * SEATS}석`);
  for (const r of results) console.log(`  #${r.account}: ${r.seats}석${r.error ? ` (${r.error})` : ''}`);
  console.log(`평가: loadtest/tools/eval_contest.sh ${TAG} ${RUN_ID}`);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
