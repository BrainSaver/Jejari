/**
 * 서버가 바빠서 못 받은 경우 (사용자 잘못이 아님). 로그인·가입 화면이 같은 문구를 쓰고, 이때는 입력값을 지우지 않는다.
 *
 *   SERVICE_UNAVAILABLE 앱이 비밀번호 해시 자리를 3초 안에 못 얻음 (auth.max_concurrent_hash, PERF-01) 또는 Redis 장애
 *   HTTP_502·503·504   앞단(Caddy)이 앱에 못 닿음: 재배포·재시작 중. 본문이 없어 서버 문구도 없다
 *   RATE_LIMITED       IP당 요청 한도 (가입 등)
 */
const BUSY = new Set(['SERVICE_UNAVAILABLE', 'HTTP_502', 'HTTP_503', 'HTTP_504']);

export const isBusy = (err) => BUSY.has(err?.code);

export const BUSY_MESSAGE = '지금 접속이 많아 잠시 처리하지 못했어요. 입력한 내용은 그대로 두었으니 몇 초 뒤 다시 눌러 주세요.';
