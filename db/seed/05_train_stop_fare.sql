-- 제자리 기준 데이터 시드 5/5 : 정차 시각 · 운임
-- 선행: 03_sale_event_run.sql, 04_dev_run.sql
-- 다시 실행해도 결과가 같습니다 (MERGE). 운행을 추가한 뒤 이 파일만 다시 돌리면 됩니다.

-- 정차 시각
--   stop_order는 "진행 방향 기준 1부터"입니다. 동시성 §2.4의 구간 수가
--   (하차역 stop_order - 승차역 stop_order)이므로 상행은 순번을 뒤집습니다.
--   소요 시간 = 진행 방향 누적거리 / 2.7 (km/분, 약 162km/h) + 중간 정차 2분씩.
--   서울~부산 441.7km → 약 2시간 52분 (주행 164분 + 중간 정차 8분).
--   기준 출발: 하행 09:00, 상행 15:00.
MERGE INTO train_stop t
USING (
  WITH rs AS (
    SELECT station_id, station_order, distance_from_origin_km,
           MAX(station_order)           OVER () AS max_order,
           MAX(distance_from_origin_km) OVER () AS max_km
      FROM route_station
     WHERE route_id = 1
  ),
  base AS (
    SELECT run.run_id,
           rs.station_id,
           CASE WHEN tr.direction = 'DOWN' THEN rs.station_order
                ELSE rs.max_order + 1 - rs.station_order END AS stop_order,
           CASE WHEN tr.direction = 'DOWN' THEN rs.distance_from_origin_km
                ELSE rs.max_km - rs.distance_from_origin_km END AS cum_km,
           rs.max_order,
           CAST(run.run_date AS TIMESTAMP)
             + NUMTODSINTERVAL(CASE WHEN tr.direction = 'DOWN' THEN 9 ELSE 15 END, 'HOUR') AS depart_base
      FROM train_run run
      JOIN train tr ON tr.train_id = run.train_id
     CROSS JOIN rs
     WHERE tr.route_id = 1
  )
  SELECT run_id, station_id, stop_order,
         CASE WHEN stop_order = 1 THEN NULL
              ELSE depart_base
                   + NUMTODSINTERVAL(ROUND(cum_km / 2.7) + 2 * (stop_order - 1), 'MINUTE')
         END AS arrival_time,
         CASE WHEN stop_order = max_order THEN NULL
              WHEN stop_order = 1        THEN depart_base
              ELSE depart_base
                   + NUMTODSINTERVAL(ROUND(cum_km / 2.7) + 2 * (stop_order - 1) + 2, 'MINUTE')
         END AS departure_time
    FROM base
) s ON (t.run_id = s.run_id AND t.station_id = s.station_id)
WHEN MATCHED THEN
  UPDATE SET t.stop_order = s.stop_order, t.arrival_time = s.arrival_time,
             t.departure_time = s.departure_time, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (run_id, station_id, stop_order, arrival_time, departure_time)
  VALUES (s.run_id, s.station_id, s.stop_order, s.arrival_time, s.departure_time);

COMMIT;

-- 운임 (거리 × 단가, 100원 단위 반올림)
--   일반실 = 2,800원 + 125원/km, 특실 = 일반실 × 1.4
--   서울~부산 441.7km → 일반 58,000원 / 특실 81,200원
--   노선 1의 역 5개 중 서로 다른 순서쌍 20개 × 등급 2 = 40행
--   fare_id = 등급 기준값(일반 0 / 특실 1000) + 출발역 순번 × 10 + 도착역 순번.
--   ROW_NUMBER()를 쓰면 일부만 적재된 뒤 다시 실행할 때 PK가 어긋날 수 있어 데이터에서 계산합니다.
MERGE INTO fare t
USING (
  SELECT g.base_id + a.station_order * 10 + b.station_order AS fare_id,
         'KTX'                 AS train_kind,
         g.seat_grade,
         a.station_id          AS from_station_id,
         b.station_id          AS to_station_id,
         ROUND((2800 + 125 * ABS(a.distance_from_origin_km - b.distance_from_origin_km))
               * g.rate / 100) * 100 AS amount,
         DATE '2026-01-01'     AS valid_from,
         CAST(NULL AS DATE)    AS valid_to
    FROM route_station a
    JOIN route_station b
      ON b.route_id = a.route_id AND b.station_id <> a.station_id
   CROSS JOIN (SELECT 'GENERAL' AS seat_grade, 1.0 AS rate,    0 AS base_id FROM dual UNION ALL
               SELECT 'FIRST',                 1.4,         1000           FROM dual) g
   WHERE a.route_id = 1
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
