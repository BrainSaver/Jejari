-- 제자리 기준 데이터 시드 4/5 : 평시 운행 (코레일 "출발 2개월 전 07:00" 규칙)
-- 선행: 03_sale_event_run.sql · 후행: 05_train_stop_fare.sql
-- 다시 실행해도 결과가 같습니다 (MERGE). status는 오픈 시각과 지금 시각을 비교해 다시 계산합니다.
--
-- 코레일은 일반 승차권을 출발일 2개월 전 오전 7시부터 판매합니다. 그래서 오픈 시각을
-- 손으로 넣지 않고 규칙으로 계산합니다: sale_open_at = ADD_MONTHS(run_date, -2) + 07:00.
-- status도 그 값과 지금 시각을 비교해 정합니다 — 하드코딩하면 규칙과 어긋날 수 있습니다.
-- (명절 운행은 특별예매라 행사 오픈 시각을 그대로 쓰며, 03에서 넣습니다.)
--
-- 03의 명절 운행은 예매 오픈이 내일이라 전부 SCHEDULED입니다. 좌석 선점(RSV-02)이나
-- 동시성 테스트를 지금 돌리려면 이미 ON_SALE인 운행이 필요해서 아래 9001·9002를 둡니다.
--
-- sale_event_id는 NULL입니다. 동시성 §10.6대로 행사 정책이 없으면 상시 정책(03의 8·9·10)이
-- 적용되므로 1회 9매·열차당 10매·1일 20매가 걸리고, PER_HOLIDAY는 적용되지 않습니다.
-- 명절 한도(1회 4·열차당 4·명절 전체 8)까지 테스트하려면 03의 명절 운행을 쓰세요.

MERGE INTO train_run t
USING (
  SELECT r.run_id, r.train_id, CAST(NULL AS NUMBER) AS sale_event_id, r.run_date,
         r.sale_open_at,
         CAST(r.run_date + 1 AS TIMESTAMP) AS sale_close_at,
         CASE WHEN r.sale_open_at <= SYSTIMESTAMP THEN 'ON_SALE' ELSE 'SCHEDULED' END AS status
    FROM (
      SELECT run_id, train_id, run_date,
             CAST(ADD_MONTHS(run_date, -2) AS TIMESTAMP) + INTERVAL '7' HOUR AS sale_open_at
        FROM (
          -- 지금 판매 중: 출발 3일 뒤 → 오픈은 약 두 달 전이라 이미 지났음
          SELECT 9001 AS run_id, 1 AS train_id, TRUNC(SYSDATE) + 3 AS run_date FROM dual UNION ALL
          SELECT 9002,           2,             TRUNC(SYSDATE) + 3            FROM dual UNION ALL
          -- 오픈 예정: 출발이 두 달 하루 뒤 → 오픈이 내일 07:00 (오픈 스케줄러 테스트용)
          SELECT 9003,           1,             ADD_MONTHS(TRUNC(SYSDATE), 2) + 1 FROM dual
        )
    ) r
) s ON (t.run_id = s.run_id)
WHEN MATCHED THEN
  UPDATE SET t.status = s.status, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
  VALUES (s.run_id, s.train_id, s.sale_event_id, s.run_date,
          s.sale_open_at, s.sale_close_at, s.status);

COMMIT;
