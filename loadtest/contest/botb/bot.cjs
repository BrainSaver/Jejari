#!/usr/bin/env node
// botb: 봇 대회 공격자 (방향 A). 실행 방법은 같은 폴더 README.md
'use strict';

const { chromium } = require('playwright');

// ------------------------------------------------------------ 설정

const env = process.env;
const BASE_URL = (env.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const RUN_ID = String(env.RUN_ID || '9001');
const TAG = env.TAG || '';
const MODE = env.MODE || 'all';                                     // signup | buy | all
const COUNT = clamp(Number(env.COUNT || 6), 1, 6);                  // 명의 수 (대회 상한 6)
const PARTY = clamp(Number(env.PARTY || 4), 1, 9);                  // 한 번에 잡을 인원
const STEP_MS = Math.max(200, Number(env.STEP_MS || 300));          // 버튼 사이 간격 (고정)
const GRADE = env.GRADE || '';                                      // GENERAL | FIRST | 비우면 아무거나
const CARD = env.CARD || '4111111111111111';                        // 가상 카드 (끝자리 1 = 승인)
const LEAD_MS = Number(env.LEAD_MS || 1500);                        // 판매 오픈 몇 ms 전부터 새로고침
const MAX_ROUNDS = Number(env.MAX_ROUNDS || 30);                    // 명의 하나의 최대 구매 시도
const HEADLESS = env.HEADLESS !== 'no';
const GROUP = 1;                                                    // 이 봇의 명의는 모두 한 묶음

if (!/^[a-z0-9]+$/.test(TAG)) die('TAG가 필요합니다 (영문 소문자·숫자). 예: TAG=t1118');
if (!['signup', 'buy', 'all'].includes(MODE)) die('MODE는 signup | buy | all');
if (!/^\d{12,19}$/.test(CARD) || !CARD.endsWith('1')) die('CARD는 12~19자리, 끝자리 1 (가상 카드)');
const host = new URL(BASE_URL).hostname;
if (!/^(localhost|127\.0\.0\.1|\[::1\]|host\.docker\.internal)$/.test(host) && env.I_OWN_THIS_TARGET !== 'yes') {
  die(`BASE_URL(${BASE_URL})이 로컬 주소가 아닙니다. 직접 띄운 대회 서버면 I_OWN_THIS_TARGET=yes`);
}

// ------------------------------------------------------------ 명의 (가짜)

const HANGUL = ['가', '나', '다', '라', '마', '바', '사', '아', '자', '차'];

function identity(n) {
  const t = parseInt(TAG, 36) % 1000;
  const eight = String(t).padStart(3, '0') + '7' + String(n).padStart(3, '0') + '1'; // 끝자리 1 (…0000 실패 번호 피함)
  return {
    n,
    name: '매크' + [...eight].map((d) => HANGUL[Number(d)]).join(''),
    phone: '010' + eight,
    email: `lt${TAG}.botb.g${GROUP}.${n}@loadtest.example.com`,
    password: `Mq7#${TAG}!Vx${n}pL`,
  };
}

// ------------------------------------------------------------ 공통

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const step = () => sleep(STEP_MS);
function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, Number.isFinite(v) ? v : lo)); }
function die(msg) { console.error('[botb] ' + msg); process.exit(2); }
const log = (who, msg) => console.log(`[botb ${new Date().toISOString().slice(11, 23)}] #${who} ${msg}`);

async function json(path) {
  const res = await fetch(BASE_URL + path, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`${path} → ${res.status}`);
  return res.json();
}

/** 화면 위 알림(role=alert) 글자. 없으면 '' */
async function alertText(page) {
  const a = page.locator('[role=alert]');
  return (await a.count()) ? (await a.first().innerText()).trim() : '';
}

async function click(page, locator) {
  await locator.click();
  await step();
}

// ------------------------------------------------------------ 가입

async function signup(ctx, id) {
  const page = await ctx.newPage();
  try {
    // 이미 만든 명의면 로그인이 된다 → 본인인증을 다시 쓰지 않고 건너뜀 (가입 제한 횟수를 아낌)
    if (await login(page, id).then(() => true, () => false)) return { ok: true, existed: true };
    await page.goto(`${BASE_URL}/signup`);
    await page.locator('#nm').fill(id.name); await step();
    await page.locator('#ph').fill(id.phone); await step();
    await click(page, page.getByRole('button', { name: '인증하기' }));
    await page.locator('#em2').or(page.locator('[role=alert]')).first().waitFor({ timeout: 15000 });
    if (!(await page.locator('#em2').count())) return { ok: false, why: await alertText(page) };
    await page.locator('#em2').fill(id.email); await step();
    await page.locator('#pw1').fill(id.password); await step();
    await page.locator('#pw2').fill(id.password); await step();
    await click(page, page.getByRole('button', { name: '가입하기' }));
    const done = await Promise.race([
      page.waitForURL(/\/login/, { timeout: 15000 }).then(() => 'ok'),
      page.getByText('이미 가입된 이메일').waitFor({ timeout: 15000 }).then(() => 'dup'),
      page.locator('[role=alert]').waitFor({ timeout: 15000 }).then(() => 'err'),
    ]).catch(() => 'timeout');
    if (done === 'ok') return { ok: true };
    if (done === 'dup') return { ok: true, existed: true };
    return { ok: false, why: done === 'err' ? await alertText(page) : '응답 없음' };
  } finally {
    await page.close();
  }
}

// ------------------------------------------------------------ 로그인

async function login(page, id) {
  await page.goto(`${BASE_URL}/login`);
  await page.locator('#em').fill(id.email); await step();
  await page.locator('#pw').fill(id.password); await step();
  await click(page, page.getByRole('button', { name: '로그인' }));
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 }).catch(() => {});
  if (new URL(page.url()).pathname.startsWith('/login')) throw new Error('로그인 실패: ' + (await alertText(page)));
}

// ------------------------------------------------------------ 구매 한 바퀴

/**
 * 검색 → 예매하기 → (대기열) → 좌석 → 결제. 결과:
 *   {bought: n}  결제까지 끝남
 *   {limit: true} 매수 제한 (인원 줄여 다시)
 *   {soldOut: true} 살 좌석이 없음
 *   {retry: why} 다시 처음부터
 */
async function round(page, who, run, party) {
  const q = new URLSearchParams({ from: run.from, to: run.to, date: run.date, time: '00:00', adult: String(party), child: '0', senior: '0' });
  if (GRADE) q.set('grade', GRADE);
  const searchUrl = `${BASE_URL}/?${q}`;

  // 1) 검색 화면에서 그 열차 카드의 [예매하기]. 안 보이면 같은 간격으로 새로고침
  let card;
  for (let i = 0; ; i++) {
    await page.goto(searchUrl);
    card = page.locator('article.ticket').filter({ hasText: run.trainNo }).first();
    await card.waitFor({ timeout: 10000 }).catch(() => {});
    if (await card.getByRole('button', { name: '예매하기' }).count()) break;
    if (Date.now() > run.closeAt) return { soldOut: true };
    if (i > 0 && i % 20 === 0) log(who, `예매하기 버튼 기다리는 중 (${i}번 새로고침)`);
    await step();
  }
  await click(page, card.getByRole('button', { name: '예매하기' }));

  // 2) 대기열: 좌석 화면으로 넘어갈 때까지 둔다 (화면이 알아서 폴링)
  await Promise.race([
    page.waitForURL(/\/runs\/\d+\/seats/, { timeout: 30 * 60 * 1000 }),
    page.getByRole('link', { name: '열차 다시 찾기' }).waitFor({ timeout: 30 * 60 * 1000 }),
  ]);
  if (!/\/seats/.test(page.url())) return { retry: '대기열: ' + (await alertText(page)) };

  // 3) 좌석: 호차를 앞에서부터 돌며 [나란히 빈 좌석 골라 주기] → [이 좌석 잡기]
  await page.locator('[role=tab]').first().waitFor({ timeout: 15000 });
  const tabs = page.locator('[role=tablist][aria-label="호차"] [role=tab]');
  const nTabs = await tabs.count();
  const holdBtn = page.getByRole('button', { name: '이 좌석 잡기' });
  const autoBtn = page.getByRole('button', { name: '나란히 빈 좌석 골라 주기' }).last();

  for (let tries = 0; tries < nTabs * 2; tries++) {
    if (/\/pay\//.test(page.url())) break;
    await click(page, tabs.nth(tries % nTabs));
    await click(page, autoBtn);
    if (!(await holdBtn.isEnabled().catch(() => false))) continue; // 이 호차엔 자리가 모자람 → 다음 호차
    await holdBtn.click();
    const r = await Promise.race([
      page.waitForURL(/\/pay\/\d+/, { timeout: 20000 }).then(() => 'pay'),
      page.locator('[role=alert], .alert').filter({ hasText: /먼저 잡았|매수|넘었|만료|다시/ }).first()
        .waitFor({ timeout: 20000 }).then(() => 'notice'),
    ]).catch(() => 'timeout');
    await step();
    if (r === 'pay') break;
    const text = (await page.locator('.alert').allInnerTexts()).join(' ');
    if (/매수/.test(text)) return { limit: true };
    if (/입장|만료|시간이 지났/.test(text)) return { retry: '입장 시간 지남' };
    // 뺏김 → 배치도가 새로 고쳐지므로 같은 순서를 다시
  }
  if (!/\/pay\//.test(page.url())) return { retry: '좌석을 못 잡음' };

  // 4) 결제: 가상 카드번호 → [결제하기]
  await page.locator('#cn').waitFor({ timeout: 15000 });
  await page.locator('#cn').fill(CARD); await step();
  await click(page, page.getByRole('button', { name: /결제하기/ }));
  await page.waitForURL(/\/pay\/\d+\/result/, { timeout: 30000 }).catch(() => {});
  if (!/\/result/.test(page.url())) return { retry: '결제: ' + (await alertText(page)) };
  return { bought: party, reservation: page.url().match(/\/pay\/(\d+)/)[1] };
}

// ------------------------------------------------------------ 명의 하나

async function buyer(browser, id, run) {
  const ctx = await browser.newContext({ locale: 'ko-KR', timezoneId: 'Asia/Seoul', viewport: { width: 1280, height: 860 } });
  const page = await ctx.newPage();
  const result = { n: id.n, email: id.email, seats: 0, reservations: [], errors: [] };
  try {
    await login(page, id);
    log(id.n, '로그인');

    // 판매 오픈 전이면 오픈 직전까지 기다린다 (모든 명의가 같은 순간 출발)
    const wait = run.openAt - LEAD_MS - Date.now();
    if (wait > 0) {
      log(id.n, `판매 오픈까지 ${Math.round(wait / 1000)}초 대기`);
      await sleep(wait);
    }

    let party = PARTY;
    for (let i = 0; i < MAX_ROUNDS && party > 0; i++) {
      let r;
      try {
        r = await round(page, id.n, run, party);
      } catch (e) {
        r = { retry: e.message.split('\n')[0] };
      }
      if (r.bought) {
        result.seats += r.bought;
        result.reservations.push(r.reservation);
        log(id.n, `${r.bought}석 결제 (예약 ${r.reservation}, 누적 ${result.seats}석)`);
      } else if (r.limit) {
        party -= 1;                                     // 남은 한도만큼 줄여 다시
        log(id.n, `매수 제한 → ${party}명으로 다시`);
      } else if (r.soldOut) {
        log(id.n, '판매 마감');
        break;
      } else {
        result.errors.push(r.retry);
        log(id.n, '다시: ' + r.retry);
      }
    }
  } catch (e) {
    result.errors.push(e.message.split('\n')[0]);
    log(id.n, '중단: ' + e.message.split('\n')[0]);
  } finally {
    await ctx.close();
  }
  return result;
}

// ------------------------------------------------------------ 시작

(async () => {
  const ids = Array.from({ length: COUNT }, (_, i) => identity(i + 1));
  const browser = await chromium.launch({ headless: HEADLESS });
  try {
    if (MODE === 'signup' || MODE === 'all') {
      for (const id of ids) {
        const ctx = await browser.newContext({ locale: 'ko-KR', timezoneId: 'Asia/Seoul' });
        const r = await signup(ctx, id).catch((e) => ({ ok: false, why: e.message.split('\n')[0] }));
        await ctx.close();
        log(id.n, r.ok ? (r.existed ? '이미 있음, 건너뜀' : '가입 ' + id.email) : '가입 실패: ' + r.why);
        if (!r.ok && /많|잠시/.test(r.why || '')) {
          console.log('[botb] 가입 제한에 걸렸습니다. 남은 명의는 잠시 뒤 MODE=signup으로 다시 (있는 명의는 건너뜀)');
          break;
        }
      }
    }
    if (MODE === 'buy' || MODE === 'all') {
      const d = await json(`/api/v1/runs/${RUN_ID}`);
      const stops = d.stops || [];
      const run = {
        trainNo: d.trainNo,
        date: d.runDate,
        from: env.FROM || d.fromStationId || stops[0]?.stationId,
        to: env.TO || d.toStationId || stops[stops.length - 1]?.stationId,
        openAt: d.saleOpenAt ? Date.parse(d.saleOpenAt + '+09:00') : 0,
        closeAt: d.saleCloseAt ? Date.parse(d.saleCloseAt + '+09:00') : Date.now() + 3 * 3600e3,
      };
      console.log(`[botb] 운행 ${RUN_ID} = ${run.trainNo} 열차 ${run.date} ${run.from}→${run.to}, 명의 ${COUNT}개 × ${PARTY}명, 간격 ${STEP_MS}ms`);
      const results = await Promise.all(ids.map((id) => buyer(browser, id, run)));
      console.log('\n[botb] 결과');
      for (const r of results) console.log(`  #${r.n} ${r.email}  결제 ${r.seats}석  예약 ${r.reservations.join(',') || '-'}  오류 ${r.errors.length}`);
      console.log(`  합계 ${results.reduce((s, r) => s + r.seats, 0)}석`);
    }
  } finally {
    await browser.close();
  }
})().catch((e) => die(e.stack || e.message));
