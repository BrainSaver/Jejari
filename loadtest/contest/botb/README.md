# botb (길동현)

## 필요한 것

- Node 22 이상
- Playwright + Chromium: `npm i -g playwright && npx playwright install chromium` (이미 있으면 생략)
- 서버가 이용자 화면을 함께 내보내야 함 (브라우저로 동작). 로컬이면 `./gradlew buildFrontend bootRun`

## 실행

```bash
# 명의 만들기 (최대 6개, 이미 있으면 건너뜀). 가입 제한에 걸리면 잠시 뒤 같은 명령을 다시
TAG=<태그> MODE=signup node loadtest/contest/botb/bot.cjs

# 구매
BASE_URL=http://localhost:8080 RUN_ID=<대회 운행 ID> TAG=<태그> MODE=buy node loadtest/contest/botb/bot.cjs

# 둘 다
RUN_ID=9001 TAG=<태그> MODE=all node loadtest/contest/botb/bot.cjs
```

| 변수 | 기본값 | |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 로컬 주소가 아니면 `I_OWN_THIS_TARGET=yes` 필요 |
| `RUN_ID` | `9001` | |
| `TAG` | (필수) | 영문 소문자·숫자 |
| `MODE` | `all` | `signup` · `buy` · `all` |
| `COUNT` | `6` | 쓸 명의 수 (최대 6) |
| `HEADLESS` | 켜짐 | `no`면 창을 띄움 |

계정: `lt{TAG}.botb.g1.{1~6}@loadtest.example.com`. 끝나면 명의마다 결제한 좌석 수를 출력합니다.
