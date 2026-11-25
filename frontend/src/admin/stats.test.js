// node --test (npm test). 화면 숫자의 계산만 시험한다 — 2차 대회 분석 문서(Python으로 계산)와 같은 값이어야 한다
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ciText, wilson } from './stats.js';

test('0/27은 0%가 아니라 0~12.5%', () => {
  assert.deepEqual(wilson(0, 27), [0, 12.5]);
});

test('2차 대회 수치가 분석 문서와 같다', () => {
  assert.deepEqual(wilson(6, 6), [61, 100]);
  assert.deepEqual(wilson(4, 27), [5.9, 32.5]);
  assert.deepEqual(wilson(0, 5), [0, 43.4]);
  assert.deepEqual(wilson(0, 62), [0, 5.8]);
});

test('표본이 늘면 구간이 좁아진다', () => {
  const [, hi30] = wilson(0, 30);
  const [, hi300] = wilson(0, 300);
  assert.ok(hi300 < hi30);
});

test('n이 0이면 구간 없음', () => {
  assert.equal(wilson(0, 0), null);
  assert.equal(ciText(0, 0), '');
  assert.equal(ciText(0, 27), '95% 구간 0~12.5%');
});
