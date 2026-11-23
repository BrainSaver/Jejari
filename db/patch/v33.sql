-- ERD v32 → v33 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v33 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 비회원(게스트) 예매 (김성원 요청, 팀장 범위 확정 2026-11-23)
--   · member.member_type   VARCHAR2(10) NULL 허용 — NULL = 일반 회원, 'GUEST' = 게스트 (CHECK ck_member_r1)
--     NULL을 허용하는 이유: 기존 member INSERT(가입·합성 데이터·테스트)를 고치지 않아도 그대로 동작하게.
--     기존 행은 모두 NULL = 일반 회원으로 남습니다 (UPDATE 없음)
--   · member.guest_expires_at TIMESTAMP NULL 허용 — 게스트 개인정보 삭제 시각
--   · app_policy guest.enabled(N) · guest.retain_days_after_run(30) · guest.lookup_rate_per_hour(10)
-- 앱 코드(게스트 가입·조회)는 아직 없습니다. guest.enabled = N이라 이 패치만 넣어도 동작은 그대로입니다.
--
-- MongoDB behavior_log 인덱스 ix_run_ts 제거는 Oracle 패치가 아니라 서버 명령입니다:
--   docs/강동균/features/서버-요청-ix_run_ts-제거.md
--
-- 다시 실행해도 안전합니다 (이미 있는 컬럼·제약·설정 값은 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v33.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE member ADD (member_type VARCHAR2(10), guest_expires_at TIMESTAMP)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -1430 THEN RAISE; END IF;  -- 이미 있으면 건너뜀 (ORA-01430)
END;
/

BEGIN
  EXECUTE IMMEDIATE q'[ALTER TABLE member ADD CONSTRAINT ck_member_r1 CHECK (member_type IN ('MEMBER', 'GUEST'))]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2264 THEN RAISE; END IF;  -- 같은 이름 제약이 있으면 건너뜀 (ORA-02264)
END;
/

COMMENT ON COLUMN member.member_type IS '회원 종류 (NULL = 일반 회원, GUEST = 비회원 예매) / 이용 제한·정지는 status가, 게스트 여부는 이 칸이. 게스트도 같은 CI 규칙(매수 제한·위험 점수)을 받는다';
COMMENT ON COLUMN member.guest_expires_at IS '게스트 개인정보 삭제 시각 (마지막 운행일 + guest.retain_days_after_run) / 게스트가 표를 더 사면 늘린다. 진행 중 예약이 있으면 지우지 않는다. 일반 회원은 NULL';

MERGE INTO app_policy t
USING (
  SELECT 'guest.enabled' k, 'N' v, 'STRING' vt, 'Y면 비회원(게스트) 예매 사용. 기능이 들어가기 전에는 N' d FROM dual UNION ALL
  SELECT 'guest.retain_days_after_run', '30', 'NUMBER', '게스트 개인정보 보관: 그 게스트의 마지막 운행일 + 이 일수 (조회·환불 문의 기간)' FROM dual UNION ALL
  SELECT 'guest.lookup_rate_per_hour', '10', 'NUMBER', 'IP당 시간당 비회원 예약 조회 시도 한도 (rate:guest_lookup)' FROM dual
) s ON (t.policy_key = s.k)
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, s.vt, s.d);

COMMIT;

SELECT column_name || ' ' || data_type || ' ' || nullable FROM user_tab_columns
 WHERE table_name = 'MEMBER' AND column_name IN ('MEMBER_TYPE', 'GUEST_EXPIRES_AT') ORDER BY column_name;
SELECT search_condition_vc FROM user_constraints WHERE constraint_name = 'CK_MEMBER_R1';
SELECT policy_key || ' = ' || policy_value FROM app_policy WHERE policy_key LIKE 'guest.%' ORDER BY policy_key;
