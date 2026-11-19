-- ERD v29 → v30 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v30 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- notification.type CHECK에 CONFIRMED 추가 — 예매 완료 알림 (RESERVATION_CONFIRMED 이벤트 → "예매가 완료됐어요").
-- **이 패치를 먼저 넣고 나서 알림 코드를 합칩니다.** 순서가 바뀌면 CONFIRMED 알림 INSERT가
-- ORA-02290(체크 제약 위반)으로 실패하고, 그 이벤트가 계속 재시도돼 알림 처리기가 막힙니다.
--
-- 다시 실행해도 안전합니다 (제약을 지우고 같은 이름으로 다시 만듭니다). 기존 행은 모두 새 목록 안이라 그대로 통과합니다.
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v30.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE notification DROP CONSTRAINT ck_notification_type';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2443 THEN RAISE; END IF;  -- 제약이 없으면 건너뜀
END;
/

ALTER TABLE notification ADD CONSTRAINT ck_notification_type
  CHECK (type IN ('WAITLIST_OFFER', 'PAY_REMINDER', 'EXPIRED', 'WAITLIST_SKIPPED', 'REFUNDED', 'CONFIRMED'));

COMMENT ON COLUMN notification.type IS 'WAITLIST_OFFER / PAY_REMINDER / EXPIRED / WAITLIST_SKIPPED / REFUNDED / CONFIRMED / 취소표 건너뜀(매수 한도 초과)·환불 완료·예매 완료 안내';

SELECT search_condition_vc FROM user_constraints WHERE constraint_name = 'CK_NOTIFICATION_TYPE';
