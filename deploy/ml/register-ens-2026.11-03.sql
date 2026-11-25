-- 위험 점수 모델 세 번째 버전 ens-2026.11-03 (data/ml/model-real-r10, 실제 데이터 둘레 6차)을 꺼진 채(N)로 등록합니다.
-- 고른 이유: docs/강동균/features/실제데이터-둘레-여러시드-평가.md (같은 시험 5세트에서 r6·r8·r10·r11·r12 비교)
-- 모델 관리 화면(A-09)에서 지금 버전과 비교한 뒤 관리자가 켭니다. 다시 실행해도 안전합니다 (없을 때만 넣음).
--
-- 먼저 필요: deploy/model-refresh.sh가 특징을 ML_RUNS·ML_HOLD·ML_LINK까지 만드는 버전(11-25)이어야 합니다.
-- 옛 스크립트면 이 버전의 점수 매기기가 "특징이 없음"으로 실패합니다.
--
-- 실행 (서버 저장소 폴더에서):
--   PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
--   docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
--     bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < deploy/ml/register-ens-2026.11-03.sql
--   sudo systemctl start jejari-model   # 바로 한 번 매겨 A-09에 점수가 보이게

SET SQLBLANKLINES ON
SET DEFINE OFF

MERGE INTO model_run m
USING (SELECT 'ens-2026.11-03' model_version FROM dual) s
ON (m.model_version = s.model_version)
WHEN NOT MATCHED THEN INSERT (model_version, algorithm, params, train_source, trained_at, threshold, is_active)
VALUES ('ens-2026.11-03', 'ENSEMBLE',
        '{"dir":"data/ml/model-real-r10","models":["gbm","iforest","gru x5","exon"],"stage":true,"runs":true,"hold":true,"pick_floor_s":1.0}',
        'tools/ml train_real.py 6차 r10 (실제 둘레·해명 모델·기기 공유 가족)', TIMESTAMP '2026-11-25 14:36:42', 96.2, 'N');

COMMIT;

SELECT model_version, algorithm, threshold, is_active FROM model_run WHERE algorithm = 'ENSEMBLE' ORDER BY model_version;
