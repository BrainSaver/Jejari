-- 제자리 기준 데이터 시드 1/5 : 역 · 노선 · 노선별 역 순서
-- 선행: docs/erd/jejari-erd.ddl.sql 실행 완료
-- 다시 실행해도 결과가 같습니다 (MERGE).
--
-- 값의 출처는 전부 공공데이터포털 원본입니다 (db/seed/data/).
--   역 코드 : 한국철도공사_철도운영정보_역코드_20240901 (STN_CD, 7자리)
--   역 순서 : 한국철도공사_KTX 노선별 역정보_20251121 (노선명 '경부선'의 순번 순서)
--   누적거리: 한국철도공사_철도운행거리_경부고속선_20240130
--             원본은 역×역 매트릭스라 기준역명이 '서울'인 행을 읽어 옮겼습니다.
--
-- KTX 경부선은 19개 역에 서지만 기획서대로 5개 역만 씁니다 (서울·광명·대전·동대구·부산).
-- 공공데이터 순번은 19개 역 기준(서울 2 · 광명 4 · 대전 8 · 동대구 11 · 부산 19)이므로,
-- 우리 노선 안에서는 그 순서를 지킨 채 1~5로 다시 매깁니다.
-- 위도·경도는 위 데이터셋에 없어 비워 둡니다 (선택 컬럼).

MERGE INTO station t
USING (
  SELECT '3900023' AS station_id, '서울'   AS station_name FROM dual UNION ALL
  SELECT '3900883',               '광명'              FROM dual UNION ALL
  SELECT '3900073',               '대전'              FROM dual UNION ALL
  SELECT '3900096',               '동대구'            FROM dual UNION ALL
  SELECT '3900114',               '부산'              FROM dual
) s ON (t.station_id = s.station_id)
WHEN MATCHED THEN
  UPDATE SET t.station_name = s.station_name, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (station_id, station_name) VALUES (s.station_id, s.station_name);

MERGE INTO route t
USING (
  SELECT 1 AS route_id, '경부선' AS route_name,
         '3900023' AS origin_station_id, '3900114' AS dest_station_id FROM dual
) s ON (t.route_id = s.route_id)
WHEN MATCHED THEN
  UPDATE SET t.route_name = s.route_name, t.origin_station_id = s.origin_station_id,
             t.dest_station_id = s.dest_station_id, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (route_id, route_name, origin_station_id, dest_station_id)
  VALUES (s.route_id, s.route_name, s.origin_station_id, s.dest_station_id);

MERGE INTO route_station t
USING (
  -- 누적거리는 경부고속선 데이터셋의 '서울' 기준 행 값 그대로
  SELECT 1 AS route_id, '3900023' AS station_id, 1 AS station_order,   0.0 AS distance_from_origin_km FROM dual UNION ALL
  SELECT 1,             '3900883',               2,                   22.0                           FROM dual UNION ALL
  SELECT 1,             '3900073',               3,                  159.8                           FROM dual UNION ALL
  SELECT 1,             '3900096',               4,                  286.7                           FROM dual UNION ALL
  SELECT 1,             '3900114',               5,                  417.4                           FROM dual
) s ON (t.route_id = s.route_id AND t.station_id = s.station_id)
WHEN MATCHED THEN
  UPDATE SET t.station_order = s.station_order,
             t.distance_from_origin_km = s.distance_from_origin_km,
             t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (route_id, station_id, station_order, distance_from_origin_km)
  VALUES (s.route_id, s.station_id, s.station_order, s.distance_from_origin_km);

COMMIT;
