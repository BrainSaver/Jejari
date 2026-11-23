-- ERD v30 → v31 델타. 이미 데이터가 들어 있는 Oracle(로컬·공용 서버)에 v31 변경만 더합니다.
-- 새로 만드는 Oracle은 이 파일이 필요 없습니다 (jejari-erd.ddl.sql에 이미 들어 있음).
--
-- 두 번째 합성 모델 ens-2026.11-02 (data/ml/model-next)를 꺼진 채(N)로 등록합니다.
-- 모델 관리 화면(A-09)에서 지금 버전(ens-2026.11-01)과 비교한 뒤 관리자가 켭니다.
-- 다시 실행해도 안전합니다 (없을 때만 넣습니다. 이미 켜 두었으면 그대로 둡니다).
--
-- 실행:
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v31.sql

SET SQLBLANKLINES ON
SET DEFINE OFF

MERGE INTO model_run m
USING (SELECT 'ens-2026.11-02' model_version FROM dual) s
ON (m.model_version = s.model_version)
WHEN NOT MATCHED THEN INSERT (model_version, algorithm, params, train_source, trained_at, threshold, is_active)
VALUES ('ens-2026.11-02', 'ENSEMBLE',
        '{"dir":"data/ml/model-next","models":["gbm","iforest","gru x5"],"stage":true,"pick_floor_s":1.0}',
        'tools/ml generate.py g7~g9 (ML_PICK_FLOOR=1, ML_STAGE=1)', TIMESTAMP '2026-11-19 03:55:00', 98.2, 'N');

COMMIT;

SELECT model_version, algorithm, threshold, is_active FROM model_run WHERE algorithm = 'ENSEMBLE' ORDER BY model_version;
