/**
 * 비율의 Wilson 95% 구간 (%, 소수 첫째 자리). 0/27을 "0%"가 아니라 "0~12.5%"로 읽게 하려고.
 * docs/강동균/features/2차-대회-결과-분석.md 1-1절과 같은 식이다.
 * 계정은 같은 사람·같은 봇이 여러 개 만든 것이라 서로 독립이 아니어서, 실제 불확실성은 이보다 크다.
 * @returns {[number, number] | null} n이 0이면 null
 */
export function wilson(k, n, z = 1.959964) {
  if (!n) return null;
  const p = k / n;
  const d = 1 + (z * z) / n;
  const c = p + (z * z) / (2 * n);
  const h = z * Math.sqrt((p * (1 - p)) / n + (z * z) / (4 * n * n));
  const r = (x) => Math.round(Math.min(1, Math.max(0, x)) * 1000) / 10;
  return [r((c - h) / d), r((c + h) / d)];
}

/** 화면에 붙이는 한 줄: "95% 구간 0~12.5%" (n이 0이면 빈 문자열) */
export function ciText(k, n) {
  const w = wilson(k, n);
  return w ? `95% 구간 ${w[0]}~${w[1]}%` : '';
}
