# [test] 전체 infraTest에서만 대기열 동시성 테스트가 실패 (Spring 컨텍스트 4개 중복)

| 항목 | 내용 |
|---|---|
| 작성자 | 강다인 |
| 날짜 | 2026-11-16 |
| 상태 | 해결 |
| 관련 커밋·이슈 | `440ca43` |

## 1. 문제

- 현상: `./gradlew infraTest` 전체를 돌리면 김성원 님의 대기열 동시성 테스트(`QueueScenarioTest`)가 Redis 시간 초과로 실패. 그 테스트만 따로 돌리면 통과.
- 재현 조건: 메모리가 작은 PC에서 전체 infraTest 실행.
- 영향: 내 테스트를 추가한 뒤부터 다른 파트 테스트가 깨지는 것처럼 보임. main에 합치기 전 회귀 확인이 안 됨.

## 2. 원인

- 확인: 앱 전체를 띄우는 내 통합 테스트 4개(`MemberAuthInfraTest`, `OracleMemberIdentityInfraTest`, `MemberQueueE2EInfraTest`, `RiskCacheInfraTest`)가 `@SpringBootTest` 속성·`@MockitoBean`·`@Import`가 조금씩 달랐다.
- 최종 원인: Spring 테스트는 설정이 같아야 컨텍스트를 재사용한다. 설정이 다르니 **컨텍스트가 4개 따로 떠서 테스트가 끝날 때까지 메모리에 남았고**, 그 뒤 도는 대기열 동시성 테스트가 느려져 시간 초과가 났다.

## 3. 해결

| 선택지 | 장점 | 단점 | 채택 |
|---|---|---|---|
| `@DirtiesContext`로 테스트마다 컨텍스트 닫기 | 메모리 해제 | 매번 앱을 다시 띄워 더 느려짐 | |
| 대기열 테스트 시간 초과를 늘리기 | 간단 | 남의 테스트를 고치는 것, 원인은 그대로 | |
| 공통 애너테이션 하나로 설정을 모아 컨텍스트 1개 공유 | 빠르고 메모리 적음 | 테스트별로 설정을 바꾸고 싶을 때 여기에 모아야 함 | ✓ |

- `src/test/java/com/jejari/support/FullStackInfraTest.java`: Redis 15번 DB, 대기열 스케줄러·위험 점수 재동기화 끔(테스트가 직접 부름), 시드의 상시 판매 운행 하나
- 4개 테스트가 이 애너테이션만 쓰도록 정리

## 4. 수치

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| infraTest 전체 시간 | 561초 | 94초 |
| `QueueScenarioTest` (전체 실행 시) | 실패 | 통과 |
| 결과 | — | test 351개, infraTest 225개 통과 |

- 측정 환경: 로컬 PC + Docker Compose (Oracle·Redis·MongoDB)
- 측정 방법: `docker compose up -d` 뒤 `./gradlew infraTest`, Gradle 결과의 전체 시간

## 5. 정리

- 면접용 한 줄 요약: "통합 테스트마다 설정이 조금씩 달라 Spring 컨텍스트가 4개 떠서 메모리가 부족해졌고, 다른 파트의 동시성 테스트가 전체 실행에서만 시간 초과로 깨졌다. 설정을 공통 애너테이션 하나로 모아 컨텍스트를 공유하게 해서 561초 → 94초로 줄었다."
- 다음에 비슷한 상황이면: "따로 돌리면 통과, 같이 돌리면 실패"는 순서·공유 자원·메모리를 의심한다. 새 통합 테스트는 설정을 바꾸지 말고 `FullStackInfraTest`에 모은다.
