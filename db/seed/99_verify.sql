-- 제자리 기준 데이터 시드 검증
-- 모든 행의 pass 컬럼이 OK여야 합니다. 시드를 고치면 기대값도 함께 고칩니다.

SET LINESIZE 160
SET PAGESIZE 100
COLUMN item   FORMAT A34
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
  SELECT '7. 좌석 1080석 (540 × 2)',           CASE WHEN COUNT(*) = 1080 THEN 1 ELSE 0 END,     '실제 ' || COUNT(*) FROM seat
  UNION ALL
  SELECT '8. 열차당 특실 36 · 일반 504',       CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,
         '규격에 어긋난 (열차,등급) ' || COUNT(*) || '건'
    FROM (SELECT train_id, seat_grade, COUNT(*) c FROM seat GROUP BY train_id, seat_grade
           HAVING NOT ((seat_grade = 'FIRST' AND COUNT(*) = 36) OR (seat_grade = 'GENERAL' AND COUNT(*) = 504)))
  UNION ALL
  SELECT '9. 좌석번호 중복 없음',              CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,      '중복 ' || COUNT(*) || '건'
    FROM (SELECT train_id, car_no, seat_no FROM seat GROUP BY train_id, car_no, seat_no HAVING COUNT(*) > 1)
  UNION ALL
  SELECT '10. 창측 비율 일반 1/2 · 특실 2/3',  CASE WHEN COUNT(*) = 0   THEN 1 ELSE 0 END,
         '어긋난 (열차,등급) ' || COUNT(*) || '건'
    FROM (SELECT train_id, seat_grade, COUNT(*) c, SUM(CASE WHEN is_window = 'Y' THEN 1 ELSE 0 END) w
            FROM seat GROUP BY train_id, seat_grade
           HAVING NOT ((seat_grade = 'GENERAL' AND SUM(CASE WHEN is_window = 'Y' THEN 1 ELSE 0 END) * 2 = COUNT(*))
                    OR (seat_grade = 'FIRST'   AND SUM(CASE WHEN is_window = 'Y' THEN 1 ELSE 0 END) * 3 = COUNT(*) * 2)))
  UNION ALL
  SELECT '11. 운행 9건 (명절 8 + 개발 1)',     CASE WHEN COUNT(*) = 9   THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM train_run
  UNION ALL
  SELECT '12. ON_SALE 운행 1건 이상',          CASE WHEN COUNT(*) >= 1  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*)
    FROM train_run WHERE status = 'ON_SALE'
  UNION ALL
  SELECT '13. 정차 45건 (운행 9 × 역 5)',      CASE WHEN COUNT(*) = 45  THEN 1 ELSE 0 END,      '실제 ' || COUNT(*) FROM train_stop
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
  SELECT '18. 매수 제한: 행사 3 + 상시 2',     CASE WHEN COUNT(*) = 5   THEN 1 ELSE 0 END,      '실제 ' || COUNT(*)
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
) ORDER BY TO_NUMBER(REGEXP_SUBSTR(item, '^[0-9]+'));

-- 참고: 서울~부산 운임과 하행 101 정차 시각
SELECT seat_grade, amount FROM fare
 WHERE from_station_id = '0001' AND to_station_id = '0020' ORDER BY seat_grade;

SELECT s.stop_order, st.station_name,
       TO_CHAR(s.arrival_time, 'HH24:MI')   AS 도착,
       TO_CHAR(s.departure_time, 'HH24:MI') AS 출발
  FROM train_stop s JOIN station st ON st.station_id = s.station_id
 WHERE s.run_id = 9001 ORDER BY s.stop_order;
