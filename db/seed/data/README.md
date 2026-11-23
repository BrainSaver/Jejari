# 공공데이터 원본 CSV 두는 곳

공공데이터포털에서 받은 파일을 그대로 여기에 넣습니다. 받은 파일 이름을 바꾸지 않습니다
(날짜가 붙어 있어 어느 시점 데이터인지 알 수 있습니다).

| # | 데이터셋 | 받는 곳 |
|---|---|---|
| 1 | 한국철도공사_철도운영정보_역코드 | https://www.data.go.kr/data/15138467/fileData.do |
| 2 | 한국철도공사_KTX 노선별 역정보 | https://www.data.go.kr/data/15127571/fileData.do |
| 3 | 한국철도공사_철도운행거리_경부고속선 | https://www.data.go.kr/data/15128657/fileData.do |
| 4 | 한국철도공사_차세대예약발매_차량유형코드 | https://www.data.go.kr/data/15137680/fileData.do |

네 개 모두 CSV·무료·이용허락 범위 제한 없음입니다. 다운로드에 포털 로그인이 필요합니다.

## 오픈 API로 받은 것

| 파일 | 데이터셋 | 받는 법 |
|---|---|---|
| `tago-train-info.json` | [국토교통부_(TAGO)_열차정보](https://www.data.go.kr/data/15098552/openapi.do) | `node tools/fetch-train-api.js` |

실제 시각표와 운임입니다. 서비스키는 `.env`의 `DATA_GO_KR_KEY`를 씁니다 (커밋 금지).
받은 응답을 그대로 저장하므로 CSV와 똑같이 "이 값이 어디서 왔나"를 추적할 수 있고,
CI는 네트워크 없이 이 파일만 보고 검사합니다.

**오퍼레이션 이름은 첫 글자가 대문자입니다** (`GetCtyCodeList`). 소문자로 부르면
`NO_OPENAPI_SERVICE_ERROR(12)`가 나는데 "키가 없다"가 아니라 "그런 경로가 없다"는 뜻이라
한참 헤맬 수 있습니다.

파일이 들어오면 `db/seed/` 의 01·02 SQL을 실제 값으로 교정합니다.
대조 결과와 고쳐야 할 것은 `db/seed/README.md` "공공데이터 대조" 절에 있습니다.

CSV는 원본이라 깃에 올립니다 (용량이 작고, 어느 값에서 왔는지 추적되어야 합니다).
