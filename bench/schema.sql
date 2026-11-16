-- §17.1 락 방식 비교용 실험 테이블
-- 실제 스키마를 건드리지 않으려고 따로 둔다. 비교하려는 것은 "락 방식"이므로
-- FK·트리거처럼 세 방식에 똑같이 붙는 비용은 넣지 않았다.

BEGIN EXECUTE IMMEDIATE 'DROP TABLE exp_seat';     EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE exp_seat_inv'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- A 비관적 · B 낙관적: 운행 좌석 행을 미리 만들어 둔다
CREATE TABLE exp_seat (
  run_id         NUMBER       NOT NULL,
  seat_id        NUMBER       NOT NULL,
  status         VARCHAR2(10) NOT NULL,
  reservation_id NUMBER,
  version        NUMBER       DEFAULT 0 NOT NULL,
  updated_at     TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_exp_seat PRIMARY KEY (run_id, seat_id),
  CONSTRAINT ck_exp_seat_status CHECK (status IN ('AVAILABLE', 'HOLD'))
);

-- C 현재 설계: seat_inventory와 같은 모양. 행을 미리 만들지 않고 INSERT하며
--              PK 충돌이 최종 판정이다 (§2.2-4)
CREATE TABLE exp_seat_inv (
  run_id          NUMBER       NOT NULL,
  seat_id         NUMBER       NOT NULL,
  segment_no      NUMBER(3)    NOT NULL,
  reservation_id  NUMBER       NOT NULL,
  status          VARCHAR2(10) NOT NULL,
  hold_expires_at TIMESTAMP,
  created_at      TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_exp_seat_inv PRIMARY KEY (run_id, seat_id, segment_no)
);
