# 공통 실패 응답 · traceId · 시간대 점검

작성자: 조영준 · 코드 위치: `com.jejari.common.error`, `com.jejari.common.web`, `com.jejari.common.security.SecurityConfig`, `com.jejari.common.config.TimeZoneVerifier`

## 한 줄 요약

어느 기능에서 실패하든 같은 모양 `{ code, message, traceId }`로 응답하고, 그 traceId로 서버 로그를 바로 찾을 수 있게 한다.

## 어떻게 동작하나

```
요청 → TraceIdFilter(traceId 생성, 로그·응답 헤더에 넣음) → Spring Security → 컨트롤러
                                                    │ 401·403            │ 예외
                                                    ▼                    ▼
                                          ErrorResponseWriter    GlobalExceptionHandler
                                                    └──── { code, message, traceId } ────┘
```

- 서비스 코드에서는 **던지기만** 합니다. 응답으로 바꾸는 건 `GlobalExceptionHandler`가 합니다.

```java
// 1) 내 도메인의 에러 코드 enum (기능설계서 9장 표의 코드·HTTP 상태 그대로)
public enum ReservationErrorCode implements ErrorCode {
    SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "이미 선택된 좌석입니다.");
    // code()는 name(), status(), message() 구현 (CommonErrorCode 참고)
}

// 2) 실패할 때
throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
```

```json
{ "code": "SEAT_ALREADY_TAKEN", "message": "이미 선택된 좌석입니다.", "traceId": "3f9c…" }
```

- 응답 헤더 `X-Trace-Id`에도 같은 값이 있고, 그 요청 동안 찍힌 로그 줄마다 `[3f9c…]`가 붙습니다.

## 자동으로 처리되는 실패

| 상황 | code | HTTP |
|---|---|---|
| `@Valid` 실패, 깨진 JSON, 필수 헤더(`Idempotency-Key` 등) 누락 | `INVALID_REQUEST` | 400 |
| 로그인 안 함 | `AUTH_REQUIRED` | 401 |
| 권한 없음 (`/api/v1/admin/**`) | `ACCESS_DENIED` | 403 |
| 없는 경로 / 안 되는 메서드 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` | 404 / 405 |
| 그 밖의 예외 | `INTERNAL_ERROR` | 500 |

## 왜 이렇게 했나

- **traceId 필터를 Security보다 먼저 둠**: 인증 실패 응답에도 traceId가 들어가야 "로그인 안 돼요" 문의를 로그로 추적할 수 있습니다.
- **요청의 `X-Trace-Id`를 이어받음**: k6 부하 테스트에서 실패한 요청과 서버 로그를 맞춰 보려고. 영문·숫자·하이픈 8~64자만 받아 로그 위조(줄바꿈 삽입)를 막습니다.
- **500에는 내부 메시지를 숨김**: 예외 메시지에 SQL·내부 정보가 섞일 수 있어서. 원인은 traceId로 로그에서 찾습니다.
- **입력값을 응답·로그에 안 넣음**: 개인정보 평문 0건 목표 때문입니다. 검증 실패는 `필드명: 이유`만 알려줍니다.
- **시작할 때 시간대 점검 (`TimeZoneVerifier`)**: JVM·DB 세션·Oracle OS 중 하나라도 `+09:00`이 아니면 마감 시각이 9시간 어긋납니다. 경고는 놓치기 쉬워 **기동을 멈춥니다** (concurrency.md §0.7, T34).

## 주의할 점

- `BusinessException` 메시지에 이메일·전화번호 같은 개인정보를 넣지 마세요. 응답에 그대로 나갑니다.
- 에러 코드는 도메인별 enum으로 만들고 `CommonErrorCode`에 기능 코드를 추가하지 않습니다.
- 스케줄러·Kafka 컨슈머처럼 HTTP 요청 밖에서는 traceId가 비어 있습니다 (나중에 이벤트 봉투로 전달 예정).
- `SecurityConfig`는 **임시**입니다. 지금은 `/api/v1/auth/**`·헬스·메트릭 외에는 모두 401. JWT 필터가 붙으면 로그인한 요청이 통과합니다.
- 인프라 없이 도는 예시 테스트: `src/test/java/com/jejari/common/error/ErrorResponseTest.java`
