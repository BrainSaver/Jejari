// 제자리 부하 테스트 공통 설정
//
// 실제로 구현된 것(queue 모듈, 개발 모드)과 아직 가정인 것을 나눠 둔다.
//   [실제] 대기열: 진입·번호표 조회·이탈, 입장 토큰으로 보호되는 좌석 배치도, 개발용 관리 API
//   [가정] 좌석 선점·결제·취소·취소표·로그인: 다른 파트(B·C·D·E)가 구현하면 이 파일만 고친다
// mock 서버(tools/mock_server.py)는 둘 다 흉내 낸다.

const rawBase = __ENV.BASE_URL || 'http://localhost:8080';
export const BASE_URL = rawBase.replace(/\/+$/, '');

// ── 안전장치 ────────────────────────────────────────────────
// 의도치 않게 외부(남의) 서비스로 부하가 나가는 것을 막는다.
// 로컬/내부 호스트가 아니면 I_OWN_THIS_TARGET=yes 를 명시해야 실행된다.
const host = BASE_URL.replace(/^https?:\/\//, '').split(/[/:?#]/)[0];
const SAFE_HOST = /^(localhost|127\.0\.0\.1|\[::1\]|host\.docker\.internal)$|\.(local|test|localhost|internal)$/;
if (!SAFE_HOST.test(host) && __ENV.I_OWN_THIS_TARGET !== 'yes') {
  throw new Error(
    `BASE_URL(${BASE_URL})이 로컬/내부 주소가 아닙니다. ` +
      '직접 소유했거나 테스트 허가를 받은 스테이징 서버라면 I_OWN_THIS_TARGET=yes 를 지정하세요.'
  );
}

// 개발 모드로 띄운 queue 앱의 판매 중 운행과 맞춘다 (--jejari.queue.dev-on-sale-runs=9001). 시드(db/seed)의 ON_SALE 운행과 같다
export const RUN_ID = __ENV.RUN_ID || '9001';
export const SEAT_PREFIX = __ENV.SEAT_PREFIX || 'LT'; // 부하 테스트용 좌석 ID 접두사

// ── 인증 방식 ───────────────────────────────────────────────
//   member-header : 개발 모드. 로그인 없이 X-Member-Id 헤더로 회원을 구분한다 (queue 앱의 현재 상태, mock 서버도 지원)
//   jwt           : 로그인 API로 받은 액세스 토큰을 Authorization: Bearer 로 보낸다 (E의 JWT 인증이 붙은 뒤)
export const AUTH_MODE = __ENV.AUTH_MODE || 'member-header';

export const API = {
  // [가정] JWT 로그인 (AUTH_MODE=jwt 일 때만 사용)
  login: '/api/auth/login',
  trains: '/api/trains',

  // [실제] 회원 인증 (member 모듈, Oracle). 비밀값 3종(JEJARI_HMAC_KEY·JEJARI_ENC_KEYS·JEJARI_PEPPER_KEYS)이 .env에 있어야 켜진다
  authVerify: '/api/v1/auth/verify',                           // POST {name, phone, provider}
  authSignup: '/api/v1/auth/signup',                           // POST {verificationToken, email, password}
  authLogin: '/api/v1/auth/login',                             // POST {email, password}

  // [실제] 대기열 (기능설계서 7장)
  queueEnter: (runId) => `/api/v1/runs/${runId}/queue`,       // POST
  queueMe: (runId) => `/api/v1/runs/${runId}/queue/me`,       // GET 번호표 조회, DELETE 이탈
  // [실제] 입장 토큰(X-Queue-Token)이 없으면 컨트롤러에 닿기 전에 403. 대기열 우회 차단 검증에 쓴다
  seatMap: (runId) => `/api/v1/runs/${runId}/seats`,           // GET

  // [실제, 개발 모드 전용] 시나리오 전 초기화·위험 점수·정책 설정
  devReset: '/api/v1/dev/reset',                               // POST
  devRiskMembers: '/api/v1/dev/risk/dev-members',              // PUT ?from=&count=&score=
  devPolicy: (key) => `/api/v1/dev/policy/${key}`,             // PUT {"value":"..."}

  // [실제] 열차 조회 (비회원 가능, TRN-01~03)
  stations: '/api/v1/stations',                                // GET
  runSearch: '/api/v1/runs',                                   // GET ?from=&to=&date=YYYY-MM-DD (행동 로그 VIEW_TRAIN)
  runDetail: (runId) => `/api/v1/runs/${runId}`,               // GET ?from=&to=
  // [실제] 예약·결제 (B 강동균·C 길동현). 시나리오 11이 쓴다. 아래 [가정] 경로(seatHold 등)는 Mock 서버용으로 남겨 둠
  reservationHold: '/api/v1/reservations',                     // POST {runId, fromStationId, toStationId, seats:[{seatId, passengerType}], channel} + X-Queue-Token + Idempotency-Key
  reservationPay: (id) => `/api/v1/reservations/${id}/payments`, // POST {cardNumber} + Idempotency-Key. 모의 PG: 끝자리 0 거절, 8 결과 모름, 그 외 승인
  reservationCancel: (id) => `/api/v1/reservations/${id}/cancel`, // POST

  // [가정] 아래는 B·C·D 파트 구현 전: 경로·필드가 바뀔 수 있다
  seatHold: '/api/seats/hold',
  seatStatus: (runId, seatId) => `/api/runs/${runId}/seats/${seatId}`,
  payment: '/api/payments',
  cancel: (reservationId) => `/api/reservations/${reservationId}/cancel`,
  waitlistJoin: '/api/waitlist',
  waitlistMe: '/api/waitlist/me',
};

// 응답 JSON 필드명 / 상태값
export const FIELDS = {
  accessToken: 'accessToken',          // [가정] JWT 로그인
  // [실제] 대기열 번호표: state, rank, ahead, estimatedWaitSec, pollIntervalSec, passToken, passExpiresAt
  queueState: 'state',
  admittedValue: 'ADMITTED',
  waitingValue: 'WAITING',
  entryToken: 'passToken',
  pollInterval: 'pollIntervalSec',
  errorCode: 'code',                   // [실제] 실패 응답: {code, message, traceId}
  // [가정]
  reservationId: 'reservationId',
  paymentId: 'paymentId',
  seatStatus: 'status',
  availableValue: 'AVAILABLE',
  waitlistStatus: 'status',
  waitlistAssignedValue: 'ASSIGNED',
};

// 요청 헤더 이름
export const HEADERS = {
  memberId: 'X-Member-Id',             // [실제, 개발 모드] 회원 구분
  entryToken: 'X-Queue-Token',         // [실제] 대기열 입장 토큰
  idempotency: 'Idempotency-Key',      // 멱등성 키 (기능설계서 7장)
  mockPg: 'X-Mock-PG',                 // [가정] mock PG 동작 지시 (success | fail | timeout)
  lockStrategy: 'X-Lock-Strategy',     // [가정] 락 방식 전환: pessimistic | optimistic | redis
  loadTest: 'X-Load-Test',             // 부하 테스트 트래픽 표시
  deviceId: 'X-Device-Id',             // [실제] 기기 ID (행동 로그가 해시로 남김, 영문·숫자·_- 8~128자). 기기 단위 위험 점수의 재료
  forwardedFor: 'X-Forwarded-For',     // 요청마다 다른 IP로 보이게 함 (앱이 --jejari.auth.trusted-proxies=127.0.0.1 일 때만 의미 있음)
};

export const HOLD_TTL_SEC = Number(__ENV.HOLD_TTL_SEC || 30); // 좌석 선점 만료(결제 기한) 초
