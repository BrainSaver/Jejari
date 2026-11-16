// 시나리오 3. 순번 조정 정확도. 위험 점수가 높은 계정(봇)이 먼저 줄을 서도 정상 사용자보다 뒤에 입장하는지 본다.
//
// 실행: ./run-penalty-order.sh   (준비·입장 시작 시점 조절까지 한 번에 한다)
//
// 봇 20명이 먼저 줄을 서고 3초 뒤 정상 사용자 100명이 줄을 선다. 입장 처리는 줄이 다 찬 뒤에 시작한다
// (오픈 직후 한꺼번에 몰리는 상황). 패널티가 맞게 적용되면 먼저 온 봇도 정상 사용자가 모두 입장한 뒤에야
// 입장해야 한다 (패널티 80초 > 정상 사용자 100명이 입장하는 시간).
//
// 입장 처리를 미리 시작하면 줄에 봇밖에 없어서 그대로 입장한다. 패널티는 "다른 사람보다 뒤"로 보낼 뿐
// 혼자 있는 사람을 붙잡아 두지 않는다 (설계 의도).
import { check } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';
import { enter, me, leave, waitUntilAdmitted, parse, preflight } from './lib/queue.js';

const BOTS = Number(__ENV.BOTS || 20);
const NORMALS = Number(__ENV.NORMALS || 100);
const BOT_BASE = Number(__ENV.BOT_BASE || 3000000);
const NORMAL_BASE = Number(__ENV.NORMAL_BASE || 3100000);
const MAX_WAIT_MS = Number(__ENV.MAX_WAIT_MS || 180000);

const admitWait = new Trend('admit_wait_ms', true);
const botAfterNormals = new Rate('bot_admitted_after_all_normals');   // 목표 100%
const normalAheadOfBots = new Rate('normal_admitted_before_bots');    // 목표 100%
const botRankAtEntry = new Trend('bot_rank_at_entry');
const normalRankAtEntry = new Trend('normal_rank_at_entry');

// 마지막으로 줄 선 정상 사용자 = 정상 사용자 중 가장 늦게 입장하는 사람 (패널티가 같으면 진입 순서대로 입장)
const LAST_NORMAL = NORMAL_BASE + NORMALS - 1;

export const options = {
  scenarios: {
    bots: {
      executor: 'shared-iterations',
      exec: 'bot',
      vus: BOTS,
      iterations: BOTS,
      startTime: '0s',
      maxDuration: '10m',
    },
    normals: {
      executor: 'shared-iterations',
      exec: 'normal',
      vus: NORMALS,
      iterations: NORMALS,
      startTime: '3s',          // 봇이 먼저 줄을 선 뒤에 정상 사용자가 온다
      maxDuration: '10m',
    },
  },
  thresholds: {
    bot_admitted_after_all_normals: ['rate==1'],
    normal_admitted_before_bots: ['rate==1'],
    // 서브 지표를 만들어 두기 위한 항목 (handleSummary에서 비교)
    'admit_wait_ms{group:bot}': ['min>=0'],
    'admit_wait_ms{group:normal}': ['min>=0'],
  },
};

// 시작 전에 앱·Redis 상태를 점검한다 (실패하면 이유를 알려 주고 중단)
export function setup() {
  preflight();
}

export function bot() {
  const id = BOT_BASE + exec.scenario.iterationInTest;
  const res = enter(id);
  if (!check(res, { '봇 진입 200': (r) => r.status === 200 })) return;
  botRankAtEntry.add(parse(res).rank);

  const t0 = Date.now();
  const w = waitUntilAdmitted(id, res, MAX_WAIT_MS);
  if (!w.admitted) return;
  admitWait.add(Date.now() - t0, { group: 'bot' });

  // 봇이 입장한 지금, 가장 늦게 서는 정상 사용자도 이미 입장해 있어야 한다
  const last = me(LAST_NORMAL);
  botAfterNormals.add(last.status === 200 && parse(last).state === 'ADMITTED');
  leave(id);
}

export function normal() {
  const id = NORMAL_BASE + exec.scenario.iterationInTest;
  const res = enter(id);
  if (!check(res, { '정상 진입 200': (r) => r.status === 200 })) return;
  normalRankAtEntry.add(parse(res).rank);

  const t0 = Date.now();
  const w = waitUntilAdmitted(id, res, MAX_WAIT_MS);
  if (!w.admitted) return;
  admitWait.add(Date.now() - t0, { group: 'normal' });

  // 정상 사용자가 입장한 지금, 봇은 아직 대기 중이어야 한다 (첫 번째 봇으로 확인)
  const bot0 = me(BOT_BASE);
  normalAheadOfBots.add(bot0.status === 200 && parse(bot0).state === 'WAITING');
}

export function handleSummary(data) {
  const g = (name) => (data.metrics[name] && data.metrics[name].values) || {};
  const bot = g('admit_wait_ms{group:bot}');
  const normal = g('admit_wait_ms{group:normal}');
  const lines = [
    '',
    '=== 순번 조정 결과 ===',
    `정상 사용자 입장까지: 최소 ${fmt(normal.min)}  평균 ${fmt(normal.avg)}  최대 ${fmt(normal.max)}`,
    `봇(위험 점수)  입장까지: 최소 ${fmt(bot.min)}  평균 ${fmt(bot.avg)}  최대 ${fmt(bot.max)}`,
    `봇이 모든 정상 사용자 뒤에 입장한 비율: ${pct(g('bot_admitted_after_all_normals').rate)}  (목표 100%)`,
    `정상 사용자가 입장할 때 봇이 대기 중이던 비율: ${pct(g('normal_admitted_before_bots').rate)}  (목표 100%)`,
    bot.min !== undefined && normal.max !== undefined
      ? (bot.min >= normal.max
        ? '판정: 봇이 모두 정상 사용자보다 뒤에 입장 (패널티 적용됨)'
        : '판정: 일부 봇이 정상 사용자보다 먼저 입장 (패널티 미적용 또는 부족)')
      : '판정: 입장한 사람이 없어 비교할 수 없음',
    '',
  ];
  return { stdout: lines.join('\n') };
}

function pct(v) {
  return v === undefined ? '-' : `${(v * 100).toFixed(1)}%`;
}

function fmt(v) {
  return v === undefined ? '-' : `${(v / 1000).toFixed(1)}s`;
}
