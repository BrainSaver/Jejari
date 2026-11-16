#!/bin/bash
# 첫 기동 때 01-schema.sh 다음으로 기준 데이터를 넣습니다 (DATA-01, 담당 B 강동균).
# 역·노선·열차·편성·좌석·운행·정차 시각·운임·매수 제한. 값의 출처는 공공데이터포털이고
# 원본 CSV는 db/seed/data/ 에 있습니다. 자세한 건 db/seed/README.md.
#
# 전부 MERGE라 여러 번 돌려도 결과가 같습니다. 스키마를 다시 만들 때는 01과 함께 다시 돕니다.
set -euo pipefail

cd /seed

for f in 01_station_route.sql 02_car_type_train_seat.sql 03_sale_event_run.sql \
         04_regular_run.sql 05_train_stop_fare.sql; do
  sqlplus -s "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
ALTER SESSION SET TIME_ZONE = 'Asia/Seoul';
@${f}
EXIT
SQL
  echo "seed: ${f} ok"
done

echo "jejari seed loaded"
