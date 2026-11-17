# 제자리 프론트엔드 (React + Vite)

사용자 화면은 시안(`docs/ui/U-*.html`) 디자인 그대로 **예쁘게**, 관리자 화면(`docs/ui/A-*.html`)은
**백엔드가 하는 일이 보이게** 만듭니다. 관리자 화면에는 숫자마다 출처(Redis 캐시 / Oracle 집계)와 지연,
응답 시간, `X-Trace-Id`를 함께 보여 줍니다.

## 실행

Node 22가 필요합니다. 백엔드(8080)를 먼저 띄웁니다 (`docker compose up -d` → IDE에서 앱 실행).

```bash
cd frontend
npm ci
npm run dev        # http://localhost:5173  (/api는 8080으로 넘김)
```

관리자 계정이 없으면 `.env`에 `JEJARI_ADMIN_BOOTSTRAP_ID`·`JEJARI_ADMIN_BOOTSTRAP_PASSWORD`를 넣고 앱을 다시 띄웁니다
(admin_user가 비어 있을 때 SUPERVISOR 1명을 만듦). 화면 주소: `/admin/login`.

## 배포

`npm run build` → `dist/`. Gradle `processResources`가 `dist/`를 jar의 `static/`에 넣어 **앱과 같은 출처**로 나갑니다
(CORS·쿠키 설정 불필요). 한 번에: `./gradlew buildFrontend bootJar`. `dist/`가 없으면 프론트 없이 백엔드만 빌드됩니다.

## 구조

```
src/
  api/client.js        모든 API 호출. 액세스 토큰은 메모리, 401이면 리프레시 쿠키로 한 번 재발급 후 재시도,
                       요청마다 상태·시간·X-Trace-Id 기록 (관리자 화면의 "이 화면이 보낸 요청")
  hooks/usePolling.js  N초마다 부르기. 탭이 숨으면 멈춤
  styles/tokens.css    디자인 토큰 (docs/ui/DESIGN.md · 시안 :root와 같은 값). 색·크기는 변수로만
  styles/admin.css     관리자 화면 스타일 (.admin 아래로 묶음)
  styles/user.css      사용자 화면 스타일 = 시안 U-*.html의 <style>을 .user 아래로 묶은 것
  admin/               관리자 화면 (A-01 로그인, A-04 실시간 현황, 나머지는 자리만)
  user/                사용자 화면 (U-03 검색, 나머지는 자리만)
```

## 화면을 추가할 때

1. `src/App.jsx`에 경로 추가
2. 새 최상위 경로(예: `/search`)면 서버 두 곳도 같이: `SpaForwardController`(새로고침 때 index.html), `SecurityConfig.SPA_PAGES`(GET 허용)
3. 시안 HTML의 마크업을 JSX로 옮기고 클래스 이름은 그대로 둡니다 (CSS를 다시 쓰지 않게)
4. API는 `api/client.js`의 `get`·`post`… 로만 부릅니다 (토큰·재발급·기록이 거기 있음)
5. 실패는 `ApiError`의 `code`(서버 오류 코드)로 분기하고, 화면에 `traceId`를 같이 보여 줍니다
