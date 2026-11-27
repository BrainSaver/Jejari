-- ERD v40 → v41 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v41 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 결제 거절 반복 신호 DECLINED_CARDS (카드 대입, 강다인 요청 2026-11-27, 추천안 강동균)
--   risk.rule.declined_cards_min     3   명의(CI)가 최근 1시간에 서로 다른 카드로 거절당한 수가 이 이상이면
--   risk.rule.declined_cards_points  30  이 점수 (점수만, 케이스 아님)
--   PG_로 시작하는 실패 사유(PG 장애)는 세지 않습니다. 같은 카드 반복 거절도 세지 않습니다 (사람의 재시도).
--
-- 행이 없어도 위험 점수 배치는 코드 기본값(3장 · 30점)으로 돕니다. 이 행은 관리자 화면에서 값을 보고 바꾸기 위한 것입니다.
-- 다시 실행해도 안전합니다 (이미 있는 설정 값은 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v41.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

MERGE INTO app_policy t
USING (
  SELECT 'risk.rule.declined_cards_min' k, '3' v, 'DECLINED_CARDS(카드 대입): 명의(CI)가 최근 1시간에 서로 다른 카드로 거절당한 수가 이 이상이면 점수. PG_로 시작하는 실패 사유(PG 장애)는 세지 않음' d FROM dual UNION ALL
  SELECT 'risk.rule.declined_cards_points', '30', 'DECLINED_CARDS 점수. 점수만 (케이스 아님). 30 = 경고 등급, 단독으로는 결제 단계 차단(80)에 닿지 않음' FROM dual
) s ON (t.policy_key = s.k)
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, 'NUMBER', s.d);

COMMIT;

SELECT policy_key || ' = ' || policy_value FROM app_policy WHERE policy_key LIKE 'risk.rule.declined_cards%' ORDER BY policy_key;
