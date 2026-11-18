# 봇 대회

규칙·일정·평가 방법은 제안서 [`docs/강동균/봇-대회-제안.md`](../../docs/강동균/봇-대회-제안.md)가 원본입니다.
이 폴더는 실행에 필요한 것만 둡니다.

> **공격자는 대회가 끝날 때까지 이 폴더의 다른 봇(`bot*/`)과 `tools/ml/generate.py`(학습용 생성 수법)를 열어 보지 않습니다.**
> 블라인드 공격자(김성원·길동현)는 `loadtest/scenarios/12_bot_detection.js`, `loadtest/tools/eval_risk.sh`,
> `docs/조영준/features/부하테스트-봇-탐지.md`도 보지 않습니다 — 규칙 문턱이 적혀 있습니다.

## 순서

```bash
# 0) 대회 날 집계자 PC: 가입 제한을 대회 동안만 올리고 → 대회 운행을 만들고 → 사람 참가자 접속 주소를 띄운다
ADMIN_ID=… ADMIN_PW=… loadtest/contest/day.sh start
#    끝나면: ADMIN_ID=… ADMIN_PW=… loadtest/contest/day.sh restore   (가입 제한을 원래 값으로)
#    사람 참가자에게: loadtest/contest/사람-참가-안내.md

# 1) (day.sh가 대신 해 줌) 대회 운행만 따로 만들 때
ADMIN_ID=… ADMIN_PW=… loadtest/contest/setup.sh https://<서버>
#    → "대회 운행 ID = 1000123" 을 공격자 모두에게 알린다

# 2) 각자 봇 실행 (태그는 대회 날 집계자가 하나 정해 모두 같은 값을 쓴다)
#    봇마다 실행 방법은 그 폴더의 README 또는 파일 머리말

# 3) 평가 (서버의 저장소 폴더에서, docker compose가 떠 있는 곳)
loadtest/tools/eval_contest.sh <태그> <대회 운행 ID>          # 규칙 엔진: 막힌 층
tools/ml/export_live.sh <태그> data/ml/contest-<태그>           # 모델: 같은 데이터를 꺼내
python3 -I tools/ml/features.py data/ml/contest-<태그>
python3 tools/ml/score.py data/ml/model/model.pkl data/ml/contest-<태그>/features.csv
```

봇과 사람이 **같은 태그**를 쓰면 한 번에 평가됩니다 (사람 이메일 `lt{태그}.human.g{번호}.{번호}@…`).
모델 점수는 봇·사람을 **같은 폴더에서 같이** 매깁니다 — 판매 오픈 시각·연결 계정 같은 특징이 같은 기준으로 계산되게.

## 라벨 규칙 (모든 봇 공통)

계정 이메일에만 정답을 넣습니다. 서버는 라벨을 볼 길이 없습니다.

```
lt{태그}.{종류}.g{묶음}.{번호}@loadtest.example.com
   종류: bota · botb · botc · botd · human (사람 참가자)
   묶음: 한 사람(봇)이 쓰는 계정끼리 같은 번호
```

- **봇 하나당 명의(본인인증)는 최대 6개.** 모의 본인인증은 가짜 명의를 끝없이 만들 수 있어서,
  상한이 없으면 매수 제한이 의미가 없어집니다. 실제로는 "명의를 빌린 사람 6명"에 해당합니다
- 대회는 **집계자(강동균) 한 명의 로컬 서버**에서 봇 네 개를 한꺼번에 돌립니다 (2026-11-18 결정)
- 봇은 환경 변수 `BASE_URL` · `RUN_ID` · `TAG`를 받고, 명의를 미리 만드는 단계가 있으면 `MODE=signup` / `MODE=buy`로 나눕니다.
  실행 명령과 필요한 것은 폴더 README 한 장에

## 평가 결과 읽기 (`eval_contest.sh`)

| 칸 | 뜻 |
|---|---|
| 명의 | 서로 다른 본인인증(CI) 수 |
| 결제한 좌석 · 잡고 안 낸 좌석 | 공격이 얼마나 성공했나 |
| 선점 시도 · 제한 거절 | 좌석 잡기 요청 수와, 그중 매수 제한(422 `LIMIT_*`)에 걸린 수 |
| 탐지 계정 | 연결된 CI·결제 수단·기기에 의심 케이스가 열린 계정 수 |
| 최고 점수 · 순번 -초 | 위험 점수, 그만큼 다음 판매에서 대기열에서 밀리는 시간 |
| 막힌 층 | 매수제한 · 규칙(케이스) · 순번조정 · 관리자판정 중 걸린 것. 하나도 없으면 **뚫림** |

사람(`human`)은 탐지되면 오탐입니다.

## 폴더

| 폴더 | 공격자 | 부류 |
|---|---|---|
| `botc/` | 강동균 | 규칙을 아는 공격자 · 브라우저 봇 |
| (나머지) | 대회 전날까지 각자 추가 | |
