-- ERD v33 → v34 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v34 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 마커 규칙 2개 (강다인 요청 2026-11-23)
--   · case_marker.rule_code CHECK에 INHUMAN_STEP(사람 손으로 불가능한 단계 속도), HEADLESS_HOLD(화면 녹화 없이 좌석 선점) 추가
--     코드는 이 CHECK를 읽어서, 패치가 들어오면 재배포 없이 5분 안에 두 규칙이 케이스 규칙으로 켜집니다
--   · app_policy risk.rule.* 15행 (값은 코드 기본값과 같음 → 넣어도 동작은 그대로, A-08 정책 화면에서 조정 가능)
-- MongoDB behavior_log.elapsed_ms, replay_event.pointer는 칸만 늘어나는 것이라 서버 명령이 없습니다 (앱이 쓰기 시작하면 생김).
--
-- 다시 실행해도 안전합니다 (제약을 지우고 같은 이름으로 다시 만듦, 이미 있는 설정 값은 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v34.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE case_marker DROP CONSTRAINT ck_case_marker_rule_code';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2443 THEN RAISE; END IF;  -- 제약이 없으면 건너뜀 (ORA-02443)
END;
/

ALTER TABLE case_marker ADD CONSTRAINT ck_case_marker_rule_code
  CHECK (rule_code IN ('FAST_CLICK', 'SAME_SEAT_RETRY', 'CANCEL_AFTER_PAY', 'MULTI_ACCOUNT_SYNC', 'REGULAR_INTERVAL', 'LINEAR_MOUSE', 'INHUMAN_STEP', 'HEADLESS_HOLD'));

COMMENT ON COLUMN case_marker.rule_code IS 'FAST_CLICK / SAME_SEAT_RETRY / CANCEL_AFTER_PAY / MULTI_ACCOUNT_SYNC / REGULAR_INTERVAL / LINEAR_MOUSE / INHUMAN_STEP / HEADLESS_HOLD / 어떤 규칙이 잡았는지. 값은 RiskRule 중 마커 규칙(marker=true)과 같게 둔다';

MERGE INTO app_policy t
USING (
  SELECT 'risk.rule.warning_threshold' k, '30' v, '위험 점수가 이 이상이면 주의(경고) 단계' d FROM dual UNION ALL
  SELECT 'risk.rule.inhuman_step_ms', '500', 'INHUMAN_STEP: 좌석표 → 선점, 선점 → 결제가 이 ms 미만이면 사람 손으로 불가능한 단계 1회' FROM dual UNION ALL
  SELECT 'risk.rule.inhuman_step.t1', '2', 'INHUMAN_STEP 1단계 횟수' FROM dual UNION ALL
  SELECT 'risk.rule.inhuman_step.p1', '30', 'INHUMAN_STEP 1단계 점수' FROM dual UNION ALL
  SELECT 'risk.rule.inhuman_step.t2', '5', 'INHUMAN_STEP 2단계 횟수' FROM dual UNION ALL
  SELECT 'risk.rule.inhuman_step.p2', '40', 'INHUMAN_STEP 2단계 점수' FROM dual UNION ALL
  SELECT 'risk.rule.headless_hold.t1', '1', 'HEADLESS_HOLD 1단계: 화면 녹화 없이 선점한 세션 수' FROM dual UNION ALL
  SELECT 'risk.rule.headless_hold.p1', '30', 'HEADLESS_HOLD 1단계 점수' FROM dual UNION ALL
  SELECT 'risk.rule.headless_hold.t2', '3', 'HEADLESS_HOLD 2단계 세션 수' FROM dual UNION ALL
  SELECT 'risk.rule.headless_hold.p2', '45', 'HEADLESS_HOLD 2단계 점수' FROM dual UNION ALL
  SELECT 'risk.rule.linear_mouse.t1', '8', 'LINEAR_MOUSE 1단계: 기계적 마우스 이동(순간이동·직선 등속) 수' FROM dual UNION ALL
  SELECT 'risk.rule.linear_mouse.p1', '25', 'LINEAR_MOUSE 1단계 점수' FROM dual UNION ALL
  SELECT 'risk.rule.linear_mouse.t2', '20', 'LINEAR_MOUSE 2단계 이동 수' FROM dual UNION ALL
  SELECT 'risk.rule.linear_mouse.p2', '40', 'LINEAR_MOUSE 2단계 점수' FROM dual UNION ALL
  SELECT 'risk.rule.no_search_entry_points', '10', 'NO_SEARCH_ENTRY(조회 없이 대기열 진입) 점수. 점수만 (케이스 아님)' FROM dual
) s ON (t.policy_key = s.k)
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, 'NUMBER', s.d);

COMMIT;

SELECT search_condition_vc FROM user_constraints WHERE constraint_name = 'CK_CASE_MARKER_RULE_CODE';
SELECT policy_key || ' = ' || policy_value FROM app_policy WHERE policy_key LIKE 'risk.rule.%' ORDER BY policy_key;
