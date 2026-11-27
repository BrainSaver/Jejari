# 연습 봇 30종 (규칙·모델 보강용)

> 만든 사람: 강동균 · 2026-11-23

**대회 봇(botb·botc)은 2차 대회(11-26) 채점용이라 손대지 않습니다.** 규칙을 보강할 때는 이 연습 봇으로 시험하고,
2차에서 대회 봇으로 처음 확인합니다. 대회 봇을 보고 규칙을 맞추면 2차 결과가 "시험 문제를 보고 푼" 숫자가 되기 때문입니다.
수법은 botb·botc 코드를 보지 않고, 실제 예매 매크로가 흔히 하는 짓에서 골랐습니다.

## 돌리기

앱이 떠 있고(최신 화면, `./gradlew buildFrontend bootRun`), 판매 중인 운행이 있어야 합니다.

```bash
RUN_ID=<판매 중 운행> TAG=<새 태그> node loadtest/contest/practice/run.mjs
```
```bash
loadtest/tools/eval_contest.sh <태그> <운행>
```

- 30종 × 명의 3개 + 합성 사람 12명 ≈ 5~8분. 일부만: `PROFILES=botfast,botsteady HUMANS=0`
- **앱은 신뢰 프록시 모드로 띄웁니다**: `--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1`. 봇은 프로필마다, 합성 사람은 사람마다 다른 IP(`X-Forwarded-For`)로 보입니다 (실제 매크로 운영자가 프록시를 쓰는 것과 같음). 이 모드가 아니면 30종이 한 IP에서 가입해 IP당 가입 제한에 전부 막힙니다
- 운행 하나에 좌석 약 525석이라 30종 × 3명의는 한 운행에 들어갑니다. `botrunhop`은 운행 번호 +2·+4 대기열에도 들릅니다
- 진행 화면(`localhost:8098`)에는 안 나옵니다 (대회 봇과 섞이지 않게)

## 프로필 (성질별)

| 성질 | 봇 |
|---|---|
| 속도 | botfast (쉬지 않음) · botjitter (50~150ms 흔들기) · botburst (쉬다가 몰아침) |
| 일정 간격 | botsteady (1초) · botslow (3초) |
| 화면 흐름 건너뜀 | botnosearch (조회 없이 대기열) · botnomap (좌석표 안 봄) · botpollmap (좌석표 0.2초 새로고침) · botdirect (조회·좌석표 없이 바로) |
| 좌석 | bothammer (같은 좌석 15번) · botspray (1석씩 여러 번) · botsniper (맨 앞 좌석만) · botbigparty (항상 4석) |
| 선점만 | bothoarder (결제 안 함) · botholdloop (선점 → 취소 반복) |
| 다계정 | botsync (같은 순간) · botstagger (간격 두고) · botshareddev (기기 공유) · botsharedcard (카드 공유) · botsameci (한 명의 여러 계정) |
| 기기 위장 | botrotdev (요청마다 기기 바꿈) · botnodev (기기 ID 없음) · botua (스크립트 User-Agent) |
| 되팔기·한도 | botresell (결제 1~2분 뒤 취소) · botlimit (매수 제한 탐색) |
| 대기열 남용 | botqueuespam (들어갔다 나갔다) · botrunhop (다른 운행 들렀다) |
| 사람 흉내 | botmimic (쉬고 둘러봄) · botmimicfast (선점 순간만 빠름) · botlate (1분 늦게 천천히) |
| 합성 사람 | human (12명, 화면 녹화도 보냄) — **진짜 사람이 아닙니다.** 오탐은 대회 사람 참가자로만 잴 수 있습니다 |

## 2단계 (제안 규칙 피하기, 9종)

`GEN=2`로 돌립니다 (`GEN=all`은 39종). 제안 규칙을 알고 피하는 봇입니다: botstep · botstepjit · botsearchfast · botresi · botpace · botsmallbuy · botretrydiff · botghostsync · botghost.
제안 규칙은 아직 코드가 아니므로 모의 채점합니다.

```bash
python3 -I loadtest/contest/practice/simulate_rules.py data/ml/practice-<태그> --step 0.5 --times 2
```

결과와 해석: [연습봇-평가와-규칙-제안.md 3-2절](../../../docs/강동균/features/연습봇-평가와-규칙-제안.md)

## 3단계 (화면 녹화 꾸미기, 9종)

`GEN=3`으로 돌립니다 (`GEN=all`은 45종). 화면 녹화 규칙(HEADLESS_HOLD·LINEAR_MOUSE)과 INHUMAN_STEP이 케이스 규칙이 된 뒤를 가정한 봇입니다.
모두 2단계의 피하기(단계 사이 0.6~1초, 조회 2번, 명의마다 IP)를 갖추고 녹화를 꾸미는 방법만 다릅니다.

| 봇 | 녹화 |
|---|---|
| botrecone | 녹화 이벤트 하나만 (녹화 0건 피하기) |
| botrecclick | 클릭만, 마우스 궤적 없음 |
| botrectouch | 직선 궤적 + 클릭을 터치라고 신고 |
| botreclinear | 직선 등속 궤적 + 마우스 클릭 (기준선: 지금 규칙이 잡아야 함) |
| botrecbezier | 휜 곡선, 등간격·손떨림 없음·도착 즉시 클릭 |
| botrectemplate | 사람 같은 궤적 하나를 모든 계정이 재사용 |
| botrecjump | 클릭 자리에 점 하나만 (순간이동) |
| botrecmobile | 휴대폰인 척, 터치 클릭만 |
| botrecreal | 사람과 같은 녹화 + 2단계 피하기 전부 (상한) |

## 4단계 (진짜 브라우저, 4종) — `browser.cjs`

2차 대회에서 규칙을 다 피한 botc(진짜 브라우저 + 곡선 마우스 + 느린 단계)에서 한 가지씩 더 나아간 봇. Playwright가 필요합니다 (`loadtest/contest/botb`에서 `npm i playwright && npx playwright install chromium`).

```bash
RUN_ID=<판매 중 운행> TAG=<새 태그> node loadtest/contest/practice/browser.cjs
```

| 봇 | 더한 것 |
|---|---|
| botwsearch | botc + 열차 찾기 → 검색 결과의 [예매하기] (botc의 흔적 "조회 없이 대기열"을 지움) |
| botwtremor | + 손떨림 1~3px · 피츠 법칙 이동 시간 · 가끔 지나쳤다 되돌아옴 |
| botwspread | + 명의마다 다른 IP · 30분에 걸쳐 (`SPREAD_LONG_MIN`) |
| botwfast | 손떨림 + 단계를 최대한 빨리 (INHUMAN_STEP 1초 기준 근처) |

`ACCOUNTS`(기본 3) · `SEATS`(기본 2) · `SPREAD_MIN`(기본 3분) · `HEADLESS=0`(창 띄우기). 결과: [2차 대회 결과 분석 5절](../../../docs/강동균/features/2차-대회-결과-분석.md)

## 사람 변형 (오탐 시험, 10종)

`GEN=h`로 돌립니다. 진짜 사람이 하는데 규칙에 걸리기 쉬운 행동입니다 (kind가 `human`으로 시작하면 평가가 사람으로 세어, 탐지되면 오탐).
humanfast (손 빠름) · humandouble (두 번 클릭) · humanretry (인기 좌석 경쟁) · humanmobile (휴대폰) · humankeyboard (키보드) · humanlink (조회 없이 링크) ·
humanfamily (가족 3명 한 컴퓨터) · humannat (같은 IP 6명) · humancancel (결제 뒤 1~2분 취소) · humanslow (천천히).
결과: [오탐 시험](../../../docs/강동균/features/오탐-시험-사람-변형.md)

## 결과 읽기

`eval_contest.sh` 표에서 봇마다 **탐지 계정**(케이스가 열린 계정)과 **순번 -초**를 봅니다.
점수만 오른 것은 탐지가 아닙니다. `human`의 최고 점수·탐지 계정이 오탐의 참고값입니다.

## 실제 서비스처럼 섞기 (2026-11-27)

봇만이 아니라 **일반·가족·단체·취소표 대기·서툰 사람**과 **사재기·되팔기·매크로·다계정·카드 대입**을 한 운행에 실제 비율로 섞어,
매진·취소표·단체 배정까지 한꺼번에 일어나게 한 뒤 "결과가 맞나"를 본다.

```bash
# 1) 판매 중 운행 하나 (명절 행사, 열차 1 = 1,050석). 관리자 계정은 .env의 JEJARI_ADMIN_BOOTSTRAP_*
ADMIN_ID=… ADMIN_PW=… NAME=mixed-$(date +%m%d) loadtest/contest/setup.sh
# 2) 섞어 돌리기: 봇 49종 × 3명의 + 사람 변형 16종 × 8배 + 합성 사람 300 ≈ 850명 → 수요가 좌석보다 많아 매진
RUN_ID=<운행> TAG=<새 태그> GEN=all ACCOUNTS=3 HUMAN_SCALE=8 HUMANS=300 WAIT_MAX_SEC=900 node loadtest/contest/practice/run.mjs
# 3) 채점
loadtest/tools/eval_mixed.sh <태그> <운행>     # 정합성 0건 · 종류별 표 받은 비율 · 사람/봇 좌석 몫 · 취소표 · 단체
loadtest/tools/eval_contest.sh <태그> <운행>   # 봇 탐지 · 사람 오탐 · 어느 방어층에서 막혔나
```

| 사람 (GEN=m, 탐지되면 오탐) | 하는 일 |
|---|---|
| humansolo | 혼자 1석. 매진이면 절반은 취소표 대기 |
| humanparty | 가족 대표가 2~4석을 한 번에, 어른·아이·경로 섞음. 매진이면 대기 |
| humanabandon | 선점하고 결제 안 함 (bothoarder와 같은 행동 → 사람인데 걸리는지) |
| humanwait | 1분 늦게 와서 못 잡으면 바로 취소표 대기 → 제안 오면 결제 |
| humangroup | 단체 12~20명 신청 → 자동 승인(할당석 먼저, 대기열이 빈 뒤 일반 좌석) → 결제 |

- `HUMAN_SCALE=n`: 사람 변형(GEN h·m) 명의 수를 n배. 봇보다 사람이 훨씬 많은 실제 비율을 만든다
- `WAIT_MAX_SEC`(기본 360): 취소표 제안을 기다리는 시간. 취소표는 결제 뒤 취소(humancancel·botresell)와 선점 만료(10분 + 유예)에서 나오니 15분(900)쯤 준다
- `GROUP_WAIT_SEC`(기본 900): 단체 승인이 미뤄질 때 기다리는 시간. 오픈 직후엔 대기열이 차 있어 일반 좌석을 못 쓴다 (11-27 로컬: 7분 뒤 승인)
- `setup.sh`는 운행을 만들자마자 판매를 열어 **할당석이 안 생긴다** (할당석은 판매 전 SCHEDULED 운행에만). 그래서 단체는 일반 좌석으로 배정된다
- 메모리가 빠듯하면 Grafana·Prometheus·redis-exporter를 끄고 앱을 jar로: `java -Xmx1g -Duser.timezone=Asia/Seoul -jar build/libs/jejari-0.0.1-SNAPSHOT.jar --jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1`
