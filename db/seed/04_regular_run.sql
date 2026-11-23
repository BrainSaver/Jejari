-- 제자리 기준 데이터 시드 4/5 : 평시 운행 (코레일 "출발 2개월 전 07:00" 규칙)
-- 선행: 03_sale_event_run.sql · 후행: 05_train_stop_fare.sql
-- 다시 실행해도 결과가 같습니다 (MERGE). 운행일·오픈·마감 시각과 status를 "돌린 날" 기준으로
-- 다시 계산해 덮어씁니다. 그래서 시연 운행이 오래돼 출발해 버렸으면 DB를 지우지 않고
-- 04 → 05만 다시 돌리면 창이 앞으로 밀립니다 (05가 정차 시각을 run_date에서 다시 만듭니다).
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

-- 시연 운행 9001·9002·9003이 옮겨 갈 날짜를 먼저 비웁니다.
--
-- 아래 MERGE는 이 셋의 운행일을 "돌린 날" 기준으로 다시 계산해 덮어씁니다. 그런데 이 파일
-- 뒤쪽이 **예매 가능한 모든 날**에 운행을 깔아 두므로, 옮겨 갈 날짜는 이미 생성된 운행이
-- 쥐고 있습니다. 그대로 UPDATE하면 uq_train_run_train_id_run_date에 걸려
-- ORA-00001로 시드가 멈춥니다 (2026-11-23에 실제로 겪었습니다).
--
-- 생성된 운행은 **번호에 뜻이 없습니다** (train_run_seq가 줍니다). 그래서 자리를 비켜 주고,
-- 비워진 옛 날짜는 뒤쪽 MERGE가 다시 채웁니다. 결과적으로 두 운행이 날짜를 맞바꿉니다.
--
-- 예약·좌석·대기자가 달린 운행은 건드리지 않습니다. 그 경우 아래 MERGE의 WHERE가 그 행을
-- 걸러 내어 9001을 옮기지 않고 둡니다 (시드는 멈추지 않고, 창은 생성된 운행이 메우고 있습니다).
DELETE FROM train_stop
 WHERE run_id IN (SELECT r.run_id FROM train_run r
                    JOIN (SELECT 1 AS train_id, TRUNC(SYSDATE) + 45 AS run_date FROM dual UNION ALL
                          SELECT 2,             TRUNC(SYSDATE) + 45            FROM dual UNION ALL
                          SELECT 1,             ADD_MONTHS(TRUNC(SYSDATE), 2) + 1 FROM dual) w
                      ON w.train_id = r.train_id AND w.run_date = r.run_date
                   WHERE r.run_id >= 100000 AND r.run_id < 400000
                     AND NOT EXISTS (SELECT 1 FROM reservation     d WHERE d.run_id = r.run_id)
                     AND NOT EXISTS (SELECT 1 FROM seat_inventory  d WHERE d.run_id = r.run_id)
                     AND NOT EXISTS (SELECT 1 FROM waitlist        d WHERE d.run_id = r.run_id));

DELETE FROM train_run r
 WHERE r.run_id >= 100000 AND r.run_id < 400000
   AND EXISTS (SELECT 1 FROM (SELECT 1 AS train_id, TRUNC(SYSDATE) + 45 AS run_date FROM dual UNION ALL
                              SELECT 2,             TRUNC(SYSDATE) + 45            FROM dual UNION ALL
                              SELECT 1,             ADD_MONTHS(TRUNC(SYSDATE), 2) + 1 FROM dual) w
                WHERE w.train_id = r.train_id AND w.run_date = r.run_date)
   AND NOT EXISTS (SELECT 1 FROM reservation    d WHERE d.run_id = r.run_id)
   AND NOT EXISTS (SELECT 1 FROM seat_inventory d WHERE d.run_id = r.run_id)
   AND NOT EXISTS (SELECT 1 FROM waitlist       d WHERE d.run_id = r.run_id);

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
          -- 지금 판매 중: 출발 45일 뒤 → 오픈(출발 2개월 전)은 15일 전이라 이미 지났음
          --   예전에는 "+3일"이었다. 오픈은 지나 있어 예매가 되지만, 3일이 지나면 출발해
          --   판매 오픈 스케줄러가 DEPARTED로 바꾸고 그때부터 예매가 막혔다. 45일이면
          --   한 번 넣어 두고 시연·테스트 기간(발표 2026-12-05)을 넘긴다.
          SELECT 9001 AS run_id, 1 AS train_id, TRUNC(SYSDATE) + 45 AS run_date FROM dual UNION ALL
          SELECT 9002,           2,             TRUNC(SYSDATE) + 45           FROM dual UNION ALL
          -- 오픈 예정: 출발이 두 달 하루 뒤 → 오픈이 내일 07:00 (오픈 스케줄러 테스트용)
          SELECT 9003,           1,             ADD_MONTHS(TRUNC(SYSDATE), 2) + 1 FROM dual
        )
    ) r
   -- 위 DELETE로도 비우지 못한 날짜(예약이 달린 생성 운행)는 건너뜁니다. 옮기지 않고 두는 것이
   -- 시드를 ORA-00001로 멈추는 것보다 낫습니다
   WHERE NOT EXISTS (SELECT 1 FROM train_run o
                      WHERE o.train_id = r.train_id AND o.run_date = r.run_date
                        AND o.run_id <> r.run_id)
) s ON (t.run_id = s.run_id)
WHEN MATCHED THEN
  -- 날짜까지 덮어씁니다. status만 다시 계산하면 이미 출발한 운행은 되살릴 수 없어
  -- DB를 지우는 수밖에 없었습니다. 예약은 run_id로 매달려 있어 열차와 함께 밀립니다.
  UPDATE SET t.run_date = s.run_date, t.sale_open_at = s.sale_open_at,
             t.sale_close_at = s.sale_close_at, t.status = s.status,
             t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
  VALUES (s.run_id, s.train_id, s.sale_event_id, s.run_date,
          s.sale_open_at, s.sale_close_at, s.status);

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- 예매 가능 기간을 날짜별로 깔기 (열차 2편 × 매일)
--
-- 화면 검색은 운행일이 **정확히 일치**하는 운행만 찾습니다 (TrainQueryService의
-- "WHERE r.run_date = ?"). 위 9001~9003처럼 날짜 몇 개에만 운행을 두면, 화면 기본값인
-- 오늘로 조회했을 때 늘 "조건에 맞는 열차가 없어요"가 나옵니다. 실제로 그랬습니다.
--
-- 그래서 코레일 규칙대로 **예매 가능 기간 전체**에 운행을 넣습니다. 지평은
-- ADD_MONTHS(오늘, 2) + 1일입니다. 달마다 길이가 달라 2개월이 59~62일이므로 날짜를 세지 않고
-- ADD_MONTHS로 잡습니다. 하루를 더 두는 이유는 **아직 안 열린 날짜**를 하나 남기려는 것입니다
-- (status가 SCHEDULED로 들어가고, 07:00이 되면 판매 오픈 스케줄러가 ON_SALE로 넘깁니다).
--
-- **이 창은 시드를 돌린 순간의 것입니다.** 하루가 지나면 끝 날짜가 따라와야 하는데 시드는
-- 다시 돌 일이 없습니다. 그 일은 RunHorizonScheduler가 합니다 (같은 자연키·같은 날짜 규칙).
-- 시드는 앱이 뜨기 전의 첫 창을 깔고, 그 뒤로는 스케줄러가 하루씩 이어 줍니다.
--
-- run_id는 **train_run_seq 시퀀스**가 줍니다 (ERD v27). 중복 판정은 번호가 아니라 진짜 자연키
-- (train_id, run_date)로 합니다 — UNIQUE가 이미 걸려 있습니다.
--   전에는 번호에 날짜를 넣었습니다 (700000 + (운행일 - 2026-01-01) * 10 + train_id).
--   길동현 님 실제 시간표 적재는 400000 + 연중일 * 1000 + 열차번호를 씁니다. 두 산식이 각자
--   자라서 대역이 겹칩니다 (겹칠 수 있는 번호 880개). 지금 안 깨지는 건 실제 열차번호가
--   1~128이라 211 이상이 안 나오기 때문인데, KTX-산천(4xx)이 들어오면 바로 겹칩니다.
--   자연키로 맞추면 번호를 맞출 필요가 없어 산식 자체가 사라집니다.
--   기존 DB는 db/patch/v27.sql 을 먼저 적용하세요 (없으면 ORA-02289).
MERGE INTO train_run t
USING (
  SELECT r.train_id, CAST(NULL AS NUMBER) AS sale_event_id, r.run_date, r.sale_open_at,
         CAST(r.run_date + 1 AS TIMESTAMP) AS sale_close_at,
         CASE WHEN r.sale_open_at <= SYSTIMESTAMP THEN 'ON_SALE' ELSE 'SCHEDULED' END AS status
    FROM (
      SELECT tr.train_id,
             TRUNC(SYSDATE) + d.n AS run_date,
             CAST(ADD_MONTHS(TRUNC(SYSDATE) + d.n, -2) AS TIMESTAMP) + INTERVAL '7' HOUR AS sale_open_at
        FROM train tr
       CROSS JOIN (SELECT LEVEL - 1 AS n
                     FROM dual
                  CONNECT BY TRUNC(SYSDATE) + LEVEL - 1 <= ADD_MONTHS(TRUNC(SYSDATE), 2) + 1) d
       WHERE tr.train_id IN (1, 2)
    ) r
) s ON (t.train_id = s.train_id AND t.run_date = s.run_date)
WHEN MATCHED THEN
  -- 이미 그 열차·그 날에 운행이 있으면(명절 8건, 9001·9002, 9003, 화면에서 만든 것, 전에 이
  -- 시드가 넣은 것) 마감 시각을 맞추고, **오픈 시각이 지난 SCHEDULED만** 엽니다.
  --   DEPARTED·CLOSED는 건드리지 않습니다 — 실제로 떠나거나 닫힌 열차라 되살리면 안 됩니다.
  --   여는 이유: 지평 밖이라 SCHEDULED로 들어간 날짜는 그 뒤 오픈 시각이 지나도 앱이
  --   떠 있지 않으면 아무도 열어 주지 않습니다. 그러면 99_verify 23번(status가 오픈 시각과
  --   일관)이 FAIL입니다 (2026-11-23에 실제로 겪었습니다). 판매 오픈 스케줄러와 같은 판정입니다
  UPDATE SET t.sale_close_at = s.sale_close_at,
             t.status = CASE WHEN t.status = 'SCHEDULED' AND t.sale_open_at <= SYSTIMESTAMP
                             THEN 'ON_SALE' ELSE t.status END,
             t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN
  INSERT (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
  VALUES (train_run_seq.NEXTVAL, s.train_id, s.sale_event_id, s.run_date,
          s.sale_open_at, s.sale_close_at, s.status);

COMMIT;
