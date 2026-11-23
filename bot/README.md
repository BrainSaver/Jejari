# 봇 대회 — 봇 꾸러미 (팀원 전달용)

봇만 따로 받아 보기 쉽게 모아 둔 것입니다. 카톡으로 보낼 때는 `bot.tar.gz`(저장소에는 안 올림)를 쓰세요.

> **원본은 `loadtest/contest/` 입니다. 여기는 사본입니다.** 대회 진행 화면·평가는 원본을 읽습니다.
> 원본을 고친 뒤에는 이 폴더도 맞춰 주세요 — 11-23에 실제로 한 번 어긋났습니다
> (`공격자-지시문.md`에 `bot.json` 요구가 생겼는데 이쪽은 옛 버전이었고, 두 봇의 `bot.json`도 빠져 있었음).
>
> ```bash
> for d in botb botc; do cp loadtest/contest/$d/{README.md,bot.json} bot/$d/; done
> cp loadtest/contest/botb/bot.cjs bot/botb/ && cp loadtest/contest/botc/bot.js bot/botc/
> cp loadtest/contest/{공격자-지시문.md,사람-참가-안내.md} bot/
> ```

| | 만든 사람 | 파일 |
|---|---|---|
| `botb/` | 길동현 | `bot.cjs` · `bot.json` · `README.md` |
| `botc/` | 강동균 | `bot.js` · `bot.json` · `README.md` (수법·1차 결과 포함) |
| `공격자-지시문.md` | 강동균 | 봇을 만들 때 받는 지시문 (`bota`·`botd` 쓰실 때) |
| `사람-참가-안내.md` | 강동균 | 사람 참가자에게 주는 안내문 |

`node_modules`는 뺐습니다 (19MB). 각 봇 폴더에서 `npm i playwright && npx playwright install chromium` 하세요.
`bot.json`은 대회 진행 화면(`node loadtest/contest/web/server.js`)이 봇을 돌리는 방법을 읽는 파일입니다 —
없으면 진행 화면에 못 나갑니다.

## 지금 상태 (2026-11-23 기준)

- 제출된 봇은 **botb·botc 둘뿐**입니다. `bota`(김성원)·`botd`(조영준)는 아직 없습니다.
- 1차 대회는 **11-19**에 돌았고, **사람 참가자가 0명**이었습니다.
- 1차 결과: 두 봇 다 24석씩 가져갔고 **규칙이 연 케이스는 0건**입니다 (botb 35점·botc 5점은 점수만).
- 그래서 모델 비교표(규칙 · Isolation Forest · 딥러닝)는 아직 못 만듭니다 —
  봇을 하나씩 빼며 학습하려면 4개가 필요하고, 사람 오탐을 보려면 사람 데이터가 필요합니다.

## 봇을 새로 만드실 분께

`공격자-지시문.md`를 **새 Claude 세션**에 붙여 넣고 시작하세요. 다른 봇의 코드는 안 보는 것이
원래 규칙이었는데(서로 모르는 봇 4개), 1차가 끝나 수법을 공개하는 단계라 같이 넣었습니다.
**다만 botb·botc를 보고 비슷하게 만들면 "서로 다른 수법 4개"라는 전제가 흐려집니다** —
돌리는 법만 참고하시고 수법은 직접 정하시는 쪽을 권합니다.
