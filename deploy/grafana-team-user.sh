#!/usr/bin/env bash
# Grafana 팀 공용 보기 전용 계정 만들기·비밀번호 맞추기 (서버에서). 다시 실행해도 안전합니다.
#   cd ~/Jejari && bash deploy/grafana-team-user.sh
#
# 계정: team (Viewer — 대시보드 보기만, 수정·데이터 소스·사용자 관리 불가)
# 비밀번호: .env의 GRAFANA_TEAM_PASSWORD (없으면 만들어 .env에 추가). 화면에 출력하지 않음 → 팀에는 따로 전달
set -euo pipefail
cd "$(dirname "$0")/.."

val() { grep "^$1=" .env | head -1 | cut -d= -f2-; }
if [[ -z "$(val GRAFANA_TEAM_PASSWORD)" ]]; then
  echo "GRAFANA_TEAM_PASSWORD=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-20)" >> .env
  echo ".env에 GRAFANA_TEAM_PASSWORD를 새로 만들어 넣었습니다 (내 PC의 jejari-server.env에도 옮겨 두세요)."
fi
AU=$(val GRAFANA_ADMIN_USER); AU=${AU:-admin}; AP=$(val GRAFANA_ADMIN_PASSWORD); TP=$(val GRAFANA_TEAM_PASSWORD)
G=http://127.0.0.1:3000/grafana

for _ in $(seq 1 30); do curl -fs "$G/api/health" >/dev/null && break; sleep 2; done
api() { curl -fsS -u "$AU:$AP" -H 'Content-Type: application/json' "$@"; }

id=$(api "$G/api/users/lookup?loginOrEmail=team" 2>/dev/null | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2 || true)
if [[ -z "$id" ]]; then
  api -X POST "$G/api/admin/users" -d "{\"name\":\"Jejari Team\",\"login\":\"team\",\"email\":\"team@jejari.local\",\"password\":\"$TP\"}" >/dev/null
  id=$(api "$G/api/users/lookup?loginOrEmail=team" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  echo "team 계정을 만들었습니다 (id $id)."
else
  api -X PUT "$G/api/admin/users/$id/password" -d "{\"password\":\"$TP\"}" >/dev/null
  echo "team 계정이 이미 있어 비밀번호만 .env 값으로 맞췄습니다 (id $id)."
fi
# 기본 조직에서 보기 전용 (혹시 바뀌었어도 되돌림)
api -X PATCH "$G/api/org/users/$id" -d '{"role":"Viewer"}' >/dev/null
echo "권한: Viewer. 로그인: https://$(val JEJARI_DOMAIN)/grafana/ (아이디 team)"
