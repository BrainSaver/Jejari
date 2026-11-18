-- 제자리 기준 데이터 시드 검증
-- 모든 행의 pass 컬럼이 OK여야 합니다. 시드를 고치면 기대값도 함께 고칩니다.

SET LINESIZE 160
SET PAGESIZE 100
COLUMN item   FORMAT A36
COLUMN pass   FORMAT A6
COLUMN detail FORMAT A64

SELECT item, CASE WHEN ok = 1 THEN 'OK' ELSE 'FAIL' END AS pass, detail
  FROM (
  SELECT '1. 역 5개'                     AS item, CASE WHEN COUNT(*) = 5   THEN 1 ELSE 0 END AS ok, '실제 ' || COUNT(*) AS detail FROM station
  UNION ALL
  SELECT '2. 노선 1개',                        CASE WHEN COUNT(*) = 1   THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM route
  UNION ALL
  SELECT '3. 노선별 역 5개',                   CASE WHEN COUNT(*) = 5   THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM route_station
  UNION ALL
  SELECT '4. 차량 유형 2개',                   CASE WHEN COUNT(*) = 2   THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM car_type
  UNION ALL
  SELECT '5. 열차 2편',                        CASE WHEN COUNT(*) = 2   THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM train
  UNION ALL
  SELECT '6. 편성 20호차',                     CASE WHEN COUNT(*) = 20  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM train_car
  UNION ALL
  SELECT '7. 좌석 1050석 (525 × 2)',           CASE WHEN COUNT(*) = 1050 THEN 1 ELSE 0 END,     '실제 ' || COUNT(*) FROM seat
  UNION ALL
  SELECT '8. 호차 좌석수 = car_type.seat_count', CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END,
         '어긋난 호차 ' || COUNT(*) || '건'
    FROM (SELECT s.train_id, s.car_no, COUNT(*) AS c, MAX(ct.seat_count) AS want
            FROM seat s
            JOIN train_car tc ON tc.train_id = s.train_id AND tc.car_no = s.car_no
            JOIN car_type  ct ON ct.car_type_code = tc.car_type_code
           GROUP BY s.train_id, s.car_no
          HAVING COUNT(*) <> MAX(ct.seat_count))
  UNION ALL
  SELECT '9. 좌석번호 중복 없음',              CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,      '중복 ' || COUNT(*) || '건'
    FROM (SELECT train_id, car_no, seat_no FROM seat GROUP BY train_id, car_no, seat_no HAVING COUNT(*) > 1)
  UNION ALL
  SELECT '10. 창측 수 = CEIL(n/k) + FLOOR(n/k)', CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END,
         '어긋난 호차 ' || COUNT(*) || '건'
    FROM (SELECT s.train_id, s.car_no
            FROM seat s
            JOIN train_car tc ON tc.train_id = s.train_id AND tc.car_no = s.car_no
            JOIN car_type  ct ON ct.car_type_code = tc.car_type_code
           GROUP BY s.train_id, s.car_no
          HAVING SUM(CASE WHEN s.is_window = 'Y' THEN 1 ELSE 0 END)
                 <> CEIL(MAX(ct.seat_count) / LENGTH(MAX(ct.seat_columns)))
                  + FLOOR(MAX(ct.seat_count) / LENGTH(MAX(ct.seat_columns))))
  UNION ALL
  SELECT '11. 운행 11건 (명절 8 + 평시 3)',    CASE WHEN COUNT(*) = 11  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM train_run
  UNION ALL
  -- 몇 건인지는 시간이 지나면 늘어난다 (오픈 시각이 지난 운행을 판매 오픈 스케줄러가 연다).
  -- 그래서 "최소 1건"만 본다. 상태가 시각과 맞는지는 23번이 확인한다
  SELECT '12. 지금 판매 중인 운행 있음',        CASE WHEN COUNT(*) >= 1  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*)
    FROM train_run WHERE status = 'ON_SALE'
  UNION ALL
  SELECT '13. 정차 55건 (운행 11 × 역 5)',     CASE WHEN COUNT(*) = 55  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM train_stop
  UNION ALL
  SELECT '14. 운행마다 stop_order 1~5',        CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,      '어긋난 운행 ' || COUNT(*) || '건'
    FROM (SELECT run_id FROM train_stop GROUP BY run_id
           HAVING MIN(stop_order) <> 1 OR MAX(stop_order) <> 5 OR COUNT(DISTINCT stop_order) <> 5)
  UNION ALL
  SELECT '15. 정차 시각이 순번대로 증가',      CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,      '역전 ' || COUNT(*) || '건'
    FROM (SELECT run_id FROM (
            SELECT run_id,
                   NVL(arrival_time, departure_time) AS at_this,
                   LAG(NVL(departure_time, arrival_time))
                     OVER (PARTITION BY run_id ORDER BY stop_order) AS left_prev
              FROM train_stop)
           WHERE left_prev IS NOT NULL AND at_this <= left_prev)
  UNION ALL
  SELECT '16. 운임 40행 (순서쌍 20 × 등급 2)', CASE WHEN COUNT(*) = 40  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM fare
  UNION ALL
  SELECT '17. 특실 > 일반 (같은 구간)',        CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,      '어긋난 구간 ' || COUNT(*) || '건'
    FROM fare f JOIN fare g
      ON g.from_station_id = f.from_station_id AND g.to_station_id = f.to_station_id
     AND f.seat_grade = 'FIRST' AND g.seat_grade = 'GENERAL' AND f.amount <= g.amount
  UNION ALL
  SELECT '18. 매수 제한: 행사 4 + 상시 3',     CASE WHEN COUNT(*) = 7   THEN 1 ELSE 0 END,      '실제 ' || COUNT(*)
    FROM purchase_limit_policy WHERE policy_id >= 4
  UNION ALL
  SELECT '19. 지금 유효한 PER_RUN 정책 있음',  CASE WHEN COUNT(*) >= 1  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*)
    FROM purchase_limit_policy
   WHERE scope = 'PER_RUN'
     AND (valid_from IS NULL OR valid_from <= SYSTIMESTAMP)
     AND (valid_to   IS NULL OR valid_to   >= SYSTIMESTAMP)
  UNION ALL
  SELECT '20. 세션 시간대 +09:00 (T34)',       CASE WHEN SESSIONTIMEZONE IN ('+09:00', 'Asia/Seoul') THEN 1 ELSE 0 END,
         'SESSIONTIMEZONE = ' || SESSIONTIMEZONE FROM dual
  UNION ALL
  SELECT '21. 지금 유효한 PER_DAY 정책 있음',   CASE WHEN COUNT(*) >= 1  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*)
    FROM purchase_limit_policy
   WHERE scope = 'PER_DAY'
     AND (valid_from IS NULL OR valid_from <= SYSTIMESTAMP)
     AND (valid_to   IS NULL OR valid_to   >= SYSTIMESTAMP)
  UNION ALL
  SELECT '22. 평시 오픈 = 출발 2개월 전 7시', CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,
         '규칙에 어긋난 운행 ' || COUNT(*) || '건'
    FROM train_run
   WHERE sale_event_id IS NULL
     AND sale_open_at <> CAST(ADD_MONTHS(run_date, -2) AS TIMESTAMP) + INTERVAL '7' HOUR
  UNION ALL
  SELECT '23. status가 오픈 시각과 일관',       CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,
         '어긋난 운행 ' || COUNT(*) || '건'
    FROM train_run
   WHERE (status = 'ON_SALE'   AND sale_open_at >  SYSTIMESTAMP)
      OR (status = 'SCHEDULED' AND sale_open_at <= SYSTIMESTAMP)
  UNION ALL
  -- 시연 운행이 묵었는지 본다. 평시 운행 9001~9003은 "시드를 돌린 날" 기준 상대값이라
  -- 오래된 DB에서는 이미 출발해 있다. 출발하면 DEPARTED가 되어 예매가 막히고
  -- 좌석 선점 테스트가 통째로 깨진다 (원인이 시각이라 메시지만 봐서는 알 수 없다).
  -- 걸리면 DB를 지우지 말고 04 → 05 시드만 다시 돌린다 (날짜가 앞으로 밀린다).
  SELECT '24. 평시 운행이 7일 넘게 남음',       CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,
         '곧 출발/출발함 ' || COUNT(*) || '건 → 04·05 다시 돌리세요'
    FROM train_run
   WHERE sale_event_id IS NULL AND run_date < TRUNC(SYSDATE) + 7
) ORDER BY TO_NUMBER(REGEXP_SUBSTR(item, '^[0-9]+'));

-- 참고: 매수 제한 정책, 서울~부산 운임, 하행 정차 시각 (TAGO 실제 시각표)
SELECT NVL(TO_CHAR(sale_event_id), '상시') AS 행사, scope, max_seats AS 한도
  FROM purchase_limit_policy WHERE policy_id >= 4 ORDER BY sale_event_id NULLS LAST, scope;

SELECT run_id, train_id, TO_CHAR(run_date, 'YYYY-MM-DD') AS 운행일,
       TO_CHAR(sale_open_at, 'YYYY-MM-DD HH24:MI') AS 예매오픈, status
  FROM train_run ORDER BY run_id;

SELECT a.station_name AS 출발, b.station_name AS 도착, f.seat_grade, f.amount
  FROM fare f
  JOIN station a ON a.station_id = f.from_station_id
  JOIN station b ON b.station_id = f.to_station_id
 WHERE a.station_name = '서울' AND b.station_name = '부산' ORDER BY f.seat_grade;

SELECT s.stop_order, st.station_name,
       TO_CHAR(s.arrival_time, 'HH24:MI')   AS 도착,
       TO_CHAR(s.departure_time, 'HH24:MI') AS 출발
  FROM train_stop s JOIN station st ON st.station_id = s.station_id
 WHERE s.run_id = 9001 ORDER BY s.stop_order;
