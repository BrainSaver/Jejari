-- 제자리 기준 데이터 시드 1/5 : 역 · 노선 · 노선별 역 순서
-- 선행: docs/erd/jejari-erd.ddl.sql 실행 완료
-- 다시 실행해도 결과가 같습니다 (MERGE).
--
-- 역 코드(station_id)는 ERD 설계대로 공공데이터 코드를 그대로 PK로 씁니다.
-- 아래 값은 코레일 NAT 역 코드를 전제로 한 시연용 시드이며,
-- 공공데이터포털 파일을 실제로 내려받은 뒤 db/seed/README.md "확인 필요" 항목대로 대조합니다.
-- 누적 거리는 경부선 영업거리 기준입니다. 운임은 03에서 이 거리로 산출합니다.
-- 위도·경도는 선택 컬럼이라 비워 둡니다 (적재 스크립트에서 채움).

MERGE INTO station t
USING (
  SELECT '0001' AS station_id, '서울'   AS station_name FROM dual UNION ALL
  SELECT '0008',               '광명'              FROM dual UNION ALL
  SELECT '0010',               '대전'              FROM dual UNION ALL
  SELECT '0015',               '동대구'            FROM dual UNION ALL
  SELECT '0020',               '부산'              FROM dual
) s ON (t.station_id = s.station_id)
WHEN MATCHED THEN
  UPDATE SET t.station_name = s.station_name, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (station_id, station_name) VALUES (s.station_id, s.station_name);

MERGE INTO route t
USING (
  SELECT 1 AS route_id, '경부선' AS route_name, '0001' AS origin_station_id, '0020' AS dest_station_id FROM dual
) s ON (t.route_id = s.route_id)
WHEN MATCHED THEN
  UPDATE SET t.route_name = s.route_name, t.origin_station_id = s.origin_station_id,
             t.dest_station_id = s.dest_station_id, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (route_id, route_name, origin_station_id, dest_station_id)
  VALUES (s.route_id, s.route_name, s.origin_station_id, s.dest_station_id);

MERGE INTO route_station t
USING (
  SELECT 1 AS route_id, '0001' AS station_id, 1 AS station_order,   0.0 AS distance_from_origin_km FROM dual UNION ALL
  SELECT 1,             '0008',               2,                   22.0                           FROM dual UNION ALL
  SELECT 1,             '0010',               3,                  166.3                           FROM dual UNION ALL
  SELECT 1,             '0015',               4,                  323.9                           FROM dual UNION ALL
  SELECT 1,             '0020',               5,                  441.7                           FROM dual
) s ON (t.route_id = s.route_id AND t.station_id = s.station_id)
WHEN MATCHED THEN
  UPDATE SET t.station_order = s.station_order,
             t.distance_from_origin_km = s.distance_from_origin_km,
             t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (route_id, station_id, station_order, distance_from_origin_km)
  VALUES (s.route_id, s.station_id, s.station_order, s.distance_from_origin_km);

COMMIT;
