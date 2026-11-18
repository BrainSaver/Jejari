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
| `setup-server.sh` | **서버에서 한 번**: 패키지·docker 그룹·도메인·systemd |
| `deploy.sh` | **서버에서 배포할 때마다**: main 빌드 → 한 대씩 재시작 |

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

- **재배포**: 서버에서 `bash deploy/deploy.sh` (main 기준, 한 대씩 재시작)
- **안 쓸 때 끄기**: `gcloud compute instances stop jejari` (디스크 값만 나감). 켜면 **외부 IP가 바뀔 수 있음** → `setup-server.sh`를 다시 돌려 도메인 갱신, 팀에 새 주소 공유. 주소를 고정하려면 고정 IP 예약(유료, 켜 둔 동안에도 과금)
- **시연 전날 Oracle 볼륨 재생성**: 시드 날짜가 적재일 기준이라 5일 지나면 9001이 판매 중이 아님
- **로컬 테스트를 서버 DB에 붙이지 않기**: 서버 앱의 스케줄러가 테스트의 HOLD·outbox를 먼저 가져감
- **서버 `.env`**: 저장소·메신저에 올리지 않음. 관리자·Grafana 비밀번호는 내 PC의 `~/jejari-server.env`에서 확인
- **Grafana**: 3000은 127.0.0.1에만. `gcloud compute ssh jejari -- -L 3000:localhost:3000` 후 `http://localhost:3000`

## Windows(Git Bash)에서 gcloud

`gcloud`(bash 스크립트)는 Windows Store의 가짜 `python`을 잡아 실패하고, `gcloud.cmd`는 `cmd`가 쉼표를 인자 구분자로 잘라 `--allow=tcp:80,tcp:443` 같은 인자가 깨집니다. SDK 내장 파이썬으로 직접 부르면 둘 다 피합니다.

```bash
B="$LOCALAPPDATA/Google/Cloud SDK/google-cloud-sdk"
gcloud() { "$B/platform/bundledpython/python.exe" "$B/lib/gcloud.py" "$@"; }
gcloud compute ssh jejari --zone=asia-northeast3-a --tunnel-through-iap
```
