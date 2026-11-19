-- ERD v25 → v26 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v26 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- train.train_no 설명을 5자리 표기(00103)로. 주석만 바뀌고 데이터·제약은 그대로입니다.
-- 다시 실행해도 안전합니다.
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v26.sql

SET DEFINE OFF

COMMENT ON COLUMN train.train_no IS '열차 번호, 앞을 0으로 채운 5자리 (예: 00103, 00026) / 코레일 표기와 같게. 같은 번호의 열차를 두 번 넣지 않도록 적재 스크립트가 이 값으로 비교';
