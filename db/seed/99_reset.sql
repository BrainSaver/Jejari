-- 제자리 기준 데이터 시드 초기화 (개발용)
-- 날짜를 다시 잡거나 시드를 바꿔 다시 넣을 때 씁니다.
-- 예약·결제 데이터가 있으면 FK 때문에 실패합니다. 그때는 그 데이터를 먼저 지우세요.
-- station·route·car_type은 참조 전용(행 삭제 금지)이라 지우지 않습니다.

DELETE FROM fare;
DELETE FROM train_stop;
DELETE FROM train_run        WHERE sale_event_id = 2 OR run_id IN (9001, 9002, 9003);
DELETE FROM purchase_limit_policy WHERE policy_id >= 4;
DELETE FROM sale_event       WHERE sale_event_id = 2;
DELETE FROM seat;
DELETE FROM train_car;
COMMIT;
