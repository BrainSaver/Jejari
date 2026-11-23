# [replay] 클릭 입력 장치를 추가하자 예전 녹화 이벤트에서 NullPointerException

| 항목 | 내용 |
|---|---|
| 작성자 | 강다인 |
| 날짜 | 2026-11-23 |
| 상태 | 해결 (배포 전 단위 테스트에서 발견) |
| 관련 커밋·이슈 | `6077ed5` (LINEAR_MOUSE, 녹화 `pointer`) |

## 1. 문제

- 현상: 녹화 클릭에 입력 장치(`pointer`: m·t·p·k)를 받게 하자 `ReplayBatchTest` 3개가 `NullPointerException`으로 실패
- 재현 조건: `pointer`가 없는 이벤트 (예전 녹화기, CLICK이 아닌 이벤트)
- 영향: 배포됐다면 **예전 녹화기를 쓰는 화면의 녹화 요청 전체가 500** → 녹화가 끊겨 재생·마우스 규칙 모두 못 씀

## 2. 원인

- `ReplayBatch.sanitize`에서 `POINTERS.contains(e.pointer())`로 허용값을 검사
- 최종 원인: `Set.of(...)`로 만든 불변 Set은 **`contains(null)`이 false가 아니라 예외**를 던진다

```
java.lang.NullPointerException
  at java.util.Objects.requireNonNull
  at java.util.ImmutableCollections$SetN.contains
  at com.jejari.replay.ReplayBatch.sanitize(ReplayBatch.java:129)
```

## 3. 해결

| 선택지 | 장점 | 단점 | 채택 |
|---|---|---|---|
| `HashSet`으로 바꿈 | null을 그냥 false | 불변성을 잃음 | |
| null을 먼저 거른 뒤 `contains` | 불변 Set 유지, 의도가 코드에 보임 | 조건 하나 더 | ✓ |

- 바뀐 코드 위치: `src/main/java/com/jejari/replay/ReplayBatch.java` (`e.pointer() != null && POINTERS.contains(...)`)
- 시험: `ReplayBatchTest` "클릭 입력 장치는 정해진 값만 클릭에만 남긴다" (m·t는 남김, 모르는 값·예전 녹화기·클릭 아닌 이벤트는 null)

## 4. 수치

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| ReplayBatchTest | 3 실패 | 전부 통과 |

## 5. 정리

- 면접용 한 줄 요약: "선택 필드를 추가하면서 `Set.of().contains(null)`이 예외를 던지는 걸 놓쳐 예전 클라이언트 요청이 500이 될 뻔했는데, 기존 형식 이벤트를 쓰는 테스트가 배포 전에 잡아 줬다."
- 다음에 비슷한 상황이면: 새 필드를 받을 때는 "그 필드가 없는 예전 요청"으로 한 번 돌려 본다. `Set.of`·`Map.of`·`List.of`는 null을 받지 않는다
