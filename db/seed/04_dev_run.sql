-- 제자리 기준 데이터 시드 4/5 : 개발·테스트용 운행 1건 (지금 바로 판매 중)
-- 선행: 03_sale_event_run.sql · 후행: 05_train_stop_fare.sql
--
-- 03의 명절 운행은 예매 오픈이 내일이라 status가 SCHEDULED입니다. 좌석 선점(RSV-02)이나
-- 동시성 테스트를 지금 돌리려면 이미 ON_SALE인 운행이 하나 필요합니다.
--
-- sale_event_id는 NULL입니다. 동시성 §10.6대로 행사 정책이 없으면 상시 정책(03의 7·8)이
-- 적용되므로 PER_RESERVATION 4매·PER_RUN 4매가 걸리고, PER_HOLIDAY는 적용되지 않습니다.
-- 명절 전체 한도까지 테스트하려면 03의 명절 운행을 ON_SALE로 바꿔 쓰세요.

MERGE INTO train_run t
USING (
  SELECT 9001                                      AS run_id,
         1                                         AS train_id,
         CAST(NULL AS NUMBER)                      AS sale_event_id,
         TRUNC(SYSDATE) + 1                        AS run_date,
         SYSTIMESTAMP - INTERVAL '1' HOUR          AS sale_open_at,
         CAST(TRUNC(SYSDATE) + 2 AS TIMESTAMP)     AS sale_close_at,
         'ON_SALE'                                 AS status
    FROM dual
) s ON (t.run_id = s.run_id)
WHEN MATCHED THEN
  UPDATE SET t.status = s.status, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
  VALUES (s.run_id, s.train_id, s.sale_event_id, s.run_date,
          s.sale_open_at, s.sale_close_at, s.status);

COMMIT;
