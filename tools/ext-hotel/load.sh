#!/usr/bin/env bash
# DATA-04 외부 데이터: Hotel Booking Demand (Antonio, Almeida, Nunes 2019, CC BY 4.0) 적재.
#
#   원본 내려받기 (TidyTuesday 2020-02-11 정리본, 원 논문 데이터와 같은 119,390건·32컬럼)
#   → SHA-256 확인 (고정값과 다르면 중단: 같은 파일로 재현했는지 보장)
#   → MongoDB ext_hotel_booking 에 통째로 다시 적재 (--drop, 두 번 돌려도 같음) + dataset_id
#   → Oracle ext_dataset 에 출처·라이선스·건수·해시 기록 (MERGE)
#
# 실행 (저장소 루트, docker compose up -d 뒤):  bash tools/ext-hotel/load.sh
# 걸리는 시간: 내려받기 16MB + 적재 약 10~20초
set -euo pipefail
cd "$(dirname "$0")/../.."

URL="https://raw.githubusercontent.com/rfordatascience/tidytuesday/master/data/2020/2020-02-11/hotels.csv"
SHA256="7c2ae42a7353905ea136e5c2287f17c92c5435826598bfbb8491c6f0c7b1fc06"
DATASET_ID="hotel_booking@v1"
DIR="build/ext/hotel"   # build/ 는 git 제외 (원본 16MB는 커밋하지 않음)
CSV="$DIR/hotels.csv"

env_val() { grep "^$1=" .env | cut -d= -f2-; }
MONGO_USER=$(env_val MONGO_APP_USER); MONGO_USER=${MONGO_USER:-jejari}
MONGO_PW=$(env_val MONGO_APP_PASSWORD)
ORACLE_PW=$(env_val ORACLE_APP_PASSWORD)

mkdir -p "$DIR"
if [ ! -f "$CSV" ]; then
  echo "[1/4] 원본 내려받기"
  curl -sSfL -o "$CSV" "$URL"
else
  echo "[1/4] 원본 있음 ($CSV)"
fi

echo "[2/4] SHA-256 확인"
actual=$(sha256sum "$CSV" | cut -d' ' -f1)
if [ "$actual" != "$SHA256" ]; then
  echo "SHA-256이 다릅니다: $actual (기대 $SHA256). 원본이 바뀌었으면 dataset_id 버전을 올리세요." >&2
  exit 1
fi
rows=$(($(wc -l < "$CSV") - 1))

echo "[3/4] MongoDB ext_hotel_booking 적재 ($rows 건)"
docker compose exec -T mongo mongoimport --quiet \
  --uri "mongodb://$MONGO_USER:$MONGO_PW@localhost:27017/jejari?authSource=jejari" \
  --collection ext_hotel_booking --type csv --headerline --drop < "$CSV"
docker compose exec -T mongo mongosh --quiet \
  "mongodb://$MONGO_USER:$MONGO_PW@localhost:27017/jejari?authSource=jejari" --eval "
    const c = db.ext_hotel_booking;
    c.updateMany({}, { \$set: { dataset_id: '$DATASET_ID' } });
    c.createIndex({ dataset_id: 1 });
    print('MongoDB 건수: ' + c.countDocuments({ dataset_id: '$DATASET_ID' }));"

echo "[4/4] Oracle ext_dataset 기록"
docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
  bash -lc "sqlplus -S -L jejari/$ORACLE_PW@localhost:1521/FREEPDB1" <<SQL
WHENEVER SQLERROR EXIT FAILURE
MERGE INTO ext_dataset d
USING (SELECT '$DATASET_ID' AS dataset_id FROM dual) s ON (d.dataset_id = s.dataset_id)
WHEN MATCHED THEN UPDATE SET row_count = $rows, checksum_sha256 = '$SHA256', ingested_at = SYSTIMESTAMP,
  updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN INSERT (dataset_id, name, source_url, license, license_checked_at, row_count, checksum_sha256,
  ingested_at, caveat)
VALUES ('$DATASET_ID', 'Hotel Booking Demand (Antonio, Almeida, Nunes 2019)',
  'https://doi.org/10.1016/j.dib.2018.11.126 (파일: $URL)', 'CC BY 4.0', DATE '2026-11-18', $rows, '$SHA256',
  SYSTIMESTAMP, '포르투갈 호텔 2곳(2015-07~2017-08) 예약. 열차·명절이 아니므로 숫자를 그대로 쓰지 않고 취소 행동의 모양을 참고하는 근거로만');
COMMIT;
SELECT dataset_id || ' | ' || license || ' | ' || row_count AS ext_dataset FROM ext_dataset WHERE dataset_id = '$DATASET_ID';
SQL
echo "끝. 분석: bash tools/ext-hotel/analyze.sh"
