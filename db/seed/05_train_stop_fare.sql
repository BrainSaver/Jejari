-- 제자리 기준 데이터 시드 5/5 : 정차 시각 · 운임
-- 선행: 03_sale_event_run.sql, 04_regular_run.sql
-- 다시 실행해도 결과가 같습니다 (MERGE). 운행을 추가한 뒤 이 파일만 다시 돌리면 됩니다.
--
-- 값의 출처는 국토교통부 (TAGO) 열차정보 API입니다 (db/seed/data/tago-train-info.json).
--   받기: node tools/fetch-train-api.js
--   반영: node tools/load-public-data.js --write
-- 아래 @generated 블록은 그 스크립트가 만듭니다. 손으로 고치지 마세요.
--
-- 예전에는 정차 시각과 운임을 우리 계산식으로 만들었습니다
-- (거리÷2.7km/분 + 정차 2분, 2,800원 + 125원/km). 실제 값과 가깝긴 했지만
-- 공공데이터가 아니었습니다. API가 실제 시각표와 운임을 주므로 그 두 칸을 실제 값으로 바꿨습니다.

-- 정차 시각
--   stop_order는 "진행 방향 기준 1부터"입니다. 동시성 §2.4의 구간 수가
--   (하차역 stop_order - 승차역 stop_order)이므로 상행은 순번이 뒤집힌 채로 들어 있습니다.
--   시각표는 시·분만 쓰고 날짜는 각 운행의 run_date에서 붙입니다. 운행일이 적재일 기준
--   상대값이라, 조회한 날짜(API)를 그대로 쓰면 우리 운행과 맞지 않기 때문입니다.
--   기점은 도착이 없고(arr_hm NULL), 종점은 출발이 없습니다(dep_hm NULL).
--   대상은 **시드 열차 1·2의 운행만**입니다 (02·03·04와 같은 조건). 전에는 `tr.route_id = 1`이라
--   경부선 전체였는데, 실제 시간표 적재(tools/load-tago-schedule.js)가 넣는 열차도 경부선이어서
--   05를 다시 돌리면 실제 편의 정차 시각을 이 시드 시각표로 덮어쓰고 안 서는 역까지 넣었습니다
--   (2026-11-18 공용 서버, 조영준 님 발견. docs/조영준/troubleshooting/20261118-시드05가-실제시간표-덮어씀.md).
--   실제 시간표 운행은 그 도구가, 관리자 화면 운행은 RunAdminService가 자기 정차를 넣으므로
--   05가 손댈 것이 없습니다. 노선이 아니라 열차로 거는 이유는 운행 번호가 v27부터 시퀀스라
--   run_id 범위로는 구분할 수 없기 때문입니다.
MERGE INTO train_stop t
USING (
  WITH sched AS (
-- @generated:schedule (tools/load-public-data.js — 손으로 고치지 마세요)
  SELECT 'DOWN' AS direction, '3900023'   AS station_id, 1 AS stop_order, CAST(NULL AS VARCHAR2(4))     AS arr_hm, '0928'                        AS dep_hm FROM dual UNION ALL
  SELECT 'DOWN', '3900883'  , 2, '0945'                       , '0947'                        FROM dual UNION ALL
  SELECT 'DOWN', '3900073'  , 3, '1033'                       , '1035'                        FROM dual UNION ALL
  SELECT 'DOWN', '3900096'  , 4, '1117'                       , '1119'                        FROM dual UNION ALL
  SELECT 'DOWN', '3900114'  , 5, '1235'                       , CAST(NULL AS VARCHAR2(4))     FROM dual UNION ALL
  SELECT 'UP', '3900114'  , 1, CAST(NULL AS VARCHAR2(4))    , '0937'                        FROM dual UNION ALL
  SELECT 'UP', '3900096'  , 2, '1023'                       , '1025'                        FROM dual UNION ALL
  SELECT 'UP', '3900073'  , 3, '1111'                       , '1113'                        FROM dual UNION ALL
  SELECT 'UP', '3900883'  , 4, '1155'                       , '1157'                        FROM dual UNION ALL
  SELECT 'UP', '3900023'  , 5, '1215'                       , CAST(NULL AS VARCHAR2(4))     FROM dual
-- @end:schedule
  )
  SELECT run.run_id, sc.station_id, sc.stop_order,
         CASE WHEN sc.arr_hm IS NULL THEN NULL
              ELSE CAST(run.run_date AS TIMESTAMP)
                   + NUMTODSINTERVAL(TO_NUMBER(SUBSTR(sc.arr_hm, 1, 2)) * 60
                                     + TO_NUMBER(SUBSTR(sc.arr_hm, 3, 2)), 'MINUTE')
         END AS arrival_time,
         CASE WHEN sc.dep_hm IS NULL THEN NULL
              ELSE CAST(run.run_date AS TIMESTAMP)
                   + NUMTODSINTERVAL(TO_NUMBER(SUBSTR(sc.dep_hm, 1, 2)) * 60
                                     + TO_NUMBER(SUBSTR(sc.dep_hm, 3, 2)), 'MINUTE')
         END AS departure_time
    FROM train_run run
    JOIN train tr ON tr.train_id = run.train_id
    JOIN sched sc ON sc.direction = tr.direction
   WHERE tr.train_id IN (1, 2)
) s ON (t.run_id = s.run_id AND t.station_id = s.station_id)
WHEN MATCHED THEN
  UPDATE SET t.stop_order = s.stop_order, t.arrival_time = s.arrival_time,
             t.departure_time = s.departure_time, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (run_id, station_id, stop_order, arrival_time, departure_time)
  VALUES (s.run_id, s.station_id, s.stop_order, s.arrival_time, s.departure_time);

COMMIT;

-- 운임
--   일반실(GENERAL)은 API의 adultcharge(어른 운임) 그대로입니다.
--   특실(FIRST)은 API가 주지 않아 일반실 × 1.4로 정합니다 (100원 단위 반올림).
--   같은 구간에 여러 편이 있으면 가장 많이 나온 값을 씁니다 (특별열차 운임에 끌려가지 않게).
--   fare_id = 등급 기준값(일반 0 / 특실 1000) + 출발역 순번 × 10 + 도착역 순번.
--   노선 1의 역 5개 중 서로 다른 순서쌍 20개 × 등급 2 = 40행.
MERGE INTO fare t
USING (
  SELECT fare_id, train_kind, seat_grade, from_station_id, to_station_id, amount,
         DATE '2026-01-01' AS valid_from, CAST(NULL AS DATE) AS valid_to
    FROM (
-- @generated:fare (tools/load-public-data.js — 손으로 고치지 마세요)
  SELECT 12 AS fare_id, 'KTX' AS train_kind, 'GENERAL' AS seat_grade, '3900023'   AS from_station_id, '3900883'   AS to_station_id, 7500   AS amount FROM dual UNION ALL
  SELECT 13, 'KTX', 'GENERAL', '3900023'  , '3900073'  , 21600  FROM dual UNION ALL
  SELECT 14, 'KTX', 'GENERAL', '3900023'  , '3900096'  , 39000  FROM dual UNION ALL
  SELECT 15, 'KTX', 'GENERAL', '3900023'  , '3900114'  , 54400  FROM dual UNION ALL
  SELECT 21, 'KTX', 'GENERAL', '3900883'  , '3900023'  , 7500   FROM dual UNION ALL
  SELECT 23, 'KTX', 'GENERAL', '3900883'  , '3900073'  , 19400  FROM dual UNION ALL
  SELECT 24, 'KTX', 'GENERAL', '3900883'  , '3900096'  , 36900  FROM dual UNION ALL
  SELECT 25, 'KTX', 'GENERAL', '3900883'  , '3900114'  , 52200  FROM dual UNION ALL
  SELECT 31, 'KTX', 'GENERAL', '3900073'  , '3900023'  , 21600  FROM dual UNION ALL
  SELECT 32, 'KTX', 'GENERAL', '3900073'  , '3900883'  , 19400  FROM dual UNION ALL
  SELECT 34, 'KTX', 'GENERAL', '3900073'  , '3900096'  , 17600  FROM dual UNION ALL
  SELECT 35, 'KTX', 'GENERAL', '3900073'  , '3900114'  , 33100  FROM dual UNION ALL
  SELECT 41, 'KTX', 'GENERAL', '3900096'  , '3900023'  , 39000  FROM dual UNION ALL
  SELECT 42, 'KTX', 'GENERAL', '3900096'  , '3900883'  , 36900  FROM dual UNION ALL
  SELECT 43, 'KTX', 'GENERAL', '3900096'  , '3900073'  , 17600  FROM dual UNION ALL
  SELECT 45, 'KTX', 'GENERAL', '3900096'  , '3900114'  , 15600  FROM dual UNION ALL
  SELECT 51, 'KTX', 'GENERAL', '3900114'  , '3900023'  , 54400  FROM dual UNION ALL
  SELECT 52, 'KTX', 'GENERAL', '3900114'  , '3900883'  , 52200  FROM dual UNION ALL
  SELECT 53, 'KTX', 'GENERAL', '3900114'  , '3900073'  , 33100  FROM dual UNION ALL
  SELECT 54, 'KTX', 'GENERAL', '3900114'  , '3900096'  , 15600  FROM dual UNION ALL
  SELECT 1012, 'KTX', 'FIRST'  , '3900023'  , '3900883'  , 10500  FROM dual UNION ALL
  SELECT 1013, 'KTX', 'FIRST'  , '3900023'  , '3900073'  , 30200  FROM dual UNION ALL
  SELECT 1014, 'KTX', 'FIRST'  , '3900023'  , '3900096'  , 54600  FROM dual UNION ALL
  SELECT 1015, 'KTX', 'FIRST'  , '3900023'  , '3900114'  , 76200  FROM dual UNION ALL
  SELECT 1021, 'KTX', 'FIRST'  , '3900883'  , '3900023'  , 10500  FROM dual UNION ALL
  SELECT 1023, 'KTX', 'FIRST'  , '3900883'  , '3900073'  , 27200  FROM dual UNION ALL
  SELECT 1024, 'KTX', 'FIRST'  , '3900883'  , '3900096'  , 51700  FROM dual UNION ALL
  SELECT 1025, 'KTX', 'FIRST'  , '3900883'  , '3900114'  , 73100  FROM dual UNION ALL
  SELECT 1031, 'KTX', 'FIRST'  , '3900073'  , '3900023'  , 30200  FROM dual UNION ALL
  SELECT 1032, 'KTX', 'FIRST'  , '3900073'  , '3900883'  , 27200  FROM dual UNION ALL
  SELECT 1034, 'KTX', 'FIRST'  , '3900073'  , '3900096'  , 24600  FROM dual UNION ALL
  SELECT 1035, 'KTX', 'FIRST'  , '3900073'  , '3900114'  , 46300  FROM dual UNION ALL
  SELECT 1041, 'KTX', 'FIRST'  , '3900096'  , '3900023'  , 54600  FROM dual UNION ALL
  SELECT 1042, 'KTX', 'FIRST'  , '3900096'  , '3900883'  , 51700  FROM dual UNION ALL
  SELECT 1043, 'KTX', 'FIRST'  , '3900096'  , '3900073'  , 24600  FROM dual UNION ALL
  SELECT 1045, 'KTX', 'FIRST'  , '3900096'  , '3900114'  , 21800  FROM dual UNION ALL
  SELECT 1051, 'KTX', 'FIRST'  , '3900114'  , '3900023'  , 76200  FROM dual UNION ALL
  SELECT 1052, 'KTX', 'FIRST'  , '3900114'  , '3900883'  , 73100  FROM dual UNION ALL
  SELECT 1053, 'KTX', 'FIRST'  , '3900114'  , '3900073'  , 46300  FROM dual UNION ALL
  SELECT 1054, 'KTX', 'FIRST'  , '3900114'  , '3900096'  , 21800  FROM dual
-- @end:fare
    )
) s ON (    t.train_kind      = s.train_kind
        AND t.seat_grade      = s.seat_grade
        AND t.from_station_id = s.from_station_id
        AND t.to_station_id   = s.to_station_id
        AND t.valid_from      = s.valid_from)
WHEN MATCHED THEN
  UPDATE SET t.amount = s.amount, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (fare_id, train_kind, seat_grade, from_station_id, to_station_id, amount, valid_from, valid_to)
  VALUES (s.fare_id, s.train_kind, s.seat_grade, s.from_station_id, s.to_station_id,
          s.amount, s.valid_from, s.valid_to);

COMMIT;
