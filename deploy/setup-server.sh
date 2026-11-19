#!/usr/bin/env bash
# 공용 서버 첫 준비 (서버에서 한 번). git clone 뒤, .env를 올린 다음 실행합니다.
#   cd ~/Jejari && bash deploy/setup-server.sh
#
# 하는 일: 패키지 설치 → docker 그룹 → .env의 JEJARI_DOMAIN을 외부 IP로 채움 → systemd 템플릿 설치
# 다시 실행해도 안전합니다.
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f .env ]] || { echo "~/Jejari/.env가 없습니다. 내 PC에서 deploy/make-server-env.sh → gcloud compute scp로 먼저 올리세요."; exit 1; }
chmod 600 .env
[[ ! -e compose.override.yml ]] || { echo "compose.override.yml이 있습니다. 각자 PC용 파일이라 서버에서는 지우세요."; exit 1; }

# 1) 패키지: Docker, Compose v2, JDK 21, git
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1 || ! command -v java >/dev/null; then
  sudo apt-get update
  sudo apt-get install -y docker.io docker-compose-v2 openjdk-21-jdk-headless git curl netcat-openbsd
fi
if ! id -nG "$USER" | grep -qw docker; then
  sudo usermod -aG docker "$USER"
  echo "docker 그룹에 추가했습니다. 로그아웃 후 다시 접속해서 이 스크립트를 한 번 더 실행하세요."; exit 0
fi

# 2) 도메인: 이미 정한 이름(예: jejari.duckdns.org, 11-18 팀장님)이 있으면 그대로.
#    비어 있거나 sslip.io(외부 IP를 하이픈으로)면 지금 외부 IP로 다시 채움 → VM을 껐다 켜 IP가 바뀐 경우
IP=$(curl -fs -H 'Metadata-Flavor: Google' \
  http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip)
[[ "$IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "외부 IP를 읽지 못했습니다."; exit 1; }
CUR_DOMAIN=$(grep '^JEJARI_DOMAIN=' .env | head -1 | cut -d= -f2-)
if [[ -n "$CUR_DOMAIN" && "$CUR_DOMAIN" != *.sslip.io ]]; then
  DOMAIN="$CUR_DOMAIN"
  echo "도메인은 정해 둔 ${DOMAIN} 그대로 (DuckDNS 등이면 그 서비스에서 IP ${IP}를 가리키는지 확인)"
else
  DOMAIN="${IP//./-}.sslip.io"
  if grep -q '^JEJARI_DOMAIN=' .env; then
    sed -i "s/^JEJARI_DOMAIN=.*/JEJARI_DOMAIN=${DOMAIN}/" .env
  else
    echo "JEJARI_DOMAIN=${DOMAIN}" >> .env
  fi
fi

# 3) systemd 템플릿 (실제 사용자·홈으로 채움)
for unit in jejari@.service jejari-autodeploy.service jejari-autodeploy.timer jejari-tago.service jejari-tago.timer jejari-model.service jejari-model.timer; do
  sed -e "s#__USER__#${USER}#g" -e "s#__HOME__#${HOME}#g" "deploy/$unit" \
    | sudo tee "/etc/systemd/system/$unit" >/dev/null
done
sudo systemctl daemon-reload
# 앱은 1대(8080)만 켬 (11-19, PERF-01). 2대로 되돌리려면 jejari@8081도 enable + Caddyfile·prometheus.server.yml
sudo systemctl enable jejari@8080 >/dev/null

# 앱 주인이 제자리 서비스만 비밀번호 없이 켜고 끌 수 있게 (OS Login을 켜면 기존 계정의 sudo가 사라져 자동 배포가 멈춤)
sed "s#__USER__#${USER}#g" deploy/sudoers-jejari > /tmp/jejari.sudoers
sudo visudo -cf /tmp/jejari.sudoers >/dev/null && sudo install -o root -g root -m 440 /tmp/jejari.sudoers /etc/sudoers.d/jejari
rm -f /tmp/jejari.sudoers
# 자동 배포 (10분마다 main 확인, CI 성공·DB 변경 없음일 때만). 끄기: sudo systemctl disable --now jejari-autodeploy.timer
sudo systemctl enable --now jejari-autodeploy.timer >/dev/null
# 실제 KTX 시간표 매일 갱신 (.env의 DATA_GO_KR_KEY_DEC 필요). 지금 한 번: sudo systemctl start jejari-tago
sudo systemctl enable --now jejari-tago.timer >/dev/null
# 위험 점수 모델 보조 의견 매시간 갱신 (DB에 v25 패치 필요). 지금 한 번: sudo systemctl start jejari-model
sudo systemctl enable --now jejari-model.timer >/dev/null

echo "준비 끝. 주소: https://${DOMAIN}"
echo "다음: docker compose -f compose.yaml -f deploy/compose.server.yaml up -d  → Oracle healthy 대기 → bash deploy/deploy.sh"
