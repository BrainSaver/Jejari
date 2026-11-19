# 위험 점수 모델(tools/ml)을 서버에서 돌리는 이미지. 서버에 파이썬 라이브러리를 깔지 않으려고 컨테이너로 (deploy/model-refresh.sh)
# 버전은 data/ml/model/model.pkl을 학습한 환경과 같게 고정 — 다르면 pickle을 못 읽거나 점수가 달라질 수 있다
FROM python:3.13-slim
RUN pip install --no-cache-dir numpy==2.5.3 scikit-learn==1.9.1 \
 && pip install --no-cache-dir torch==2.15.0 --index-url https://download.pytorch.org/whl/cpu
ENV PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /w
