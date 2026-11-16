# Redis Lua 스크립트 (좌석 선점·매수 한도)

담당 B 강동균 · 기능 RSV-02·RSV-03·RSV-05 · 절차 원천 `docs/concurrency.md` §14.2

좌석 경쟁과 매수 한도의 **1차 판정**을 Redis에서 원자적으로 처리하는 스크립트입니다.
최종 판정은 언제나 Oracle입니다 (§0.1). Redis가 통과시켜도 DB가 거절하면 거절입니다.

Spring 뼈대가 아직 main에 없어서 지금은 여기 두고, 뼈대가 올라오면
`src/main/resources/lua/` 로 옮겨 `DefaultRedisScript`로 읽습니다.

## 스크립트

| 파일 | 쓰는 곳 | 하는 일 |
|---|---|---|
| `hold.lua` | §2.1-4 선점, §9.6 취소표 제안 | 구간 키 전부를 한꺼번에 `SET`. 하나라도 남이 쥐면 **아무것도 안 쓰고** 0 |
| `release.lua` | §2.3 롤백 보상 | 값이 내 예약인 키만 DEL |
| `extend.lua` | §4.2-1 결제 연장, §11.2 릴레이 리스 | 값이 내 것일 때만 `PEXPIREAT`. **없는 키는 만들지 않음** |
| `rehold.lua` | §6.2-2 PG 결과 모름 | 내 것이거나 빈 키만 다시 잡음 |
| `sold.lua` | §5.1-10 확정 | 값과 상관없이 `SOLD:R`로 덮어씀 (DB가 확정했으므로) |
| `reoffer.lua` | §8.2·§14.3 커밋 후 보상 | 내 HOLD·SOLD 키를 `REOFFER:R`로. 이미 REOFFER면 TTL 안 늘림 |
| `limit_incr.lua` | §2.1-2 한도 1차 | 세 scope를 올리고 하나라도 넘으면 전부 원복 + 걸린 키 번호 반환 |
| `limit_decr.lua` | §2.3·§9.1-1·§14.3 | 표시 키를 선점한 쪽만 감소. **예약당 한 번만** |

## 왜 Lua인가

좌석 경쟁은 "전부 비었는지 확인 → 전부 쓰기"를 **쪼갤 수 없어야** 합니다. 애플리케이션에서
`GET` 여러 번 한 뒤 `SET` 하면 그 사이에 다른 요청이 끼어들어 같은 좌석이 두 번 팔립니다.
`WATCH`/`MULTI`로도 되지만 경쟁이 심하면 재시도가 폭증합니다. Lua는 Redis가 한 덩어리로
실행하므로 재시도 없이 한 번에 끝납니다.

매수 한도도 같습니다. 세 scope(열차당·1일·명절)를 올리다 세 번째에서 걸리면 앞의 둘을
되돌려야 하는데, 그 사이에 다른 요청이 읽으면 한도가 틀어집니다.

## 반환값 약속

| 스크립트 | 반환 |
|---|---|
| `hold` | `1` 전부 잡음 / `0` 실패 |
| `release` · `extend` · `rehold` · `reoffer` | 처리한 키 수 (좌석이면 K(§2.4)와 비교) |
| `sold` | 쓴 키 수 (= 키 개수) |
| `limit_incr` | `{1}` 통과 / `{0, i}` i번째 키에서 초과 |
| `limit_decr` | `1` 이번에 감소 / `0` 이미 처리된 예약 |

`limit_incr`의 키 순서는 **열차당 → 1일 → 명절**로 고정합니다. 돌려받은 `i`로
`LIMIT_PER_RUN`·`LIMIT_PER_DAY`·`LIMIT_PER_HOLIDAY`를 가립니다 (기능설계서 9장).

## 테스트

```bash
# Redis 띄우기 (동시성 §14.5 설정)
docker run -d --name jejari-redis -p 6379:6379 redis:7-alpine \
  redis-server --maxmemory-policy noeviction --appendonly yes --appendfsync everysec

pip install redis
python redis/test/lua_test.py       # 47항목 전부 OK여야 함
```

`--maxmemory-policy noeviction`이 중요합니다 (§14.5). 기본값(`allkeys-lru`)이면 메모리가
찰 때 `seat:hold`나 멱등 키가 조용히 사라져 같은 좌석이 두 번 팔릴 수 있습니다.

### 지금 확인되는 것

| | |
|---|---|
| **T1** | 같은 좌석에 100명 동시 선점 → 승자 **정확히 1명** |
| 한도 동시성 | 같은 CI 10명 동시 요청 → 한도 4만큼만 통과 |
| **T14** | 키가 없으면 `purchase_counter` 값으로 복원 후 판정 |
| **T26** | 보상이 두 경로로 와도 한도는 한 번만 감소 |
| 멱등성 | `hold` 재시도, `reoffer` 두 번 실행이 같은 결과 |
| 부작용 없음 | `extend`·`rehold`·`limit_decr`이 없는 키를 만들지 않음 |

T1의 "DB 쓰기 0건"과 T3·T5 재선점은 DB가 붙어야 확인됩니다. 뼈대가 올라오면 통합 테스트로
옮깁니다 (§16).

## 주의

- **키 이름의 `{}`는 자리표시자**입니다. 실제 키에는 중괄호가 없습니다
  (`seat:hold:9001:1001:0`). 클러스터 해시 태그와 다릅니다 (§14.4).
- 만료는 전부 **앱 시계로 계산한 `PXAT`(epoch ms)** 를 넘깁니다. Redis 서버 시계를
  쓰지 않습니다 (§0.7). `reservation.pay_deadline_at`과 같은 값이어야 합니다 (§0.2).
- 지금은 **단일 노드 전제**입니다. 클러스터로 가면 한 스크립트가 쓰는 키가 같은 슬롯에
  있어야 해서 키 이름에 해시 태그를 넣어야 합니다 (§14.4).
