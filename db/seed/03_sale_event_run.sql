-- 제자리 기준 데이터 시드 3/5 : 명절 행사 · 매수 제한 정책 · 날짜별 운행
-- 선행: 02_car_type_train_seat.sql · 후행: 05_train_stop_fare.sql로 정차 시각을 채웁니다
--
-- 날짜는 "실행한 날" 기준 상대값입니다 (연휴 = 오늘 + 30~33일, 예매 오픈 = 내일 07:00).
-- 행사·운행은 이미 있으면 날짜를 덮어쓰지 않습니다 (INSERT만). 날짜를 다시 잡으려면
-- 99_reset.sql로 지운 뒤 다시 실행합니다.
--
-- 왜 행사를 새로 만드나: DDL의 예시 행 sale_event 1(2026 추석)과 그 매수 제한 정책은
-- valid_to가 2026-09-27로 이미 지났습니다. 동시성 §10.6은 "지금 시각이 valid_from~valid_to
-- 안인 행"만 후보로 보기 때문에, 그대로 두면 매수 제한(PER_RUN·PER_HOLIDAY)이 적용되지
-- 않습니다. 시연·테스트 기간을 덮는 행사와 정책이 필요합니다.

MERGE INTO sale_event t
USING (
  SELECT 2                                                        AS sale_event_id,
         '시연 명절 (시드)'                                       AS name,
         TRUNC(SYSDATE) + 30                                      AS holiday_from,
         TRUNC(SYSDATE) + 33                                      AS holiday_to,
         CAST(TRUNC(SYSDATE) + 1 AS TIMESTAMP) + INTERVAL '7' HOUR AS sale_open_at,
         CAST(TRUNC(SYSDATE)     AS TIMESTAMP) + INTERVAL '7' HOUR AS priority_open_at,
         'PLANNED'                                                AS status
    FROM dual
) s ON (t.sale_event_id = s.sale_event_id)
WHEN NOT MATCHED THEN
  INSERT (sale_event_id, name, holiday_from, holiday_to, sale_open_at, priority_open_at, status)
  VALUES (s.sale_event_id, s.name, s.holiday_from, s.holiday_to,
          s.sale_open_at, s.priority_open_at, s.status);

-- 매수 제한 (동시성 §10.6)
--   4~6: 행사 2 전용. valid_from을 어제로 두어 지금 시각이 범위 안에 들어오게 합니다.
--   7~8: 상시 정책(sale_event_id NULL). 행사에 묶이지 않은 운행(04의 개발용 운행)에 적용됩니다.
--        PER_HOLIDAY는 행사가 있어야만 의미가 있으므로 상시 정책에 두지 않습니다.
MERGE INTO purchase_limit_policy t
USING (
  SELECT 4 AS policy_id, 2 AS sale_event_id, 'PER_RESERVATION' AS scope, 4 AS max_seats,
         CAST(TRUNC(SYSDATE) - 1 AS TIMESTAMP)      AS valid_from,
         CAST(TRUNC(SYSDATE) + 34 AS TIMESTAMP)     AS valid_to      FROM dual UNION ALL
  SELECT 5,              2,                 'PER_RUN',              4,
         CAST(TRUNC(SYSDATE) - 1 AS TIMESTAMP),
         CAST(TRUNC(SYSDATE) + 34 AS TIMESTAMP)                      FROM dual UNION ALL
  SELECT 6,              2,                 'PER_HOLIDAY',          8,
         CAST(TRUNC(SYSDATE) - 1 AS TIMESTAMP),
         CAST(TRUNC(SYSDATE) + 34 AS TIMESTAMP)                      FROM dual UNION ALL
  SELECT 7,              NULL,              'PER_RESERVATION',      4, NULL, NULL FROM dual UNION ALL
  SELECT 8,              NULL,              'PER_RUN',              4, NULL, NULL FROM dual
) s ON (t.policy_id = s.policy_id)
WHEN NOT MATCHED THEN
  INSERT (policy_id, sale_event_id, scope, max_seats, valid_from, valid_to)
  VALUES (s.policy_id, s.sale_event_id, s.scope, s.max_seats, s.valid_from, s.valid_to);

-- 날짜별 운행: 열차 2편 × 연휴 4일 = 8건
--   run_id = train_id * 1000 + 연휴 n일차
--   status는 SCHEDULED. 판매 오픈 시각이 되면 ADM-02 스케줄러가 ON_SALE로 바꿉니다.
MERGE INTO train_run t
USING (
  SELECT tr.train_id * 1000 + d.n        AS run_id,
         tr.train_id,
         se.sale_event_id,
         se.holiday_from + (d.n - 1)     AS run_date,
         se.sale_open_at,
         CAST(se.holiday_from + d.n AS TIMESTAMP) AS sale_close_at,
         'SCHEDULED'                     AS status
    FROM train tr
   CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 4) d
   CROSS JOIN sale_event se
   WHERE tr.train_id IN (1, 2)
     AND se.sale_event_id = 2
) s ON (t.run_id = s.run_id)
WHEN NOT MATCHED THEN
  INSERT (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
  VALUES (s.run_id, s.train_id, s.sale_event_id, s.run_date,
          s.sale_open_at, s.sale_close_at, s.status);

COMMIT;
