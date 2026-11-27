#!/usr/bin/env bash
# CI 통합 테스트용 일회용 .env (.github/workflows/infra-test.yml). 실행마다 임의 값으로 새로 만들고 저장하지 않는다.
# 키 목록은 .env.example과 같게. 테스트는 이 실행에서 만든 데이터만 쓰므로 팀 공용 HMAC 키가 필요 없다.
set -euo pipefail
r() { openssl rand -base64 32; }
pw() { openssl rand -hex 16; }   # compose 명령줄(--requirepass 등)에 들어가므로 기호 없는 값
cat <<EOF
ORACLE_PASSWORD=$(pw)
ORACLE_APP_USER=jejari
ORACLE_APP_PASSWORD=$(pw)
ORACLE_HOST=localhost
ORACLE_PORT=1521
ORACLE_SERVICE=FREEPDB1
MONGO_ROOT_USER=root
MONGO_ROOT_PASSWORD=$(pw)
MONGO_APP_USER=jejari
MONGO_APP_PASSWORD=$(pw)
MONGO_HOST=localhost
MONGO_PORT=27017
REDIS_PASSWORD=$(pw)
REDIS_HOST=localhost
REDIS_PORT=6379
KAFKA_BOOTSTRAP=localhost:9092
JWT_ACTIVE_KID=k1
JWT_SECRET_K1=$(r)
JWT_REFRESH_COOKIE_SECURE=false
JEJARI_HMAC_KEY=$(r)
JEJARI_ENC_KEYS=k1:$(r)
JEJARI_ENC_ACTIVE=k1
JEJARI_PEPPER_KEYS=p1:$(r)
JEJARI_PEPPER_ACTIVE=p1
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=$(pw)
EOF
