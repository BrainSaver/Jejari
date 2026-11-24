// UUID v4. crypto.randomUUID는 https·localhost(보안 컨텍스트)에서만 있어서,
// 같은 와이파이의 휴대폰이 http://{PC IP}:8080으로 들어오면 undefined → 좌석 선점·결제 버튼이 멈췄다 (2026-11-24 2차 대회 리허설).
// getRandomValues는 http에서도 쓸 수 있다.
export function uuid() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40; // 버전 4
  b[8] = (b[8] & 0x3f) | 0x80; // 변형 RFC 4122
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
