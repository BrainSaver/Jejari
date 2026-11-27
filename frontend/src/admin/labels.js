/**
 * 화면에 보이는 상태·코드의 한국어 이름. 값(API·DB 코드)은 그대로 두고 보여 줄 때만 바꾼다.
 * 모르는 값이 오면 코드를 그대로 보인다. 원래 코드는 마우스를 올리면(title) 보인다.
 * 코드 목록은 ERD CHECK 제약과 같다 (suspicious_case, case_marker, train_run, app_policy).
 */
export const CASE_STATUS = { OPEN: '열림', REVIEWING: '검토 중', CONFIRMED: '확정', DISMISSED: '기각' };
export const TRIGGER = { RISK_THRESHOLD: '점수 초과', RULE: '규칙', MODEL: '모델', ADMIN: '관리자' };
export const VERDICT = { RESELLER: '되팔이', MACRO: '매크로', CANCEL_ABUSE: '취소 남용', NORMAL: '정상' };
export const SUBJECT = { CI: '명의', PAYMENT: '결제수단', DEVICE: '기기' };
export const SEVERITY = { LOW: '낮음', MID: '중간', HIGH: '높음' };
export const RUN_STATUS = { SCHEDULED: '판매 전', ON_SALE: '판매 중', CLOSED: '판매 마감', DEPARTED: '출발' };
export const VALUE_TYPE = { NUMBER: '숫자', STRING: '문자', BOOLEAN: '참/거짓', JSON: 'JSON' };

/** 코드 → 한국어. 없으면 코드 그대로 */
export const ko = (map, code) => (code == null ? '' : map[code] ?? code);

/** 운영 설정 분류(정책 키의 앞부분, 예: auth.lock_minutes → auth) */
export const POLICY_GROUP = {
  auth: '인증', behavior: '행동 로그', cancel: '취소', evidence: '증거 보관', group: '단체', guest: '비회원',
  idem: '중복 요청 막기', inbox: '이벤트 수신', inventory: '좌석 재고', login_attempt: '로그인 시도', notification: '알림',
  outbox: '이벤트 발행', payment: '결제', queue: '대기열', queue_log: '대기열 기록', relay: '이벤트 릴레이', replay: '행동 재생',
  reservation: '예약', risk: '위험 점수', sale: '판매', scanner: '스캐너', seatmap: '좌석 배치도', waitlist: '취소표 대기',
};

export const GROUP_STATUS = { REQUESTED: '신청', APPROVED: '승인', REJECTED: '반려', WITHDRAWN: '철회' };
export const RESV_STATUS = { HOLD: '선점', PAYMENT_PENDING: '결제 대기', CONFIRMED: '확정', CANCELED: '취소', EXPIRED: '만료' };
export const MEMBER_STATUS = { ACTIVE: '정상', RESTRICTED: '예매 제한', BANNED: '이용 정지' };
export const CASE_ACTION = { CLEAR: '해제', CONFIRM: '확정만', RESTRICT: '예매 제한', BAN: '이용 정지' };
export const SALE_STATUS = { PLANNED: '준비', OPEN: '판매 중', CLOSED: '끝' };

/** 관리자 권한 (토큰 adm) */
export const ROLE = { SUPERVISOR: '총괄 관리자', REVIEWER: '검토자', VIEWER: '열람자' };

/** 점수 근거·마커 규칙 (RiskRule). 코드는 마우스를 올리면 보인다 */
export const RULE = {
  FAST_CLICK: '너무 빠른 연속 요청', SAME_SEAT_RETRY: '같은 좌석 반복 시도', CANCEL_AFTER_PAY: '결제 직후 취소',
  MULTI_ACCOUNT_SYNC: '여러 계정 동시 선점', REGULAR_INTERVAL: '기계처럼 일정한 간격', HIGH_CANCEL_RATIO: '취소 비율 높음',
  LAST_MINUTE_CANCEL: '출발 직전 취소 많음', BULK_PURCHASE: '많이 구매', LINKED_ACCOUNTS: '연결된 계정 많음',
  NEW_ACCOUNT: '새 계정', LINEAR_MOUSE: '기계 같은 마우스 움직임', HEADLESS_HOLD: '화면 없이 선점',
  NO_SEARCH_ENTRY: '조회 없이 대기열 진입', INHUMAN_STEP: '사람이 못 낼 속도', DECLINED_CARDS: '여러 카드 결제 거절',
};

/** 트래픽 흐름도의 저장소 이름 (서버 key 기준) */
export const STORE = { redis: '임시 저장소', oracle: '예약 DB', mongo: '이용 기록 DB', kafka: '메시지 전달' };

/**
 * 서버(TrafficService·TrafficHealth)가 보내는 그래프 이름·상태 문장의 개발 용어를 쉬운 말로.
 * 서버 문구는 Grafana·로그와 같게 두고, 관리자 화면에서만 바꿔 보인다
 */
const PLAIN = [
  [/응답 시간 p95/g, '느린 응답(상위 5%)'], [/^p50$/, '보통'], [/^p95$/, '느림(상위 5%)'], [/^p99$/, '아주 느림(상위 1%)'],
  [/5xx 서버 오류/g, '서버 오류'], [/기타 4xx/g, '그 밖의 사용자 오류'], [/429 요청 제한/g, '요청 제한'], [/요청 제한\(429\)/g, '요청 제한'],
  [/409 충돌/g, '좌석 겹침'], [/DB 커넥션 풀/g, 'DB 연결'], [/DB 커넥션/g, 'DB 연결'], [/Tomcat 스레드/g, '동시 처리 칸'],
  [/힙 메모리/g, '서버 메모리'], [/Redis 메모리/g, '임시 저장소'], [/ \(noeviction이라 다 차면 쓰기 실패\)/g, ' (다 차면 저장 실패)'],
  [/Kafka가 받음/g, '서버가 받음'], [/MongoDB 적재/g, '저장'], [/Kafka 소비 밀림/g, '처리 밀림'], [/Kafka 연결 실패/g, '메시지 전달 장치 연결 실패'],
  [/Kafka 전송 실패/g, '전송 실패'], [/버림 \(큐 넘침\)/g, '버림 (대기 줄 넘침)'], [/outbox 발행 실패/g, '예약 알림 보내기 실패'],
  [/\(outbox /g, '('], [/ \(앱이 꺼졌거나 Prometheus 설정 확인\)/g, ' (앱이 꺼졌을 수 있음)'], [/ \(MongoDB·적재 컨슈머 확인\)/g, ''],
  [/MongoDB/g, '저장소'], [/^Kafka /, ''], [/예약 이벤트 발행/g, '예약 알림 보내기'],
  [/4xx 클라이언트 오류/g, '사용자 쪽 오류'], [/409·429 제외/g, '좌석 겹침·요청 제한 제외'], [/잘못된 호출/g, '잘못된 요청'],
];
export const plain = (s) => (typeof s === 'string' ? PLAIN.reduce((t, [re, to]) => t.replace(re, to), s) : s);

/** 트래픽 화면 응답을 쉬운 말로 (그래프 제목·선 이름·상태 문장) */
export const plainTraffic = (v) =>
  v && {
    ...v,
    charts: v.charts?.map((c) => ({ ...c, title: plain(c.title), lines: c.lines.map((l) => ({ ...l, name: plain(l.name) })) })),
    health: v.health && { ...v.health, reasons: v.health.reasons?.map((r) => ({ ...r, text: plain(r.text) })) },
  };

/** 트래픽 흐름도 응답: 저장소 이름(Redis·Oracle…)을 하는 일로, 상태 문장을 쉬운 말로 */
export const plainFlow = (f) =>
  f && { ...f, stores: f.stores?.map((s) => ({ ...s, name: STORE[s.key] ?? s.name, note: plain(s.note) })) };
