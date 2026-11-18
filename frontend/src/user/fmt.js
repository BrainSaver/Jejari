import { useEffect, useState } from 'react';

// 사용자 화면 공통 글자 만들기. 서버 시각은 두 가지로 온다:
//   LocalDateTime "2026-11-20T09:00:00"     (운행·좌석 API, 서울 시각, 시간대 없음)
//   Instant       "2026-11-20T00:00:00Z"    (예약 조회 API, UTC)
// 어느 쪽이든 서울 기준으로 보여 준다 (concurrency.md §0.7: 서버는 Asia/Seoul 고정)
const TZ = 'Asia/Seoul';
const hasZone = (t) => /Z$|[+-]\d\d:?\d\d$/.test(t);

/** 서버 시각 → epoch ms */
export const toMs = (t) => (!t ? NaN : Date.parse(hasZone(t) ? t : t.slice(0, 23) + '+09:00'));

const parts = (t) => {
  const d = new Date(toMs(t));
  const p = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', weekday: 'short' })
      .formatToParts(d).map((x) => [x.type, x.value]),
  );
  return p;
};

/** "09:00" */
export const hm = (t) => (t ? (({ hour, minute }) => `${hour}:${minute}`)(parts(t)) : '--:--');
const DAYS = '일월화수목금토';
/** "2026-11-20" 또는 시각 → "11월 20일(금)" */
export function shortDate(t) {
  if (!t) return '';
  const ymd = t.length === 10 ? t : (({ year, month, day }) => `${year}-${month}-${day}`)(parts(t));
  return `${+ymd.slice(5, 7)}월 ${+ymd.slice(8, 10)}일(${DAYS[new Date(ymd + 'T00:00:00Z').getUTCDay()]})`;
}
/** 두 시각 사이 "2시간 43분" */
export function duration(a, b) {
  if (!a || !b) return '';
  const m = Math.round((toMs(b) - toMs(a)) / 60000);
  return m >= 60 ? `${Math.floor(m / 60)}시간 ${m % 60 ? `${m % 60}분` : ''}`.trim() : `${m}분`;
}
export const won = (n) => `${Number(n || 0).toLocaleString()}원`;
export const mmss = (s) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
export const GRADE = { GENERAL: '일반실', FIRST: '특실' };
export const PASSENGER = { ADULT: '어른', CHILD: '어린이', SENIOR: '경로' };

/** 예약 상태 → 배지 (색만이 아니라 글자로도) */
export const RES_STATUS = {
  HOLD: ['amber', '결제 대기'],
  PAYMENT_PENDING: ['amber', '결제 확인 중'],
  CONFIRMED: ['go', '확정'],
  CANCELED: ['', '취소'],
  EXPIRED: ['', '만료'],
};

/** 인원 요약 "어른 2명, 어린이 1명" */
export function people(seats) {
  const c = {};
  seats.forEach((s) => (c[s.passengerType] = (c[s.passengerType] || 0) + 1));
  return Object.entries(c).map(([t, n]) => `${PASSENGER[t] || t} ${n}명`).join(', ');
}
/** 좌석 요약 "2호차 1A, 1B" */
export function seatText(seats) {
  const by = {};
  seats.forEach((s) => (by[s.carNo] = [...(by[s.carNo] || []), s.seatNo]));
  return Object.entries(by).map(([car, nos]) => `${car}호차 ${nos.join(', ')}`).join(' · ');
}

/** 남은 초. 매 렌더마다 지금 시각으로 다시 계산하고 1초마다 다시 그린다 (기한이 바뀐 첫 렌더에도 늦지 않게) */
export function useLeft(deadline) {
  const [, setTick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  return deadline ? Math.max(0, Math.floor((toMs(deadline) - Date.now()) / 1000)) : 0;
}
