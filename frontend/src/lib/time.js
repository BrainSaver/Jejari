// 서버 시각은 시간대 없는 LocalDateTime이고 Asia/Seoul 고정이다 (concurrency.md §0.7).
// 화면은 브라우저 시간대와 관계없이 서울 기준으로 읽고 쓴다.
const TZ = 'Asia/Seoul';

/** "2026-11-17T16:17:51.123" (서울) → epoch ms */
export const seoulMs = (local) => Date.parse(local.slice(0, 23) + '+09:00');
/** Date → "16:17:51" (서울) */
export const seoulTime = (d) => d.toLocaleTimeString('en-GB', { timeZone: TZ });
/** 오늘 날짜 "2026-11-17" (서울) */
export const seoulToday = () => new Date().toLocaleDateString('en-CA', { timeZone: TZ });
/** 지금 시각 "16:17" (서울) */
export const seoulHm = () => new Date().toLocaleTimeString('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit' });
/** 지금 "2026-11-17T16:17:51" (서울, 시간대 없는 LocalDateTime 모양) */
export const seoulNowLocal = () => `${seoulToday()}T${seoulTime(new Date())}`;
/** "2026-11-17" + n일 */
export const addDays = (ymd, n) => {
  const d = new Date(ymd + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};
const DAYS = '일월화수목금토';
/** "2026-11-20" → "11-20 (금)" */
export const md = (ymd) => `${ymd.slice(5, 10)} (${DAYS[new Date(ymd.slice(0, 10) + 'T00:00:00Z').getUTCDay()]})`;
/** "2026-11-20T09:00:00" → "11-20 09:00" */
export const mdhm = (t) => (t ? `${t.slice(5, 10)} ${t.slice(11, 16)}` : '—');
/** LocalDateTime → <input type="datetime-local"> 값 */
export const toInput = (t) => (t ? t.slice(0, 16) : '');
