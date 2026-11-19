-- ERD v26 → v27 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v27 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- train_run_seq 시퀀스 추가. 날짜별 평시 운행의 run_id를 산식이 아니라 시퀀스로 받습니다.
-- 이 패치 없이 새 시드(04)나 RunHorizonScheduler가 돌면
-- ORA-02289(시퀀스가 없음)으로 운행 생성이 실패합니다.
--
-- 왜 산식을 버렸나
--   전에는 번호에 날짜를 넣었습니다 — 시드·스케줄러는 700000 + (운행일-2026-01-01)*10 + train_id,
--   길동현 님 실제 시간표 적재(tools/load-tago-schedule.js)는 400000 + 연중일*1000 + 열차번호.
--   두 산식이 각자 자라서 대역이 겹칩니다 (겹칠 수 있는 번호 880개). 지금 안 깨지는 건
--   실제 열차번호가 1~128이라 211 이상이 안 나오기 때문인데, KTX-산천(4xx)이나 다른 노선이
--   들어오면 바로 겹칩니다. 우연에 기대지 않으려고 번호를 시퀀스에 맡기고,
--   중복 판정은 진짜 자연키 (train_id, run_date)로 합니다 (UNIQUE가 이미 있습니다).
--
--   MAXVALUE 399999가 할당 범위를 강제합니다. 다 쓰면 조용히 남의 대역(401000~)으로
--   넘어가지 않고 ORA-08004로 멈춥니다. 하루 ~110편이면 2,700일분입니다.
--
-- 다시 실행해도 안전합니다 (이미 있으면 건너뜁니다).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v27.sql

SET SQLBLANKLINES ON
SET DEFINE OFF
BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE train_run_seq '
                 || 'START WITH 100000 INCREMENT BY 1 MAXVALUE 399999 NOCYCLE CACHE 50';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;             -- ORA-00955: 이미 있음
    ELSE RAISE;
    END IF;
END;
/
-- 옛 산식으로 만든 운행(700000~707999)을 정리합니다. 시드와 스케줄러가 시퀀스 번호로 다시 만듭니다.
-- 예약·좌석·대기자가 달린 운행은 건드리지 않습니다 (시연 중 만든 예약을 지우지 않으려고).
-- 남겨진 운행도 (train_id, run_date)로 매칭되므로 중복은 생기지 않습니다 — 옛 번호만 그대로 남습니다.
DELETE FROM train_stop
 WHERE run_id BETWEEN 700000 AND 707999
   AND run_id NOT IN (SELECT run_id FROM reservation
                       UNION SELECT run_id FROM seat_inventory
                       UNION SELECT run_id FROM waitlist);
DELETE FROM train_run
 WHERE run_id BETWEEN 700000 AND 707999
   AND run_id NOT IN (SELECT run_id FROM reservation
                       UNION SELECT run_id FROM seat_inventory
                       UNION SELECT run_id FROM waitlist);
COMMIT;

-- 확인: 시퀀스가 있고, 옛 대역에 남은 운행이 있다면 예약이 달린 것뿐이어야 합니다
SELECT '시퀀스' AS 항목, sequence_name AS 값 FROM user_sequences WHERE sequence_name = 'TRAIN_RUN_SEQ';
SELECT '옛 대역에 남은 운행' AS 항목, TO_CHAR(COUNT(*)) AS 값
  FROM train_run WHERE run_id BETWEEN 700000 AND 707999;
