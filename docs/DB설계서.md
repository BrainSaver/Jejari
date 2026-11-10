# 제자리 DB설계서

> 작성 2026-11-10 · 길동현

제자리(명절 열차 공정 예매 시스템)의 데이터 저장 구조를 정의합니다. 기능 ID는 [기능설계서](기능설계서.md)와 같습니다.

## 목차

1. [저장소 분담](#1-저장소-분담)
2. [공통 규칙](#2-공통-규칙)
3. [기능별 테이블 사용](#3-기능별-테이블-사용)
4. [테이블 정의](#4-테이블-정의)
5. [상태 코드표](#5-상태-코드표)
6. [MongoDB·Redis·Kafka](#6-mongodbrediskafka)

---

## 1. 저장소 분담

| 저장소 | 맡는 데이터 | 왜 여기에 |
|---|---|---|
| Oracle | 기준 데이터, 회원·인증, 예약·결제·취소, 매수 카운터, outbox·inbox·Saga, 취소표, 위험 점수, 관리자 조치, 의심 케이스 | 돈과 좌석이 걸린 데이터는 트랜잭션·PK·UNIQUE·조건부 UPDATE로 DB가 최종 판정 |
| MongoDB | 행동 로그, 대기열 기록, 로그인 시도, (P2) 화면 녹화·외부 데이터 | 건수가 많고 형태가 자주 바뀌는 로그. Kafka에서 묶어 적재, TTL로 자동 삭제 |
| Redis | 대기열, 입장 토큰, 좌석 선점 1차 경쟁, 매수 한도 1차, 멱등 키, 캐시 | 빠른 1차 판정. 통과해도 DB가 거절하면 거절 |
| Kafka | 예약 흐름 이벤트, 행동 로그 | 예약 이후 처리(결제·확정·취소·재배정)를 비동기로, reservation_id 키로 순서 보장 |

### 구현 범위별 테이블

P1은 1차 구현, P2·P3는 이후 단계, 확장 예정은 DDL에서 제외합니다.

| 우선 | Oracle | MongoDB |
|---|---|---|
| P1 필수 | station, route, route_station, car_type, train, train_car, seat, train_run, train_stop, fare, purchase_limit_policy, sale_event, app_policy, app_policy_history, member, payment_method, member_device, member_credential, identity_verification, auth_session, admin_user, seat_inventory, reservation, reservation_seat, reservation_history, payment, cancellation, cancel_fee_policy, purchase_counter, outbox_event, processed_event, saga_instance, waitlist, notification, risk_score, admin_action, model_run, suspicious_case, case_session, case_marker, synthetic_label (41개) | behavior_log, queue_log, login_attempt |
| P2 판단 | risk_score_history, ext_dataset | replay_session, replay_event, risk_feature, ext_hotel_booking, ext_web_access_log, ext_renfe_price |
| P3 발표 후 | evidence_snapshot, replay_access_log | ext_bot_detection |
| 확장 예정 (DDL 제외) | common_code, member_push_token, password_reset_token, ticket_transfer | |

---

## 2. 공통 규칙

| 항목 | 규칙 | 이유 |
|---|---|---|
| 이름 | 소문자 snake_case, Oracle 30자 이내, 예약어 금지 | Oracle 12.1 호환 |
| PK | `NUMBER` 시퀀스 또는 Snowflake. 이벤트·세션은 `VARCHAR2(36)` UUID | Kafka 키로 사용 |
| 공통 컬럼 | Oracle 테이블은 `created_at`, `updated_at`을 DDL이 자동 추가 (INSERT만 하는 processed_event, reservation_history, app_policy_history는 updated_at 없음) | 디버깅 |
| 시간 | `TIMESTAMP`, Asia/Seoul. Oracle 컨테이너 OS·JVM·JDBC 세션 시간대를 모두 고정. 마감 시각은 앱 시계로 계산해 바인딩 | `SYSTIMESTAMP`가 서버 OS 시간대를 따름 |
| 금액 | `NUMBER(10)` 원 단위 | 부동소수점 오차 방지 |
| 상태 코드 | `VARCHAR2` 대문자 코드. 설명이 `A / B / C`면 DDL이 CHECK 생성. Java는 `@Enumerated(EnumType.STRING)` | 잘못된 값 저장 차단 |
| 여부 | `CHAR(1)` Y/N + CHECK. Java는 `AttributeConverter<Boolean,String>` | |
| 식별 해시 | `*_hash` = HMAC-SHA256, 키는 프로젝트 기간 고정 | 원문 없이 같은 값끼리 묶음 |
| 복호화 필요 개인정보 | AES-256-GCM 암호문 + 키 버전 (`phone_enc` + `enc_key_id`) | 알림 발송 등 |
| 비밀번호 | Argon2id PHC 문자열 `VARCHAR2(255)` | 파라미터 내장, 재해싱 가능 |
| 토큰 | 리프레시 토큰은 SHA-256 해시만 | |
| 네이티브 UPDATE | `updated_at = SYSTIMESTAMP`, version 컬럼이 있으면 `version + 1`, 현재 상태를 WHERE에 넣고 영향 행 수 확인 | 다른 트랜잭션이 먼저 바꾼 경우를 감지 |
| 락 순서 | 예약에 딸린 행은 reservation 행을 먼저 잠근 뒤 변경. 여러 예약은 reservation_id 오름차순 한 건씩 | 데드락 방지 |
| 복합 PK | route_station, train_car, train_stop, member_device, seat_inventory, reservation_seat, processed_event, risk_score, case_session, purchase_counter → JPA `@IdClass` 또는 `@EmbeddedId` | |

---

## 3. 기능별 테이블 사용

C = 생성, R = 조회, U = 수정, D = 삭제. 기능 ID는 기능설계서 3장과 같습니다.

| 기능 | Oracle | MongoDB·Redis |
|---|---|---|
| AUTH-01·02 회원가입·본인인증 | member C, member_credential C, identity_verification C | |
| AUTH-03 로그인 | member R, member_credential RU, auth_session C | login_attempt C, `rate:login` |
| AUTH-04 재발급·로그아웃 | auth_session CU | |
| AUTH-05 관리자 로그인 | admin_user RU, auth_session C | login_attempt C, `rate:login` |
| AUTH-06 내 정보·탈퇴 | member RU | |
| AUTH-07 결제 수단 | payment_method CR | |
| TRN-01~03 조회 | station, route, train, train_run, train_stop, fare, car_type R | `seatmap:{runId}` R |
| QUE-01 진입 | member R, risk_score R(캐시 없을 때) | `risk:*` R, `queue:{runId}` C, queue_log C |
| QUE-02·03 번호표·입장 | | `queue:*` RU, `queue:pass` C |
| QUE-04 토큰 검증 | | `queue:pass` R |
| RSV-01 배치도 | seat, train_car R | `seatmap` R |
| RSV-02 선점 | purchase_limit_policy R, seat_inventory C(재선점 시 D), reservation C(재선점 시 이전 예약 U), reservation_seat C, purchase_counter CU, reservation_history C, saga_instance C, outbox_event C | `idem`, `limit:*`, `seat:hold` |
| RSV-03 기한 초과 | reservation U, seat_inventory D, purchase_counter U, saga_instance U, reservation_history C, outbox_event C, waitlist U | `seat:hold` TTL, `limit:*` |
| RSV-04 내역 | reservation, reservation_seat, payment, cancellation R | |
| RSV-05 결제 전 취소 | RSV-03과 같음 (to = CANCELED) | `seat:hold` → REOFFER |
| PAY-01 결제 시작 | reservation U, seat_inventory U, saga_instance U, waitlist U, payment C, reservation_history C, outbox_event C | `idem:pay`, `seat:hold` 연장 |
| PAY-02 승인 | reservation U, seat_inventory U, payment U, saga_instance U, waitlist U, reservation_history C, outbox_event C | `seat:hold` → SOLD |
| PAY-03 실패 | payment U + RSV-03과 같음 | |
| PAY-04 결과 확인 | payment RU | `seat:hold` 재설정 |
| PAY-05 늦은 승인 환불 | payment U, cancellation C | |
| CNL-01 수수료 미리보기 | cancel_fee_policy, train_stop, reservation_seat R | |
| CNL-02 확정 취소 | reservation U, seat_inventory D, purchase_counter U, cancellation C, reservation_history C, outbox_event C | `seat:hold` → REOFFER, `limit:*` |
| CNL-03 환불 재시도 | cancellation U, payment U | |
| CNL-04 재판매 집계 | cancellation U, seat_inventory R | |
| WL-01·02 대기 신청·취소 | waitlist CRU | |
| WL-03 제안 | processed_event C, waitlist U, RSV-02의 쓰기 전부, notification C | `seat:hold` REOFFER 인계 |
| WL-05 수락 | PAY-01·02와 같음 | |
| EVT-01·02 outbox | outbox_event CU, 정리 배치 D | `relay:shard:{n}` 리스 |
| EVT-03 inbox | processed_event C, 정리 배치 D | |
| EVT-06 알림 | notification CU | |
| RISK-01 행동 로그 | | behavior_log C (Kafka 경유) |
| RISK-02 점수 산정 | risk_score CU, model_run R, cancellation·reservation R | behavior_log R |
| RISK-03 캐시 | risk_score R | `risk:*` C |
| RISK-04 케이스 생성 | suspicious_case C, case_session C, case_marker C | |
| ADM-01·02 기준 데이터 | station, route, route_station, car_type, train, train_car, seat, train_run, train_stop, fare, sale_event, purchase_limit_policy, cancel_fee_policy CRU | `seatmap` 생성 |
| ADM-03 현황 | reservation, seat_inventory, payment R | `queue:*` R |
| ADM-04 위험 목록 | risk_score, member R | |
| ADM-05 타임라인 | suspicious_case, case_session, case_marker R | behavior_log R |
| ADM-06 판정·조치 | suspicious_case U, admin_action C, risk_score U, member U, auth_session U(BAN 시 폐기) | `risk:*` 갱신 |
| ADM-07 강제 취소 | CNL-02와 같음 + admin_action C | |
| ADM-08 운영 설정 | app_policy U, app_policy_history C | `policy:cache` |
| ADM-09 결과 모름 처리 | payment R, PAY-02·03 | |
| DATA-01~03 데이터 | 기준 데이터 C, member 등 C, synthetic_label C | behavior_log C |

---

## 4. 테이블 정의

1차 구현(P1) 테이블의 컬럼과 제약입니다. "기능"은 그 테이블을 쓰는 기능 ID입니다.

### 4.1 기준 데이터

담당: B

#### `station` 역

저장소: Oracle · 참조 전용(행 삭제 없음) · 기능: TRN-01, ADM-01, DATA-01


역 기본 정보. 공공데이터의 역 코드를 그대로 PK로 사용한다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `station_id` | VARCHAR2(10) | PK | 공공 | 역 코드 | 공공데이터 코드 그대로 써야 다른 데이터와 조인 가능 |
| `station_name` | VARCHAR2(50) | NN | 공공 | 역 이름 | 화면 표시 |
| `latitude` | NUMBER(9,6) |  | 공공 | 위도 | 선택. 지도·관리자 화면 |
| `longitude` | NUMBER(9,6) |  | 공공 | 경도 | 선택 |

> **제약·비고** 참조 전용: 행 삭제 금지 (이 테이블을 가리키는 FK에는 인덱스를 만들지 않음)

#### `route` 노선

저장소: Oracle · 참조 전용(행 삭제 없음) · 기능: TRN-02, ADM-01


노선(예: 경부선)과 기점·종점역.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `route_id` | NUMBER | PK | 생성 | 노선 ID |  |
| `route_name` | VARCHAR2(50) | NN | 공공 | 노선명 (예: 경부선) |  |
| `origin_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 기점역 |  |
| `dest_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 종점역 |  |

> **제약·비고** 참조 전용: 행 삭제 금지 (이 테이블을 가리키는 FK에는 인덱스를 만들지 않음)

#### `route_station` 노선별 역 순서

저장소: Oracle · 기능: TRN-02, ADM-01


노선 안에서 각 역의 순번과 누적 거리. 구간 재고와 운임 계산의 기준.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `route_id` | NUMBER | PK, FK → route | 생성 | 노선 |  |
| `station_id` | VARCHAR2(10) | PK, FK → station | 공공 | 역 |  |
| `station_order` | NUMBER(3) | NN | 공공 | 노선 내 순번 (1부터) | 구간 재고의 핵심. 인접 순번 사이가 1개 segment |
| `distance_from_origin_km` | NUMBER(7,1) | NN | 공공 | 기점부터 누적 거리 | 운임 계산, 구간 거리 = 두 역 누적거리 차 |

> **제약·비고** 한 역이 여러 노선에 속할 수 있어 station과 분리. 노선 추가 시 행만 추가.

#### `car_type` 차량 유형

저장소: Oracle · 참조 전용(행 삭제 없음) · 기능: RSV-01, ADM-01


차량 유형 코드와 좌석 수. seat 자동 생성의 기준.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `car_type_code` | VARCHAR2(10) | PK | 공공 | 차량 유형 코드 | 실제 코드값으로 세팅 |
| `car_type_name` | VARCHAR2(50) |  | 공공 | 차량 유형명 |  |
| `seat_count` | NUMBER(3) | NN | 공공 | 좌석 수 | seat 자동 생성 기준 |
| `seat_grade` | VARCHAR2(10) | NN | 공공/생성 | GENERAL / FIRST | 등급별 운임·재고 분리 |
| `seat_columns` | VARCHAR2(10) |  | 생성 | 좌석 열 배치 (예: ABCD) | 좌석 번호(5A) 생성 규칙 |

> **제약·비고** 다운로드 후 실제 헤더명에 맞춰 컬럼명 조정 필요 · 참조 전용: 행 삭제 금지 (이 테이블을 가리키는 FK에는 인덱스를 만들지 않음)

#### `train` 열차 (정의)

저장소: Oracle · 기능: TRN-02, ADM-01


열차 번호·종류·노선. 날짜와 무관한 열차 정의.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `train_id` | NUMBER | PK | 생성 | 열차 ID |  |
| `train_no` | VARCHAR2(10) | NN | 생성 | 열차 번호 (101, 103…) |  |
| `train_kind` | VARCHAR2(20) | NN | 생성 | KTX / 새마을 등 | 운임 단가 구분 |
| `route_id` | NUMBER | FK, NN → route | 생성 | 운행 노선 |  |
| `direction` | VARCHAR2(4) | NN | 생성 | DOWN / UP | 상·하행 구분 |

#### `train_car` 열차 편성

저장소: Oracle · 기능: RSV-01, ADM-01


열차의 호차별 차량 유형.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `train_id` | NUMBER | PK, FK → train | 생성 | 열차 |  |
| `car_no` | NUMBER(2) | PK | 생성 | 호차 번호 |  |
| `car_type_code` | VARCHAR2(10) | FK, NN → car_type | 공공 | 차량 유형 | 호차마다 좌석 수·등급이 다름 |

#### `seat` 물리 좌석

저장소: Oracle · 기능: RSV-01·02, ADM-01


열차 기준의 고정 좌석 배치. 판매 상태는 seat_inventory에서 날짜별로 관리.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `seat_id` | NUMBER | PK | 생성 | 좌석 ID |  |
| `train_id` | NUMBER | FK, NN → train | 생성 | 열차 |  |
| `car_no` | NUMBER(2) | NN | 생성 | 호차 |  |
| `seat_no` | VARCHAR2(5) | NN | 생성 | 좌석 번호 (5A) | 화면 표시 |
| `seat_grade` | VARCHAR2(10) | NN | 생성 | GENERAL / FIRST | car_type에서 상속. 코드값 통일로 CHECK 제약 적용 |
| `is_window` | CHAR(1) | NN | 생성 | 창측 여부 Y/N | 좌석 선호 분포를 가상 트래픽에 반영 |

> **제약·비고** UNIQUE(train_id, car_no, seat_no) · train_car × car_type.seat_count로 스크립트 자동 생성 · UNIQUE(train_id, seat_id) — seat_inventory 복합 FK의 참조 대상

#### `train_run` 날짜별 운행

저장소: Oracle · 기능: TRN-02, QUE-01, RSV-02, ADM-02


특정 날짜의 열차 운행. 대기열·재고·Kafka 집계의 기준 단위.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `run_id` | NUMBER | PK | 생성 | 운행 ID | 대기열·재고·Kafka 집계의 기준 단위 |
| `train_id` | NUMBER | FK, NN → train | 생성 | 열차 |  |
| `sale_event_id` | NUMBER | FK → sale_event | 생성 | 명절 예매 행사 | 오픈 일정·정책을 행사 단위로 묶음 |
| `run_date` | DATE | NN | 생성 | 운행일 | 명절 기간 |
| `sale_open_at` | TIMESTAMP | NN | 생성 | 예매 오픈 시각 | 트래픽 폭증 시점. 부하 테스트 시나리오 기준 |
| `sale_close_at` | TIMESTAMP |  | 생성 | 판매 마감 |  |
| `status` | VARCHAR2(10) | NN | 시스템 | SCHEDULED / ON_SALE / CLOSED / DEPARTED | 판매 가능 여부 판단 |

> **제약·비고** UNIQUE(train_id, run_date) · UNIQUE(run_id, train_id) — seat_inventory 복합 FK의 참조 대상

#### `train_stop` 정차 시각

저장소: Oracle · 기능: TRN-02, RSV-02, CNL-01


운행별 정차역과 도착·출발 시각.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `run_id` | NUMBER | PK, FK → train_run | 생성 | 운행 | 날짜별 시각 변경 대응 |
| `station_id` | VARCHAR2(10) | PK, FK → station | 공공 | 정차역 |  |
| `stop_order` | NUMBER(3) | NN | 생성 | 정차 순서 | route_station.station_order와 매핑 |
| `arrival_time` | TIMESTAMP |  | 생성 | 도착 시각 |  |
| `departure_time` | TIMESTAMP |  | 생성 | 출발 시각 | 출발 직전 취소 판단 기준 |

#### `fare` 운임

저장소: Oracle · 기능: TRN-03, RSV-02


열차 종류·등급·구간별 운임과 적용 기간.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `fare_id` | NUMBER | PK | 생성 |  |  |
| `train_kind` | VARCHAR2(20) |  | 생성 | 열차 종류 |  |
| `seat_grade` | VARCHAR2(10) | NN | 생성 | GENERAL / FIRST | 좌석 등급 코드 통일 |
| `from_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 출발역 |  |
| `to_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 도착역 |  |
| `amount` | NUMBER(10) | NN | 생성 | 운임 | 거리 × 단가로 산출 |
| `valid_from` | DATE | NN | 생성 | 적용 시작 | 운임 변경 이력 대응 |
| `valid_to` | DATE |  | 생성 | 적용 종료 |  |

#### `purchase_limit_policy` 매수 제한 정책

저장소: Oracle · 기능: RSV-02, WL-03


1회·열차별·명절 전체 매수 제한을 코드 수정 없이 조정.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `policy_id` | NUMBER | PK | 생성 |  |  |
| `sale_event_id` | NUMBER | FK → sale_event | 생성 | 적용 행사 (null이면 상시) | PER_HOLIDAY 범위 정의 |
| `scope` | VARCHAR2(20) | NN | 생성 | PER_RESERVATION / PER_RUN / PER_HOLIDAY | 1회·열차별·명절 전체 제한을 코드 수정 없이 조정 |
| `max_seats` | NUMBER(3) | NN | 생성 | 최대 매수 |  |
| `valid_from` | TIMESTAMP |  | 생성 | 적용 시작 | 설·추석별 다른 정책 |
| `valid_to` | TIMESTAMP |  | 생성 | 적용 종료 |  |

> **제약·비고** 매수 집계 대상: HOLD·PAYMENT_PENDING·CONFIRMED (CANCELED·EXPIRED 제외) · 값은 예시 · 한도 값 고르기: 기간 안의 행사 정책 우선, 없으면 상시, 여러 개면 가장 작은 값. PER_RESERVATION은 요청 매수와 값 비교만

#### `sale_event` 명절 예매 행사

저장소: Oracle · 기능: RSV-02, ADM-02


설·추석 등 예매 행사. 연휴 기간과 오픈 일정을 묶어 매수 제한·수수료 정책의 범위가 된다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `sale_event_id` | NUMBER | PK | 생성 |  |  |
| `name` | VARCHAR2(50) | NN | 생성 | 예: 2026 추석 |  |
| `holiday_from` | DATE | NN | 생성 | 연휴 시작 | PER_HOLIDAY 매수 제한의 기간 정의 |
| `holiday_to` | DATE | NN | 생성 | 연휴 끝 |  |
| `sale_open_at` | TIMESTAMP | NN | 생성 | 일반 예매 오픈 | 부하 테스트 시나리오 기준 |
| `priority_open_at` | TIMESTAMP |  | 생성 | 교통약자 사전 예매 오픈 | 확장 시 사용 |
| `status` | VARCHAR2(10) | NN | 시스템 | PLANNED / OPEN / CLOSED |  |

#### `app_policy` 운영 설정

저장소: Oracle · 기능: 전체, ADM-08


팀이 정하는 운영 값(샘플링 비율, 보관 기간, 대기열 처리량 등)을 코드 수정 없이 바꾸는 키-값 설정. 현재 값은 모두 예시.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `policy_key` | VARCHAR2(60) | PK | 생성 | 설정 키 (예: replay.full_record_ratio) | 점(.)으로 영역 구분 |
| `policy_value` | VARCHAR2(200) | NN | 생성 | 설정 값 (문자열로 저장) |  |
| `version` | NUMBER | NN | 시스템 | 변경할 때마다 1 증가 | 캐시 무효화 기준 (policy:cache의 version과 다르면 다시 로딩) |
| `value_type` | VARCHAR2(10) | NN | 생성 | NUMBER / STRING / BOOL | 읽을 때 형 변환 |
| `description` | VARCHAR2(300) |  | 생성 | 설정 설명 |  |
| `updated_by` | NUMBER | FK → admin_user | 시스템 | 마지막 변경 관리자 | 변경 책임 추적 |

> **제약·비고** 애플리케이션 시작 시 로딩 · 값을 바꾸면 version + 1, app_policy_history에 한 행, Redis policy:cache 갱신 · 모델별 임계값·감쇠는 model_run에 두고 여기엔 중복 저장하지 않음 · 값은 모두 예시

#### `app_policy_history` 운영 설정 이력

저장소: Oracle · updated_at 생략 · 기능: ADM-08


app_policy 값이 바뀔 때마다 한 행. 누가 언제 무엇을 바꿨는지와 캐시 버전을 추적한다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `history_id` | NUMBER | PK | 시스템 |  |  |
| `policy_key` | VARCHAR2(60) | FK, NN → app_policy | 시스템 | 설정 키 |  |
| `old_value` | VARCHAR2(200) |  | 시스템 | 변경 전 값 | 처음 생성이면 null |
| `new_value` | VARCHAR2(200) | NN | 시스템 | 변경 후 값 |  |
| `version` | NUMBER | NN | 시스템 | 변경 후 버전 | app_policy.version과 같은 값 |
| `changed_by` | NUMBER | FK → admin_user | 시스템 | 변경한 관리자 | 시스템 변경이면 null |
| `changed_at` | TIMESTAMP | NN | 시스템 | 변경 시각 |  |

> **제약·비고** INSERT만 하는 테이블 (updated_at 생략)

### 4.2 회원·인증

담당: D·E

#### `member` 회원

저장소: Oracle · 기능: AUTH-01·06, QUE-01, ADM-06


회원 계정. ci_hash가 위험 점수·매수 제한의 묶음 단위. 비밀번호는 member_credential로 분리.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `member_id` | NUMBER | PK | 생성 | 회원 ID |  |
| `email` | VARCHAR2(100) | UQ, NN | 생성 | 로그인 ID |  |
| `ci_hash` | VARCHAR2(64) | NN | 생성 | 모의 본인인증 CI 해시 | 위험 점수·매수 제한의 묶음 단위. 다계정이어도 같은 값 |
| `name` | VARCHAR2(50) | NN | 생성 | 이름 | 회원가입·예매자 확인 |
| `phone_enc` | VARCHAR2(200) |  | 생성 | 휴대폰 번호 암호문 (AES-256-GCM) | 알림 발송에 원문이 필요해 해시 대신 암호화 |
| `phone_hash` | VARCHAR2(64) |  | 생성 | 휴대폰 번호 HMAC | 암호문끼리는 비교 불가 → 중복 가입 탐지 |
| `enc_key_id` | VARCHAR2(20) |  | 시스템 | phone_enc 암호화 키 버전 | 키 교체 시 어느 키로 복호화할지 판별 |
| `status` | VARCHAR2(10) | NN | 시스템 | ACTIVE / RESTRICTED / BANNED | 관리자 조치 반영 |
| `created_at` | TIMESTAMP |  | 생성 | 가입 시각 | 신규 계정 판별 (기본 신뢰도 낮음) |
| `withdrawn_at` | TIMESTAMP |  | 시스템 | 탈퇴 시각 | 소프트 삭제. 위험 이력은 보존 기간 후 파기 |

> **제약·비고** ci_hash는 UNIQUE를 걸지 않음. 다계정 어뷰저를 재현하려면 같은 CI가 여러 계정에 있어야 함. · ci_hash는 변경 불가 (본인인증 결과가 바뀌면 신규 회원으로 처리). 그래서 reservation.ci_hash 비정규화가 안전함

#### `payment_method` 결제 수단

저장소: Oracle · 기능: AUTH-07, PAY-01, RISK-02


회원의 결제 수단. method_hash로 여러 계정의 같은 카드를 연결.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `method_id` | NUMBER | PK | 생성 |  |  |
| `member_id` | NUMBER | FK, NN → member | 생성 |  |  |
| `method_hash` | VARCHAR2(64) |  | 생성 | 카드 번호 해시 | 묶음 단위. 여러 계정이 같은 카드를 쓰면 연결 |
| `method_type` | VARCHAR2(10) | NN | 생성 | CARD / ACCOUNT / PAY |  |
| `card_last4` | CHAR(4) |  | 생성 | 끝 4자리 | 화면 표시용 |

#### `member_device` 기기

저장소: Oracle · 기능: AUTH-03, RISK-02


기기 지문과 회원의 N:M 연결. 한 기기 다계정 탐지.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `device_hash` | VARCHAR2(64) | PK | 생성 | 기기 지문 해시 | 묶음 단위 |
| `member_id` | NUMBER | PK, FK → member | 생성 |  | 한 기기 다계정 탐지 (N:M) |
| `first_seen_at` | TIMESTAMP |  | 생성 | 최초 사용 |  |
| `last_seen_at` | TIMESTAMP |  | 시스템 | 최근 사용 |  |

#### `member_credential` 로그인 인증 정보

저장소: Oracle · 기능: AUTH-01·03


Argon2id 비밀번호 해시와 잠금 상태. 회원 프로필과 분리해 해시가 일반 조회에 섞이지 않게 한다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `member_id` | NUMBER | PK, FK → member | 생성 | 회원 (1:1) | 프로필과 인증 정보 분리 |
| `password_hash` | VARCHAR2(255) | NN | 시스템 | Argon2id PHC 문자열 ($argon2id$v=19$m=19456,t=2,p=1$salt$hash) | 솔트·파라미터가 문자열 안에 있어 salt 컬럼 불필요 |
| `hash_algo` | VARCHAR2(20) | NN | 시스템 | ARGON2ID | 알고리즘 교체 대비 |
| `hash_params` | VARCHAR2(40) | NN | 시스템 | m=19456,t=2,p=1 | 정책 파라미터와 다르면 재해싱 대상 |
| `pepper_key_id` | VARCHAR2(20) |  | 시스템 | 페퍼 키 버전 | 페퍼는 DB 밖(환경변수·KMS)에 두고 버전만 기록 |
| `needs_rehash` | CHAR(1) | NN | 시스템 | Y/N | 다음 로그인 성공 시 새 파라미터로 재해싱 |
| `failed_count` | NUMBER(3) | NN | 시스템 | 연속 로그인 실패 횟수 | 계정 잠금 기준 |
| `locked_until` | TIMESTAMP |  | 시스템 | 잠금 해제 시각 | 무차별 대입 방지 |
| `password_changed_at` | TIMESTAMP |  | 시스템 | 비밀번호 변경 시각 | 변경 직후 대량 구매 = 탈취 의심 신호 |
| `last_login_at` | TIMESTAMP |  | 시스템 | 마지막 로그인 | 휴면 계정 갑작스런 활동 탐지 |

> **제약·비고** 파라미터 OWASP 권장값 m=19456(19MiB), t=2, p=1로 시작 · 해시 1회 약 19MiB → 동시 검증 수 세마포어 제한 · 없는 계정도 더미 해시로 검증해 응답 시간 동일하게

#### `identity_verification` 본인인증 이력

저장소: Oracle · 기능: AUTH-02


모의 본인인증 이력. 같은 CI로 여러 계정을 인증하는 다계정 신호를 잡는다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `verification_id` | NUMBER | PK | 생성 |  |  |
| `member_id` | NUMBER | FK, NN → member | 생성 | 인증한 계정 |  |
| `ci_hash` | VARCHAR2(64) |  | 생성 | 인증 결과 CI 해시 | 같은 CI로 짧은 기간 여러 계정 인증 = 다계정 신호 |
| `provider` | VARCHAR2(20) | NN | 생성 | MOCK_PASS / MOCK_PHONE | 모의 본인인증 |
| `result` | VARCHAR2(10) | NN | 시스템 | SUCCESS / FAIL |  |
| `verified_at` | TIMESTAMP |  | 생성 | 인증 시각 |  |

#### `auth_session` 로그인 세션

저장소: Oracle · 기능: AUTH-03·04·05, ADM-06


로그인 세션과 리프레시 토큰. session_id가 CCTV 녹화 단위이자 행동 로그 연결 키.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `session_id` | VARCHAR2(36) | PK | 시스템 | UUID | CCTV 녹화 단위. replay_session, behavior_log와 같은 키 |
| `member_id` | NUMBER | FK → member | 생성 |  |  |
| `actor_type` | VARCHAR2(10) | NN | 시스템 | MEMBER / ADMIN | 관리자 세션은 녹화 대상에서 제외 |
| `admin_id` | NUMBER | FK → admin_user | 시스템 | 관리자 세션일 때 |  |
| `refresh_token_hash` | VARCHAR2(64) | NN | 시스템 | 리프레시 토큰 SHA-256 | 고엔트로피 무작위 토큰이라 Argon2 불필요. 매 갱신마다 검증하므로 빠른 해시 |
| `token_family_id` | VARCHAR2(36) | NN | 시스템 | 토큰 계열 ID | 회전된 옛 토큰 재사용 시 계열 전체 폐기 (탈취 탐지) |
| `device_hash` | VARCHAR2(64) |  | 생성 | 기기 지문 해시 |  |
| `ip_hash` | VARCHAR2(64) |  | 생성 | IP 해시 |  |
| `user_agent` | VARCHAR2(300) |  | 생성 |  |  |
| `created_at` | TIMESTAMP |  | 시스템 | 로그인 시각 |  |
| `expires_at` | TIMESTAMP | NN | 시스템 | 만료 시각 |  |
| `revoked_at` | TIMESTAMP |  | 시스템 | 강제 종료 시각 |  |
| `revoke_reason` | VARCHAR2(20) |  | 시스템 | LOGOUT / ROTATED / ADMIN / SUSPICIOUS | 관리자 조치로 끊긴 세션 구분 |

> **제약·비고** member_id와 admin_id 중 actor_type에 맞는 하나만 채움: CHECK((actor_type = 'MEMBER' AND member_id IS NOT NULL AND admin_id IS NULL) OR (actor_type = 'ADMIN' AND admin_id IS NOT NULL AND member_id IS NULL))

#### `admin_user` 관리자

저장소: Oracle · 기능: AUTH-05, ADM 전체


관리자 계정과 역할. 녹화 마스킹 해제·내보내기는 SUPERVISOR만.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `admin_id` | NUMBER | PK | 생성 | 관리자 ID | admin_action, suspicious_case, case_marker, replay_access_log, evidence_snapshot이 참조 |
| `login_id` | VARCHAR2(50) | UQ, NN | 생성 | 로그인 ID |  |
| `password_hash` | VARCHAR2(255) | NN | 시스템 | Argon2id PHC 문자열 | 회원과 같은 정책 |
| `role` | VARCHAR2(20) | NN | 생성 | VIEWER / REVIEWER / SUPERVISOR | 마스킹 해제·내보내기는 SUPERVISOR만 |
| `mfa_secret_enc` | VARCHAR2(200) |  | 시스템 | TOTP 비밀키 암호문 | 녹화·개인정보 접근 계정이라 2단계 인증 자리 확보 (구현은 확장) |
| `status` | VARCHAR2(10) | NN | 시스템 | ACTIVE / DISABLED |  |
| `last_login_at` | TIMESTAMP |  | 시스템 | 마지막 로그인 |  |

#### `login_attempt` 로그인 시도

저장소: MongoDB · 🅱 빅데이터 · 기능: AUTH-03·05


모든 로그인 시도. 크리덴셜 스터핑 탐지와 Argon2 검증 부하 측정.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `_id` | ObjectId | PK | 시스템 |  |  |
| `member_id` | Long | FK → member | 생성 | 대상 계정 (없으면 null) |  |
| `actor_type` | String |  | 시스템 | MEMBER / ADMIN | 관리자 로그인도 같은 방식으로 기록 |
| `admin_id` | Long | FK → admin_user | 시스템 | 관리자 로그인일 때 | 관리자 계정 무차별 대입 탐지 |
| `email_hash` | String |  | 생성 | 입력한 이메일 해시 | 없는 계정 대상 시도도 추적 → 크리덴셜 스터핑 탐지 |
| `result` | String |  | 생성 | SUCCESS / WRONG_PASSWORD / LOCKED / NOT_FOUND / RATE_LIMITED |  |
| `ip_hash` | String |  | 생성 |  | 한 IP에서 여러 계정 시도 탐지 |
| `device_hash` | String |  | 생성 |  |  |
| `user_agent` | String |  | 생성 |  |  |
| `session_id` | String | FK → auth_session | 시스템 | 성공 시 발급된 세션 |  |
| `verify_ms` | Int |  | 시스템 | Argon2 검증 소요 시간 | 예매 오픈 직전 로그인 폭주 시 CPU·메모리 병목 측정 |
| `ts` | Date |  | 생성 | 시도 시각 | TTL 기준 |

> **제약·비고** TTL { ts: 1 } · 인덱스 { ip_hash: 1, ts: -1 }, { email_hash: 1, ts: -1 }

### 4.3 예약·결제·취소

담당: B·C·D

#### `seat_inventory` 좌석 구간 재고 ★

저장소: Oracle · 기능: RSV-02·03·05, PAY-01·02, CNL-02, WL-03


운행·좌석·구간 단위 점유. PK와 열차 일치 복합 FK로 중복 예약과 다른 열차 좌석 판매를 DB가 막고, HOLD는 hold_expires_at이 지나면 다음 사람이 이어받는다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `run_id` | NUMBER | PK, FK → train_run | 시스템 | 운행 |  |
| `train_id` | NUMBER | NN | 시스템 | 좌석이 속한 열차 (= train_run.train_id) | 복합 FK 두 개로 다른 열차 좌석 판매를 DB가 차단 |
| `seat_id` | NUMBER | PK, FK → seat | 시스템 | 좌석 |  |
| `segment_no` | NUMBER(3) | PK | 시스템 | 구간 번호 (역 순번 i → i+1 = i) | 구간 단위 점유 |
| `reservation_id` | NUMBER | FK, NN → reservation | 시스템 | 점유 예약 | 반환 시 이 예약 행만 삭제 |
| `status` | VARCHAR2(10) | NN | 시스템 | HOLD / SOLD | 선점과 확정 구분 |
| `hold_expires_at` | TIMESTAMP |  | 시스템 | HOLD 만료 시각 (SOLD면 null) | = reservation.pay_deadline_at 복사. Redis TTL이 끝난 뒤 남은 HOLD 행을 다른 사람이 이어받는 기준 |

> **제약·비고** PK(run_id, seat_id, segment_no) · FK(train_id, seat_id) → seat(train_id, seat_id) · FK(run_id, train_id) → train_run(run_id, train_id) — 같은 대상의 단일 FK는 DDL에서 생략, 편집기 선만 유지 · CHECK((status = 'HOLD' AND hold_expires_at IS NOT NULL) OR (status = 'SOLD' AND hold_expires_at IS NULL)) · 인덱스는 PK·복합 FK만 (hold_expires_at으로 찾는 쿼리가 없어 부분 인덱스를 두지 않음) · 확장 과제를 안 하면 segment_no = 0 · 예약 하나의 행 수 K = seat_count × 구간 수

#### `reservation` 예약

저장소: Oracle · 기능: RSV 전체, PAY 전체, CNL-02, WL-03


예약 상태 머신의 중심. reservation_id가 Kafka 파티션 키.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `reservation_id` | NUMBER | PK | 시스템 | 예약 ID | Kafka 파티션 키 → 상태 변경 순서 보장 |
| `member_id` | NUMBER | FK, NN → member | 생성 | 예약자 |  |
| `ci_hash` | VARCHAR2(64) | NN | 생성 | 예약 시점 CI | 매수 제한 집계를 조인 없이 빠르게 |
| `run_id` | NUMBER | FK, NN → train_run | 생성 | 운행 |  |
| `from_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 승차역 |  |
| `to_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 하차역 |  |
| `seat_count` | NUMBER(2) | NN | 생성 | 예약 매수 | 1회 구매 매수 위험 신호 |
| `total_amount` | NUMBER(10) | NN | 시스템 | 예약 시점 금액 | 운임 변경과 무관하게 보존 |
| `status` | VARCHAR2(16) | NN | 시스템 | HOLD / PAYMENT_PENDING / CONFIRMED / CANCELED / EXPIRED | 상태 머신 |
| `channel` | VARCHAR2(10) | NN | 생성 | WEB / APP | 채널별 트래픽 분석 |
| `source` | VARCHAR2(10) | NN | 시스템 | NORMAL / WAITLIST | 취소표 재배정 여부 |
| `held_at` | TIMESTAMP | NN | 시스템 | 좌석 선점 시각 |  |
| `pay_deadline_at` | TIMESTAMP | NN | 시스템 | 결제 기한 (마감 시각의 원천) | seat_inventory.hold_expires_at, seat:hold TTL, saga_instance.timeout_at, waitlist.offer_deadline_at은 이 값을 한 번 계산해 복사. 바꿀 땐 다섯 곳을 같은 트랜잭션·같은 값으로 |
| `confirmed_at` | TIMESTAMP |  | 시스템 | 확정 시각 | 구매→취소 시간 계산 시작점 |
| `canceled_at` | TIMESTAMP |  | 시스템 | 취소 시각 |  |
| `idempotency_key` | VARCHAR2(64) | NN | 시스템 | 클라이언트 요청 키 | 따닥 클릭·재시도 시 중복 예약 방지. UNIQUE 범위는 회원 단위 |
| `version` | NUMBER | NN | 시스템 | 낙관적 락 버전 | 락 방식 성능 비교 실험용 |

> **제약·비고** CREATE INDEX ix_resv_hold_due ON reservation (CASE WHEN status = 'HOLD' THEN pay_deadline_at END); HOLD 만료 스캐너용 (조회도 같은 CASE 식). 결제 중 예약은 payment.next_check_at으로 따로 조회 · UNIQUE(member_id, idempotency_key) · 마감 시각 원천 = pay_deadline_at · 결제 시작 연장은 GREATEST로 늘리기만 · 네이티브 UPDATE는 updated_at·version 갱신 · 결제 실패는 별도 상태 없이 EXPIRED · from·to 역은 운행의 정차역이고 from 순번 < to 순번 (애플리케이션 검증)

#### `reservation_seat` 예약 좌석

저장소: Oracle · 기능: RSV-02·04, CNL-01


한 예약에 포함된 좌석과 좌석별 금액.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `reservation_id` | NUMBER | PK, FK → reservation | 시스템 |  |  |
| `seat_id` | NUMBER | PK, FK → seat | 시스템 |  | 한 예약에 여러 좌석 |
| `ticket_no` | VARCHAR2(20) | UQ, NN | 시스템 | 승차권 번호 | 예매 내역·반환·신고 대상 식별 |
| `passenger_type` | VARCHAR2(10) | NN | 생성 | ADULT / CHILD / SENIOR | 운임 할인 확장 |
| `amount` | NUMBER(10) | NN | 시스템 | 좌석별 금액 | 부분 취소 대응 |

> **제약·비고** ticket_no는 선점 시 발급하지만 확정(CONFIRMED) 전에는 효력 없음

#### `reservation_history` 예약 상태 이력

저장소: Oracle · updated_at 생략 · 기능: 상태를 바꾸는 모든 기능


상태 전이 기록. 순서 보장과 잘못된 전이를 테스트로 증명하는 근거.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `history_id` | NUMBER | PK | 시스템 |  |  |
| `reservation_id` | NUMBER | FK, NN → reservation | 시스템 |  |  |
| `from_status` | VARCHAR2(16) |  | 시스템 | 이전 상태 | 첫 기록이면 null. 허용값은 비고의 CHECK |
| `to_status` | VARCHAR2(16) | NN | 시스템 | HOLD / PAYMENT_PENDING / CONFIRMED / CANCELED / EXPIRED | 변경 상태. 잘못된 전이(CANCELED→CONFIRMED) 탐지 |
| `event_id` | VARCHAR2(36) |  | 시스템 | 원인 Kafka 이벤트 | 어떤 이벤트가 바꿨는지 추적 |
| `actor_type` | VARCHAR2(10) | NN | 시스템 | USER / SYSTEM / ADMIN | 누가 상태를 바꿨는지 |
| `changed_at` | TIMESTAMP | NN | 시스템 | 변경 시각 | 순서 보장 검증 근거 |

> **제약·비고** event_id는 outbox_event를 논리 참조 (outbox 정리 후에도 이력 보존을 위해 FK 미설정) · INSERT만 하는 테이블 (updated_at 생략) · CHECK(from_status IN ('HOLD', 'PAYMENT_PENDING', 'CONFIRMED', 'CANCELED', 'EXPIRED'))

#### `payment` 결제

저장소: Oracle · 기능: PAY 전체, CNL-03


PG 결제 요청과 결과. 실패 시 Saga 보상 트리거.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `payment_id` | NUMBER | PK | 시스템 |  |  |
| `reservation_id` | NUMBER | FK, NN → reservation | 시스템 |  |  |
| `method_id` | NUMBER | FK, NN → payment_method | 생성 | 결제 수단 | 결제 수단 단위 위험 점수 |
| `amount` | NUMBER(10) | NN | 시스템 | 결제 금액 |  |
| `status` | VARCHAR2(10) | NN | 시스템 | REQUESTED / APPROVED / FAILED / REFUNDED | Saga 보상 트리거 |
| `pg_tx_id` | VARCHAR2(64) |  | 시스템 | PG 거래 ID | PG 대사·환불 |
| `fail_reason` | VARCHAR2(100) |  | 시스템 | 실패 사유 | 트러블슈팅 |
| `idempotency_key` | VARCHAR2(64) | NN | 시스템 | 결제 요청 키 | 같은 요청 재시도 방지. UNIQUE 범위는 예약 단위 |
| `requested_at` | TIMESTAMP | NN | 시스템 | 요청 시각 | 결제 소요 시간 측정 |
| `approved_at` | TIMESTAMP |  | 시스템 | 승인 시각 |  |
| `next_check_at` | TIMESTAMP |  | 시스템 | PG 결과 조회 예정 시각 | 결제 시작 때 마감 값으로 시작. PG 결과를 모르면 payment.pg_recheck_sec만큼 미뤄 스캐너가 같은 건을 매 회차 다시 보지 않게 함 |

> **제약·비고** UNIQUE(reservation_id, idempotency_key) · 예약당 승인 1건: CREATE UNIQUE INDEX ux_payment_approved ON payment (CASE WHEN status = 'APPROVED' THEN reservation_id END) · payment.status=REFUNDED는 cancellation.refund_status=DONE과 같은 트랜잭션에서 갱신 · CREATE INDEX ix_pay_recheck ON payment (CASE WHEN status = 'REQUESTED' THEN next_check_at END); 결제 결과 확인 스캐너용 · payment 상태를 바꾸는 트랜잭션은 그 예약의 reservation 행을 먼저 잠금 · FAILED 뒤 승인이 오면 APPROVED로 기록하고 전액 환불

#### `cancellation` 취소·환불

저장소: Oracle · 기능: CNL-02·03·04, PAY-05, RISK-02


취소 유형과 시간 지표. 위험 신호와 재판매율 성과 지표의 원천.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `cancel_id` | NUMBER | PK | 시스템 |  |  |
| `reservation_id` | NUMBER | FK, NN → reservation | 시스템 |  |  |
| `cancel_type` | VARCHAR2(10) | NN | 시스템 | USER / PAY_FAILED / ADMIN | USER = 사용자 확정 취소(수수료), ADMIN = 운영 취소(수수료 0), PAY_FAILED = 늦은 승인 환불(전액). 만료는 돈이 오가지 않아 cancellation을 만들지 않음 |
| `canceled_at` | TIMESTAMP | NN | 생성 | 취소 시각 |  |
| `minutes_after_purchase` | NUMBER |  | 시스템 | 확정 후 취소까지 분 | 구매 후 취소 시간 위험 신호 |
| `minutes_before_departure` | NUMBER |  | 시스템 | 출발 전 남은 분 | 노쇼성 직전 취소 탐지, 기획 배경 통계와 연결 |
| `refund_amount` | NUMBER(10) | NN | 시스템 | 환불액 |  |
| `cancel_fee` | NUMBER(10) | NN | 시스템 | 취소 수수료 | 노쇼 억제 수단 |
| `fee_policy_id` | NUMBER | FK → cancel_fee_policy | 생성 | 적용 수수료 정책 |  |
| `refund_pg_tx_id` | VARCHAR2(64) |  | 시스템 | PG 환불 거래 ID | 환불 대사 |
| `refund_status` | VARCHAR2(10) |  | 시스템 | REQUESTED / DONE / FAILED | 환불 실패 재시도 |
| `resold_yn` | CHAR(1) | NN | 시스템 | 재판매 여부 | INSERT 때 N, 운행 출발 후 배치가 갱신 |

> **제약·비고** refund_status·resold_yn을 나중에 UPDATE하므로 INSERT만 하는 테이블이 아님

#### `cancel_fee_policy` 취소 수수료 정책

저장소: Oracle · 기능: CNL-01·02


출발 전 남은 시간 구간별 수수료율. 기획 배경의 노쇼 증가에 대한 정책 수단.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `fee_policy_id` | NUMBER | PK | 생성 |  |  |
| `sale_event_id` | NUMBER | FK → sale_event | 생성 | 적용 행사 (null이면 상시) | 명절만 수수료 강화 가능 |
| `min_minutes_before` | NUMBER |  | 생성 | 출발 전 구간 시작(분) |  |
| `max_minutes_before` | NUMBER |  | 생성 | 출발 전 구간 끝(분) |  |
| `fee_rate` | NUMBER(4,3) |  | 생성 | 수수료율 | 노쇼 억제 |
| `after_departure` | CHAR(1) | NN | 생성 | 출발 후 반환 여부 | 출발 후 반환은 재판매 불가 구간 |

> **제약·비고** 예시값이며 실제 코레일 기준이 아님 · 구간은 [min, max) 반열림, max가 null이면 상한 없음 · 같은 행사 안에서 구간이 겹치면 안 됨 · after_departure=Y 행은 출발 후 반환에 적용

#### `purchase_counter` 매수 카운터

저장소: Oracle · 기능: RSV-02·03, CNL-02, WL-03


CI별 보유 매수. 매수 제한의 최종 판정을 조건부 UPDATE 한 번으로 원자적으로 처리하고, Redis 카운터가 사라지면 여기서 복원한다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `scope` | VARCHAR2(20) | PK | 시스템 | PER_RUN / PER_HOLIDAY | 열차별·명절 전체 제한을 한 테이블에서 |
| `scope_ref_id` | NUMBER | PK | 시스템 | run_id 또는 sale_event_id | scope에 따라 가리키는 대상이 달라 FK 미설정 |
| `ci_hash` | VARCHAR2(64) | PK | 시스템 | CI 해시 | 계정이 아닌 사람 단위 합산 |
| `seat_count` | NUMBER(3) | NN | 시스템 | 현재 보유 매수 | 조건부 UPDATE로 한도 초과 차단 |

> **제약·비고** CHECK(seat_count >= 0) · 증가는 reservation INSERT와 같은 트랜잭션, 감소는 예약 상태 UPDATE가 1행일 때만, 한 트랜잭션의 카운터 행은 (scope, scope_ref_id, ci_hash) 정렬 순서로 갱신, sale_event_id가 NULL이면 PER_HOLIDAY 미적용

### 4.4 이벤트 신뢰성

담당: C

#### `outbox_event` Outbox 이벤트

저장소: Oracle · 기능: EVT-01·02


비즈니스 변경과 같은 트랜잭션에서 INSERT → 이벤트 유실 0건.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `event_id` | VARCHAR2(36) | PK | 시스템 | UUID | 소비자 중복 판별 키 |
| `seq` | NUMBER | UQ, NN | 시스템 | 발행 순서 번호 (outbox_seq) | 같은 예약의 이벤트는 reservation 행 락 뒤에 INSERT되므로 seq가 커밋 순서와 같음. 정렬 기준 |
| `aggregate_type` | VARCHAR2(20) | NN | 시스템 | RESERVATION / PAYMENT / WAITLIST | 토픽 라우팅 |
| `aggregate_id` | VARCHAR2(36) | NN | 시스템 | reservation_id, case_id 등 | 숫자·UUID 키 모두 수용 |
| `partition_key` | VARCHAR2(64) | NN | 시스템 | Kafka 메시지 키 | 결제·취소 이벤트도 reservation_id를 키로 써야 같은 예약의 이벤트 순서 보장 |
| `shard_no` | NUMBER(2) | NN | 시스템 | 릴레이 샤드 번호 0~15. INSERT 문에서 ORA_HASH(TO_CHAR(:partition_key), 15)로 계산 | 같은 예약 이벤트는 항상 같은 샤드 → 한 릴레이만 순서대로 발행. 가상 컬럼 대신 일반 컬럼으로 두어 부분 인덱스와 실행 계획을 단순하게 |
| `event_type` | VARCHAR2(30) | NN | 시스템 | SEAT_HELD, PAYMENT_APPROVED … |  |
| `payload` | CLOB | NN | 시스템 | 이벤트 본문 (JSON) | 스키마 변경에 유연 |
| `status` | VARCHAR2(10) | NN | 시스템 | PENDING / PUBLISHED / FAILED | FAILED = retry_count가 outbox.max_retry에 도달. 그 샤드를 멈추고 관리자 확인 후 PENDING으로 되돌림 |
| `retry_count` | NUMBER(2) | NN | 시스템 | 재시도 횟수 | 무한 재시도 방지 |
| `created_at` | TIMESTAMP | NN | 시스템 | 생성 시각 |  |
| `published_at` | TIMESTAMP |  | 시스템 | 발행 시각 | 발행 지연 모니터링 |

> **제약·비고** CREATE SEQUENCE outbox_seq CACHE 1000; · CHECK(shard_no BETWEEN 0 AND 15) · CREATE INDEX ix_outbox_pending ON outbox_event (CASE WHEN status IN ('PENDING', 'FAILED') THEN shard_no END, CASE WHEN status IN ('PENDING', 'FAILED') THEN seq END); FAILED도 포함 — 샤드 맨 앞이 FAILED면 그 샤드 정지 · 샤드 담당은 Redis 리스 relay:shard:{n} · 맨 앞 N개 rowid만 FOR UPDATE WAIT (SKIP LOCKED 금지), 발행 실패 시 그 샤드 회차 즉시 중단 · partition_key는 항상 문자열로 바인딩 · status·retry_count를 UPDATE하므로 INSERT만 하는 테이블이 아님

#### `processed_event` 처리 완료 (Inbox)

저장소: Oracle · updated_at 생략 · 기능: EVT-03, WL-03


Kafka at-least-once 대응. 이미 처리한 이벤트는 skip → 멱등 소비.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `consumer_group` | VARCHAR2(50) | PK | 시스템 | 소비자 그룹 | 그룹마다 독립 처리 |
| `event_id` | VARCHAR2(36) | PK | 시스템 | 이벤트 ID | 이미 있으면 skip → 멱등 소비 |
| `processed_at` | TIMESTAMP | NN | 시스템 | 처리 시각 | 정리 배치 기준 (inbox.retention_days) |

> **제약·비고** INSERT만 하는 테이블 (updated_at 생략) · CREATE INDEX ix_inbox_processed ON processed_event (processed_at); 정리 배치용 · inbox.retention_days(8일)가 지난 행 삭제. Kafka 보관(기본 7일)보다 길게 둬야 재전달 중복을 막음

#### `saga_instance` Saga 진행 상태

저장소: Oracle · 기능: EVT-05, RSV-02·03, PAY 전체


예약→결제→확정 흐름의 진행 단계. 중단 지점에서 재개하거나 보상.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `saga_id` | VARCHAR2(36) | PK | 시스템 |  |  |
| `reservation_id` | NUMBER | FK, NN → reservation | 시스템 |  |  |
| `current_step` | VARCHAR2(20) | NN | 시스템 | SEAT_HELD / PAYING / CONFIRMED | 마지막으로 도달한 단계. 결과는 status (보상은 한 트랜잭션이라 진행 중 단계를 따로 두지 않음) |
| `status` | VARCHAR2(12) | NN | 시스템 | RUNNING / DONE / COMPENSATED | 좌석 복구율 100% 검증 |
| `timeout_at` | TIMESTAMP |  | 시스템 | 다음 단계 제한 시각 | = reservation.pay_deadline_at 복사. 지나면 보상 실행 |
| `updated_at` | TIMESTAMP |  | 시스템 | 갱신 시각 | 멈춘 Saga 탐지 |

> **제약·비고** current_step = 마지막 단계, status = 결과 · 선점 SEAT_HELD·RUNNING · 결제 시작 PAYING + timeout_at 연장 · 확정 CONFIRMED·DONE · 해제는 status만 COMPENSATED, current_step 유지

### 4.5 취소표·알림

담당: D·C

#### `waitlist` 취소표 대기

저장소: Oracle · 기능: WL 전체, PAY-01·02


취소 발생 시 신청 순서대로 배정 → 알림 → 제한 시간 내 결제.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `waitlist_id` | NUMBER | PK | 생성 |  |  |
| `member_id` | NUMBER | FK, NN → member | 생성 |  |  |
| `run_id` | NUMBER | FK, NN → train_run | 생성 |  |  |
| `from_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 희망 승차역 | 구간 일치 취소표만 배정 |
| `to_station_id` | VARCHAR2(10) | FK, NN → station | 공공 | 희망 하차역 |  |
| `seat_grade` | VARCHAR2(10) | NN | 생성 | GENERAL / FIRST | 희망 등급. 좌석 등급 코드 통일 |
| `seat_count` | NUMBER(2) | NN | 생성 | 희망 매수 |  |
| `requested_at` | TIMESTAMP | NN | 생성 | 신청 시각 | 대기 순서 기준 |
| `offered_at` | TIMESTAMP |  | 시스템 | 배정 제안 시각 |  |
| `offer_deadline_at` | TIMESTAMP |  | 시스템 | 결제 기한 | = 제안 예약의 pay_deadline_at 복사. 결제 시작 때 함께 연장 |
| `offered_reservation_id` | NUMBER | FK → reservation | 시스템 | 제안된 예약 |  |
| `status` | VARCHAR2(10) | NN | 시스템 | WAITING / OFFERED / ACCEPTED / EXPIRED / CANCELED / SKIPPED | SKIPPED = 제안 시 매수 한도 초과로 건너뜀 |

> **제약·비고** CREATE INDEX ix_waitlist_next ON waitlist (run_id, status, requested_at); 다음 대기자 조회용 · 제안 예약의 idempotency_key = 'wl:' || waitlist_id (신청 하나에 제안 하나) · 대기자 우선: 취소로 풀린 좌석은 seat:hold를 REOFFER 값으로 잠시 막고 먼저 제안 · 제안 예약도 purchase_counter 증가, 한도에 걸리면 SKIPPED

#### `notification` 알림

저장소: Oracle · 기능: EVT-06, WL-04


취소표 배정·결제 독촉·만료 알림.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `notification_id` | NUMBER | PK | 시스템 |  |  |
| `member_id` | NUMBER | FK, NN → member | 시스템 |  |  |
| `type` | VARCHAR2(20) | NN | 시스템 | WAITLIST_OFFER / PAY_REMINDER / EXPIRED / WAITLIST_SKIPPED / REFUNDED | 취소표 건너뜀(매수 한도 초과)·환불 완료 안내 |
| `ref_type` | VARCHAR2(20) | NN | 시스템 | RESERVATION / WAITLIST / CASE | ref_id가 어느 테이블을 가리키는지 |
| `ref_id` | NUMBER |  | 시스템 | 관련 예약·대기 ID |  |
| `channel` | VARCHAR2(10) | NN | 시스템 | PUSH / EMAIL / IN_APP | 채널 추가 확장 |
| `sent_at` | TIMESTAMP |  | 시스템 | 발송 시각 | 알림→결제 전환 분석 |
| `read_at` | TIMESTAMP |  | 시스템 | 확인 시각 |  |

### 4.6 위험 점수·관리자

담당: D

#### `risk_score` 위험 점수 (현재값)

저장소: Oracle · 기능: RISK-02·03, QUE-01, ADM-04·06


계정이 아닌 CI·결제수단·기기 단위 위험 점수. final_score만큼 대기열 순번을 뒤로.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `subject_type` | VARCHAR2(10) | PK | 시스템 | CI / PAYMENT / DEVICE | 계정이 아닌 식별 단위 |
| `subject_hash` | VARCHAR2(64) | PK | 시스템 | 해당 해시 |  |
| `total_purchase_seats` | NUMBER |  | 시스템 | 누적 구매 매수 | 신호 |
| `max_single_purchase` | NUMBER |  | 시스템 | 1회 최대 매수 | 신호 |
| `cancel_ratio` | NUMBER(4,3) |  | 시스템 | 취소 비율 | 신호 |
| `avg_minutes_to_cancel` | NUMBER |  | 시스템 | 평균 구매→취소 시간 | 신호 |
| `last_minute_cancel_cnt` | NUMBER |  | 시스템 | 출발 직전 취소 수 | 신호 |
| `linked_account_cnt` | NUMBER |  | 시스템 | 연결된 계정 수 | 다계정 신호 |
| `avg_request_interval_ms` | NUMBER |  | 시스템 | 평균 요청 간격 | 매크로 신호 |
| `account_age_days` | NUMBER |  | 시스템 | 가장 오래된 계정 나이 | 신규 계정 기본 신뢰도 |
| `rule_score` | NUMBER(5,2) |  | 시스템 | 규칙 기반 점수 |  |
| `iforest_score` | NUMBER(5,2) |  | 시스템 | Isolation Forest 점수 | 두 방식 비교 |
| `final_score` | NUMBER(5,2) | NN | 시스템 | 최종 점수 (0~100) | 대기열 순번 조정량 |
| `reasons` | CLOB |  | 시스템 | 점수 근거 목록 (JSON) | 운영 원칙: 근거 기록 |
| `model_version` | VARCHAR2(20) | FK → model_run | 시스템 | 산정 모델 버전 | 모델 교체 시 비교 |
| `calculated_at` | TIMESTAMP | NN | 시스템 | 산정 시각 | 감쇠 기준 |
| `review_status` | VARCHAR2(10) | NN | 시스템 | NONE / PENDING / CONFIRMED / CLEARED | 높은 점수는 검토 후 조치 |

> **제약·비고** 회원 대기열 패널티 = 연결된 CI·결제수단·기기 점수 중 최댓값

#### `admin_action` 관리자 조치

저장소: Oracle · 기능: ADM-06·07


위험 사용자 검토·제재 기록. 감사 추적.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `action_id` | NUMBER | PK | 시스템 |  |  |
| `admin_id` | NUMBER | FK, NN → admin_user | 시스템 | 조치자 | 감사 추적 |
| `subject_type` | VARCHAR2(10) | NN | 시스템 | CI / PAYMENT / DEVICE | 대상 유형 코드 통일 (CHECK) |
| `subject_hash` | VARCHAR2(64) | NN | 시스템 | 대상 해시 |  |
| `action_type` | VARCHAR2(20) | NN | 시스템 | CONFIRM / CLEAR / RESTRICT / BAN / CANCEL_RESERVATION / RESOLVE_PAYMENT / UNMASK | 예약 대상 조치는 그 예약의 CI를 subject로 기록하고 reservation_id를 채움. UNMASK는 위험 사용자 목록의 마스킹 해제 |
| `memo` | VARCHAR2(500) |  | 시스템 | 사유 |  |
| `reservation_id` | NUMBER |  | 시스템 | 관련 예약 (예약 대상 조치일 때) | 강제 취소·결제 결과 수동 처리 추적. NULL 허용이라 FK는 비고로 선언 |
| `acted_at` | TIMESTAMP | NN | 시스템 | 조치 시각 |  |

> **제약·비고** 회원 상태 변경(RESTRICT·BAN)은 member.status와 같은 트랜잭션에서 갱신 · FK(reservation_id) → reservation(reservation_id) · CANCEL_RESERVATION·RESOLVE_PAYMENT는 reservation_id 필수: CHECK(action_type NOT IN ('CANCEL_RESERVATION', 'RESOLVE_PAYMENT') OR reservation_id IS NOT NULL)

#### `model_run` 모델 학습 기록

저장소: Oracle · 기능: RISK-02·04


규칙·Isolation Forest 모델 버전별 파라미터와 성능. 발표용 수치의 원천.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `model_version` | VARCHAR2(20) | PK | 시스템 | 예: iforest-2026.11-01 | risk_score.model_version이 참조 |
| `algorithm` | VARCHAR2(20) | NN | 시스템 | RULE / IFOREST | 두 방식 비교 |
| `params` | CLOB |  | 시스템 | contamination, n_estimators 등 (JSON) | 재현 |
| `train_source` | VARCHAR2(100) |  | 시스템 | 학습 데이터 범위 |  |
| `trained_at` | TIMESTAMP | NN | 시스템 |  |  |
| `precision_score` | NUMBER(4,3) |  | 시스템 | 정밀도 | 발표용 성능 수치 |
| `recall_score` | NUMBER(4,3) |  | 시스템 | 재현율 |  |
| `f1_score` | NUMBER(4,3) |  | 시스템 | F1 |  |
| `threshold` | NUMBER(5,2) |  | 시스템 | 케이스 자동 생성 임계값 | suspicious_case 트리거 기준 |
| `decay_half_life_days` | NUMBER(4) |  | 시스템 | 점수 반감기 (일) | 기간 경과 시 점수 감소 파라미터 |
| `is_active` | CHAR(1) | NN | 시스템 | 운영 적용 여부 |  |

> **제약·비고** is_active = Y는 한 번에 하나

#### `suspicious_case` 의심 케이스

저장소: Oracle · 기능: RISK-04, ADM-05·06


위험 점수·규칙·관리자 지정으로 열리는 조사 건. 되돌아볼 시간 범위와 판정을 기록.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `case_id` | NUMBER | PK | 시스템 |  |  |
| `subject_type` | VARCHAR2(10) | NN | 시스템 | CI / PAYMENT / DEVICE | 계정이 아닌 묶음 단위 |
| `subject_hash` | VARCHAR2(64) | NN | 시스템 |  |  |
| `trigger_type` | VARCHAR2(20) | NN | 시스템 | RISK_THRESHOLD / RULE / MODEL / ADMIN | 어떻게 열렸는지 |
| `trigger_detail` | VARCHAR2(200) |  | 시스템 | 예: 같은 CI 4계정 동시 좌석 선점 | 첫 화면에서 이유 확인 |
| `risk_score_at_open` | NUMBER(5,2) |  | 시스템 | 생성 시점 점수 |  |
| `window_from` | TIMESTAMP |  | 시스템 | 되돌아볼 시작 시각 | 재생 범위 기본값 |
| `window_to` | TIMESTAMP |  | 시스템 | 되돌아볼 끝 시각 |  |
| `status` | VARCHAR2(12) | NN | 시스템 | OPEN / REVIEWING / CONFIRMED / DISMISSED |  |
| `assignee_id` | NUMBER | FK → admin_user | 시스템 | 담당 관리자 |  |
| `verdict` | VARCHAR2(20) |  | 시스템 | RESELLER / MACRO / CANCEL_ABUSE / NORMAL | synthetic_label과 비교해 오탐률 측정 |
| `admin_action_id` | NUMBER | FK → admin_action | 시스템 | 최종 조치 |  |
| `evidence_hash` | VARCHAR2(64) |  | 시스템 | 증거 조각 해시를 이어 붙인 SHA-256 | 증거 묶음 전체 무결성. Oracle에 따로 둬서 Mongo 쪽 변조와 분리 |
| `opened_at` | TIMESTAMP | NN | 시스템 |  |  |
| `closed_at` | TIMESTAMP |  | 시스템 |  | 처리 소요 시간 |

#### `case_session` 케이스-세션 연결

저장소: Oracle · 기능: RISK-04, ADM-05


한 케이스에 묶인 여러 세션. 같은 CI·기기의 다계정 세션을 나란히 재생.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `case_id` | NUMBER | PK, FK → suspicious_case | 시스템 |  |  |
| `session_id` | VARCHAR2(36) | PK, FK → auth_session | 시스템 |  | 한 케이스에 여러 세션 |
| `member_id` | NUMBER | FK, NN → member | 시스템 | 세션 계정 |  |
| `link_reason` | VARCHAR2(20) | NN | 시스템 | TRIGGER / SAME_CI / SAME_DEVICE / SAME_PAYMENT / SAME_IP | 다계정을 나란히 재생하는 근거 |
| `sync_offset_ms` | NUMBER |  | 시스템 | 동시 재생 시간 보정 | 기기 시계 오차 보정 |

#### `case_marker` 타임라인 마커

저장소: Oracle · 기능: RISK-04, ADM-05


재생 타임라인의 북마크. 시스템이 자동으로 찍고 관리자가 메모를 더한다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `marker_id` | NUMBER | PK | 시스템 |  |  |
| `case_id` | NUMBER | FK, NN → suspicious_case | 시스템 |  |  |
| `session_id` | VARCHAR2(36) | FK, NN → auth_session | 시스템 | 표시할 세션 |  |
| `at_ts` | TIMESTAMP | NN | 시스템 | 타임라인 위치 | 누르면 그 시점으로 이동 |
| `marker_type` | VARCHAR2(10) | NN | 시스템 | AUTO / MANUAL | 시스템 탐지와 관리자 메모 구분 |
| `rule_code` | VARCHAR2(30) | NN | 시스템 | FAST_CLICK / SAME_SEAT_RETRY / CANCEL_AFTER_PAY / MULTI_ACCOUNT_SYNC / LINEAR_MOUSE | 어떤 규칙이 잡았는지 |
| `label` | VARCHAR2(200) | NN | 시스템 | 예: 0.1초 간격 좌석 클릭 28회 |  |
| `severity` | VARCHAR2(6) | NN | 시스템 | LOW / MID / HIGH |  |
| `created_by` | NUMBER | FK → admin_user | 시스템 | 작성 관리자 (AUTO는 null) |  |

### 4.7 MongoDB 로그

담당: D·A

#### `behavior_log` 행동 로그

저장소: MongoDB · 🅱 빅데이터 · 기능: RISK-01·02, ADM-05


요청 단위 행동 로그. Python 분석으로 위험 점수의 신호를 뽑는다.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `_id` | ObjectId | PK | 시스템 |  |  |
| `member_id` | Long | FK → member | 생성 |  |  |
| `ci_hash` | String |  | 생성 |  | 묶음 단위 집계 |
| `device_hash` | String |  | 생성 |  | 묶음 단위 집계 |
| `ip_hash` | String |  | 생성 |  | IP 집중도 |
| `session_id` | String | FK → auth_session | 생성 | = auth_session.session_id | 세션 단위 패턴, CCTV 녹화와 연결 |
| `event_type` | String |  | 생성 | VIEW_TRAIN / VIEW_SEAT / QUEUE_ENTER / SEAT_HOLD / PAY / CANCEL |  |
| `run_id` | Long | FK → train_run | 생성 | 대상 운행 | 인기 열차 쏠림 분석 |
| `ts` | Date |  | 생성 | 이벤트 시각 (ms) |  |
| `expire_at` | Date |  | 시스템 | 삭제 예정 시각 | TTL 기준. ts + behavior.retention_days로 기록 |
| `interval_ms` | Int |  | 생성 | 직전 요청과 간격 | 매크로 신호 (사람은 수백 ms~초, 봇은 균일·짧음) |
| `user_agent` | String |  | 생성 |  | 헤드리스 브라우저 탐지 |
| `channel` | String |  | 생성 | WEB / APP |  |
| `response_status` | Int |  | 시스템 | HTTP 상태 | 차단·에러 분석 |
| `meta` | Object |  | 시스템 | 이벤트별 추가 정보 | 스키마 변경 없이 필드 확장 |

> **제약·비고** 인덱스: { ci_hash: 1, ts: -1 }, { run_id: 1, ts: 1 } · TTL { expire_at: 1 }, expireAfterSeconds: 0 · 앱 → Kafka → insertMany 배치 (앱이 Mongo에 직접 쓰지 않음) · 샤드 키는 부하 테스트 후 판단 (후보: { ci_hash: "hashed" }) · 점선은 논리 참조

#### `queue_log` 대기열 기록

저장소: MongoDB · 🅱 빅데이터 · 기능: QUE-01·03


대기열 진입·조정·입장 기록. 순번 조정이 의도대로 됐는지 사후 검증.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `member_id` | Long | FK → member | 시스템 |  |  |
| `run_id` | Long | FK → train_run | 시스템 |  |  |
| `session_id` | String | FK → auth_session | 시스템 | 로그인 세션 | CCTV 재생과 연결 |
| `ticket_no` | Long |  | 시스템 | 발급 번호표 | 사용자에게 보인 번호와 대조 |
| `entered_at` | Date |  | 시스템 | 실제 진입 시각 |  |
| `expire_at` | Date |  | 시스템 | 삭제 예정 시각 | entered_at + queue_log.retention_days |
| `risk_score` | Double |  | 시스템 | 진입 시 점수 |  |
| `penalty_ms` | Long |  | 시스템 | 뒤로 밀린 양 |  |
| `initial_rank` | Long |  | 시스템 | 조정 전 순번 | 조정이 의도대로 됐는지 검증 |
| `adjusted_rank` | Long |  | 시스템 | 조정 후 순번 |  |
| `admitted_at` | Date |  | 시스템 | 입장 시각 | 실제 대기 시간 vs 예상 대기 시간 비교 |
| `left_reason` | String |  | 시스템 | ADMITTED / TIMEOUT / LEFT / REJECTED_DUPLICATE | 이탈률, 중복 대기 차단 |

> **제약·비고** TTL { expire_at: 1 }, expireAfterSeconds: 0 · 점선은 논리 참조

### 4.8 가상 데이터

담당: E·D

#### `synthetic_label` 정답 라벨

저장소: Oracle · 기능: DATA-02·03, 성과 지표(탐지율)


가상 데이터의 정답. 위험 점수의 정밀도·재현율 측정용. 운영 로직에서 참조 금지.

| 컬럼 | 타입 | 키 | 출처 | 설명 | 이유 |
|---|---|---|---|---|---|
| `member_id` | NUMBER | PK, FK → member | 생성 | 회원 |  |
| `user_type` | VARCHAR2(20) | NN | 생성 | NORMAL / RESELLER / CANCEL_ABUSER / MACRO | 위험 점수의 정밀도·재현율 측정 |
| `group_id` | NUMBER |  | 생성 | 같은 어뷰저가 만든 다계정 묶음 | 묶음 탐지 성능 측정 |
| `generation_params` | CLOB |  | 생성 | 생성 시 사용한 분포 파라미터 (JSON) | 재현 가능성, 발표 시 근거 |

> **제약·비고** 운영 로직에서 참조 금지

---

## 5. 상태 코드표

4장 테이블에서 CHECK가 걸리는 코드 컬럼만 모았습니다. Java enum 값도 같게 씁니다.

| 테이블 | 컬럼 | 값 | 비고 |
|---|---|---|---|
| `car_type` | `seat_grade` | GENERAL / FIRST | 등급별 운임·재고 분리 |
| `train` | `direction` | DOWN / UP | 상·하행 구분 |
| `seat` | `seat_grade` | GENERAL / FIRST | car_type에서 상속. 코드값 통일로 CHECK 제약 적용 |
| `train_run` | `status` | SCHEDULED / ON_SALE / CLOSED / DEPARTED | 판매 가능 여부 판단 |
| `fare` | `seat_grade` | GENERAL / FIRST | 좌석 등급 코드 통일 |
| `purchase_limit_policy` | `scope` | PER_RESERVATION / PER_RUN / PER_HOLIDAY | 1회·열차별·명절 전체 제한을 코드 수정 없이 조정 |
| `sale_event` | `status` | PLANNED / OPEN / CLOSED |  |
| `app_policy` | `value_type` | NUMBER / STRING / BOOL | 읽을 때 형 변환 |
| `member` | `status` | ACTIVE / RESTRICTED / BANNED | 관리자 조치 반영 |
| `payment_method` | `method_type` | CARD / ACCOUNT / PAY |  |
| `identity_verification` | `provider` | MOCK_PASS / MOCK_PHONE | 모의 본인인증 |
| `identity_verification` | `result` | SUCCESS / FAIL |  |
| `auth_session` | `actor_type` | MEMBER / ADMIN | 관리자 세션은 녹화 대상에서 제외 |
| `auth_session` | `revoke_reason` | LOGOUT / ROTATED / ADMIN / SUSPICIOUS | 관리자 조치로 끊긴 세션 구분 |
| `admin_user` | `role` | VIEWER / REVIEWER / SUPERVISOR | 마스킹 해제·내보내기는 SUPERVISOR만 |
| `admin_user` | `status` | ACTIVE / DISABLED |  |
| `login_attempt` | `actor_type` | MEMBER / ADMIN | 관리자 로그인도 같은 방식으로 기록 |
| `login_attempt` | `result` | SUCCESS / WRONG_PASSWORD / LOCKED / NOT_FOUND / RATE_LIMITED |  |
| `seat_inventory` | `status` | HOLD / SOLD | 선점과 확정 구분 |
| `reservation` | `status` | HOLD / PAYMENT_PENDING / CONFIRMED / CANCELED / EXPIRED | 상태 머신 |
| `reservation` | `channel` | WEB / APP | 채널별 트래픽 분석 |
| `reservation` | `source` | NORMAL / WAITLIST | 취소표 재배정 여부 |
| `reservation_seat` | `passenger_type` | ADULT / CHILD / SENIOR | 운임 할인 확장 |
| `reservation_history` | `to_status` | HOLD / PAYMENT_PENDING / CONFIRMED / CANCELED / EXPIRED | 변경 상태. 잘못된 전이(CANCELED→CONFIRMED) 탐지 |
| `reservation_history` | `actor_type` | USER / SYSTEM / ADMIN | 누가 상태를 바꿨는지 |
| `payment` | `status` | REQUESTED / APPROVED / FAILED / REFUNDED | Saga 보상 트리거 |
| `cancellation` | `cancel_type` | USER / PAY_FAILED / ADMIN | USER = 사용자 확정 취소(수수료), ADMIN = 운영 취소(수수료 0), PAY_FAILED = 늦은 승인 환불(전액). 만료는 돈이 오가지 않아 cancellation을 만들지 않음 |
| `cancellation` | `refund_status` | REQUESTED / DONE / FAILED | 환불 실패 재시도 |
| `purchase_counter` | `scope` | PER_RUN / PER_HOLIDAY | 열차별·명절 전체 제한을 한 테이블에서 |
| `outbox_event` | `aggregate_type` | RESERVATION / PAYMENT / WAITLIST | 토픽 라우팅 |
| `outbox_event` | `status` | PENDING / PUBLISHED / FAILED | FAILED = retry_count가 outbox.max_retry에 도달. 그 샤드를 멈추고 관리자 확인 후 PENDING으로 되돌림 |
| `saga_instance` | `current_step` | SEAT_HELD / PAYING / CONFIRMED | 마지막으로 도달한 단계. 결과는 status (보상은 한 트랜잭션이라 진행 중 단계를 따로 두지 않음) |
| `saga_instance` | `status` | RUNNING / DONE / COMPENSATED | 좌석 복구율 100% 검증 |
| `waitlist` | `seat_grade` | GENERAL / FIRST | 희망 등급. 좌석 등급 코드 통일 |
| `waitlist` | `status` | WAITING / OFFERED / ACCEPTED / EXPIRED / CANCELED / SKIPPED | SKIPPED = 제안 시 매수 한도 초과로 건너뜀 |
| `notification` | `type` | WAITLIST_OFFER / PAY_REMINDER / EXPIRED / WAITLIST_SKIPPED / REFUNDED | 취소표 건너뜀(매수 한도 초과)·환불 완료 안내 |
| `notification` | `ref_type` | RESERVATION / WAITLIST / CASE | ref_id가 어느 테이블을 가리키는지 |
| `notification` | `channel` | PUSH / EMAIL / IN_APP | 채널 추가 확장 |
| `risk_score` | `subject_type` | CI / PAYMENT / DEVICE | 계정이 아닌 식별 단위 |
| `risk_score` | `review_status` | NONE / PENDING / CONFIRMED / CLEARED | 높은 점수는 검토 후 조치 |
| `admin_action` | `subject_type` | CI / PAYMENT / DEVICE | 대상 유형 코드 통일 (CHECK) |
| `admin_action` | `action_type` | CONFIRM / CLEAR / RESTRICT / BAN / CANCEL_RESERVATION / RESOLVE_PAYMENT / UNMASK | 예약 대상 조치는 그 예약의 CI를 subject로 기록하고 reservation_id를 채움. UNMASK는 위험 사용자 목록의 마스킹 해제 |
| `model_run` | `algorithm` | RULE / IFOREST | 두 방식 비교 |
| `suspicious_case` | `subject_type` | CI / PAYMENT / DEVICE | 계정이 아닌 묶음 단위 |
| `suspicious_case` | `trigger_type` | RISK_THRESHOLD / RULE / MODEL / ADMIN | 어떻게 열렸는지 |
| `suspicious_case` | `status` | OPEN / REVIEWING / CONFIRMED / DISMISSED |  |
| `suspicious_case` | `verdict` | RESELLER / MACRO / CANCEL_ABUSE / NORMAL | synthetic_label과 비교해 오탐률 측정 |
| `case_session` | `link_reason` | TRIGGER / SAME_CI / SAME_DEVICE / SAME_PAYMENT / SAME_IP | 다계정을 나란히 재생하는 근거 |
| `case_marker` | `marker_type` | AUTO / MANUAL | 시스템 탐지와 관리자 메모 구분 |
| `case_marker` | `rule_code` | FAST_CLICK / SAME_SEAT_RETRY / CANCEL_AFTER_PAY / MULTI_ACCOUNT_SYNC / LINEAR_MOUSE | 어떤 규칙이 잡았는지 |
| `case_marker` | `severity` | LOW / MID / HIGH |  |
| `behavior_log` | `event_type` | VIEW_TRAIN / VIEW_SEAT / QUEUE_ENTER / SEAT_HOLD / PAY / CANCEL |  |
| `behavior_log` | `channel` | WEB / APP |  |
| `queue_log` | `left_reason` | ADMITTED / TIMEOUT / LEFT / REJECTED_DUPLICATE | 이탈률, 중복 대기 차단 |
| `synthetic_label` | `user_type` | NORMAL / RESELLER / CANCEL_ABUSER / MACRO | 위험 점수의 정밀도·재현율 측정 |
| `reservation_history` | `from_status` | HOLD / PAYMENT_PENDING / CONFIRMED / CANCELED / EXPIRED (NULL 허용) | 비고의 CHECK |

---

## 6. MongoDB·Redis·Kafka

### 6.1 MongoDB 컬렉션 (P1)

컬럼은 4장에 있습니다. 여기서는 만들 인덱스와 보관만 모읍니다. `mongo-init.js`로 생성합니다.

| 컬렉션 | 인덱스 | 보관 (`expire_at` + TTL `expireAfterSeconds: 0`) | 적재 |
|---|---|---|---|
| behavior_log | `{ci_hash: 1, ts: -1}`, `{run_id: 1, ts: 1}`, `{expire_at: 1}` TTL. 타임라인(ADM-05)용 `{member_id: 1, ts: 1}` | `ts` + 180일 | 앱 → Kafka `jejari.behavior` → insertMany 1000건 |
| queue_log | `{expire_at: 1}` TTL. 순번 검증용 `{run_id: 1, entered_at: 1}` | `entered_at` + 30일 | 같은 경로 |
| login_attempt | `{ip_hash: 1, ts: -1}`, `{email_hash: 1, ts: -1}`, TTL | 30일 | 로그인 처리 중 비동기 |

### 6.2 Redis 키

키의 `{…}`는 자리표시자입니다. 설정: 단일 노드, `maxmemory-policy noeviction`, AOF `everysec`.

| 키 | 자료형 | 값 | 만료 | 기능 |
|---|---|---|---|---|
| `queue:{runId}` | ZSET | member=`{12자리 seq}:{memberId}`, score=진입시각ms + penalty_ms | 운행 판매 마감 | QUE-01 |
| `queue:seq:{runId}` | STRING | 진입 시퀀스 (INCR) | 운행 판매 마감 | QUE-01 |
| `queue:member:{memberId}` | STRING | `{runId}\|{queue member}` (회원당 대기 1개) | 토큰 만료 | QUE-01·05 |
| `queue:ci:{ciHash}` | ZSET | memberId, score=만료 시각 (CI당 대기 1개) | 멤버별 score | QUE-01·05 |
| `queue:active:{runId}` | ZSET | 입장한 회원, score=토큰 만료 시각 | | QUE-03 |
| `queue:throughput:{runId}` | STRING | 초당 입장 수 | | QUE-02 |
| `queue:pass:{runId}:{memberId}` | STRING | 입장 토큰 | `queue.pass_ttl_sec` | QUE-03·04 |
| `idem:{memberId}:{key}` | STRING | reservation_id | `idem.ttl_sec` | RSV-02 |
| `idem:pay:{reservationId}:{key}` | STRING | payment_id | `idem.ttl_sec` | PAY-01 |
| `seat:hold:{runId}:{seatId}:{segNo}` | STRING | `R` / `SOLD:R` / `REOFFER:R` | 결제 기한 / 출발 시각 / 60초 | RSV-02, PAY-02, CNL-02, WL-03 |
| `limit:{runId}:{ciHash}` | STRING | 열차당 보유 매수 | 운행 출발 | RSV-02 |
| `limit:event:{saleEventId}:{ciHash}` | STRING | 명절 전체 보유 매수 | 행사 종료 | RSV-02 |
| `limit:dec:{reservationId}` | STRING | 한도 감소 완료 표시 | 출발·행사 종료 중 늦은 쪽 | RSV-03, CNL-02 |
| `relay:shard:{n}` | STRING | 릴레이 인스턴스 ID | `relay.lease_sec` | EVT-02 |
| `risk:{subjectType}:{hash}` | STRING | final_score | 다음 산정 주기 × 2 | RISK-03 |
| `seatmap:{runId}` | HASH | seatId → 상태 | 출발 | TRN-02, RSV-01 |
| `rate:login:{ipHash}` | STRING | 시도 수 | 짧은 창 | AUTH-03 |
| `rate:api:{memberId}` | STRING | 초당 호출 수 | 1초 | 전체 |
| `policy:cache` | HASH | app_policy 값 | | ADM-08 |

Lua 스크립트(`hold`, `release`, `extend`, `rehold`, `sold`, `reoffer`, `limit_incr`, `limit_decr`)로 여러 키를 원자적으로 처리합니다.

### 6.3 Kafka와 outbox

| 토픽 | 메시지 키 | 만드는 곳 | outbox `aggregate_type` |
|---|---|---|---|
| `jejari.reservation` | reservation_id | outbox 릴레이 | RESERVATION, PAYMENT |
| `jejari.waitlist` | waitlist_id | outbox 릴레이 | WAITLIST |
| `jejari.behavior` | ci_hash | 앱 직접 | (outbox 안 거침) |

이벤트 종류와 payload는 기능설계서 8장을 따릅니다. 소비자는 모두 `processed_event`에 `(consumer_group, event_id)`를 업무 처리와 같은 트랜잭션으로 INSERT합니다.

---

## 7. 2주차 진행 예정

### 7.1 2주차 말에 진행 여부를 정하는 테이블

| 기능 | 테이블 | 저장소 |
|---|---|---|
| 화면 녹화 재생 (ADM-11) | replay_session, replay_event | MongoDB |
| 위험 점수 이력 | risk_score_history | Oracle |
| 외부 데이터 분석 (DATA-04) | ext_dataset, ext_hotel_booking, ext_web_access_log, ext_renfe_price, ext_bot_detection, risk_feature | Oracle, MongoDB |
| 증거 동결·열람 감사 | evidence_snapshot, replay_access_log | Oracle |
| 확장 기능 | common_code, member_push_token, password_reset_token, ticket_transfer | Oracle |

진행하기로 하면 이 문서 4장에 컬럼 정의를 추가합니다.
