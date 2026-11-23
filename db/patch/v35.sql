-- ERD v34 → v35 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v35 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 1. 좌석 조회·선점 속도 제한 (김성원 요청 18번, SeatRateLimiter)
--    app_policy seatmap.rate_burst(10) · rate_per_sec(1) · abuse_threshold(20) · abuse_window_sec(60) · cooldown_sec(60)
--    값은 코드 기본값과 같아 넣어도 동작은 그대로입니다. 이제 관리자 설정 화면(ADM-08)에서 바꿀 수 있습니다.
--    Redis 키 rate:seatmap·rate:seathold는 앱이 만들고 TTL로 사라지므로 서버 명령이 없습니다.
-- 2. 결제 기한이 지나 풀린 좌석도 대기자 우선 (김성원 요청 19번 A안, 강동균 결정 2026-11-23)
--    app_policy reservation.hold_release_grace_sec(10). 코드가 이 값을 읽기 전까지는 아무 영향이 없습니다.
--    0으로 바꾸면 예전 동작(마감 즉시 일반 사용자에게 열림)으로 돌아갑니다.
--
-- 다시 실행해도 안전합니다 (이미 있는 설정 값은 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v35.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

MERGE INTO app_policy t
USING (
  SELECT 'seatmap.rate_burst' k, '10' v, '한 계정(운행 단위)이 처음에 막힘 없이 보낼 수 있는 좌석 조회·선점 횟수 (물통 크기)' d FROM dual UNION ALL
  SELECT 'seatmap.rate_per_sec', '1', '그 뒤 초당 다시 채워지는 좌석 조회·선점 횟수' FROM dual UNION ALL
  SELECT 'seatmap.abuse_threshold', '20', '물이 바닥난 채로 이만큼 거절당하면 잠금' FROM dual UNION ALL
  SELECT 'seatmap.abuse_window_sec', '60', '거절을 세는 시간 창(초)' FROM dual UNION ALL
  SELECT 'seatmap.cooldown_sec', '60', '잠금 시간(초)' FROM dual UNION ALL
  SELECT 'reservation.hold_release_grace_sec', '10', '선점·제안 키를 결제 마감 뒤 이만큼 더 붙잡아, 스캐너가 대기자 제안으로 넘길 때까지 일반 사용자가 못 잡게 함. 0이면 마감 즉시 열림(예전 동작)' FROM dual
) s ON (t.policy_key = s.k)
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, 'NUMBER', s.d);

COMMIT;

SELECT policy_key || ' = ' || policy_value FROM app_policy
 WHERE policy_key LIKE 'seatmap.rate%' OR policy_key LIKE 'seatmap.abuse%' OR policy_key = 'seatmap.cooldown_sec'
    OR policy_key = 'reservation.hold_release_grace_sec'
 ORDER BY policy_key;
