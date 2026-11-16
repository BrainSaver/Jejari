-- 제자리 기준 데이터 시드 2/5 : 차량 유형 · 열차 · 편성 · 물리 좌석
-- 선행: 01_station_route.sql
-- 다시 실행해도 결과가 같습니다 (MERGE).
--
-- seat은 ADM-01 설계대로 "train_car × car_type.seat_count"로 자동 생성합니다.
-- seat_id는 시퀀스가 아니라 train_id*100000 + car_no*1000 + 좌석순번으로 계산합니다.
-- 동시성 테스트(T1 등)가 좌석 ID를 코드에 고정해 쓰기 때문에 매번 같은 값이어야 합니다.

MERGE INTO car_type t
USING (
  SELECT 'KTX-GEN' AS car_type_code, 'KTX 일반실' AS car_type_name, 56 AS seat_count,
         'GENERAL'  AS seat_grade,   'ABCD'        AS seat_columns FROM dual UNION ALL
  SELECT 'KTX-FST',                  'KTX 특실',                    36,
         'FIRST',                     'ABC'                         FROM dual
) s ON (t.car_type_code = s.car_type_code)
WHEN MATCHED THEN
  UPDATE SET t.car_type_name = s.car_type_name, t.seat_count = s.seat_count,
             t.seat_grade = s.seat_grade, t.seat_columns = s.seat_columns,
             t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (car_type_code, car_type_name, seat_count, seat_grade, seat_columns)
  VALUES (s.car_type_code, s.car_type_name, s.seat_count, s.seat_grade, s.seat_columns);

-- 101 하행(서울→부산), 102 상행(부산→서울)
MERGE INTO train t
USING (
  SELECT 1 AS train_id, '101' AS train_no, 'KTX' AS train_kind, 1 AS route_id, 'DOWN' AS direction FROM dual UNION ALL
  SELECT 2,             '102',             'KTX',                1,             'UP'               FROM dual
) s ON (t.train_id = s.train_id)
WHEN MATCHED THEN
  UPDATE SET t.train_no = s.train_no, t.train_kind = s.train_kind,
             t.route_id = s.route_id, t.direction = s.direction, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (train_id, train_no, train_kind, route_id, direction)
  VALUES (s.train_id, s.train_no, s.train_kind, s.route_id, s.direction);

-- 10량 편성: 1호차 특실(36석), 2~10호차 일반실(56석) = 열차당 540석
MERGE INTO train_car t
USING (
  SELECT tr.train_id,
         c.car_no,
         CASE WHEN c.car_no = 1 THEN 'KTX-FST' ELSE 'KTX-GEN' END AS car_type_code
    FROM train tr
   CROSS JOIN (SELECT LEVEL AS car_no FROM dual CONNECT BY LEVEL <= 10) c
   WHERE tr.train_id IN (1, 2)
) s ON (t.train_id = s.train_id AND t.car_no = s.car_no)
WHEN MATCHED THEN
  UPDATE SET t.car_type_code = s.car_type_code, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (train_id, car_no, car_type_code) VALUES (s.train_id, s.car_no, s.car_type_code);

-- 물리 좌석: 좌석 번호는 "열번호 + 열문자" (예: 14D)
--   열번호 = CEIL(순번 / 열문자 수), 창측 = 열문자가 양 끝(A 또는 D/C)
MERGE INTO seat t
USING (
  SELECT tc.train_id * 100000 + tc.car_no * 1000 + n.i                        AS seat_id,
         tc.train_id,
         tc.car_no,
         TO_CHAR(CEIL(n.i / LENGTH(ct.seat_columns)))
           || SUBSTR(ct.seat_columns, MOD(n.i - 1, LENGTH(ct.seat_columns)) + 1, 1) AS seat_no,
         ct.seat_grade,
         CASE WHEN MOD(n.i - 1, LENGTH(ct.seat_columns)) + 1 IN (1, LENGTH(ct.seat_columns))
              THEN 'Y' ELSE 'N' END                                            AS is_window
    FROM train_car tc
    JOIN car_type  ct ON ct.car_type_code = tc.car_type_code
    JOIN (SELECT LEVEL AS i FROM dual CONNECT BY LEVEL <= 200) n
      ON n.i <= ct.seat_count
) s ON (t.seat_id = s.seat_id)
WHEN MATCHED THEN
  UPDATE SET t.train_id = s.train_id, t.car_no = s.car_no, t.seat_no = s.seat_no,
             t.seat_grade = s.seat_grade, t.is_window = s.is_window, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (seat_id, train_id, car_no, seat_no, seat_grade, is_window)
  VALUES (s.seat_id, s.train_id, s.car_no, s.seat_no, s.seat_grade, s.is_window);

COMMIT;
