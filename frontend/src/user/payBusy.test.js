import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BUSY_MARGIN_SEC, BUSY_RETRY_MAX, busyWaitSec } from './payBusy.js';

const mid = () => 0.5; // 흔들림 없이 (×1.0)

test('Retry-After만큼 기다린다', () => {
  assert.equal(busyWaitSec(3, 0, 300, mid), 3);
  assert.equal(busyWaitSec('5', 0, 300, mid), 5);
});

test('헤더가 없으면 3초', () => {
  assert.equal(busyWaitSec(null, 0, 300, mid), 3);
  assert.equal(busyWaitSec(undefined, 0, 300, mid), 3);
});

test('±20% 안에서 흔든다', () => {
  assert.equal(busyWaitSec(10, 0, 300, () => 0), 8);
  assert.equal(busyWaitSec(10, 0, 300, () => 0.999), 12);
  assert.equal(busyWaitSec(1, 0, 300, () => 0), 1); // 0초로 내려가지 않음
});

test('횟수를 다 쓰면 멈춘다', () => {
  assert.notEqual(busyWaitSec(3, BUSY_RETRY_MAX - 1, 300, mid), null);
  assert.equal(busyWaitSec(3, BUSY_RETRY_MAX, 300, mid), null);
});

test('결제 기한이 모자라면 멈춘다', () => {
  assert.equal(busyWaitSec(3, 0, BUSY_MARGIN_SEC + 3, mid), 3);
  assert.equal(busyWaitSec(3, 0, BUSY_MARGIN_SEC + 2, mid), null);
  assert.equal(busyWaitSec(3, 0, NaN, mid), null); // 기한을 모르면 자동으로 보내지 않음
});
