# 2차 봇 대회 데이터 (c1124d, 2026-11-24 · 사람 참가)

**이 폴더가 2차 대회의 원본 사본입니다.** 대회는 강동균 PC(집계 PC)의 로컬 서버에서 돌았고 다른 팀원 DB에는 없습니다.

| 항목 | 값 |
|---|---|
| 실행 태그 · 운행 | `c1124d` · `run_id 1000002` (2027-02-03 서울→부산, 열차 1). 같은 날 리허설 c1124v가 만든 운행을 `CONTEST_RUN_ID`로 다시 씀 |
| 시간 | 시작 14:20:35 · 사람 참가 14:23:50 ~ 14:48:39 · 평가 끝 14:50:34 (KST) |
| 참가 | 봇 12계정 (botb 6 · botc 6), **사람 27계정 · 30회 예매 (실제 사람 5명, 역할 5개)** |
| 사람 결과 | 30건 모두 결제(좌석 38). 별도로 2-1의 잡고 안 낸 선점 1건이 마감에 EXPIRED |

## 파일

| 파일 | 내용 | 만든 방법 |
|---|---|---|
| `events.jsonl` · `members.jsonl` · `runs.jsonl` | 모델 입력 모양의 행동 로그 345건·회원 39 | 진행 화면 평가 단계 (`tools/ml/export_live.sh c1124d`) |
| `features.csv` · `compare.md` | 계정별 특징 · 모델 비교 | `tools/ml/features.py` · `tools/ml/compare_dl.py` |
| `eval.txt` · `summary.json` | 규칙·점수 평가(`eval_contest.sh`) · 진행 화면 단계 결과 | 진행 화면 |
| `raw/mongo-behavior_log.jsonl` | **원본** Mongo 행동 로그 345건 (대회 계정 39개 전부) | mongosh `EJSON.stringify` |
| `raw/oracle.jsonl` | **원본** Oracle 행 467줄 — 한 줄에 한 행, `table` 칸으로 구분 | `JSON_OBJECT` |
| `이메일-정정.tsv` | 대회 중 고친 이메일 13건의 원래 값 | 아래 "이메일 정정" |

`raw/oracle.jsonl`에 든 테이블: `train_run` 1 · `member` 39 · `payment_method` 41 · `reservation` 43 · `reservation_seat` 87 ·
`payment` 42 · `seat_inventory` 86 · `reservation_history` 128 (취소 0건).

## 이메일 정정 (평가 전에 고침)

사람이 태그를 잘못 넣어 가입한 계정이 있어, **사람 참가를 끝내기 전에** `member.email`만 `ltc1124d.human.…`으로 바꿨습니다
(예약·결제·행동 로그는 member_id로 이어져 그대로). 원래 값은 `이메일-정정.tsv`.

- 역할 5 전체(g5.1~5.6): 리허설 명단(`ltc1124w…`)을 씀
- 역할 1의 2~6회차(g1.2~1.6): `ltc1124…` (d 빠짐)
- 역할 1이 나중에 바르게 다시 만든 g1.5·g1.6(예약 없음)은 `ltc1124dx…`로 비켜 평가에서 뺌
- g5.3은 가입 때 비밀번호를 잘못 쳐서, 대회 중 다른 사람 계정(g3.1)의 해시를 복사해 비밀번호를 `seoul2busan`으로 맞춤 (14:39)

## 결과 요약

- 규칙(케이스): botb 6/6 탐지 (`HEADLESS_HOLD`·`LINEAR_MOUSE`), botc 0/6 (순번조정 15초만), **사람 오탐 0건**
- 모델 비교(`compare.md`): tools/ml 사람 오탐 14.8%, 봇 100%. 모든 모델이 사람 오탐 5% 기준을 넘어 **판정 보류** (사람 27 < 30)

## 이 데이터의 한계

- 계정 27개지만 **실제 사람은 5명**. 같은 사람의 계정은 IP·기기·손놀림이 이어져 보입니다 — 역할(g 번호)별로 나눠 읽으세요
- 역할 3(한 컴퓨터 가족)은 6회 모두 같은 기기 (다른 기기가 없었음). 역할 1·2·5의 5~6회차 "다른 기기"도 지켜졌는지 확인 안 됨
- 역할 2의 2-1은 선점을 두 번 만들어(하나는 결제, 하나는 마감 만료) 예약 행이 31개처럼 보입니다
- 대회 운행 1000002에는 리허설(c1124v)의 봇 예약도 있습니다. 이 폴더에는 `ltc1124d.` 계정의 행만 넣었습니다

## 다시 넣기

1차(`contest-c1119/README.md`)와 같습니다. 행동 로그는 `EJSON.parse` 해서 `behavior_log`에, Oracle 행은 FK 순서
(`train_run` → `member` → `payment_method` → `reservation` → `reservation_seat`·`seat_inventory` → `payment` → `reservation_history`)로.
