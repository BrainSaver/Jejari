-- ERD v24 → v25 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v25 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 모델 보조 의견 (RISK-05): model_run.algorithm에 ENSEMBLE 추가, 합성 모델 기록 1행,
-- 쓰는 코드가 없던 risk_score.iforest_score 삭제. 점수 자체는 MongoDB model_score (docker/mongo/mongo-init.js).
--
-- 다시 실행해도 안전합니다 (제약은 지우고 다시 만들고, 컬럼·행은 있을 때만 처리합니다).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v25.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE model_run DROP CONSTRAINT ck_model_run_algorithm';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -2443 THEN RAISE; END IF;  -- 제약이 없으면 건너뜀
END;
/

ALTER TABLE model_run ADD CONSTRAINT ck_model_run_algorithm
  CHECK (algorithm IN ('RULE', 'IFOREST', 'ENSEMBLE'));

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE risk_score DROP COLUMN iforest_score';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -904 THEN RAISE; END IF;  -- 이미 없으면 건너뜀
END;
/

MERGE INTO model_run m
USING (SELECT 'ens-2026.11-01' model_version FROM dual) s
ON (m.model_version = s.model_version)
WHEN NOT MATCHED THEN INSERT (model_version, algorithm, params, train_source, trained_at, threshold, is_active)
VALUES ('ens-2026.11-01', 'ENSEMBLE',
        '{"models":["gbm","iforest","gru x5"],"dropped":["account_age_days_log","accounts_on_ip","distinct_ips","min_sync_gap_ip_s_log"]}',
        'tools/ml generate.py 18000계정', TIMESTAMP '2026-11-18 10:52:00', 97.6, 'N');

COMMENT ON TABLE model_run IS '모델 학습 기록 - 규칙·모델 버전별 파라미터와 성능. 발표용 수치의 원천. 규칙(RULE)은 risk_score를, 합성 모델(ENSEMBLE)은 model_score를 낸다.';
COMMENT ON COLUMN model_run.model_version IS '예: rule-2026.11-01, ens-2026.11-01 / risk_score.model_version·model_score.model_version이 참조';
COMMENT ON COLUMN model_run.algorithm IS 'RULE / IFOREST / ENSEMBLE / 두 방식 비교. ENSEMBLE = 부스팅 + IF + GRU 합성 (tools/ml, 점수는 model_score)';

COMMIT;

SELECT search_condition_vc FROM user_constraints WHERE constraint_name = 'CK_MODEL_RUN_ALGORITHM';
SELECT model_version, algorithm, is_active FROM model_run ORDER BY model_version;
