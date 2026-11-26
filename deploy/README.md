# 공용 서버 배포 (Google Cloud VM)

담당: 조영준 · 바탕: 강동균 님 조사 [`docs/강동균/features/배포-구글클라우드.md`](../docs/강동균/features/배포-구글클라우드.md) (선택지 비교·비용·막혔을 때 표)

```
인터넷 ──443──> Caddy (HTTPS, 인증서 자동) ──> 앱 jejari@8080 ──> Oracle·Mongo·Redis·Kafka (127.0.0.1에만)
                /actuator/* 는 health만 밖으로      (11-19부터 1대. jejari@8081은 꺼 둠)
```

VM 1대(e2-standard-4, 서울)에 로컬과 같은 `compose.yaml`을 올리고, 이 폴더의 파일만 더합니다.

| 파일 | 하는 일 |
|---|---|
| `compose.server.yaml` | Caddy 추가, Prometheus를 서버 앱 수집 설정으로 교체 (`-f`로 겹쳐 씀) |
| `Caddyfile` | HTTPS · 앱으로 보내기(재시작 중엔 최대 45초 기다림) · 지표 숨기기 |
| `prometheus.server.yml` | 앱(app-1) 수집. app-2 줄은 주석으로 남김 |
| `jejari@.service` | 앱 systemd 템플릿 (인스턴스 이름 = 포트) |
| `make-server-env.sh` | **내 PC에서** 서버용 `.env` 생성 (HMAC 키만 팀 공용, 나머지 새로) |
| `setup-server.sh` | **서버에서 한 번**: 패키지·docker 그룹·도메인·systemd (앱 1대 + 자동 배포 타이머) + 시간표 타이머 · 서비스 명령 sudo 허용) |
| `deploy.sh` | 배포: main(또는 `DEPLOY_SHA`) 빌드 → 켜 둔 앱만 재시작 → 배포한 커밋 기록 |
| `auto-deploy.sh` · `jejari-autodeploy.{service,timer}` | **자동 배포**: 10분마다 (관리자 화면 "배포" 버튼으로 바로도), CI 성공 + DB·인프라 변경 없음일 때만 `deploy.sh` |
| `tago-refresh.sh` · `jejari-tago.{service,timer}` | 실제 KTX 시간표(앞뒤 7일) 매일 갱신 |
| `model-refresh.sh` · `jejari-model.{service,timer}` · `ml/Dockerfile` | 위험 점수 모델 보조 의견 매시간 갱신 (최근 행동 로그 → `model_score`) |
| `model-retrain.sh` · `jejari-model-retrain.{service,timer}` · `model-lib.sh` | 위험 점수 모델 주간 재학습 + 자동 시험 (일요일 새벽, 통과하면 꺼진 후보 → A-09) |
| `grafana-alerting/` · `grafana-team-user.sh` · `sudoers-jejari` | Discord 경보 · Grafana 팀 계정 · 앱 주인의 좁은 sudo |

## 강동균 님 문서와 다른 점 (이유)

| 문서 | 여기 | 이유 |
|---|---|---|
| `http://IP:8080` 직접 공개 | Caddy로 **HTTPS**, 80·443만 엶 | 리프레시 쿠키가 Secure라 http에서는 브라우저가 저장·전송하지 않아 재발급이 전부 실패. 8080을 열면 `/actuator/prometheus`(내부 지표)도 공개됨 |
| 앱 1대 | 2대로 시작 → **11-19부터 1대** | 2대로 멀티 인스턴스 규칙을 확인했고(`docs/조영준/features/멀티인스턴스-코딩-규칙.md`, 대기열 입장 2배 문제를 실제로 찾음), PERF-01에서 CPU 4개 VM에서는 2대가 더 느려 1대로 (예매 p95 1대+해시4 4.9초 vs 2대 10~18초). 대가: **배포 때 약 40초~1분 느려짐**(Caddy가 최대 45초 기다렸다 보냄) |
| `User=ubuntu` | 실제 사용자로 채움 | GCP Ubuntu에는 `ubuntu` 계정이 없음 (`gcloud compute ssh`가 내 PC 사용자 이름으로 만듦) |
| 내 PC `.env`를 복사 | **새로 생성** | 로컬 비밀번호·임시 키가 서버로 가지 않게. HMAC 키만 팀 공용 |

도메인은 사지 않습니다. 처음엔 `외부IP.sslip.io`(IP를 이름으로 바꿔 주는 공개 DNS)로 시작했고, **11-18부터 팀장님이 만든 DuckDNS 이름**을 씁니다 (`.env`의 `JEJARI_DOMAIN`, 주소는 팀 채널). 둘 다 Let's Encrypt 인증서를 Caddy가 자동으로 받습니다. DuckDNS는 IP를 가리키는 방식이라 **VM을 껐다 켜 외부 IP가 바뀌면 DuckDNS에서 IP를 다시 맞춰야** 합니다. `setup-server.sh`는 정해 둔 이름을 덮어쓰지 않습니다.

## 순서

```bash
# ── 내 PC ──
gcloud compute instances create jejari --machine-type=e2-standard-4 \
  --image-family=ubuntu-2404-lts-amd64 --image-project=ubuntu-os-cloud \
  --boot-disk-size=50GB --boot-disk-type=pd-balanced --tags=jejari-web
gcloud compute firewall-rules create jejari-https --allow=tcp:80,tcp:443 --target-tags=jejari-web
#   8080·DB 포트는 열지 않음. SSH는 IAP로 좁히기 (강동균 님 문서 3-2절, 지우기 전에 IAP 접속 먼저 확인)
deploy/make-server-env.sh                                  # → ~/jejari-server.env

# ── 서버 ──
gcloud compute ssh jejari
git clone https://github.com/BrainSaver/Jejari.git && exit
gcloud compute scp ~/jejari-server.env jejari:~/Jejari/.env   # 내 PC에서
gcloud compute ssh jejari
cd ~/Jejari && bash deploy/setup-server.sh                  # 처음엔 docker 그룹 때문에 재접속 후 한 번 더
docker compose -f compose.yaml -f deploy/compose.server.yaml up -d
until [ "$(docker inspect -f '{{.State.Health.Status}}' jejari-oracle-1)" = healthy ]; do sleep 10; done
bash deploy/deploy.sh
```

> 위는 첫 설치(11-18 오전, OS Login 켜기 전) 순서. **지금은 OS Login이라** 접속하면 구글 계정 사용자가 되므로, 서버 `.env`를 다시 올릴 때는
> `gcloud compute scp ~/jejari-server.env jejari:/tmp/jejari.env` → 서버에서 `sudo install -o dita_810 -g dita_810 -m 600 /tmp/jejari.env ~dita_810/Jejari/.env && rm /tmp/jejari.env`

## 확인 (내 PC에서)

```bash
D=<외부IP를 하이픈으로>.sslip.io
curl https://$D/actuator/health                         # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code} %{content_type}
' https://$D/   # 200 text/html 여야 함 (화면). JSON NOT_FOUND면 화면 빌드 누락
curl -o /dev/null -w '%{http_code}\n' https://$D/actuator/prometheus   # 404 여야 함 (지표 숨김)
curl "https://$D/api/v1/runs?from=3900023&to=3900114&date=$(date -d '+3 days' +%F)"   # 9001 bookable:true
nc -zv -w 3 <외부IP> 8080; nc -zv -w 3 <외부IP> 1521   # 둘 다 실패해야 함
```

## 운영 규칙

- **자동 배포** (11-18~): 서버의 `jejari-autodeploy.timer`가 **10분마다** main을 확인해 `auto-deploy.sh`를 실행 (11-19 10분 → 20분, 11-26 다시 10분). 기다리기 싫으면 **관리자 화면 → 배포 → "지금 배포"** (아래). 서버에 비밀값을 더 두지 않음 (공개 저장소를 받고, CI 결과는 공개 GitHub API로 봄)

  | main의 새 커밋이 | 자동 배포는 |
  |---|---|
  | CI(빌드·테스트·ERD 검사) **진행 중** | 그보다 앞선 커밋 중 CI가 성공한 가장 최근 커밋을 배포 (최대 5개 거슬러 봄). 없으면 다음 차례에 (`WAITING_CI`) |
  | CI **실패** | 배포 안 함 (`CI_FAILED`), 다음 커밋을 기다림 |
  | 문서·테스트만 바뀜 | 빌드 없이 "반영됨"으로 기록 (`OK`) |
  | 코드·화면·빌드 설정이 바뀜 | **그 커밋으로 고정해 배포** (앱 재시작, 약 40초~1분 느려짐) (`OK`) |
  | **ERD·DB 패치·시드·compose·Caddy가 바뀜** | **멈추고 사람을 기다림** (`BLOCKED`) → 관리자 화면 A-08 "막힌 커밋 승인"에서 패치 적용·승인, 또는 DB 패치·`docker compose up -d` 뒤 `bash deploy/deploy.sh`를 직접 실행하면 기준이 앞으로 가서 풀림 |
  | 배포 실패 (앱이 안 뜸) | 같은 커밋을 반복하지 않음 (`FAILED`), 살아 있는 한 대가 응답. 로그 확인 후 고쳐서 다음 커밋으로 |

  ```bash
  cat ~/jejari-app/autodeploy.state        # 마지막 판단 (예: OK abc1234 자동 배포 완료)
  journalctl -u jejari-autodeploy -n 50    # 자세한 로그
  systemctl list-timers jejari-autodeploy  # 다음 실행 시각
  sudo systemctl start jejari-autodeploy   # 지금 한 번 실행
  sudo systemctl disable --now jejari-autodeploy.timer   # 자동 배포 끄기 (시연 중 등)
  ```

- **관리자 화면 "배포" 버튼** (A-08, 11-19~): 위 자동 배포 장치를 **지금 한 번** 실행 (`sudo systemctl start --no-block jejari-autodeploy.service`). 그래서 CI 확인·BLOCKED는 그대로이고 **BLOCKED는 배포 버튼으로 못 풂** → 같은 화면 아래 **"막힌 커밋 승인"**(11-19~, `DeployApprovalService`)에서 DB 패치 [적용]·서버 작업 체크 후 승인하면 그 커밋만 BLOCKED를 건너뛰고 배포 (`~/jejari-app/autodeploy.blocked`·`autodeploy.approved`·`db-patches.applied`·`deploy-approvals.log`, 문서 `docs/길동현/features/배포-승인.md`). 누를 수 있는 사람은 서버 `.env`의 `JEJARI_OPS_DEPLOY_ADMIN_IDS`(관리자 번호), 켜는 스위치 `JEJARI_OPS_DEPLOY_ENABLED=true`. 누른 기록 `~/jejari-app/deploy-requests.log`. 연타는 1분 간격. **프로젝트가 끝나면 끔** (웹에서 서버 명령을 실행하는 기능)
- **직접 배포**: `bash deploy/deploy.sh` (main 기준) 또는 `DEPLOY_SHA=<커밋> bash deploy/deploy.sh`. DB를 맞춘 뒤, 또는 자동 배포를 끈 동안
  - **DB 패치 확인 (11-23~)**: 지난 배포 이후 새로 생기거나 바뀐 `db/patch/vNN.sql` 중 적용 기록(`~/jejari-app/db-patches.applied`, A-08 [적용]도 여기에 씀)이 없는 것이 있으면 **빌드 전에 멈추고 목록을 보여 준다**. 11-23에 v33 없이 비회원 예매 코드가 올라가 ORA-00904가 130건 났기 때문
    - 내용을 보고 `APPLY_PATCHES=1 bash deploy/deploy.sh` → 버전 순서대로 앱 계정으로 넣고 기록한 뒤 배포 (출력에 ORA-·SP2-·PLS-가 있으면 멈춤, 앱은 그대로)
    - 이미 손으로 넣었으면 `SKIP_PATCH_CHECK=1 bash deploy/deploy.sh`
  - 끝나면 지난 배포 이후 바뀐 설정 중 사람이 할 일(경보 규칙 → `restart grafana`, Caddy·Prometheus 재시작, compose `up -d`, mongo-init, 유닛·sudoers 설치)을 **알려 주기만** 한다

- **BLOCKED 풀기**: 아래 "운영자 안내 → BLOCKED 풀기 (한 단계씩)" (ERD가 바뀌면 거의 항상 멈춤. 11-19 v25~v28 모두 이렇게 풂)
- **시연 중에는 자동 배포를 끔** (시연 도중 재시작 방지). 끝나면 다시 켬. **11-19부터 앱 1대라 더 중요** (재시작하는 40초~1분 느려짐)
- **앱 1대 ↔ 2대 전환** (11-19 1대로, PERF-01): 2대로 되돌리려면 ① `sudo systemctl enable --now jejari@8081` ② `Caddyfile`의 `reverse_proxy`에 `127.0.0.1:8081` 추가·`lb_try_duration 10s` ③ `prometheus.server.yml`의 app-2 주석 풀기 ④ 커밋 → BLOCKED 풀기에서 caddy·prometheus 재시작 ⑤ `auth.max_concurrent_hash`를 앱 수에 맞게 (1대 4, 2대면 2대+해시4에서 로그인 503이 났으니 다시 재기). `deploy.sh`는 켜 둔(enabled) 앱만 재시작해서 고칠 것 없음

- **데이터 재생성** (테스트 데이터 삭제): Oracle만 지우면 회원·예약 번호가 1부터 다시 시작해 Redis(매수 카운터·대기열·좌석 키)·Mongo(행동 로그)에 남은 옛 번호 데이터를 새 회원이 물려받음 → **셋을 함께**. Caddy(인증서)·Grafana·Prometheus·Kafka는 남김
  ```bash
  C='docker compose -f compose.yaml -f deploy/compose.server.yaml'
  sudo systemctl stop jejari@8080
  $C rm -sf oracle mongo redis && docker volume rm jejari_oracle-data jejari_mongo-data jejari_redis-data
  $C up -d oracle mongo redis      # Oracle healthy까지 약 5분, 관리자 계정은 같은 .env 비밀번호로 다시 생김
  bash deploy/deploy.sh
  ```
- **시드 날짜**: 운행 9001·9002는 **적재한 날 + 3일 운행** (9001 09:00 출발, 9002 15:00 출발). ERD v23부터 **출발 시각이 지나면 판매 마감 전이라도 `DEPARTED`**로 바뀌어 예매가 막힘 → 서버(11-18 적재)는 **11-21 09:00부터 9001 예매 불가**. 시연 전날 데이터 재생성
- **안 쓸 때 끄기**: `gcloud compute instances stop jejari` (디스크 값만 나감). 켜면 **외부 IP가 바뀔 수 있음** → `setup-server.sh`를 다시 돌려 도메인 갱신, 팀에 새 주소 공유. 주소를 고정하려면 고정 IP 예약(유료, 켜 둔 동안에도 과금)
- **로컬 테스트를 서버 DB에 붙이지 않기**: 서버 앱의 스케줄러가 테스트의 HOLD·outbox를 먼저 가져감
- **서버 `.env`**: 저장소·메신저에 올리지 않음. 관리자·Grafana 비밀번호는 내 PC의 `~/jejari-server.env`에서 확인
- **Grafana**: `https://{도메인}/grafana/` (11-18~). 팀원은 보기 전용 계정 `team`(비밀번호 `GRAFANA_TEAM_PASSWORD`, `bash deploy/grafana-team-user.sh`로 만들기·맞추기), 관리자는 `GRAFANA_ADMIN_*`. 익명 보기는 꺼 둠. 3000 포트 자체는 여전히 127.0.0.1에만
- **경보 → Discord** (11-18~, `deploy/grafana-alerting/`): 자동 배포가 실행마다 상태를 Pushgateway(127.0.0.1:9091)에 보고 → Prometheus 수집 → Grafana 경보 → `.env`의 `DISCORD_WEBHOOK_URL`

  | 경보 | 언제 | 할 일 |
  |---|---|---|
  | 자동 배포 실패 또는 사람이 처리해야 함 | 상태가 `FAILED`·`BLOCKED` | 위 "운영자 안내" 표 |
  | 자동 배포가 30분 넘게 돌지 않음 | 타이머·Pushgateway 고장 (5분 지속) | `systemctl list-timers jejari-autodeploy`. 시연 때문에 껐다면 무시 |
  | CI 대기가 1시간 넘게 이어짐 | main 새 커밋이 CI를 못 통과 | GitHub Actions 확인 |
  | 실제 KTX 시간표 갱신 실패 | 마지막 갱신 실패 또는 26시간 넘게 안 돎 | `journalctl -u jejari-tago -n 50`, 서비스 키 확인 → `sudo systemctl start jejari-tago` |
  | 위험 점수 모델 보조 의견 갱신 실패 (11-23~) | 매시간 갱신이 70분 넘게 실패(두 번 연달아)·3시간 넘게 안 돎, 또는 주간 재학습 실패·8일 넘게 안 돎 | `journalctl -u jejari-model -n 30` → "꺼내기 실패"면 Oracle·MongoDB, "점수 매기기 실패"면 `/tmp/jejari-model-*.log`. 보조 의견뿐이라 예매엔 영향 없음 |
  | 앱 한 대가 응답하지 않음 | `up{job="jejari-app"}`이 2분 넘게 0 | `journalctl -u jejari@8080` |
  | **예매 정합성: 돈·좌석이 어긋남** (critical, 11-20~) | 매시간 점검에서 결제했는데 표 없음·표 있는데 결제 없음·좌석과 예약 상태 어긋남 1건↑ | `bash loadtest/tools/check_booking.sh all 120` 으로 어느 예약인지 → 결제·예약 담당. 관리자 강제 취소·환불로 바로잡기 |
  | 예매 정합성: 이벤트·알림·행동 로그가 빠짐 | 같은 점검에서 안 풀린 선점·발행 안 된 이벤트·확정 알림 빠짐·행동 로그 빠짐 1건↑ | 위 명령 → 알림이면 `tools/dlq.sh list` |
  | 예매 정합성 점검이 돌지 않음 | 3시간 넘게 안 돎 또는 DB를 못 읽음 | `journalctl -u jejari-integrity -n 50` |
  | 소비자가 처리하지 못한 메시지가 격리 보관함에 들어감 | 최근 10분 격리 보관 1건↑ | `tools/dlq.sh list` → 원인 해결 → `tools/dlq.sh replay <토픽> <그룹>` (concurrency.md §12.1) |
  | 예약 이벤트 발행이 멈춤 (critical) | outbox 가장 오래 기다린 것 2분↑ 또는 FAILED | Kafka 상태, FAILED면 concurrency.md §11.4 |
  | Kafka 소비가 밀림 | 예약 흐름 그룹 1000건↑·대량 적재 10만 건↑ 5분 | A-10 데이터 놓침·밀림에서 그룹 확인 → 앱 로그 |
  | Kafka 소비 밀림을 잴 수 없음 | 앱이 Kafka에 5분 넘게 못 닿음 | `docker compose ps kafka` |
  | 행동 로그(봇 탐지 재료)를 버림 | 최근 10분 큐 넘침·전송 실패·형식 오류 1건↑ | A-10 → 형식 오류면 `tools/dlq.sh list` |
  | 행동 로그 적재가 멈춤 | 받는데 MongoDB 저장이 10% 미만 5분 | `docker compose ps mongo` (복구하면 따라잡음) |

  매시간 정합성 점검: `deploy/integrity-check.sh` (매시간 41분 `jejari-integrity.timer`, 읽기만, 몇 초) → Pushgateway `jejari_integrity_*`. 지금 한 번 `sudo systemctl start jejari-integrity`

- **위험 점수 모델 보조 의견** (11-19~, `deploy/model-refresh.sh`, 매시간 17분 `jejari-model.timer`): 최근 1일 행동 로그가 있는 회원을 꺼내(`tools/ml/export_live.sh --recent 1`) 컨테이너(`deploy/ml/Dockerfile`, 서버에 파이썬 라이브러리를 깔지 않음)에서 `data/ml/model`로 점수를 매기고 `tools/ml/load_scores.sh`로 MongoDB `model_score`에 넣는다. **등록된 버전마다** 따로 매긴다 (`MODELS`, 기본 `ens-2026.11-01=data/ml/model ens-2026.11-02=data/ml/model-next`). 어느 버전을 보여 줄지는 관리자 화면 **A-09 모델 관리**에서 비교해 고른다 (처음 한 번, 사용 중인 버전이 없을 때만 첫 번째를 켬). 관리자 케이스 화면(A-06)의 "모델 보조 의견" 카드에 나온다. **보조 의견뿐 — 대기열 순번·이용 제한·케이스 열기에는 쓰지 않는다.** 처음 한 번 필요: DB에 `db/patch/v25.sql`·`v31.sql`(model_run 행), 그리고 `bash deploy/setup-server.sh`(타이머 설치) 또는 아래 수동 설치. 첫 실행은 이미지 빌드(torch CPU) 때문에 몇 분 걸림. 숨기려면 앱 설정 `jejari.model.show=false`
- **실제 KTX 시간표** (11-18~, `deploy/tago-refresh.sh`, 매일 새벽 4:30 `jejari-tago.timer`): 시드 운행은 "넣은 날 + 3일" 하루뿐이라, 길동현 님 `tools/load-tago-schedule.js`(공공데이터포털 TAGO)로 **오늘 기준 앞뒤 7일** 실제 KTX를 매일 다시 넣음 (MERGE라 중복 없음, run_id 400000~). 서버에 Node 없이 Node 22 컨테이너로. **`.env`의 `DATA_GO_KR_KEY_DEC` 또는 `DATA_GO_KR_KEY`(공공데이터포털 서비스 키) 필요**. 지금 한 번: `sudo systemctl start jejari-tago`, 결과: `journalctl -u jejari-tago -n 20`

  같은 경보는 4시간마다만 다시 오고, 해결되면 "해결됨"이 옴. 웹훅 주소를 바꾸면 `.env` 수정 뒤 `docker compose -f compose.yaml -f deploy/compose.server.yaml up -d grafana`
- **Caddyfile·`prometheus.server.yml`을 바꾼 뒤에는 그 컨테이너를 재시작** (`docker compose -f compose.yaml -f deploy/compose.server.yaml restart caddy`). 파일 하나를 bind mount한 것이라 git이 파일을 새로 쓰면 실행 중인 컨테이너는 옛 파일을 계속 봄. compose 환경 변수를 바꿨으면 `up -d grafana`처럼 다시 만듦
- **`docker/mongo/mongo-init.js`가 바뀌면** (새 컬렉션·인덱스, 자동 배포가 BLOCKED로 멈춤): 기존 Mongo에 직접 반영. 같은 이유로 컨테이너 안 파일은 옛것이라 **저장소 파일을 표준 입력으로** 넣음 (createIndex는 여러 번 돌려도 안전)
  ```bash
  docker compose -f compose.yaml -f deploy/compose.server.yaml exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' < docker/mongo/mongo-init.js
  ```

## 운영자 안내 (담당 조영준, 대신 운영 길동현)

서버 운영 권한은 **두 명만** 가집니다 (OS Login, 2026-11-18~). 나머지 팀원은 https 주소와 Grafana(`team` 계정)만 씁니다. 결제·방화벽·VM 삭제·권한 부여는 프로젝트 주인(조영준)만 할 수 있습니다.

### 접속

**운영 명령은 전부 서버 터미널에 입력합니다** (내 PC 터미널이 아님). 들어가는 방법은 둘입니다.

**A. 브라우저 (가장 쉬움)**: https://console.cloud.google.com → 위쪽 프로젝트 **jejari** → **Compute Engine → VM 인스턴스** → `jejari` 줄의 **SSH** 버튼 → 새 창의 검은 터미널. 붙여넣기는 `Ctrl+Shift+V` 또는 마우스 오른쪽

**B. 내 PC의 Git Bash (Windows)**

1. **처음 한 번만** (PC마다): [Google Cloud CLI](https://cloud.google.com/sdk/docs/install) 설치 → Git Bash에서
   ```bash
   B="$LOCALAPPDATA/Google/Cloud SDK/google-cloud-sdk"
   gcloud() { "$B/platform/bundledpython/python.exe" "$B/lib/gcloud.py" "$@"; }   # 왜 이렇게 부르는지: 아래 "Windows(Git Bash)에서 gcloud"
   gcloud auth login                   # 브라우저가 열림 → 서버 권한을 받은 구글 계정으로 로그인
   gcloud config set project jejari
   ```
   매번 `B=`·`gcloud()` 두 줄을 치기 싫으면 `~/.bashrc` 맨 아래에 그대로 붙여 두기 (Git Bash를 다시 열면 적용)
2. **들어갈 때마다**
   ```bash
   gcloud compute ssh jejari --zone=asia-northeast3-a --tunnel-through-iap
   ```
   - 처음엔 SSH 키를 만들고 구글 계정에 등록합니다 (1~2분, 비밀번호 문구는 빈칸으로 Enter 두 번 해도 됨). PuTTY 창이 뜨면 그 창이 서버 터미널입니다
   - 들어가면 맨 앞이 `구글계정이름@jejari:~$`처럼 바뀝니다. **이게 보이면 서버 안**
   - `Permission denied`·`IAP`가 나오면 그 구글 계정에 권한이 없는 것 → 프로젝트 주인(조영준)에게
3. **나올 때**: `exit` (`sudo -iu dita_810`으로 바꿨다면 `exit` 두 번)

**들어간 뒤 공통**

```bash
sudo -iu dita_810        # 앱·배포 파일 주인으로 바꾸기 → 맨 앞이 dita_810@jejari:~$ 로 바뀜
cd ~/Jejari              # 서버의 저장소 (지금 배포된 커밋으로 맞춰져 있음, 직접 고치지 않기)
```

- 로그인하면 **구글 계정 이름의 사용자**가 됩니다 (OS Login). 앱·`.env`·systemd는 `dita_810` 소유라 운영은 꼭 `sudo -iu dita_810` 뒤에. 단, **timer·sudoers 설치처럼 `sudo`가 필요한 일은 바꾸기 전(구글 계정 사용자)에** (`dita_810`의 sudo는 서비스 켜고 끄기만 됨)
- `sudo -iu dita_810`는 **새 셸을 여는 명령**이라 `sudo -iu dita_810 && cd …`처럼 한 줄로 이으면 뒤가 실행되지 않습니다. 한 줄씩
- `sudo`가 있으면 서버 `.env`(모든 비밀값)를 볼 수 있습니다. **밖으로 복사·메신저 공유 금지**

### 상태 보기

```bash
cat ~/jejari-app/autodeploy.state                    # 자동 배포 마지막 판단 (OK / WAITING_CI / BLOCKED / FAILED)
curl -s localhost:8080/actuator/health
docker compose -f compose.yaml -f deploy/compose.server.yaml ps
journalctl -u jejari@8080 -n 100 --no-pager          # 앱 로그
journalctl -u jejari-autodeploy -n 50 --no-pager     # 자동 배포 로그
```

### 자주 하는 일

| 상황 | 명령 |
|---|---|
| 지금 main으로 배포 | 관리자 화면 → **배포** → "지금 배포" (허용된 관리자만), 또는 `sudo systemctl start jejari-autodeploy` |
| 자동 배포 한 번 지금 실행 | `sudo systemctl start jejari-autodeploy` |
| **시연 중 자동 배포 끄기** / 다시 켜기 | `sudo systemctl disable --now jejari-autodeploy.timer` / `sudo systemctl enable --now jejari-autodeploy.timer` |
| 앱이 이상함 | `sudo systemctl restart jejari@8080` (1대라 재시작하는 40초쯤 느려짐) |
| `BLOCKED` (DB·인프라 변경) | 아래 "BLOCKED 풀기 (한 단계씩)" |
| `FAILED` (앱이 안 뜸) | `journalctl -u jejari@8080 -n 200` (TZ·`.env` 키 누락이 흔함). 고친 커밋이 main에 오면 자동으로 다시 시도 |
| 실제 시간표 지금 다시 넣기 | `sudo systemctl start jejari-tago` → `journalctl -u jejari-tago -n 20` |
| 모델 보조 의견 지금 다시 매기기 | `sudo systemctl start jejari-model` → `journalctl -u jejari-model -n 20` |
| 모델 재학습 지금 한 번 | `sudo systemctl start jejari-model-retrain` → `journalctl -u jejari-model-retrain -n 30` (학습이라 몇십 분, 판매 없는 시간에) |

### BLOCKED 풀기 (한 단계씩)

자동 배포는 ERD·DB 패치·시드·compose·Caddy·서버 설정이 바뀐 커밋을 보면 **멈추고 Discord로 알립니다** ("자동 배포 실패 또는 사람이 처리해야 함"). 새 코드가 바뀐 DB를 전제로 하는데 DB 패치는 사람이 읽고 적용해야 해서입니다 (열·행 삭제가 섞여 있음). 관리자 화면 "배포" 버튼으로는 못 풉니다.

위 "접속"으로 서버에 들어가 `sudo -iu dita_810` → `cd ~/Jejari` 한 다음, 한 줄씩:

| # | 입력 | 이게 보이면 다음으로 |
|---|---|---|
| 1 | `git fetch -q && cat ~/jejari-app/autodeploy.state` | `… BLOCKED <커밋> … 바뀐 파일 목록` |
| 2 | `T=<위 줄의 커밋 7자리>` (예: `T=e521a7f`) | 아무것도 안 나옴 (변수만 정함) |
| 3 | `git diff --stat $(cat ~/jejari-app/deployed-sha) $T -- db docs/erd docker compose.yaml deploy` | 바뀐 파일과 줄 수. 아래 표에서 파일마다 할 일 확인 |
| 4 | `git show $T:db/patch/v28.sql \| less` (번호는 3의 목록대로, 여러 개면 낮은 번호부터) | 패치 설명. **"Oracle은 바뀌는 것이 없습니다"면 5는 건너뜀**. `DROP`·`DELETE`가 있으면 무엇이 지워지는지 읽고, 모르겠으면 멈추고 묻기. `q`로 나옴 |
| 5 | `PW=$(grep '^ORACLE_APP_PASSWORD=' .env \| cut -d= -f2)` 다음 줄 `git show $T:db/patch/v28.sql \| docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1"` | 패치 끝의 확인 결과 (예: 시퀀스 이름). `ORA-`로 시작하는 오류가 없어야 함. 패치는 다시 돌려도 안전하게 짜여 있음 |
| 6 | (`docker/mongo/mongo-init.js`가 목록에 있으면) `git show $T:docker/mongo/mongo-init.js \| docker compose -f compose.yaml -f deploy/compose.server.yaml exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin'` | 끝에 `jejari mongo init done` |
| 7 | `DEPLOY_SHA=$T bash deploy/deploy.sh` | 2~4분 뒤 `배포 완료: …` (앱 재시작 동안 40초쯤 느려짐) |
| 8 | `sudo systemctl start jejari-autodeploy && cat ~/jejari-app/autodeploy.state` | `OK …` → 끝. Discord에 "해결됨"이 옴. 또 `BLOCKED`면 그 뒤 커밋에도 DB 변경이 있는 것 → 1부터 다시 |
| 9 | `exit` 두 번 | 내 PC로 돌아옴 |

3의 목록에 다른 파일이 있으면 7 전에:

| 바뀐 파일 | 할 일 |
|---|---|
| `db/patch/vNN.sql` | 4·5 |
| `db/seed/*.sql` | 시드를 다시 돌릴지 판단 (`db/seed/README.md`). 보통 필요 없음 |
| `docker/mongo/mongo-init.js` | 6 |
| `compose.yaml`·`deploy/compose.server.yaml` | `docker compose -f compose.yaml -f deploy/compose.server.yaml up -d` |
| `deploy/Caddyfile`·`deploy/prometheus.server.yml` | 7 뒤에 `docker compose -f compose.yaml -f deploy/compose.server.yaml restart caddy` (또는 `prometheus`). Caddy는 몇 초 끊김 |
| `deploy/grafana-alerting/*` | 7 뒤에 `… restart grafana` |
| `deploy/jejari-*.timer`·`*.service`·`sudoers-jejari` | `exit`로 **구글 계정 사용자로 돌아가서** 아래 명령 → 다시 `sudo -iu dita_810` |
| `docs/erd/*`만 | 할 일 없음 (설명 문서) |

timer·service·sudoers 설치 (구글 계정 사용자로, `T`는 다시 정하기):

```bash
cd /home/dita_810/Jejari && sudo -u dita_810 git fetch -q && T=<커밋>
sudo -u dita_810 git show $T:deploy/jejari-autodeploy.timer | sed -e "s#__USER__#dita_810#g" -e "s#__HOME__#/home/dita_810#g" | sudo tee /etc/systemd/system/jejari-autodeploy.timer >/dev/null
sudo systemctl daemon-reload && systemctl list-timers jejari-autodeploy       # 다음 실행 시각이 보이면 OK
sudo -u dita_810 git show $T:deploy/sudoers-jejari | sed "s#__USER__#dita_810#g" > /tmp/sj
sudo visudo -cf /tmp/sj && sudo install -m 440 -o root -g root /tmp/sj /etc/sudoers.d/jejari && rm /tmp/sj   # parsed OK가 보여야 설치됨
```

### 백업과 복구 (11-19~)

**매일 새벽 3시(KST) 디스크 스냅샷**, 최근 **7일** 보관 (GCP 스냅샷 정책 `jejari-daily`, 디스크 `jejari`에 붙어 있음). 디스크 전체라 Oracle·Mongo·Redis 데이터, `.env`, Caddy 인증서, 배포 파일이 모두 들어 있습니다. 서버는 멈추지 않습니다 (몇 분간 디스크가 약간 느려질 수 있음). 첫 수동 스냅샷 `jejari-manual-20261119` (9.1GB, 2분 25초).

- 전원을 갑자기 끈 것과 같은 상태로 찍힙니다(crash-consistent). Oracle·Mongo는 이 상태에서 스스로 복구하게 만들어져 있지만 100%는 아님 → 중요한 시연 전에는 **수동 스냅샷을 하나 더** 찍어 두기
- 스냅샷에는 `.env` 비밀값이 들어 있습니다. 스냅샷으로 디스크를 만드는 권한은 프로젝트 주인만
- 아래 `gcloud`는 **내 PC**(Git Bash, 위 "접속 B"의 `gcloud()` 함수)에서 실행합니다. 서버 안이 아님

```bash
gcloud compute snapshots list --format="table(name,creationTimestamp,storageBytes)"     # 있는 백업
gcloud compute snapshots create jejari-manual-$(date +%Y%m%d-%H%M) --source-disk=jejari \
  --source-disk-zone=asia-northeast3-a --storage-location=asia-northeast3              # 지금 하나 더 (2~3분)
```

**복구 (그 시점의 서버로 통째로 되돌리기)**: 지금 디스크는 지우지 않고 떼어 두므로, 잘못되면 다시 붙이면 됩니다. 15~20분, **그동안 사이트가 멈춤** → 팀에 먼저 알리기

```bash
S=<스냅샷 이름>                                    # 위 list에서 고르기
Z=--zone=asia-northeast3-a
gcloud compute disks create jejari-restore --source-snapshot=$S --type=pd-balanced $Z   # 1) 스냅샷 → 새 디스크
gcloud compute instances stop jejari $Z                                                  # 2) VM 끄기 (사이트 멈춤)
gcloud compute instances detach-disk jejari --disk=jejari $Z                             # 3) 지금 디스크 떼기 (지우지 않음)
gcloud compute instances attach-disk jejari --disk=jejari-restore --boot $Z              # 4) 새 디스크를 부팅 디스크로
gcloud compute instances start jejari $Z                                                 # 5) 켜기
gcloud compute instances describe jejari $Z --format="value(networkInterfaces[0].accessConfigs[0].natIP)"   # 6) 외부 IP
```

7. **외부 IP가 바뀌었으면** DuckDNS(`jejari.duckdns.org`, 팀장님 계정)에서 새 IP로 바꾸기. 고정 IP가 아니라 VM을 껐다 켜면 바뀔 수 있음
8. 서버에 들어가 확인: `curl -s localhost:8080/actuator/health`, `cat ~/jejari-app/autodeploy.state`. 앱·컨테이너는 부팅 때 자동으로 뜸 (Oracle healthy까지 수 분)
9. 백업 정책을 새 디스크에 붙이기: `gcloud compute disks add-resource-policies jejari-restore --resource-policies=jejari-daily $Z`
10. 며칠 써 보고 괜찮으면 옛 디스크 삭제 (`gcloud compute disks delete jejari $Z`) — 남겨 두면 디스크 값이 계속 나감

DB 하나만(예: Oracle만) 되돌리고 싶으면 스냅샷 디스크를 두 번째 디스크로 붙여 그 볼륨만 복사해야 해서 단계가 더 많습니다 → 필요할 때 조영준에게.

### 조심 (데이터가 사라지거나 밖에서 보이게 되는 것)

- **데이터 재생성**(위 "운영 규칙")은 회원·예약을 지웁니다. 시연 전날처럼 정해진 때에만, 팀에 알리고. 끝나면 실제 시간표를 바로 다시 넣기 (`sudo systemctl start jejari-tago`, 안 하면 다음 새벽 4:30까지 실제 KTX가 없음)
- 시드 `05_train_stop_fare.sql`은 **시드 열차 1·2의 운행만** 건드립니다. 실제 KTX 시간표(`train_id 10000~`)는 05를 다시 돌려도 그대로입니다 — 11-18에 전부 09:28로 덮어쓴 그 문제는 05의 대상이 경부선 전체였기 때문이고, 11-19에 고쳤습니다 (`docs/조영준/troubleshooting/20261118-시드05가-실제시간표-덮어씀.md`). **옛 05가 들어 있는 배포**에서 05를 돌렸다면 이어서 `sudo systemctl start jejari-tago`
- `docker volume rm`, `docker compose down -v` 금지 (`caddy-data`를 지우면 인증서를 다시 받아야 하고 발급 횟수 제한이 있음)
- `compose.yaml`의 `127.0.0.1:` 포트 바인딩, 방화벽 규칙을 바꾸지 않기 (DB가 인터넷에 열림)
- 서버 `.env`의 `JEJARI_HMAC_KEY`·`JEJARI_PEPPER_KEYS`·`JEJARI_ENC_KEYS`는 바꾸지 않기 (바꾸면 기존 해시·비밀번호·전화번호를 못 읽음)

## Windows(Git Bash)에서 gcloud

`gcloud`(bash 스크립트)는 Windows Store의 가짜 `python`을 잡아 실패하고, `gcloud.cmd`는 `cmd`가 쉼표를 인자 구분자로 잘라 `--allow=tcp:80,tcp:443` 같은 인자가 깨집니다. SDK 내장 파이썬으로 직접 부르면 둘 다 피합니다.

```bash
B="$LOCALAPPDATA/Google/Cloud SDK/google-cloud-sdk"
gcloud() { "$B/platform/bundledpython/python.exe" "$B/lib/gcloud.py" "$@"; }
gcloud compute ssh jejari --zone=asia-northeast3-a --tunnel-through-iap
```
