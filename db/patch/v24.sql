-- ERD v23 → v24 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v24 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- case_marker.rule_code CHECK에 REGULAR_INTERVAL 추가 (강다인 요청, 일정 간격 매크로를 마커 규칙으로).
-- 이 패치 없이 REGULAR_INTERVAL 마커를 넣으면 ORA-02290(체크 제약 위반)으로 케이스 열기가 실패합니다.
--
-- 다시 실행해도 안전합니다 (제약을 지우고 같은 이름으로 다시 만듭니다).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v24.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE case_marker DROP CONSTRAINT ck_case_marker_rule_code';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2443 THEN RAISE; END IF;  -- 제약이 없으면 건너뜀
END;
/

ALTER TABLE case_marker ADD CONSTRAINT ck_case_marker_rule_code
  CHECK (rule_code IN ('FAST_CLICK', 'SAME_SEAT_RETRY', 'CANCEL_AFTER_PAY', 'MULTI_ACCOUNT_SYNC', 'REGULAR_INTERVAL', 'LINEAR_MOUSE'));

SELECT search_condition_vc FROM user_constraints WHERE constraint_name = 'CK_CASE_MARKER_RULE_CODE';
