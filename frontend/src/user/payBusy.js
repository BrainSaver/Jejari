/**
 * 결제 요청이 몰려 서버가 "잠시 후 다시"(503 PAYMENT_BUSY + Retry-After)라고 했을 때 자동 재시도 간격 (concurrency.md §4.4, PgBulkhead).
 *
 * <p>서버는 결제 행을 만들기 전에 거절하고 멱등 키도 지운다. 그래서 같은 멱등 키로 다시 보내면 처음 요청처럼 처리되고,
 * 좌석은 HOLD 그대로다 — 자동으로 다시 보내도 두 번 결제될 일이 없다.
 *
 * <ul>
 *   <li><b>Retry-After를 지킨다</b>: 거절당한 사람이 바로 다시 보내면 그 재시도가 또 부하가 된다 (11-27 시험: 503 1,451번)</li>
 *   <li><b>±20% 흔든다</b>: 같은 순간에 거절당한 사람들이 같은 순간에 다시 몰리지 않게 (로그인 대기실과 같은 방식)</li>
 *   <li><b>결제 기한 안에서만</b>: 기한이 {@link BUSY_MARGIN_SEC}초보다 적게 남으면 멈추고 사람에게 알린다. PG 처리에도 시간이 든다</li>
 * </ul>
 */
export const BUSY_RETRY_MAX = 10;
export const BUSY_MARGIN_SEC = 10;
const DEFAULT_SEC = 3; // 서버 기본 Retry-After와 같은 값 (헤더가 없을 때)

/**
 * @param retryAfter 서버가 알려 준 초 (없으면 3)
 * @param attempt    지금까지 자동으로 다시 보낸 횟수
 * @param leftSec    결제 기한까지 남은 초
 * @returns 기다릴 초, 다시 보내지 않으면 null
 */
export function busyWaitSec(retryAfter, attempt, leftSec, rand = Math.random) {
  if (attempt >= BUSY_RETRY_MAX) return null;
  const base = Math.max(1, Math.ceil(Number(retryAfter) || DEFAULT_SEC));
  const sec = Math.max(1, Math.round(base * (0.8 + rand() * 0.4)));
  if (!(leftSec - sec >= BUSY_MARGIN_SEC)) return null;
  return sec;
}
