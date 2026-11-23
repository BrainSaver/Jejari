#!/usr/bin/env bash
# DATA-04 Hotel Booking 분석 실행. 먼저 bash tools/ext-hotel/load.sh 로 적재해 두어야 한다.
# 결과: 화면 출력 + build/ext/hotel/analysis.json (문서 docs/강다인/features/외부데이터-호텔-취소-분석.md 의 수치 원천)
set -euo pipefail
cd "$(dirname "$0")/../.."
env_val() { grep "^$1=" .env | cut -d= -f2-; }
MONGO_USER=$(env_val MONGO_APP_USER); MONGO_USER=${MONGO_USER:-jejari}
MONGO_PW=$(env_val MONGO_APP_PASSWORD)
mkdir -p build/ext/hotel
# 표준 입력으로 넘기면 mongosh가 대화형으로 줄마다 프롬프트를 찍어서 --eval 로 넘긴다
docker compose exec -T mongo mongosh --quiet \
  "mongodb://$MONGO_USER:$MONGO_PW@localhost:27017/jejari?authSource=jejari" \
  --eval "$(cat tools/ext-hotel/analysis.mongodb.js)" | tee build/ext/hotel/analysis.json
