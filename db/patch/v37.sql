-- ERD v36 → v37 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v37 변경만 더합니다.
-- (처음엔 v34로 만들었는데, 위험 마커 규칙(v34)·속도 제한(v35)·단체 예매(v36)가 먼저 올라가 v37이 됐습니다)
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 도용 카드 — 카드 한 장에 붙는 서로 다른 명의(CI) 수 상한 (concurrency.md §4.3)
--   · card_lock 테이블: 카드 해시마다 한 행. 같은 카드를 새 명의에 붙이는 결제 시작을 한 줄로 세움.
--     행이 없는 처음 보는 카드도 잠가야 동시 요청에 상한이 지켜지므로 payment_method와 따로 둡니다
--   · app_policy payment.max_ci_per_card = 0 (끔). 켤 때 권장 3 (가족 카드 고려).
--     끔이 기본인 이유: 결제 화면 버튼·k6 시나리오 11·대회 안내 예시가 같은 가상 카드번호를 써서
--     바로 켜면 네 번째 사람부터 결제가 막힙니다. 그쪽을 사람마다 다른 번호로 바꾼 뒤 관리자 화면에서 켭니다
--
-- 상한을 켠 채(1 이상) 이 패치 없이 앱을 띄우면 처음 보는 카드로 결제를 시작할 때 ORA-00942(테이블 없음)로 실패합니다.
-- 끔(0)이면 card_lock을 건드리지 않으므로 패치 전에도 결제는 그대로 됩니다.
-- 기존 payment_method 행은 건드리지 않습니다. 이미 상한을 넘은 카드가 있어도 그 연결은 그대로 두고,
-- 새로 붙이는 명의부터 막습니다. card_lock 행은 결제 시작이 필요할 때 만듭니다 (미리 채울 필요 없음).
--
-- 다시 실행해도 안전합니다 (이미 있는 테이블·설정 값은 건너뜀).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v37.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE q'[CREATE TABLE card_lock (
    method_hash VARCHAR2(64) NOT NULL,
    created_at  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_card_lock PRIMARY KEY (method_hash))]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 THEN RAISE; END IF;  -- 이미 있으면 건너뜀 (ORA-00955)
END;
/

COMMENT ON TABLE card_lock IS '카드 잠금 행 - 카드 해시마다 한 행. 같은 카드를 새 명의에 붙이는 결제 시작을 한 줄로 세워, 카드 한 장에 붙는 명의 수 상한(도용 카드)을 동시 요청에도 지킨다.';
COMMENT ON COLUMN card_lock.method_hash IS '카드 해시 (payment_method.method_hash와 같은 값) / 아직 payment_method 행이 없는 처음 보는 카드도 잠글 수 있게 따로 둠';

MERGE INTO app_policy t
USING (
  SELECT 'payment.max_ci_per_card' k, '0' v, 'NUMBER' vt,
         '카드 한 장에 붙을 수 있는 서로 다른 명의(CI) 수. 0이면 끔. 켤 때 권장 3(가족 카드). 넘으면 결제 시작 거절 (concurrency.md §4.3)' d
    FROM dual
) s ON (t.policy_key = s.k)
WHEN NOT MATCHED THEN
  INSERT (policy_key, policy_value, version, value_type, description) VALUES (s.k, s.v, 1, s.vt, s.d);

COMMIT;

SELECT 'v37: card_lock ' || (SELECT COUNT(*) FROM user_tables WHERE table_name = 'CARD_LOCK')
       || ' · payment.max_ci_per_card = ' || (SELECT policy_value FROM app_policy WHERE policy_key = 'payment.max_ci_per_card')
       AS note FROM dual;
