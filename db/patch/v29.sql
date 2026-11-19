-- ERD v28 → v29 델타.
--
-- v29는 MongoDB model_score에 칸(type · type_prob, 닮은 유형)만 더합니다. **Oracle은 바뀌는 것이 없습니다.**
-- 이 파일은 "v29를 확인했다"는 자리만 지킵니다. 실행할 필요도, 실행해도 바뀌는 것도 없습니다.
-- Mongo는 스키마가 없어 칸을 미리 만들 필요가 없고, tools/ml/load_scores.sh가 넣을 때 생깁니다.

SELECT 'v29: Oracle 변경 없음' AS note FROM dual;
