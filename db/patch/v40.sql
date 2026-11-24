-- ERD v39 → v40 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v40 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 2차 대회(c1124d, 11-24) 진짜 사람 5명 · 30회 예매로 잰 위험 점수 규칙 기준값 (docs/강동균/features/2차-대회-결과-분석.md)
--   risk.rule.inhuman_step_ms         500 → 1000  사람 가장 빠른 단계 2.74초 (0.5초 미만 0/62)
--   risk.rule.account_switch_sec      (없음) → 15  가족 역할 계정 전환 최소 31.7초. 행이 없으면 코드 기본값을 씀
--   risk.rule.no_search_entry_points  10 → 30      조회 없이 대기열 진입: botc 6/6 · 사람 0/27 (점수만, 케이스 아님)
--
-- 관리자가 이미 다른 값으로 바꿨으면 건드리지 않습니다 (예전 기본값일 때만 바꿈). 위험 점수 배치가 1분마다 다시 읽어
-- 재배포 없이 적용됩니다. 관리자 화면 이력(app_policy_history)에는 남지 않습니다 (패치라 바꾼 관리자가 없음) — 이 파일이 기록
--
-- 다시 실행해도 안전합니다.
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v40.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

MERGE INTO app_policy t
USING (
  SELECT 'risk.rule.inhuman_step_ms' k, '500' old_v, '1000' v,
         'INHUMAN_STEP: 좌석표 → 선점, 선점 → 결제가 이 ms 미만이면 사람 손으로 불가능한 단계 1회' d FROM dual UNION ALL
  SELECT 'risk.rule.account_switch_sec', '3', '15',
         '기기(DEVICE) 대상 MULTI_ACCOUNT_SYNC: 그 기기에서 다른 계정으로 이 초 안에 바뀐 계정만 동시 선점으로 셈 (가족이 한 컴퓨터로 차례로 사는 것과 가르기)' FROM dual UNION ALL
  SELECT 'risk.rule.no_search_entry_points', '10', '30',
         'NO_SEARCH_ENTRY(조회 없이 대기열 진입) 점수. 점수만 (케이스 아님)' FROM dual
) s ON (t.policy_key = s.k)
WHEN MATCHED THEN
  UPDATE SET t.policy_value = s.v, t.version = t.version + 1, t.updated_at = SYSTIMESTAMP
   WHERE t.policy_value = s.old_v
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, 'NUMBER', s.d);

COMMIT;

SELECT policy_key || ' = ' || policy_value FROM app_policy
 WHERE policy_key IN ('risk.rule.inhuman_step_ms', 'risk.rule.account_switch_sec', 'risk.rule.no_search_entry_points')
 ORDER BY policy_key;
