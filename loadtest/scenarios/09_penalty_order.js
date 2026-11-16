// 시나리오 9: 순번 조정 정확도
// 위험 점수가 높은 계정(봇)이 먼저 줄을 서도 정상 사용자보다 뒤에 입장하는지 확인한다.
//
// 흐름 (모두 이 스크립트가 개발 모드 관리 API로 준비한다. Redis 접속 불필요)
//   setup   : 대기열 초기화 → 봇 계정에 위험 점수 설정 → 입장 정지(queue.admit_per_sec = 0)
//   bots    : t=0초에 봇이 먼저 줄을 선다
//   normals : t=3초에 정상 사용자가 줄을 선다
//   release : t=RELEASE_AFTER초에 입장을 시작한다 (줄이 다 찬 뒤, 오픈 직후 몰리는 상황)
//   teardown: 입장 속도를 기본값(200)으로 되돌린다
//
// 입장을 미리 시작하면 줄에 봇밖에 없어서 그대로 입장한다. 패널티는 "다른 사람보다 뒤"로 보낼 뿐
// 혼자 있는 사람을 붙잡아 두지 않는다 (설계 의도).
// 판정: 봇이 입장할 때 모든 정상 사용자가 이미 입장해 있고, 정상 사용자가 입장할 때 봇은 아직 대기 중.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';
import {
  enterQueue, leaveQueue, headers, url, safeJson, preflight,
  devReset, devSetRiskForMembers, devSetPolicy,
} from '../lib/helpers.js';
import { API, RUN_ID, FIELDS } from '../config.js';

const BOTS = Number(__ENV.BOTS || 20);
const NORMALS = Number(__ENV.NORMALS || 100);
const BOT_SCORE = Number(__ENV.BOT_SCORE || 80);
const ADMIT_PER_SEC = Number(__ENV.ADMIT_PER_SEC || 10);        // 입장 시작 후 초당 입장 인원
const RELEASE_AFTER = Number(__ENV.RELEASE_AFTER || 6);          // 입장을 시작하는 시각(초)
const POLICY_WAIT = Number(__ENV.POLICY_WAIT || 2);              // 앱이 정책값을 최대 1초 들고 있어서 기다리는 시간(초)
const MAX_WAIT_MS = Number(__ENV.MAX_WAIT_SEC || 180) * 1000;
const BOT_BASE = Number(__ENV.BOT_BASE || 3000000);
const NORMAL_BASE = Number(__ENV.NORMAL_BASE || 3100000);

const admitWaitBot = new Trend('admit_wait_bot_ms', true);
const admitWaitNormal = new Trend('admit_wait_normal_ms', true);
const botAfterNormals = new Rate('bot_admitted_after_all_normals');   // 목표 100%
const normalBeforeBots = new Rate('normal_admitted_before_bots');     // 목표 100%

// 마지막으로 줄 선 정상 사용자 = 정상 사용자 중 가장 늦게 입장하는 사람 (패널티가 같으면 진입 순서대로 입장)
const LAST_NORMAL = NORMAL_BASE + NORMALS - 1;

export const options = {
  scenarios: {
    bots: {
      executor: 'shared-iterations', exec: 'bot', vus: BOTS, iterations: BOTS, startTime: '0s', maxDuration: '10m',
    },
    normals: {
      executor: 'shared-iterations', exec: 'normal', vus: NORMALS, iterations: NORMALS, startTime: '3s', maxDuration: '10m',
    },
    release: {
      executor: 'shared-iterations', exec: 'release', vus: 1, iterations: 1, startTime: `${RELEASE_AFTER}s`, maxDuration: '1m',
    },
  },
  thresholds: {
    bot_admitted_after_all_normals: ['rate==1'],
    normal_admitted_before_bots: ['rate==1'],
  },
};

function mustOk(res, what) {
  if (res.status !== 200) {
    throw new Error(`사전 준비 실패: ${what}이 ${res.status}를 돌려줬습니다. 개발 모드 서버(--jejari.dev-mode=true) 또는 mock 서버인지 확인하세요. 응답: ${String(res.body || '').slice(0, 150)}`);
  }
}

export function setup() {
  preflight();
  mustOk(devReset(), '대기열 초기화');
  mustOk(devSetRiskForMembers(BOT_BASE, BOTS, BOT_SCORE), '봇 위험 점수 설정');
  mustOk(devSetPolicy('queue.admit_per_sec', 0), '입장 정지');
  sleep(POLICY_WAIT);   // 입장 정지가 앱에 반영될 때까지
}

export function teardown() {
  devSetPolicy('queue.admit_per_sec', 200);   // 기본값(ERD 예시)으로 되돌린다
}

function stateOf(memberId) {
  const r = http.get(url(API.queueMe(RUN_ID)), { headers: headers(String(memberId)), tags: { name: 'queue_status' } });
  return r.status === 200 ? safeJson(r)[FIELDS.queueState] : null;
}

export function bot() {
  const id = BOT_BASE + exec.scenario.iterationInTest;
  const t0 = Date.now();
  const q = enterQueue(String(id), { maxWaitSec: MAX_WAIT_MS / 1000 });
  if (!check(q, { '봇 입장 성공': (r) => r.ok })) return;
  admitWaitBot.add(Date.now() - t0);
  // 봇이 입장한 지금, 가장 늦게 서는 정상 사용자도 이미 입장해 있어야 한다
  botAfterNormals.add(stateOf(LAST_NORMAL) === FIELDS.admittedValue);
  leaveQueue(String(id));
}

export function normal() {
  const id = NORMAL_BASE + exec.scenario.iterationInTest;
  const t0 = Date.now();
  const q = enterQueue(String(id), { maxWaitSec: MAX_WAIT_MS / 1000 });
  if (!check(q, { '정상 사용자 입장 성공': (r) => r.ok })) return;
  admitWaitNormal.add(Date.now() - t0);
  // 정상 사용자가 입장한 지금, 봇은 아직 대기 중이어야 한다 (첫 번째 봇으로 확인)
  normalBeforeBots.add(stateOf(BOT_BASE) === FIELDS.waitingValue);
}

export function release() {
  devSetPolicy('queue.admit_per_sec', ADMIT_PER_SEC);
}
