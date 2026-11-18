#!/usr/bin/env bash
# 공용 서버용 .env를 새로 만든다 (내 PC에서 실행). 값은 화면에 출력하지 않는다.
#
#   deploy/make-server-env.sh            → ~/jejari-server.env (저장소 밖)
#   그 다음 서버로:  gcloud compute scp ~/jejari-server.env jejari:~/Jejari/.env
#
# 규칙
#   - JEJARI_HMAC_KEY만 팀 공용 값을 그대로 쓴다 (내 로컬 .env에서 읽음). 바꾸면 ci_hash가 달라져
#     매수 제한·위험 점수가 로컬과 이어지지 않는다
#   - 나머지(DB 비밀번호·JWT·암호화·pepper·관리자·Grafana)는 전부 새로 만든다. 로컬 값이 서버에 가지 않게
#   - 리프레시 쿠키 Secure=true (서버는 Caddy로 HTTPS)
#   - 관리자·Grafana 비밀번호는 이 파일에서 직접 열어 확인한다 (화면에 출력하지 않음)
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-$HOME/jejari-server.env}"
if [[ -e "$OUT" ]]; then
  echo "이미 있습니다: $OUT (덮어쓰지 않음. 지우고 다시 실행하세요)"; exit 1
fi
HMAC=$(grep -E '^JEJARI_HMAC_KEY=' .env 2>/dev/null | head -1 | cut -d= -f2-)
if [[ -z "$HMAC" ]]; then
  echo "로컬 .env에 JEJARI_HMAC_KEY가 없습니다 (팀 공용 값)."; exit 1
fi
command -v openssl >/dev/null || { echo "openssl이 필요합니다."; exit 1; }

alnum() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-"${1:-24}"; } # URI·명령줄에 안전한 문자만
b64() { openssl rand -base64 32; }

umask 077
cat > "$OUT" <<EOF
# 제자리 공용 서버 .env — $(date +%F) make-server-env.sh로 생성. 커밋·공유 채널 게시 금지
ORACLE_PASSWORD=$(alnum)
ORACLE_APP_USER=jejari
ORACLE_APP_PASSWORD=$(alnum)
ORACLE_HOST=localhost
ORACLE_PORT=1521
ORACLE_SERVICE=FREEPDB1

MONGO_ROOT_USER=root
MONGO_ROOT_PASSWORD=$(alnum)
MONGO_APP_USER=jejari
MONGO_APP_PASSWORD=$(alnum)
MONGO_HOST=localhost
MONGO_PORT=27017

REDIS_PASSWORD=$(alnum 32)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_MAXMEMORY=1gb

KAFKA_BOOTSTRAP=localhost:9092

JWT_ACTIVE_KID=k1
JWT_SECRET_K1=$(b64)
JWT_REFRESH_COOKIE_SECURE=true

JEJARI_HMAC_KEY=${HMAC}
JEJARI_ENC_KEYS=k1:$(b64)
JEJARI_ENC_ACTIVE=k1
JEJARI_PEPPER_KEYS=p1:$(b64)
JEJARI_PEPPER_ACTIVE=p1

JEJARI_ADMIN_BOOTSTRAP_ID=admin
JEJARI_ADMIN_BOOTSTRAP_PASSWORD=Adm!$(alnum 20)

GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=$(alnum 20)
# 팀 공용 보기 전용 Grafana 계정(team) 비밀번호. deploy/grafana-team-user.sh가 씀
GRAFANA_TEAM_PASSWORD=$(alnum 20)

# Caddy 주소. 서버에서 deploy/setup-server.sh가 외부 IP로 채움 (예: 34-64-12-34.sslip.io)
JEJARI_DOMAIN=
EOF
chmod 600 "$OUT" 2>/dev/null || true
echo "만들었습니다: $OUT (값은 출력하지 않음)"
echo "다음: gcloud compute scp \"$OUT\" jejari:~/Jejari/.env"
