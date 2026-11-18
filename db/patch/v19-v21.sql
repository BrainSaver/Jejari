-- ERD v18 → v21 델타. 이미 데이터가 들어 있는 Oracle에 v19·v20·v21 변경만 더합니다.
--
-- 원칙은 "스키마가 바뀌면 볼륨을 지우고 다시 띄운다"입니다 (docker/oracle/init/01-schema.sh).
-- 그런데 지금은 각자 로컬에 예약·결제 데이터가 쌓여 있어, 지우지 않고 이어서 쓰고 싶을 때를 위해 둡니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 다시 실행해도 안전합니다 (이미 있으면 건너뜁니다).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v19.sql

SET SQLBLANKLINES ON
SET DEFINE OFF
ALTER SESSION SET TIME_ZONE = 'Asia/Seoul';

-- 1. ID 시퀀스 ------------------------------------------------------------
-- 결제·취소 (길동현 요청, PAY-01~04·CNL)
-- 의심 케이스·마커 (강다인 요청, RISK-04)
-- 취소표 대기·관리자 조치 (곧 씀)
DECLARE
  TYPE t_list IS TABLE OF VARCHAR2(100);
  seqs t_list := t_list(
    'CREATE SEQUENCE payment_seq CACHE 100',
    'CREATE SEQUENCE payment_method_seq',
    'CREATE SEQUENCE cancellation_seq',
    'CREATE SEQUENCE suspicious_case_seq',
    'CREATE SEQUENCE case_marker_seq',
    'CREATE SEQUENCE waitlist_seq',
    'CREATE SEQUENCE admin_action_seq',
    'CREATE SEQUENCE notification_seq CACHE 100');   -- v20 (취소표 제안 알림)
BEGIN
  FOR i IN 1 .. seqs.COUNT LOOP
    BEGIN
      EXECUTE IMMEDIATE seqs(i);
    EXCEPTION
      WHEN OTHERS THEN
        IF SQLCODE != -955 THEN RAISE; END IF;   -- ORA-00955: 이미 있음
    END;
  END LOOP;
END;
/

-- 2. case_marker.session_id NULL 허용 -------------------------------------
-- 구매 직후 취소 마커는 Oracle 기록만 보고 찍어서 녹화 세션이 없습니다 (강다인 요청).
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_tab_columns
   WHERE table_name = 'CASE_MARKER' AND column_name = 'SESSION_ID' AND nullable = 'N';
  IF n > 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE case_marker MODIFY (session_id NULL)';
  END IF;
END;
/

-- 3. 인덱스 ---------------------------------------------------------------
-- 같은 CI·같은 카드를 쓰는 다른 계정 찾기 (RISK-04 연결 계정, ADM-05 타임라인)
-- 대상당 열린 케이스 1건 보장 (닫힌 케이스는 인덱스에 들어가지 않아 다시 열 수 있음)
DECLARE
  TYPE t_list IS TABLE OF VARCHAR2(400);
  idx t_list := t_list(
    'CREATE INDEX ix_member_ci_hash ON member (ci_hash)',
    'CREATE INDEX ix_pay_method_hash ON payment_method (method_hash)',
    'CREATE UNIQUE INDEX ux_case_open_subject ON suspicious_case (' ||
      'CASE WHEN status IN (''OPEN'', ''REVIEWING'') THEN subject_type END, ' ||
      'CASE WHEN status IN (''OPEN'', ''REVIEWING'') THEN subject_hash END)');
BEGIN
  FOR i IN 1 .. idx.COUNT LOOP
    BEGIN
      EXECUTE IMMEDIATE idx(i);
    EXCEPTION
      WHEN OTHERS THEN
        IF SQLCODE NOT IN (-955, -1408) THEN RAISE; END IF;  -- 이미 있음 / 이미 인덱싱됨
    END;
  END LOOP;
END;
/

-- 4. 상시 취소 수수료 정책 (강다인 요청, v21) ---------------------------
-- 기존 예시 1~5는 모두 지난 행사(sale_event_id = 1)에 묶여 있어, 평시 운행(9001·9002)에서는
-- 적용되는 정책이 없어 예매 내역 상세가 "취소 불가"로 보였습니다.
-- 3일 전 0% · 1~3일 5% · 3시간~1일 10% · 3시간 이내 20% · 출발 후 40%
MERGE INTO cancel_fee_policy t
USING (
  SELECT 6  AS fee_policy_id, 4320 AS mn, CAST(NULL AS NUMBER) AS mx, 0    AS rate, 'N' AS ad FROM dual UNION ALL
  SELECT 7,                   1440,       4320,                       0.05,        'N'        FROM dual UNION ALL
  SELECT 8,                   180,        1440,                       0.1,         'N'        FROM dual UNION ALL
  SELECT 9,                   0,          180,                        0.2,         'N'        FROM dual UNION ALL
  SELECT 10,                  CAST(NULL AS NUMBER), CAST(NULL AS NUMBER), 0.4,      'Y'        FROM dual
) s ON (t.fee_policy_id = s.fee_policy_id)
WHEN NOT MATCHED THEN
  INSERT (fee_policy_id, sale_event_id, min_minutes_before, max_minutes_before, fee_rate, after_departure)
  VALUES (s.fee_policy_id, NULL, s.mn, s.mx, s.rate, s.ad);

COMMIT;

-- 확인 -------------------------------------------------------------------
SET PAGESIZE 60 LINESIZE 120
SELECT COUNT(*) AS "시퀀스(14여야 함)" FROM user_sequences;
SELECT nullable AS "case_marker.session_id(Y여야 함)" FROM user_tab_columns
 WHERE table_name = 'CASE_MARKER' AND column_name = 'SESSION_ID';
SELECT index_name AS "새 인덱스(3개)" FROM user_indexes
 WHERE index_name IN ('IX_MEMBER_CI_HASH', 'IX_PAY_METHOD_HASH', 'UX_CASE_OPEN_SUBJECT')
 ORDER BY 1;
SELECT COUNT(*) AS "상시 취소 수수료(5여야 함)" FROM cancel_fee_policy WHERE sale_event_id IS NULL;
