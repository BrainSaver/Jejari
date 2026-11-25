# [auth] 비ASCII User-Agent로 로그인하면 500 (ORA-12899), 제어 문자면 헤더 원문이 에러 로그에

| 항목 | 내용 |
|---|---|
| 작성자 | 김성원 |
| 날짜 | 2026-11-25 |
| 상태 | 해결 (한글 UTF-8 바이트 UA는 400으로 남음, 아래 5장) |
| 관련 커밋·이슈 | 이 문서와 같은 커밋 (보안 점검 2026-11-25 §2.3) |

## 1. 문제

- 현상: `User-Agent`에 비ASCII 문자 200자(예: `é`×200)를 넣어 회원·관리자 로그인을 하면 **500 INTERNAL_ERROR**.
  ```
  ORA-12899: value too large for column "JEJARI"."AUTH_SESSION"."USER_AGENT" (actual: 400, maximum: 300)
  ```
- 고치다가 찾은 두 번째 현상: 값에 제어 문자(줄바꿈 등)가 섞이면 역시 500이 나고, **거절된 헤더 원문이 에러 로그에 그대로 찍혔다**.
- 재현 조건: `curl -H "User-Agent: $(printf 'é%.0s' {1..200})" -X POST /api/v1/auth/login …`
- 영향: 헤더 하나로 로그인 API를 500으로 만들 수 있다(오류 경보·로그 오염). 관리자 로그인은 제어 문자 제거도 안 해서 로그인 기록에 원문이 남았다.

## 2. 원인

- 최종 원인 1: `auth_session.user_agent`는 `VARCHAR2(300)` = **300바이트**인데 코드는 `length() > 300`, 즉 **300글자**로 잘랐다. `é`는 UTF-8 2바이트라 200자 = 400바이트, 한글은 3바이트라 100자만 넘어도 초과한다.
- 최종 원인 2: Spring Security의 `StrictHttpFirewall`은 헤더 값을 요청 입구가 아니라 **`getHeader()`를 호출하는 순간** 검사한다. 그래서 거절 예외(`RequestRejectedException`)가 컨트롤러 안에서 터지고, `GlobalExceptionHandler`가 모르는 예외로 보고 500 + 전체 메시지(헤더 값 포함)를 로그에 남겼다.
- 같이 찾은 것: UA 정리 코드가 회원·비회원 컨트롤러, `ClientInfo`, 행동 로그에 복사돼 있었고, 관리자 로그인(`AdminAuthController`)만 정리 없이 원문을 넘겼다.

```
ClientInfo            : userAgent.length() > 300 ? substring(0, 300)        -- 글자 수
DDL                   : user_agent VARCHAR2(300)                            -- 바이트
StrictHttpFirewall    : The request was rejected because the header: "User-Agent" has a value "…"  -- 500
```

## 3. 해결

| 선택지 | 장점 | 단점 | 채택 |
|---|---|---|---|
| 컬럼을 `VARCHAR2(300 CHAR)`로 | 코드 수정이 적다 | ERD 패치라 강동균 님 요청 필요. 글자 수로 잘라도 제어 문자·관리자 경로 문제는 남는다 | |
| UA 정리를 한 곳(`UserAgents`)에 모으고 **UTF-8 바이트 기준**으로 자름 + 방화벽 거절을 400으로 | 모든 로그인 경로가 같은 규칙. DB 변경 없음 | 컬럼보다 짧게 잘릴 수 있다(한글 100자) — UA라 문제 없음 | ✓ |

- 선택한 이유: DB를 바꾸지 않고 모든 경로를 한 번에 막는다. `ClientInfo` 생성자에서도 같은 함수를 불러 어느 경로로 들어와도 DB 직전에 한 번 더 걸러진다.
- 바뀐 코드
  - `common/web/UserAgents.java` (새 파일): 제어 문자 제거 + 300바이트 절단. 코드 포인트 단위로 잘라 이모지 같은 서로게이트 쌍을 중간에서 끊지 않는다.
  - `auth/session/ClientInfo.java`: 글자 수 절단 → `UserAgents.clean`
  - `member/web/MemberAuthController.java`, `member/guest/web/GuestController.java`: 복사된 함수를 `UserAgents.from`으로
  - `auth/admin/AdminAuthController.java`: 원문 전달 → `UserAgents.from` (제어 문자 제거 추가)
  - `common/error/GlobalExceptionHandler.java`: `RequestRejectedException` → 400 `INVALID_REQUEST`, 로그엔 예외 이름만

## 4. 수치

| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| `é`×200 UA로 로그인 | 500 (ORA-12899) | 200, 150자·300바이트로 저장 |
| 제어 문자 섞인 UA로 로그인 | 500 + 헤더 원문 로그 | 400 INVALID_REQUEST, 로그엔 예외 이름만 |
| 한글 UTF-8 바이트 UA로 로그인 | 500 | 400 (5장 참고) |

- 측정 환경: 로컬 Docker Compose + `bootRun`(운영 모드), Node `http` 모듈로 헤더 바이트를 그대로 보냄
- 측정 방법: 단위 `src/test/java/com/jejari/common/web/UserAgentsTest.java`, 통합 `SecurityAuditInfraTest`의 User-Agent 두 건

## 5. 정리

- 면접용 한 줄 요약: "DB 컬럼은 바이트, 코드는 글자 수로 잘라 비ASCII 헤더 하나로 로그인이 500이 됐다. UA 정리를 바이트 기준 공용 함수로 모으고 방화벽 거절을 400으로 바꿔 모든 로그인 경로의 500을 없앴다."
- 남은 것: 한글을 UTF-8 **바이트 그대로** 보낸 UA는 로그인 대신 400이 된다. Tomcat이 헤더를 ISO-8859-1로 읽어 UTF-8 바이트 일부(0x80~0x9F)가 제어 문자로 보이고 방화벽이 거절하기 때문이다. 일반 브라우저 UA는 ASCII라 실제로 걸릴 일은 드물다. 문제가 되면 `StrictHttpFirewall.setAllowedHeaderValues`로 규칙을 완화할 수 있다.
- 다음에 비슷한 상황이면: Oracle `VARCHAR2(n)`은 기본이 바이트다. 사용자 입력을 자를 때는 `String.length()`가 아니라 `getBytes(UTF_8).length`로 맞춘다.
