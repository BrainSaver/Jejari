-- ERD v35 → v36 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v36 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 단체 예매(여행사, 길동현 요청 2026-11-24) — 절차는 docs/concurrency.md §18
--   · 새 표 group_fee_policy(단체 위약금 5행), group_booking(단체 신청), group_passenger(탑승자 명단)
--   · seat_inventory: status에 BLOCKED(단체 할당석), BLOCKED면 reservation_id NULL → reservation_id NULL 허용 + CHECK 2개 교체
--   · reservation: source에 GROUP, seat_count NUMBER(2) → NUMBER(3)
--   · sale_event.group_quota_rate 칸, app_policy group.* 9행
-- 단체 기능 코드가 들어오기 전에는 BLOCKED·GROUP 행을 만드는 곳이 없어 넣어도 동작은 그대로입니다.
--
-- 다시 실행해도 안전합니다 (이미 있는 표·칸·제약·색인·값은 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v36.sql

SET SQLBLANKLINES ON
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

-- 1. 기존 표 변경
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE seat_inventory MODIFY (reservation_id NULL)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -1451 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE seat_inventory DROP CONSTRAINT ck_seat_inventory_r1';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2443 THEN RAISE; END IF;
END;
/

ALTER TABLE seat_inventory ADD CONSTRAINT ck_seat_inventory_r1 CHECK ((status = 'HOLD' AND hold_expires_at IS NOT NULL AND reservation_id IS NOT NULL) OR (status = 'SOLD' AND hold_expires_at IS NULL AND reservation_id IS NOT NULL) OR (status = 'BLOCKED' AND hold_expires_at IS NULL AND reservation_id IS NULL));

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE seat_inventory DROP CONSTRAINT ck_seat_inventory_status';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2443 THEN RAISE; END IF;
END;
/

ALTER TABLE seat_inventory ADD CONSTRAINT ck_seat_inventory_status CHECK (status IN ('HOLD', 'SOLD', 'BLOCKED'));

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE reservation DROP CONSTRAINT ck_reservation_source';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2443 THEN RAISE; END IF;
END;
/

ALTER TABLE reservation ADD CONSTRAINT ck_reservation_source CHECK (source IN ('NORMAL', 'WAITLIST', 'GROUP'));

ALTER TABLE reservation MODIFY (seat_count NUMBER(3));

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE sale_event ADD (group_quota_rate           NUMBER(4,3))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -1430 THEN RAISE; END IF;
END;
/

COMMENT ON COLUMN seat_inventory.reservation_id IS '점유 예약 (BLOCKED면 null) / 반환 시 이 예약 행만 삭제';
COMMENT ON COLUMN seat_inventory.status IS 'HOLD / SOLD / BLOCKED / 선점과 확정 구분. BLOCKED = 단체 할당석으로 묶어 둔 좌석';
COMMENT ON COLUMN seat_inventory.hold_expires_at IS 'HOLD 만료 시각 (SOLD·BLOCKED면 null) / = reservation.pay_deadline_at 복사. Redis TTL이 끝난 뒤 남은 HOLD 행을 다른 사람이 이어받는 기준';
COMMENT ON COLUMN reservation.seat_count IS '예약 매수 / 1회 구매 매수 위험 신호. 단체는 최대 200명이라 3자리';
COMMENT ON COLUMN reservation.source IS 'NORMAL / WAITLIST / GROUP / 취소표 재배정 여부, 단체 예매 구분';
COMMENT ON COLUMN sale_event.group_quota_rate IS '이 행사 운행의 단체 할당석 비율 (null이면 app_policy group.quota_rate) / 명절엔 0으로 꺼서 개인 예매에 좌석을 모두 돌림';

-- 2. 새 표
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE group_fee_policy (
  fee_policy_id              NUMBER NOT NULL,
  min_minutes_before         NUMBER,
  max_minutes_before         NUMBER,
  fee_rate                   NUMBER(4,3),
  fixed_amount               NUMBER(10),
  description                VARCHAR2(100),
  created_at                 TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at                 TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_group_fee_policy PRIMARY KEY (fee_policy_id)
)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 THEN RAISE; END IF;
END;
/

COMMENT ON TABLE group_fee_policy IS '단체 위약금 정책 - 단체승차권 반환 위약금 구간 (코레일 현행). 개인 취소 수수료(cancel_fee_policy)와 계산 규칙이 달라 따로 둔다.';
COMMENT ON COLUMN group_fee_policy.min_minutes_before IS '구간 시작(분, 음수 = 출발 후)';
COMMENT ON COLUMN group_fee_policy.max_minutes_before IS '구간 끝(분, 음수 = 출발 후)';
COMMENT ON COLUMN group_fee_policy.fee_rate IS '위약금 비율';
COMMENT ON COLUMN group_fee_policy.fixed_amount IS '1인당 고정 위약금(원) / 출발 2일 전까지 1인 400원';
COMMENT ON COLUMN group_fee_policy.description IS '관리자 화면 표시용 설명';
COMMENT ON COLUMN group_fee_policy.created_at IS '생성 시각';
COMMENT ON COLUMN group_fee_policy.updated_at IS '수정 시각';

BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE group_booking (
  group_id                   NUMBER NOT NULL,
  rep_member_id              NUMBER NOT NULL,
  run_id                     NUMBER NOT NULL,
  from_station_id            VARCHAR2(10) NOT NULL,
  to_station_id              VARCHAR2(10) NOT NULL,
  passenger_count            NUMBER(3) NOT NULL,
  status                     VARCHAR2(12) NOT NULL,
  reservation_id             NUMBER,
  approved_at                TIMESTAMP,
  approved_by                NUMBER,
  discount_yn                CHAR(1) NOT NULL,
  consent_at                 TIMESTAMP NOT NULL,
  roster_changed_at          TIMESTAMP,
  warn_no_phone_yn           CHAR(1) NOT NULL,
  warn_dup_phone_yn          CHAR(1) NOT NULL,
  cancel_reason              VARCHAR2(100),
  created_at                 TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at                 TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_group_booking PRIMARY KEY (group_id),
  CONSTRAINT ck_group_booking_r1 CHECK (passenger_count BETWEEN 11 AND 200),
  CONSTRAINT ck_group_booking_r2 CHECK (status <> ''APPROVED'' OR reservation_id IS NOT NULL),
  CONSTRAINT ck_group_booking_status CHECK (status IN (''REQUESTED'', ''APPROVED'', ''REJECTED'', ''WITHDRAWN'')),
  CONSTRAINT ck_group_booking_discount_yn CHECK (discount_yn IN (''Y'', ''N'')),
  CONSTRAINT ck_group_booking_9467l5 CHECK (warn_no_phone_yn IN (''Y'', ''N'')),
  CONSTRAINT ck_group_booking_tllj59 CHECK (warn_dup_phone_yn IN (''Y'', ''N''))
)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 THEN RAISE; END IF;
END;
/

COMMENT ON TABLE group_booking IS '단체 신청 - 여행사 단체 예매 신청 (코레일 단체승차권 기준: 같은 구간 11명 이상). 대표자 1명이 전화 인증하고 일괄 결제한다. 매크로 신호가 없으면 자동 승인, 승인되면 단체 할당석에서 붙여 배정해 reservation(source=GROUP) 한 건을 만든다. 할당석이 모자라면 남은 일반 좌석에서 선착순으로 이어 배정한다 (대기열은 거치지 않고, 대표자 전화 인증·매크로 신호 검사가 별도 관문, concurrency.md §18.3a).';
COMMENT ON COLUMN group_booking.rep_member_id IS '대표자 회원 (전화 인증, 본인도 탑승) / 단체 신청 이력을 대표자 번호에 쌓아 반복 신청 탐지';
COMMENT ON COLUMN group_booking.run_id IS '운행';
COMMENT ON COLUMN group_booking.from_station_id IS '출발역 (전원 같음) / 같은 구간만 단체 허용';
COMMENT ON COLUMN group_booking.to_station_id IS '도착역 (전원 같음)';
COMMENT ON COLUMN group_booking.passenger_count IS '신청 인원 (대표자 포함) / 11~200 (app_policy group.min_size·max_size)';
COMMENT ON COLUMN group_booking.status IS 'REQUESTED / APPROVED / REJECTED / WITHDRAWN / 신청 단계만. 승인 뒤 결제·만료·취소는 reservation.status가 원천';
COMMENT ON COLUMN group_booking.reservation_id IS '배정된 단체 예약 (승인 전 null) / 좌석·결제·취소는 기존 예약 흐름 재사용';
COMMENT ON COLUMN group_booking.approved_at IS '승인 시각';
COMMENT ON COLUMN group_booking.approved_by IS '승인 관리자 (null이면 자동 승인) / 매크로 신호 없으면 자동 승인';
COMMENT ON COLUMN group_booking.discount_yn IS '단체 할인 적용 여부 Y/N / 출발 1개월 이내 신청분만 10%';
COMMENT ON COLUMN group_booking.consent_at IS '대표자가 탑승자 개인정보 제공 동의를 확인한 시각 / 남의 번호를 받는 근거';
COMMENT ON COLUMN group_booking.roster_changed_at IS '명단 변경 시각 (null이면 아직 변경 안 함) / 변경은 1회, 예약 후 group.roster_change_days 이내';
COMMENT ON COLUMN group_booking.warn_no_phone_yn IS '휴대폰 없음 비율 경고 Y/N / group.no_phone_warn_rate 초과 시 관리자 화면 표시';
COMMENT ON COLUMN group_booking.warn_dup_phone_yn IS '다른 단체와 번호 중복 경고 Y/N / 같은 명단을 돌려 쓰는 표팔이 탐지';
COMMENT ON COLUMN group_booking.cancel_reason IS '반려·취소 사유 / 관리자 감사 추적';
COMMENT ON COLUMN group_booking.created_at IS '생성 시각';
COMMENT ON COLUMN group_booking.updated_at IS '수정 시각';

BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE group_passenger (
  group_id                   NUMBER NOT NULL,
  passenger_no               NUMBER(3) NOT NULL,
  name_enc                   VARCHAR2(200) NOT NULL,
  phone_enc                  VARCHAR2(200),
  enc_key_id                 VARCHAR2(20) NOT NULL,
  phone_hash                 VARCHAR2(64),
  no_phone_yn                CHAR(1) NOT NULL,
  passenger_type             VARCHAR2(10) NOT NULL,
  rep_yn                     CHAR(1) NOT NULL,
  seat_id                    NUMBER,
  status                     VARCHAR2(10) NOT NULL,
  created_at                 TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at                 TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_group_passenger PRIMARY KEY (group_id, passenger_no),
  CONSTRAINT ck_group_passenger_r1 CHECK ((no_phone_yn = ''Y'' AND phone_hash IS NULL) OR (no_phone_yn = ''N'' AND phone_hash IS NOT NULL)),
  CONSTRAINT ck_group_passenger_no_phone_yn CHECK (no_phone_yn IN (''Y'', ''N'')),
  CONSTRAINT ck_group_passenger_1r2fl8 CHECK (passenger_type IN (''ADULT'', ''CHILD'')),
  CONSTRAINT ck_group_passenger_rep_yn CHECK (rep_yn IN (''Y'', ''N'')),
  CONSTRAINT ck_group_passenger_status CHECK (status IN (''ACTIVE'', ''REFUNDED''))
)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 THEN RAISE; END IF;
END;
/

COMMENT ON TABLE group_passenger IS '단체 탑승자 - 단체 신청의 탑승자 명단. 대표자가 이름·전화번호를 입력하고, 각 번호로 승차권을 전달한다. 휴대폰이 없는 탑승자는 대표자 번호로 전달.';
COMMENT ON COLUMN group_passenger.group_id IS '단체 신청';
COMMENT ON COLUMN group_passenger.passenger_no IS '명단 순번';
COMMENT ON COLUMN group_passenger.name_enc IS '탑승자 이름 암호문 (AES-256-GCM) / 승차권 표시에 원문 필요';
COMMENT ON COLUMN group_passenger.phone_enc IS '탑승자 휴대폰 암호문 (휴대폰 없음이면 null) / 승차권 전달에 원문 필요';
COMMENT ON COLUMN group_passenger.enc_key_id IS 'name_enc·phone_enc 암호화 키 버전';
COMMENT ON COLUMN group_passenger.phone_hash IS '탑승자 휴대폰 HMAC / 여러 단체에 같은 번호 반복 탐지';
COMMENT ON COLUMN group_passenger.no_phone_yn IS '휴대폰 없음 여부 Y/N / Y면 대표자 번호로 승차권 전달';
COMMENT ON COLUMN group_passenger.passenger_type IS 'ADULT / CHILD / 단체 할인은 어른만';
COMMENT ON COLUMN group_passenger.rep_yn IS '대표자 본인 여부 Y/N';
COMMENT ON COLUMN group_passenger.seat_id IS '배정 좌석 (배정 전 null)';
COMMENT ON COLUMN group_passenger.status IS 'ACTIVE / REFUNDED / 일부 환불 시 해당 탑승자만 REFUNDED';
COMMENT ON COLUMN group_passenger.created_at IS '생성 시각';
COMMENT ON COLUMN group_passenger.updated_at IS '수정 시각';

-- 3. 외래 키·색인
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_group_pax_phone ON group_passenger (phone_hash)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_booking ADD CONSTRAINT fk_group_booking_rep_member_id FOREIGN KEY (rep_member_id) REFERENCES member (member_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_group_booking_rep_member_id ON group_booking (rep_member_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_booking ADD CONSTRAINT fk_group_booking_run_id FOREIGN KEY (run_id) REFERENCES train_run (run_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_group_booking_run_id ON group_booking (run_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_booking ADD CONSTRAINT fk_group_booking_1i6x9o FOREIGN KEY (from_station_id) REFERENCES station (station_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_booking ADD CONSTRAINT fk_group_booking_to_station_id FOREIGN KEY (to_station_id) REFERENCES station (station_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_booking ADD CONSTRAINT fk_group_booking_umzwyh FOREIGN KEY (reservation_id) REFERENCES reservation (reservation_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_group_booking_vy83jr ON group_booking (reservation_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_booking ADD CONSTRAINT fk_group_booking_approved_by FOREIGN KEY (approved_by) REFERENCES admin_user (admin_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_group_booking_approved_by ON group_booking (approved_by)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_passenger ADD CONSTRAINT fk_group_passenger_group_id FOREIGN KEY (group_id) REFERENCES group_booking (group_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE group_passenger ADD CONSTRAINT fk_group_passenger_seat_id FOREIGN KEY (seat_id) REFERENCES seat (seat_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2275 AND SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_group_passenger_seat_id ON group_passenger (seat_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

-- 4. 예시·설정 값
MERGE INTO group_fee_policy t
USING (
  SELECT 1 fee_policy_id, 2880 min_minutes_before, NULL max_minutes_before, NULL fee_rate, 400 fixed_amount, '출발 2일 전까지 1인 400원' description FROM dual UNION ALL
  SELECT 2 fee_policy_id, 0 min_minutes_before, 2880 max_minutes_before, 0.1 fee_rate, NULL fixed_amount, '1일 전 ~ 출발 전 10%' description FROM dual UNION ALL
  SELECT 3 fee_policy_id, -20 min_minutes_before, 0 max_minutes_before, 0.15 fee_rate, NULL fixed_amount, '출발 후 20분까지 15%' description FROM dual UNION ALL
  SELECT 4 fee_policy_id, -60 min_minutes_before, -20 max_minutes_before, 0.4 fee_rate, NULL fixed_amount, '출발 후 20~60분 40%' description FROM dual UNION ALL
  SELECT 5 fee_policy_id, NULL min_minutes_before, -60 max_minutes_before, 0.7 fee_rate, NULL fixed_amount, '출발 후 60분 ~ 도착 70%' description FROM dual
) s ON (t.fee_policy_id = s.fee_policy_id)
WHEN NOT MATCHED THEN
  INSERT (fee_policy_id, min_minutes_before, max_minutes_before, fee_rate, fixed_amount, description)
  VALUES (s.fee_policy_id, s.min_minutes_before, s.max_minutes_before, s.fee_rate, s.fixed_amount, s.description);

MERGE INTO app_policy t
USING (
  SELECT 'group.min_size' k, '11' v, 'NUMBER' t, '단체 최소 인원 (같은 구간, 어린이 포함)' d FROM dual UNION ALL
  SELECT 'group.max_size' k, '200' v, 'NUMBER' t, '단체 신청 1건 최대 인원' d FROM dual UNION ALL
  SELECT 'group.quota_rate' k, '0.05' v, 'NUMBER' t, '운행별 단체 할당석 비율 (행사 운행은 sale_event.group_quota_rate 우선)' d FROM dual UNION ALL
  SELECT 'group.quota_release_days' k, '30' v, 'NUMBER' t, '출발 며칠 전까지 안 쓰인 할당석을 일반 판매로 돌릴지' d FROM dual UNION ALL
  SELECT 'group.no_phone_warn_rate' k, '0.3' v, 'NUMBER' t, '휴대폰 없음 탑승자 비율이 이 값을 넘으면 관리자 경고' d FROM dual UNION ALL
  SELECT 'group.pay_deadline_days' k, '3' v, 'NUMBER' t, '승인 후 결제 기한(일). 넘기면 배정 취소·할당석 반납' d FROM dual UNION ALL
  SELECT 'group.roster_change_days' k, '7' v, 'NUMBER' t, '예약 후 명단 변경 가능 기간(일), 1회' d FROM dual UNION ALL
  SELECT 'group.discount_rate' k, '0.10' v, 'NUMBER' t, '단체 할인율 (어른 운임만, 중복 할인 없음)' d FROM dual UNION ALL
  SELECT 'group.discount_within_days' k, '30' v, 'NUMBER' t, '출발 며칠 이내 신청분에 할인 적용' d FROM dual
) s ON (t.policy_key = s.k)
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, s.t, s.d);

COMMIT;

SELECT constraint_name || ': ' || search_condition_vc FROM user_constraints
 WHERE constraint_name IN ('CK_SEAT_INVENTORY_R1', 'CK_SEAT_INVENTORY_STATUS', 'CK_RESERVATION_SOURCE');
SELECT table_name FROM user_tables WHERE table_name LIKE 'GROUP\_%' ESCAPE '\' ORDER BY table_name;
SELECT policy_key || ' = ' || policy_value FROM app_policy WHERE policy_key LIKE 'group.%' ORDER BY policy_key;
