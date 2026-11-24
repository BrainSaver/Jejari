// 입력칸 모양 맞추기 (보기만 바꾼다). 서버는 숫자만 남겨 쓰므로 보낼 때 다시 숫자만 뽑는다.

/** 휴대폰 번호를 010-1234-5678 모양으로. 숫자만 남기고 11자리까지. 10자리(011-123-4567)도 맞춘다 */
export function formatPhone(value) {
  const d = String(value).replace(/\D/g, '').slice(0, 11);
  if (d.length <= 3) return d;
  if (d.length <= 7) return `${d.slice(0, 3)}-${d.slice(3)}`;
  // 010은 늘 3-4-4 (치는 도중 10자리에서 3-3-4로 잠깐 바뀌지 않게). 예전 번호(011·016~019) 10자리만 3-3-4
  if (d.length === 10 && !d.startsWith('010')) return `${d.slice(0, 3)}-${d.slice(3, 6)}-${d.slice(6)}`;
  return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`;
}

/** 카드번호를 4자리씩 띄워 4111 2222 3333 4441 모양으로. 숫자만 남기고 19자리까지 */
export function formatCard(value) {
  const d = String(value).replace(/\D/g, '').slice(0, 19);
  return d.replace(/(\d{4})(?=\d)/g, '$1 ');
}
