#!/bin/bash
# 첫 기동 때 앱 계정(APP_USER)으로 ERD 생성 DDL을 실행합니다.
# DDL 원본은 docs/erd/jejari-erd.ddl.sql (check-erd.js가 생성). 스키마가 바뀌면 Oracle 볼륨을 지우고 다시 띄웁니다
# (데이터가 모두 사라짐):
#   docker compose rm -sf oracle && docker volume rm jejari_oracle-data && docker compose up -d oracle
set -euo pipefail

sqlplus -s "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" <<'SQL'
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
ALTER SESSION SET TIME_ZONE = 'Asia/Seoul';
@/schema/jejari-erd.ddl.sql
COMMIT;
EXIT
SQL

echo "jejari schema created"
