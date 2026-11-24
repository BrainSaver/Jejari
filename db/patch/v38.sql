-- ERD v37 → v38 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v38 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 단체 신청 번호 시퀀스 group_booking_seq (길동현 요청 2026-11-24). group_booking.group_id를 매깁니다.
-- v36(group_booking 표)이 먼저 들어가 있어야 의미가 있지만, 시퀀스 자체는 표와 상관없이 만들어집니다.
--
-- 다시 실행해도 안전합니다 (이미 있으면 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v38.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE group_booking_seq';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 THEN RAISE; END IF;   -- ORA-00955: 이미 있음
END;
/

SELECT sequence_name FROM user_sequences WHERE sequence_name = 'GROUP_BOOKING_SEQ';
