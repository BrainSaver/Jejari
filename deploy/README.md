# 공용 서버 배포 (Google Cloud VM)

담당: 조영준 · 바탕: 강동균 님 조사 [`docs/강동균/features/배포-구글클라우드.md`](../docs/강동균/features/배포-구글클라우드.md) (선택지 비교·비용·막혔을 때 표)

```
인터넷 ──443──> Caddy (HTTPS, 인증서 자동) ──┬─> 앱 jejari@8080 ─┐
                /actuator/* 는 health만 밖으로  └─> 앱 jejari@8081 ─┴─> Oracle·Mongo·Redis·Kafka (127.0.0.1에만)
```

VM 1대(e2-standard-4, 서울)에 로컬과 같은 `compose.yaml`을 올리고, 이 폴더의 파일만 더합니다.

| 파일 | 하는 일 |
|---|---|
| `compose.server.yaml` | Caddy 추가, Prometheus를 앱 2대 수집 설정으로 교체 (`-f`로 겹쳐 씀) |
| `Caddyfile` | HTTPS · 앱 2대에 나눠 보내기 · 지표 숨기기 |
| `prometheus.server.yml` | 앱 2대(app-1·app-2) 수집 |
| `jejari@.service` | 앱 systemd 템플릿 (인스턴스 이름 = 포트) |
| `make-server-env.sh` | **내 PC에서** 서버용 `.env` 생성 (HMAC 키만 팀 공용, 나머지 새로) |
| `setup-server.sh` | **서버에서 한 번**: 패키지·docker 그룹·도메인·systemd (앱 2대 + 자동 배포 타이머) |
| `deploy.sh` | 배포: main(또는 `DEPLOY_SHA`) 빌드 → 한 대씩 재시작 → 배포한 커밋 기록 |
| `auto-deploy.sh` · `jejari-autodeploy.{service,timer}` | **자동 배포**: 10분마다, CI 성공 + DB·인프라 변경 없음일 때만 `deploy.sh` |

## 강동균 님 문서와 다른 점 (이유)

| 문서 | 여기 | 이유 |
|---|---|---|
| `http://IP:8080` 직접 공개 | Caddy로 **HTTPS**, 80·443만 엶 | 리프레시 쿠키가 Secure라 http에서는 브라우저가 저장·전송하지 않아 재발급이 전부 실패. 8080을 열면 `/actuator/prometheus`(내부 지표)도 공개됨 |
| 앱 1대 | **2대** (8080·8081) | 멀티 인스턴스 규칙을 실제로 확인 (`docs/조영준/features/멀티인스턴스-코딩-규칙.md`). 배포 때 한 대씩 재시작해 끊김 없음 |
| `User=ubuntu` | 실제 사용자로 채움 | GCP Ubuntu에는 `ubuntu` 계정이 없음 (`gcloud compute ssh`가 내 PC 사용자 이름으로 만듦) |
| 내 PC `.env`를 복사 | **새로 생성** | 로컬 비밀번호·임시 키가 서버로 가지 않게. HMAC 키만 팀 공용 |

도메인은 사지 않고 `외부IP.sslip.io`(예: `34-64-12-34.sslip.io`)를 씁니다. IP를 이름으로 바꿔 주는 공개 DNS라 Let's Encrypt 인증서를 바로 받을 수 있습니다.

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

- **자동 배포** (11-18~): 서버의 `jejari-autodeploy.timer`가 **10분마다** main을 확인해 `auto-deploy.sh`를 실행. 서버에 비밀값을 더 두지 않음 (공개 저장소를 받고, CI 결과는 공개 GitHub API로 봄)

  | main의 새 커밋이 | 자동 배포는 |
  |---|---|
  | CI(빌드·테스트·ERD 검사) **진행 중** | 그보다 앞선 커밋 중 CI가 성공한 가장 최근 커밋을 배포 (최대 5개 거슬러 봄). 없으면 다음 차례에 (`WAITING_CI`) |
  | CI **실패** | 배포 안 함 (`CI_FAILED`), 다음 커밋을 기다림 |
  | 문서·테스트만 바뀜 | 빌드 없이 "반영됨"으로 기록 (`OK`) |
  | 코드·화면·빌드 설정이 바뀜 | **그 커밋으로 고정해 배포** (한 대씩 재시작, 끊김 없음) (`OK`) |
  | **ERD·DB 패치·시드·compose·Caddy가 바뀜** | **멈추고 사람을 기다림** (`BLOCKED`) → DB 패치·`docker compose up -d` 뒤 `bash deploy/deploy.sh`를 직접 실행하면 기준이 앞으로 가서 풀림 |
  | 배포 실패 (앱이 안 뜸) | 같은 커밋을 반복하지 않음 (`FAILED`), 살아 있는 한 대가 응답. 로그 확인 후 고쳐서 다음 커밋으로 |

  ```bash
  cat ~/jejari-app/autodeploy.state        # 마지막 판단 (예: OK abc1234 자동 배포 완료)
  journalctl -u jejari-autodeploy -n 50    # 자세한 로그
  systemctl list-timers jejari-autodeploy  # 다음 실행 시각
  sudo systemctl start jejari-autodeploy   # 지금 한 번 실행
  sudo systemctl disable --now jejari-autodeploy.timer   # 자동 배포 끄기 (시연 중 등)
  ```

- **직접 배포**: `bash deploy/deploy.sh` (main 기준) 또는 `DEPLOY_SHA=<커밋> bash deploy/deploy.sh`. DB를 맞춘 뒤, 또는 자동 배포를 끈 동안
- **시연 중에는 자동 배포를 끔** (시연 도중 재시작 방지). 끝나면 다시 켬

- **데이터 재생성** (테스트 데이터 삭제): Oracle만 지우면 회원·예약 번호가 1부터 다시 시작해 Redis(매수 카운터·대기열·좌석 키)·Mongo(행동 로그)에 남은 옛 번호 데이터를 새 회원이 물려받음 → **셋을 함께**. Caddy(인증서)·Grafana·Prometheus·Kafka는 남김
  ```bash
  C='docker compose -f compose.yaml -f deploy/compose.server.yaml'
  sudo systemctl stop jejari@8080 jejari@8081
  $C rm -sf oracle mongo redis && docker volume rm jejari_oracle-data jejari_mongo-data jejari_redis-data
  $C up -d oracle mongo redis      # Oracle healthy까지 약 5분, 관리자 계정은 같은 .env 비밀번호로 다시 생김
  bash deploy/deploy.sh
  ```
- **시드 날짜**: 운행 9001·9002는 **적재한 날 + 3일 운행** (9001 09:00 출발, 9002 15:00 출발). ERD v23부터 **출발 시각이 지나면 판매 마감 전이라도 `DEPARTED`**로 바뀌어 예매가 막힘 → 서버(11-18 적재)는 **11-21 09:00부터 9001 예매 불가**. 시연 전날 데이터 재생성
- **안 쓸 때 끄기**: `gcloud compute instances stop jejari` (디스크 값만 나감). 켜면 **외부 IP가 바뀔 수 있음** → `setup-server.sh`를 다시 돌려 도메인 갱신, 팀에 새 주소 공유. 주소를 고정하려면 고정 IP 예약(유료, 켜 둔 동안에도 과금)
- **로컬 테스트를 서버 DB에 붙이지 않기**: 서버 앱의 스케줄러가 테스트의 HOLD·outbox를 먼저 가져감
- **서버 `.env`**: 저장소·메신저에 올리지 않음. 관리자·Grafana 비밀번호는 내 PC의 `~/jejari-server.env`에서 확인
- **Grafana**: `https://{도메인}/grafana/` (11-18~). 팀원은 보기 전용 계정 `team`(비밀번호 `GRAFANA_TEAM_PASSWORD`, `bash deploy/grafana-team-user.sh`로 만들기·맞추기), 관리자는 `GRAFANA_ADMIN_*`. 익명 보기는 꺼 둠. 3000 포트 자체는 여전히 127.0.0.1에만
- **Caddyfile·`prometheus.server.yml`을 바꾼 뒤에는 그 컨테이너를 재시작** (`docker compose -f compose.yaml -f deploy/compose.server.yaml restart caddy`). 파일 하나를 bind mount한 것이라 git이 파일을 새로 쓰면 실행 중인 컨테이너는 옛 파일을 계속 봄. compose 환경 변수를 바꿨으면 `up -d grafana`처럼 다시 만듦

## Windows(Git Bash)에서 gcloud

`gcloud`(bash 스크립트)는 Windows Store의 가짜 `python`을 잡아 실패하고, `gcloud.cmd`는 `cmd`가 쉼표를 인자 구분자로 잘라 `--allow=tcp:80,tcp:443` 같은 인자가 깨집니다. SDK 내장 파이썬으로 직접 부르면 둘 다 피합니다.

```bash
B="$LOCALAPPDATA/Google/Cloud SDK/google-cloud-sdk"
gcloud() { "$B/platform/bundledpython/python.exe" "$B/lib/gcloud.py" "$@"; }
gcloud compute ssh jejari --zone=asia-northeast3-a --tunnel-through-iap
```
