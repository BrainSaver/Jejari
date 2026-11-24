#!/usr/bin/env node
// 4단계 연습 봇 — 진짜 브라우저(Playwright)로 화면을 거치는 봇. botc(2차 대회에서 규칙을 다 피함)에서 한 가지씩 더 나아간다.
//
//   RUN_ID=<판매 중 운행> TAG=<새 태그> node loadtest/contest/practice/browser.cjs
//   평가: loadtest/tools/eval_contest.sh <TAG> <RUN_ID>
//
// 프로필 (botw = web browser):
//   botwsearch  botc + 열차 찾기를 눌러 검색 결과에서 [예매하기] (botc가 남긴 유일한 흔적 "조회 없이 대기열"을 지움)
//   botwtremor  botwsearch + 손떨림(1~3px) · 피츠 법칙 이동 시간 · 가끔 목표를 지나쳤다 되돌아옴 (녹화 모델이 볼 손 모양)
//   botwspread  botwsearch + 명의마다 다른 IP · 30분에 걸쳐 들어옴 (같은 IP 여러 명의 신호의 한계)
//   botwfast    botwtremor인데 좌석표 → 선점, 선점 → 결제를 1~1.5초에 (INHUMAN_STEP 1초 기준 바로 위)
//
// 환경 변수: BASE_URL(기본 http://localhost:8080) · RUN_ID(필수) · TAG(필수, 영문 소문자·숫자 7자)
//   PROFILES(쉼표, 기본 전부) · ACCOUNTS(프로필당 명의, 기본 3) · SEATS(명의당 좌석, 기본 2)
//   SPREAD_MIN(명의들이 들어오는 폭, 기본 3분) · SPREAD_LONG_MIN(botwspread, 기본 30분) · HEADLESS=0이면 창을 띄움
//   FROM·TO(기본 서울 3900023 → 부산 3900114)
// 앱은 신뢰 프록시 모드로: --jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1 (X-Forwarded-For로 IP를 나눔. run.mjs와 같음)
// 대회 봇(botb·botc)과 섞이지 않게 진행 화면에는 나오지 않습니다.
'use strict';
const crypto = require('crypto');
const { chromium } = require('playwright');

const BASE = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const RUN_ID = Number(process.env.RUN_ID);
const TAG = process.env.TAG || '';
const FROM = process.env.FROM || '3900023';
const TO = process.env.TO || '3900114';
const ACCOUNTS = Math.min(6, Math.max(1, Number(process.env.ACCOUNTS || 3)));
const SEATS = Math.min(4, Math.max(1, Number(process.env.SEATS || 2)));
const SPREAD_MIN = Math.max(0, Number(process.env.SPREAD_MIN ?? 3));
const SPREAD_LONG_MIN = Math.max(0, Number(process.env.SPREAD_LONG_MIN ?? 30));
const DOMAIN = 'loadtest.example.com';
if (!RUN_ID || !/^[a-z0-9]{1,7}$/.test(TAG)) {
  console.error('RUN_ID와 TAG(영문 소문자·숫자 7자 이내)가 필요합니다');
  process.exit(1);
}

const P = (kind, desc, o) => ({ kind, desc, search: true, tremor: false, ipEach: false, spreadMin: SPREAD_MIN, fast: false, ...o });
const PROFILES = [
  P('botwsearch', 'botc + 검색 결과에서 [예매하기]', {}),
  P('botwtremor', '+ 손떨림·피츠 이동 시간·지나쳤다 되돌아옴', { tremor: true }),
  P('botwspread', '+ 명의마다 다른 IP·30분 분산', { ipEach: true, spreadMin: SPREAD_LONG_MIN }),
  P('botwfast', '손떨림 + 좌석표 → 선점 · 선점 → 결제 1~1.5초', { tremor: true, fast: true }),
];
const want = (process.env.PROFILES || '').split(',').map((s) => s.trim()).filter(Boolean);
const chosen = want.length ? PROFILES.filter((p) => want.includes(p.kind)) : PROFILES;

// ── 도우미 ────────────────────────────────────────────────────────
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rand = (a, b) => a + Math.random() * (b - a);
const gauss = () => Math.sqrt(-2 * Math.log(1 - Math.random())) * Math.cos(2 * Math.PI * Math.random());
/** 사람 생각 시간: 짧은 쪽에 몰리고 가끔 김 (botc와 같음) */
const think = (minMs, maxMs) => sleep(Math.min(maxMs, minMs + Math.exp(rand(0, Math.log(maxMs - minMs + 1)))));
const log = (who, msg) => console.log(`${new Date().toISOString().slice(11, 19)} [${who}] ${msg}`);
const h = (s) => crypto.createHash('sha256').update(`${TAG}.${s}`).digest();
const fakeIp = (key) => { const b = h(key); return `10.${b[0]}.${b[1]}.${1 + (b[2] % 250)}`; };

function identity(kind, n) {
  const d = [...h(`${kind}.${n}`)].map((b) => b % 10).join('').slice(0, 7);
  return {
    name: '연습' + [...d.slice(0, 3)].map((x) => '가나다라마바사아자차'[Number(x)]).join(''),
    phone: `010${d}1`,
    email: `lt${TAG}.${kind}.g1.${n}@${DOMAIN}`,
    password: `Br4w!${TAG}#${kind.length}x${n}Qz`,
    card: '4' + [...h(`card.${kind}.${n}`)].slice(0, 14).map((x) => x % 10).join('') + '1',
  };
}

async function api(ip, path, body) {
  const res = await fetch(BASE + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': ip },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null;
  try { json = await res.json(); } catch { /* 본문 없음 */ }
  return { status: res.status, data: json };
}

async function signup(ip, id) {
  const v = await api(ip, '/api/v1/auth/verify', { name: id.name, phone: id.phone, provider: 'MOCK_PASS' });
  if (v.status !== 200) throw new Error(`본인인증 ${v.status} ${v.data?.code || ''}`);
  const s = await api(ip, '/api/v1/auth/signup', { verificationToken: v.data.verificationToken, email: id.email, password: id.password });
  if (![200, 201].includes(s.status) && s.data?.code !== 'AUTH_EMAIL_DUPLICATED') throw new Error(`가입 ${s.status} ${s.data?.code || ''}`);
}

// ── 마우스 ────────────────────────────────────────────────────────
/** botc 방식: 베지어 곡선 + 가속·감속, 매끈함 (손떨림 없음) */
async function moveSmooth(page, from, to) {
  const c1 = { x: from.x + (to.x - from.x) * 0.3 + rand(-80, 80), y: from.y + (to.y - from.y) * 0.3 + rand(-80, 80) };
  const c2 = { x: from.x + (to.x - from.x) * 0.7 + rand(-40, 40), y: from.y + (to.y - from.y) * 0.7 + rand(-40, 40) };
  const steps = Math.round(rand(18, 40));
  for (let s = 1; s <= steps; s++) {
    const t = s / steps;
    const e = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
    const u = 1 - e;
    await page.mouse.move(u ** 3 * from.x + 3 * u * u * e * c1.x + 3 * u * e * e * c2.x + e ** 3 * to.x,
      u ** 3 * from.y + 3 * u * u * e * c1.y + 3 * u * e * e * c2.y + e ** 3 * to.y);
    await sleep(rand(4, 16));
  }
}

/** 사람 손 흉내: 피츠 법칙 이동 시간 + 최소 저크(빨라졌다 느려짐) + 손떨림(이어지는 작은 흔들림) + 가끔 지나쳤다 되돌아옴 */
async function moveHuman(page, from, to, width) {
  const D = Math.hypot(to.x - from.x, to.y - from.y);
  const dur = 220 + 140 * Math.log2(D / Math.max(8, width) + 1) + rand(0, 160); // ms
  const over = Math.random() < 0.25 && D > 120;
  const aim = over ? { x: to.x + (to.x - from.x) / D * rand(6, 14), y: to.y + (to.y - from.y) / D * rand(6, 14) } : to;
  const bend = rand(-0.18, 0.18) * D;
  const c = { x: (from.x + aim.x) / 2 - (aim.y - from.y) / Math.max(1, D) * bend, y: (from.y + aim.y) / 2 + (aim.x - from.x) / Math.max(1, D) * bend };
  let jx = 0, jy = 0;
  const t0 = Date.now();
  for (;;) {
    const u = Math.min(1, (Date.now() - t0) / dur);
    const s = u * u * u * (10 - 15 * u + 6 * u * u);
    jx = jx * 0.7 + gauss() * 0.9; // 이어지는 떨림 (1~3px)
    jy = jy * 0.7 + gauss() * 0.9;
    const x = (1 - s) ** 2 * from.x + 2 * (1 - s) * s * c.x + s * s * aim.x + jx;
    const y = (1 - s) ** 2 * from.y + 2 * (1 - s) * s * c.y + s * s * aim.y + jy;
    await page.mouse.move(x, y);
    if (u >= 1) break;
    await sleep(rand(12, 20));
  }
  if (over) { // 지나친 만큼 짧게 되돌아옴
    await sleep(rand(40, 110));
    const back = { x: aim.x, y: aim.y };
    const n = Math.round(rand(5, 9));
    for (let k = 1; k <= n; k++) {
      const s = k / n;
      await page.mouse.move(back.x + (to.x - back.x) * s + gauss() * 0.6, back.y + (to.y - back.y) * s + gauss() * 0.6);
      await sleep(rand(14, 24));
    }
  }
}

async function click(page, p, locator) {
  if (locator.scrollIntoViewIfNeeded) await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  if (!box) throw new Error('누를 요소가 화면에 없음');
  const to = { x: box.x + box.width * rand(0.3, 0.7), y: box.y + box.height * rand(0.3, 0.7) };
  const from = page.__mouse || { x: rand(100, 600), y: rand(100, 500) };
  if (p.tremor) await moveHuman(page, from, to, box.width); else await moveSmooth(page, from, to);
  page.__mouse = to;
  await sleep(p.fast ? rand(40, 90) : rand(80, 260)); // 도착 뒤 멈췄다 누름
  await page.mouse.down();
  await sleep(rand(40, 120));
  await page.mouse.up();
}

async function type(page, p, locator, text) {
  await click(page, p, locator);
  for (const ch of text) { await page.keyboard.type(ch); await sleep(rand(60, 240)); }
}

// ── 한 명의 흐름 ──────────────────────────────────────────────────
async function oneAccount(browser, p, id, n, ip, run) {
  const who = `${p.kind}#${n}`;
  const ctx = await browser.newContext({
    viewport: { width: Math.round(rand(1280, 1600)), height: Math.round(rand(800, 1000)) },
    locale: 'ko-KR', timezoneId: 'Asia/Seoul', extraHTTPHeaders: { 'X-Forwarded-For': ip },
  });
  const page = await ctx.newPage();
  const r = { seats: 0, error: null };
  try {
    await page.goto(`${BASE}/login`);
    await think(800, 3000);
    await type(page, p, page.locator('#em'), id.email);
    await think(300, 1200);
    await type(page, p, page.locator('#pw'), id.password);
    await think(300, 1000);
    await click(page, p, page.getByRole('button', { name: '로그인' }));
    await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 30_000 });

    // 검색: 주소의 출발·도착·날짜로 화면이 열차를 찾음(VIEW_TRAIN) → 그 열차 카드의 [예매하기]
    await page.goto(`${BASE}/?from=${FROM}&to=${TO}&date=${run.runDate}&time=00:00&adult=${SEATS}`);
    const card = page.locator('article.ticket', { hasText: String(run.trainNo) }).first();
    await card.waitFor({ timeout: 30_000 });
    await think(1500, 6000);
    await click(page, p, card.getByRole('button', { name: '예매하기' }));
    await page.waitForURL(new RegExp(`/runs/${RUN_ID}/seats`), { timeout: 20 * 60_000 });

    // 좌석
    await page.locator('button.seat:visible').first().waitFor({ timeout: 30_000 });
    let held = false;
    for (let attempt = 0; attempt < 2 && !held; attempt++) {
      if (!p.fast) await think(1200, 4000);
      const tabs = page.getByRole('tab');
      const nTabs = await tabs.count();
      if (nTabs > 1 && !p.fast) { await click(page, p, tabs.nth(1 + Math.floor(Math.random() * (nTabs - 1)))); await think(800, 3000); }
      const free = page.locator('button.seat.free:visible');
      const total = await free.count();
      if (total < SEATS) continue;
      const picks = new Set();
      while (picks.size < SEATS) picks.add(Math.floor(Math.random() * total));
      const handles = await free.elementHandles();
      const t0 = Date.now();
      for (const k of [...picks].sort((a, b) => a - b)) { await click(page, p, handles[k]); if (!p.fast) await think(300, 2000); }
      if (p.fast) await sleep(Math.max(0, rand(1000, 1500) - (Date.now() - t0) - 400)); // 좌석표 → 선점 1~1.5초
      else await think(500, 2500);
      await click(page, p, page.getByRole('button', { name: '이 좌석 잡기' }));
      try { await page.waitForURL(/\/pay\//, { timeout: 15_000 }); held = true; } catch {
        const mine = page.locator('button.seat.mine:visible');
        for (let k = (await mine.count()) - 1; k >= 0; k--) await click(page, p, mine.nth(k));
      }
    }
    if (!held) throw new Error('좌석을 잡지 못함');

    // 결제
    const cn = page.locator('#cn');
    await cn.waitFor({ timeout: 15_000 });
    if (p.fast) { // 선점 → 결제 1~1.5초: 카드는 붙여넣기처럼 한 번에
      const t0 = Date.now();
      await cn.fill(id.card);
      await sleep(Math.max(0, rand(1000, 1500) - (Date.now() - t0) - 450));
    } else {
      await think(2000, 7000);
      await cn.fill('');
      await type(page, p, cn, id.card);
      await think(600, 2500);
    }
    await click(page, p, page.getByRole('button', { name: /결제하기$/ }));
    await page.waitForURL(/\/result/, { timeout: 60_000 });
    r.seats = SEATS;
    log(who, `결제 ${SEATS}석`);
  } catch (e) {
    r.error = e.message.split('\n')[0];
    log(who, `중단: ${r.error}`);
  } finally {
    await ctx.close();
  }
  return r;
}

(async () => {
  const run = (await api('127.0.0.1', `/api/v1/runs/${RUN_ID}?from=${FROM}&to=${TO}`)).data;
  if (!run?.runDate) { console.error(`운행 ${RUN_ID}을 찾지 못했습니다`); process.exit(1); }
  console.log(`4단계 연습 봇 ${chosen.length}종 × 명의 ${ACCOUNTS} × ${SEATS}석 · 운행 ${RUN_ID} (${run.runDate}, ${run.trainNo} 열차) · 태그 ${TAG}`);
  const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
  const t0 = Date.now();
  const done = await Promise.all(chosen.map(async (p) => {
    const ids = Array.from({ length: ACCOUNTS }, (_, i) => identity(p.kind, i + 1));
    const ipOf = (n) => fakeIp(p.ipEach ? `${p.kind}.${n}` : p.kind);
    for (const [i, id] of ids.entries()) await signup(ipOf(i + 1), id);
    const gap = ACCOUNTS > 1 ? (p.spreadMin * 60_000) / ACCOUNTS : 0;
    const res = await Promise.all(ids.map(async (id, i) => {
      await sleep(Math.round(i * gap + rand(0, gap * 0.5)));
      return oneAccount(browser, p, id, i + 1, ipOf(i + 1), run);
    }));
    return [p, res];
  }));
  await browser.close();
  console.log(`\n끝 (${Math.round((Date.now() - t0) / 1000)}초)\n종류          결제 좌석  오류`);
  for (const [p, res] of done) {
    const errs = [...new Set(res.map((x) => x.error).filter(Boolean))];
    console.log(`${p.kind.padEnd(13)} ${String(res.reduce((a, x) => a + x.seats, 0)).padStart(5)}    ${errs.slice(0, 2).join(' / ') || '-'}`);
  }
  console.log(`\n평가: loadtest/tools/eval_contest.sh ${TAG} ${RUN_ID}`);
})().catch((e) => { console.error(e); process.exit(1); });
