-- ERD v38 → v39 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v39 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 인증 시도 제한 임시 해제 (강동균 2026-11-24, docs/강동균/설계안-인증-시도제한-임시해제.md)
--   app_policy 5행. 넣은 직후에는 OFF·NONE이라 아무 동작도 바뀌지 않습니다.
--   관리자 화면 "운영 설정 → 인증 제한 임시 해제"에서 IP·시간·사유를 넣으면 그 IP만 그 시각까지 풀립니다.
--   이 행들이 없으면 그 칸은 "정책 키가 없습니다"로 실패합니다.
--
-- 다시 실행해도 안전합니다 (이미 있는 설정 값은 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v39.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

MERGE INTO app_policy t
USING (
  SELECT 'auth.rate_relax_until' k, 'OFF' v, 'STRING' ty, '인증 시도 제한 임시 해제 종료 시각 yyyy-MM-dd HH:mm (Asia/Seoul). OFF면 해제 안 함. 지금 + 24시간을 넘는 값은 저장 거절·읽을 때 무시. 시각이 지나면 자동으로 평소 한도' d FROM dual UNION ALL
  SELECT 'auth.rate_relax_ip_hashes', 'NONE', 'STRING', '임시 해제할 IP의 ip_hash(HMAC) 목록, 쉼표로 최대 3개. NONE이면 없음. 원본 IP는 저장하지 않음 (관리자 화면이 IP를 해시로 바꿔 넣음)' FROM dual UNION ALL
  SELECT 'auth.rate_relax_reason', '-', 'STRING', '임시 해제 사유 (정책 이력에 누가·언제와 함께 남음)' FROM dual UNION ALL
  SELECT 'auth.rate_relax_signup_per_hour', '2000', 'NUMBER', '임시 해제 동안 해제 IP의 시간당 본인인증·가입 한도 (1~5000)' FROM dual UNION ALL
  SELECT 'auth.rate_relax_login_per_min', '3000', 'NUMBER', '임시 해제 동안 해제 IP의 분당 회원 로그인 한도 (1~10000). 관리자 로그인·비회원 조회는 해제하지 않음' FROM dual
) s ON (t.policy_key = s.k)
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, s.ty, s.d);

COMMIT;

SELECT policy_key || ' = ' || policy_value FROM app_policy WHERE policy_key LIKE 'auth.rate_relax%' ORDER BY policy_key;
