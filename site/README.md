# 문서 사이트 (jejari.pages.dev)

기능설계서, DB설계서·ERD, 화면설계, 기획서, 동시성 문서를 웹에서 보고 고치는 사이트입니다.

- 보기는 누구나 합니다.
- 고치려면 이름(브랜치 이름 5개 중 하나)과 숫자 4자리 PIN으로 들어옵니다. PIN은 처음 들어오는 사람이 정합니다.
- 고친 내용은 그 사람 이름 브랜치에 커밋됩니다. 저장 직전에 main을 그 브랜치에 먼저 합칩니다.
- 커밋 메시지는 Groq AI가 바뀐 줄을 보고 `타입(범위): 제목` 형식으로 써 주고, 저장 전에 고칠 수 있습니다.
- "main에 합치기"를 누르면 PR을 만들고 바로 merge합니다 (squash 아님, 이름별 커밋 보존).

## 구조

```
site/
├─ public/                 화면 (정적 파일)
│  ├─ index.html, app.js, style.css
│  └─ vendor/marked.min.js 마크다운 변환 (MIT)
├─ functions/api/[[path]].js  API (Cloudflare Pages Functions)
├─ lib/                    API가 쓰는 모듈: 설정, GitHub 호출, PIN 로그인, Groq
└─ test/                   가짜 GitHub와 API 흐름 검사
```

사이트가 읽고 고치는 파일은 `lib/config.js`의 `EDITABLE`에 적힌 문서뿐입니다.

| 메뉴 | 파일 |
|---|---|
| 기획서 | `README.md` |
| 기능설계 | `docs/기능설계서.md` |
| DB설계 · ERD | `docs/DB설계서.md`, `docs/erd/jejari-erd.html` (편집기), 저장은 `docs/erd/exports/{이름}-{날짜}.json` |
| 화면설계 | `docs/ui/index.json` (목록), `docs/ui/{화면ID}.md` (설명), `docs/ui/{화면ID}.html` (시안) |
| 동시성 | `docs/concurrency.md` |

## 처음 설정 (한 번)

### Cloudflare (Pages 프로젝트 `jejari` → Settings)

| 종류 | 이름 | 값 |
|---|---|---|
| KV 바인딩 | `AUTH` | 새 KV 네임스페이스 (예: `jejari-auth`). PIN 해시·로그인 세션 저장 |
| 비밀값 | `GITHUB_TOKEN` | fine-grained 토큰. 저장소 `brainsaver/jejari`만, 권한 Contents 읽기·쓰기 + Pull requests 읽기·쓰기 |
| 비밀값 | `GROQ_API_KEY` | Groq API 키. 없으면 기본 형식 메시지로 저장 |
| 비밀값 (선택) | `PIN_PEPPER` | 아무 긴 문자열. 넣으려면 **누구도 PIN을 정하기 전에** 넣어야 함 (나중에 넣으면 기존 PIN이 안 맞음) |

선택 변수: `GROQ_MODEL` (기본 `llama-3.3-70b-versatile`), `ADMIN_NAME` (PIN 초기화 권한, 기본 `DongHyeon`).

### 배포

GitHub Actions는 쓰지 않습니다. main에 `site/` 수정이 올라오면 Claude가 `site` 폴더 안에서 직접 배포합니다 (Functions도 함께 올라감).

```bash
cd site && npx -y wrangler@4.40.0 pages deploy public --project-name jejari --branch main --commit-dirty=true
```

필요한 값: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` 환경 변수 (Claude 클라우드 환경에 들어 있음).

## 팀원이 알아둘 것

- 사이트에서 저장하면 **원격** 이름 브랜치에 커밋이 생깁니다. 내 PC에서 그 브랜치로 작업하기 전에 `git pull`을 하세요.
- 저장할 때 main과 충돌이 나면 저장이 멈추고 충돌 파일을 알려줍니다. PC에서 `git pull origin main`으로 충돌을 풀고 push하면 다시 저장할 수 있습니다.
- 같은 섹션을 누가 먼저 바꿨으면 "그 사이 바뀌었어요" 창이 뜨고, 내가 쓴 글과 최신 내용의 차이를 보여줍니다.
- 되돌리기는 내가 만든 수정만, 내 브랜치에서만 됩니다. 지우지 않고 "revert:" 커밋을 새로 남깁니다.
- 다른 사람 브랜치는 볼 수만 있습니다.

## 로컬에서 돌려 보기

```bash
cd site
node test/mock-github.mjs .. 9100 &        # 가짜 GitHub (레포 문서로 시작)
npx wrangler@4.40.0 pages dev public --kv AUTH \
  --binding GITHUB_TOKEN=test-token --binding GITHUB_API=http://127.0.0.1:9100 --port 8790
node test/api-test.mjs http://127.0.0.1:8790 http://127.0.0.1:9100   # API 흐름 39개 검사
```

브라우저로 http://127.0.0.1:8790 을 열면 가짜 GitHub 위에서 로그인·저장·합치기를 해 볼 수 있습니다.
