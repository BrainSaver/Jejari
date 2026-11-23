# 1차 봇 대회 데이터 (c1119, 2026-11-19)

**이 폴더가 1차 대회의 유일한 원본 사본입니다.** 대회는 강동균 PC(집계 PC)의 로컬 서버에서 돌았고,
다른 팀원 DB에는 없습니다. 2026-11-23에 저장소로 옮겼습니다 (`.gitignore`의 `!/data/ml/contest-c1119/`).

| 항목 | 값 |
|---|---|
| 실행 태그 · 운행 | `c1119` · `run_id 1000000` (관리자 화면으로 만든 대회 운행) |
| 참가 | 봇 12계정 (botb 6 · botc 6), **사람 0명** |
| 제출된 봇 | botb(길동현) · botc(강동균). bota·botd는 미제출 |

## 파일

| 파일 | 내용 | 만든 방법 |
|---|---|---|
| `events.jsonl` · `members.jsonl` · `runs.jsonl` | 모델 입력 모양으로 바꾼 행동 로그·회원 | `tools/ml/export_live.sh c1119` (11-19) |
| `features.csv` | 계정별 특징 | `tools/ml/features.py` (11-19) |
| `compare.md` | 모델 비교 결과 | `tools/ml/compare_dl.py` (11-19) |
| `raw/mongo-behavior_log.jsonl` | **원본** Mongo 행동 로그 240건 (대회 계정 12개 전부) | mongosh `EJSON.stringify` (11-23) |
| `raw/oracle.jsonl` | **원본** Oracle 행 181줄 — 한 줄에 한 행, `table` 칸으로 구분 | `JSON_OBJECT` (11-23) |

`raw/oracle.jsonl`에 든 테이블: `train_run` 1 · `member` 12 · `payment_method` 12 · `reservation` 30 ·
`reservation_seat` 48 · `payment` 30 · `seat_inventory` 48.

## 뺀 것과 그 이유

- **회원 이름·암호화 전화번호, 카드 끝 4자리** — 대회 계정이라 가짜 값이지만 원칙대로 뺐습니다.
  명의·카드·IP·기기는 원래 HMAC 해시라 그대로 둡니다 (`ci_hash` · `method_hash` · `ip_hash` · `device_hash`)
- **`suspicious_case`(의심 케이스)** — 11-23에 집계 PC에서 통합 테스트(`ModelAdminInfraTest`의 무작위 점검 표본)를
  돌리면서 대회 계정 2개에 ADMIN 케이스가 열렸습니다. 대회 결과가 아니라 넣지 않았습니다.
  **1차 대회에서 규칙·점수로 열린 케이스는 0건**입니다
- **`risk_score`** — 위험 점수 배치가 행동 로그로 다시 계산하는 값이라 원본이 아닙니다

## 원본과 다른 점 하나

운행 1000000의 **정차 시각(`train_stop`)** 은 원본이 아닙니다. `TrainAdminInfraTest`의 정리 구문이 관리자 대역을
통째로 지우던 버그(11-23 고침, `f683c38`)로 한 번 지워졌고, 시드 05가 같은 시각표로 다시 만들었습니다.
행동 로그·예약 분석에는 영향이 없어 내보내지 않았습니다.

## 다시 넣기

행동 로그는 `mongosh`로 `EJSON.parse` 해서 `behavior_log`에 넣으면 됩니다. Oracle 행은 FK 순서
(`train_run` → `member` → `payment_method` → `reservation` → `reservation_seat`·`seat_inventory` → `payment`)로 넣으세요.
모델·평가만 다시 돌릴 거면 다시 넣을 필요 없이 `events.jsonl`·`members.jsonl`을 그대로 쓰면 됩니다.
