// 회원 인증(Oracle) 시나리오용 공용 함수.
//
// 인증 API는 IP당 시도 횟수를 제한한다 (기본: 본인인증·가입 시간당 10회, 로그인 분당 20회). 한 PC에서 부하를 걸면
// 모든 요청이 같은 IP라 금방 429가 나므로, 시나리오는 요청마다 다른 IP를 X-Forwarded-For로 보낸다.
// 앱이 이 헤더를 믿으려면 연결 주소가 신뢰 프록시 목록에 있어야 한다:
//   ./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"
// (끄려면 SPOOF_IP=no. 그러면 한 IP로 보이고 제한에 걸리는 것도 같이 보게 된다.)
import http from 'k6/http';
import { check } from 'k6';
import { url, headers, safeJson } from './helpers.js';
import { API, HEADERS, FIELDS, BASE_URL } from '../config.js';

export const SPOOF_IP = (__ENV.SPOOF_IP || 'yes') !== 'no';
export const EMAIL_DOMAIN = __ENV.EMAIL_DOMAIN || 'loadtest.example.com';

// 숫자 한 자리씩 한글로 바꿔 이름을 만든다 (본인인증의 이름 규칙은 글자만 허용, 숫자 금지)
const HANGUL = ['가', '나', '다', '라', '마', '바', '사', '아', '자', '차'];

/** n번째 가짜 IP (10.x.y.z). 요청 묶음(가입 = 본인인증 + 가입)마다 하나를 쓴다 */
export function fakeIp(n) {
  const v = Math.abs(Math.floor(n)) % 16777216;
  return `10.${(v >> 16) & 255}.${(v >> 8) & 255}.${v & 255}`;
}

function ipHeaders(ip) {
  return SPOOF_IP && ip ? { [HEADERS.forwardedFor]: ip } : {};
}

/**
 * 실행마다 다른 가짜 신원. tag는 실행 하나를 구분하는 값(영문 소문자·숫자 4자, setup에서 만들어 모든 VU가 공유),
 * seq는 그 실행 안에서 겹치지 않는 번호(0~9999). 같은 값을 다시 만들면 같은 신원이다.
 */
export function identity(tag, seq) {
  const t = Number.parseInt(tag, 36) % 1000;                  // 실행 구분 3자리
  // 휴대폰 = 010 + 실행 3자리 + 번호 4자리 + "1". 끝자리를 1로 고정해 모의 본인인증의 실패 번호(…0000)를 피한다
  const eight = String(t).padStart(3, '0') + String(seq % 10000).padStart(4, '0') + '1';
  const phone = `010${eight}`;
  const name = '테스트' + [...eight].map((d) => HANGUL[Number(d)]).join('');
  const local = `lt${tag}x${seq}`;
  return {
    name,
    phone,
    email: `${local}@${EMAIL_DOMAIN}`,
    password: `Zq9!${tag}Rt#${seq}vL2m`,                      // 10~64자, 4종 이상 문자, 흔한 패턴·이메일 아이디 포함 없음
  };
}

/** 모의 본인인증 → 1회용 토큰. 실패하면 null과 응답을 돌려준다 */
export function verify(id, { ip, tag = 'auth_verify' } = {}) {
  const res = http.post(
    url(API.authVerify),
    JSON.stringify({ name: id.name, phone: id.phone, provider: 'MOCK_PASS' }),
    { headers: headers(null, ipHeaders(ip)), tags: { name: tag } },
  );
  const body = safeJson(res);
  const token = res.status === 200 && body ? body.verificationToken : null;
  return { token: token || null, res };
}

export function signup(token, id, { ip, tag = 'auth_signup' } = {}) {
  return http.post(
    url(API.authSignup),
    JSON.stringify({ verificationToken: token, email: id.email, password: id.password }),
    { headers: headers(null, ipHeaders(ip)), tags: { name: tag } },
  );
}

export function login(email, password, { ip, tag = 'auth_login' } = {}) {
  return http.post(
    url(API.authLogin),
    JSON.stringify({ email, password }),
    { headers: headers(null, ipHeaders(ip)), tags: { name: tag } },
  );
}

/** 응답이 어느 부류인지: 서버 한도(429)·과부하(503)는 "실패"와 따로 센다 */
export function classify(res) {
  if (res.status === 429) return 'rate_limited';
  if (res.status === 503) return 'overloaded';
  return null;
}

export function errorCodeOf(res) {
  const b = safeJson(res);
  return b && b[FIELDS.errorCode] ? b[FIELDS.errorCode] : '';
}

/**
 * 시작 전에 가입 → 로그인을 한 번 끝까지 해 본다. 실패하면 이유를 알려 주고 중단한다.
 * 비밀번호 규칙·이메일 형식·키 설정 같은 문제를 "실패율 100%" 표 대신 한 줄 원인으로 보여 주려는 것이다.
 */
export function authSmokeTest(tag) {
  const id = identity(tag, 999);
  const ip = fakeIp(1);
  const v = verify(id, { ip, tag: 'preflight' });
  if (!v.token) fail('본인인증', v.res);
  const s = signup(v.token, id, { ip, tag: 'preflight' });
  if (s.status !== 201) fail('가입', s);
  const l = login(id.email, id.password, { ip, tag: 'preflight' });
  if (l.status !== 200) fail('로그인', l);
  return true;
}

/** 단계 이름과 응답으로 "무엇을 하면 되는지"까지 담은 오류를 던진다 (setup에서 쓴다) */
export function fail(stage, res) {
  const hints = {
    0: `${BASE_URL}에 연결하지 못했습니다. 서버가 떠 있는지 확인하세요 (${res.error || '연결 오류'}).`,
    404: '회원 인증 기능이 꺼져 있습니다. .env에 JEJARI_HMAC_KEY, JEJARI_ENC_KEYS, JEJARI_PEPPER_KEYS(비어 있으면 기능이 꺼짐)를 채우고 앱을 다시 띄우세요. 다른 서버가 이 포트를 쓰고 있을 수도 있습니다.',
    409: '이미 가입된 이메일입니다. 같은 실행 태그로 다시 돌렸는지 확인하세요 (RUN_TAG를 바꾸거나 비우세요).',
    429: 'IP당 시도 제한에 걸렸습니다. 앱을 --jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1 로 띄워 X-Forwarded-For를 믿게 하거나, 잠시(최대 1시간) 뒤 다시 하세요. (Redis의 rate:signup:*, rate:login:* 키를 지워도 됩니다)',
    400: '요청 값이 규칙에 맞지 않습니다(이메일 형식·비밀번호 규칙·본인인증). 응답 내용을 확인하세요.',
    500: '서버 오류입니다. Oracle 연결이나 스키마를 확인하세요: docker compose ps (oracle이 healthy인지), 서버 콘솔의 오류 메시지.',
    503: 'Redis나 서버가 준비되지 않았습니다. docker compose ps에서 redis가 healthy인지 확인하세요.',
  };
  throw new Error(`사전 점검 실패(${stage}): ${res.status}. ${hints[res.status] || ''} 응답: ${String(res.body || '').slice(0, 200)}`);
}

export { check };
