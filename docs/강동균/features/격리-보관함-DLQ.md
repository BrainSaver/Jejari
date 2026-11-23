# 격리 보관함 (DLQ) · 다시 넣기

작성자: 강동균 · 코드 위치: `src/main/java/com/jejari/event/dlq/` · 도구 `tools/dlq.sh` · 절차 `docs/concurrency.md` §12.1

> 전체 점검표·방식 비교: [데이터-유실-점검](데이터-유실-점검.md)

## 한 줄 요약

Kafka 소비자가 **처리하지 못한 메시지를 버리지 않고** `<토픽>.DLT`에 옮겨 두고, 원인을 고친 뒤 그 소비자만 다시 처리하게 한다.

## 어떻게 동작하나

```
소비자 실패 (형식 오류 / 3번 다시 해도 실패 / 처리 실패)
  → DeadLetters.park: 원본 키·값 + 헤더(그룹·이유·출처·오류·시각) → jejari.reservation.DLT 등 (30일 보관)
  → A-10 "격리 보관" 건수, 최근 5분 1건↑이면 주의
원인 해결 → tools/dlq.sh replay reservation notification
  → 원래 토픽에 헤더 dlt-replay-for=notification 붙여 다시 넣음 → notification만 처리, 다른 그룹은 건너뜀
```

## 왜 이렇게 했나

- 예전에는 알림(3번 실패), seat-release(처리 실패), 깨진 메시지(5곳)를 **로그 한 줄만 남기고 버렸다**. seat-release 주석의 "재시작 때 다시 온다"도 틀렸다 (묶음 소비라 끝나면 커밋)
- DB 테이블 대신 Kafka 토픽: 원본·키(순서)를 그대로, 스키마 변경 없이
- 실측: 알림 저장을 일부러 실패시키고 → 보관 → 다시 넣기 → 알림 3건 = 예약 3건, 두 번 넣어도 3건 (멱등)

## 주의할 점

- 형식 오류(`malformed`)는 코드를 고치기 전에 다시 넣으면 또 보관함으로 온다 → replay는 기본으로 빼고, 고친 뒤 `replay … malformed`
- DB·Redis·MongoDB 장애처럼 기다리면 낫는 실패는 보관함이 아니라 무한 재시도 (밀림으로 보임)
- 보관함에도 못 넣으면 예외를 던져 커밋하지 않음 (버리지 않고 멈춤)
- 행동 로그 보내기는 이와 별개로 실패 시 3번 재시도 (`BehaviorPublisher`)
- 트러블슈팅: [소비자가 처리하지 못한 메시지가 사라짐](../troubleshooting/20261120-소비자가-처리하지-못한-메시지가-사라짐.md)
