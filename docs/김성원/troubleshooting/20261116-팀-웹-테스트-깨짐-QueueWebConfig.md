# [queue] 팀 구조로 옮기자 다른 파트의 웹 테스트가 깨짐

| 항목 | 내용 |
|---|---|
| 작성자 | 김성원 |
| 날짜 | 2026-11-16 |
| 상태 | 해결 |
| 관련 커밋·이슈 | `df1a618` |

> 파일 이름: `docs/김성원/troubleshooting/20261116-팀-웹-테스트-깨짐-QueueWebConfig.md`

## 1. 문제

- 현상: 대기열을 main의 루트 프로젝트로 옮기고 `./gradlew test`를 돌리자, **내 테스트가 아니라 팀의 기존 테스트**(`AuthWebTest`, `ErrorResponseTest`)가 실패했다. 내 코드를 추가하기 전에는 통과하던 것들이다.

```
AuthWebTest > 로그아웃하면_현재_세션_폐기와_쿠키_삭제() FAILED
    java.lang.IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:195
        Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException
            Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException
```

- 재현 조건: `src/main/java/com/jejari/queue/api/QueueWebConfig.java`를 추가한 상태에서 `./gradlew test`
- 영향: 내 변경이 main에 들어가면 CI가 깨지고, 다른 파트가 "내가 안 건드렸는데 왜 실패하지?" 하며 시간을 쓴다.

## 2. 원인

- 가설: 테스트 컨텍스트가 어떤 빈을 못 찾는다 → 에러 메시지의 `NoSuchBeanDefinitionException`이 어떤 빈인지 따라가 보니 내 `QueuePassService`였다.
- 최종 원인: 팀의 웹 테스트는 `@WebMvcTest`로 필요한 컨트롤러만 띄운다. 그런데 `@WebMvcTest`는 **`WebMvcConfigurer` 구현체를 컴포넌트 스캔으로 자동 포함**한다. 내 `QueueWebConfig`(좌석 API에 토큰 검사를 붙이는 설정)가 `WebMvcConfigurer`였고, 생성자에서 `QueuePassService`를 요구했다. 대기열 빈이 없는 다른 파트의 웹 테스트에서는 그 빈이 없어 컨텍스트 로딩이 실패했다.

## 3. 해결

| 선택지 | 장점 | 단점 | 채택 |
|---|---|---|---|
| A. 모든 웹 테스트에 `@MockitoBean QueuePassService` 추가 | 코드 변경이 작아 보임 | 남의 테스트를 전부 고쳐야 하고, 앞으로 생기는 테스트마다 같은 일이 반복됨 | |
| B. 빈을 요청이 올 때 꺼내 쓰기 (`ObjectProvider<QueuePassService>`) | 대기열 빈이 없는 환경에서도 설정이 읽힘. 남의 테스트를 안 건드림 | 한 단계 간접 호출 | ✓ |

- 선택한 이유: 내가 만든 설정이 다른 파트의 테스트에 영향을 주면 안 된다. 좌석 경로(`/api/v1/runs/*/seats`)가 호출될 때만 검증 서비스가 필요하므로 그때 꺼내도 동작은 같다.
- 바뀐 코드 위치: `src/main/java/com/jejari/queue/api/QueueWebConfig.java`, `QueueSeatTokenInterceptor.java` (`ObjectProvider.getObject()`)

## 4. 수치

| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| 팀의 기존 웹 테스트 (`AuthWebTest` 등) | 컨텍스트 로딩 실패 | 모두 통과 |
| 인프라 없이 도는 전체 테스트 | 실패 | 82개 통과 (이후 main 합치기 뒤 83개) |

- 측정 환경: 클라우드 컨테이너, JDK 21, Gradle 9.8, Spring Boot 4.0.9
- 측정 방법: `LC_ALL=C.UTF-8 ./gradlew test` (로케일 문제는 `20261116-한글-인코딩-컴파일과-테스트-리포트.md`)

## 5. 정리

- 면접용 한 줄 요약: "내 모듈을 팀 프로젝트에 합쳤더니 내 코드와 상관없는 다른 파트의 웹 테스트가 깨졌는데, `@WebMvcTest`가 `WebMvcConfigurer`를 자동으로 읽는다는 걸 알고 필요한 빈을 요청 시점에 꺼내도록 바꿔 다른 사람의 테스트를 건드리지 않고 해결했다."
- 다음에 비슷한 상황이면: 공통 영역에 `WebMvcConfigurer`·`@ControllerAdvice`·`Filter`처럼 슬라이스 테스트가 자동으로 읽는 빈을 추가할 때는, 그 빈이 **다른 파트의 빈에 의존하지 않게** 만든다. 합치기 전에 전체 `./gradlew test`를 돌려 남의 테스트까지 확인한다.
