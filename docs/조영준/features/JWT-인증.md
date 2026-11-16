# JWT 인증 · 토큰 재발급 · 로그아웃 (AUTH-03 발급, AUTH-04)

작성자: 조영준 · 코드 위치: `com.jejari.auth` (`jwt/`, `session/`, `web/`), `common.security.SecurityConfig`

## 한 줄 요약

로그인에 성공하면 15분짜리 액세스 토큰(JWT)과 14일짜리 리프레시 토큰을 주고, 모든 API는 `Authorization: Bearer {액세스 토큰}`으로 누가 요청했는지 확인한다.

## 어떻게 동작하나

```
로그인 성공 ─→ AuthTokenService.issue() ─→ auth_session 행 생성 ─→ 본문: accessToken / 쿠키: jejari_rt
API 요청    ─→ Bearer 검증(서명·만료) ─→ @AuthenticationPrincipal AuthPrincipal
15분 지남   ─→ 401 AUTH_TOKEN_EXPIRED ─→ POST /api/v1/auth/refresh (쿠키 자동 전송) ─→ 새 액세스 + 새 쿠키
로그아웃    ─→ POST /api/v1/auth/logout ─→ 세션 폐기 + 쿠키 삭제
```

- **액세스 토큰(JWT)**: 서버가 비밀키로 서명한 값. 서명만 확인하면 되므로 요청마다 DB를 보지 않습니다 (부하 테스트에서 중요).
- **리프레시 토큰**: `{session_id}.{무작위 32바이트}`. DB에는 SHA-256 해시만 저장합니다.
- **회전**: 재발급할 때마다 리프레시 토큰도 새 값으로 바꿉니다. 같은 `auth_session` 행의 해시만 바뀌고 `session_id`는 그대로라서 CCTV 녹화·행동 로그가 끊기지 않습니다.
- **탈취 감지**: 이미 바뀐 옛 리프레시 토큰이 다시 오면 "누군가 복사해 갔다"고 보고 세션을 `SUSPICIOUS`로 폐기합니다.

## 다른 기능에서 쓰는 법

```java
// 내 API에서 로그인한 회원 꺼내기
@GetMapping("/api/v1/me")
MeResponse me(@AuthenticationPrincipal AuthPrincipal me) {
    long memberId = me.memberId();   // 관리자 토큰이면 403
    String sessionId = me.sessionId(); // 행동 로그·CCTV 연결 키
}

// 로그인 API (강다인): 비밀번호·잠금·정지 확인이 끝난 뒤
IssuedTokens tokens = authTokenService.issue(ActorType.MEMBER, memberId, new ClientInfo(deviceHash, ipHash, userAgent));
return tokenResponses.ok(tokens);   // 본문 + 리프레시 쿠키를 같은 형식으로
```

| 상황 | code | 클라이언트가 할 일 |
|---|---|---|
| 토큰 없음 | `AUTH_REQUIRED` 401 | 로그인 |
| 액세스 만료 | `AUTH_TOKEN_EXPIRED` 401 | `/auth/refresh` 후 재시도 |
| 위조·손상 토큰 | `AUTH_TOKEN_INVALID` 401 | 로그인 |
| 리프레시 없음·만료·폐기·재사용 | `AUTH_REFRESH_INVALID` 401 | 로그인 |
| 회원이 관리자 API | `ADMIN_FORBIDDEN` 403 | - |

## 왜 이렇게 했나

- **HS256 + kid**: 서버가 하나라 공개키 방식까지 필요 없음. 키에 ID(kid)를 붙여 두면 새 키로 바꾸는 동안 옛 키 토큰도 검증됩니다.
- **서명·검증은 Spring Security(Nimbus)에 맡김**: 직접 파싱하면 `alg: none` 같은 공격을 놓치기 쉽습니다. HS256 외 알고리즘은 거부합니다.
- **payload에 식별자만** (`sub`, `role`, `sid`): JWT는 암호화가 아니라 누구나 읽을 수 있어서 개인정보를 넣지 않습니다.
- **리프레시는 HttpOnly 쿠키**: JavaScript가 못 읽어 XSS로 훔쳐 가기 어렵고, `Path=/api/v1/auth`, `SameSite=Strict`로 꼭 필요한 요청에만 실립니다.
- **회전·폐기는 조건부 UPDATE 한 문장**: `WHERE 해시 = 옛 값 AND 폐기 안 됨 AND 만료 안 됨`. 영향 행이 1이면 성공이라 동시 요청에도 한 쪽만 이깁니다.
- **인증 경로에서는 Bearer 헤더 무시** (로그아웃 제외): 만료된 토큰을 단 채 `/auth/refresh`를 불러도 막히지 않게.

## 주의할 점

- **로그아웃·정지 후에도 액세스 토큰은 최대 15분 유효**합니다. 요청마다 DB를 보지 않는 대가입니다. 즉시 차단이 필요해지면 Redis 차단 목록을 추가합니다.
- **두 탭이 동시에 재발급**하면 한 쪽이 옛 토큰을 쓴 셈이 되어 세션이 폐기될 수 있습니다 (다시 로그인). 프론트는 재발급을 한 번에 하나만 보내 주세요.
- 리프레시 유효기간 14일은 **로그인 시점부터 고정**이고 재발급해도 늘지 않습니다.
- 키는 `.env`의 `JWT_SECRET_K1` (`openssl rand -base64 32`). 32바이트 미만이면 앱이 시작되지 않습니다. 로컬 http에서 쿠키를 쓰려면 `JWT_REFRESH_COOKIE_SECURE=false`.
- `revoke_reason`의 `ROTATED`는 쓰지 않습니다 (행을 새로 만들지 않기 때문).
- TTL은 지금 `application.yml`(`jejari.jwt.*`)에 있습니다. `app_policy`로 옮기려면 ERD 패치가 필요합니다.
