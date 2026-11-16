# 로컬 인프라 (Docker Compose)

Oracle · MongoDB · Redis · Kafka · Prometheus · Grafana를 한 번에 띄웁니다. 앱(Spring Boot)은 IDE에서 호스트로 실행합니다.

## 처음 한 번

1. Docker Desktop 설치 (Windows는 WSL 2 백엔드)
2. 저장소 루트에서 `.env.example`을 `.env`로 복사하고 빈 값을 채웁니다 (`.env`는 커밋 금지)
   - 비밀번호: 영문·숫자만 (Mongo 접속 URI에 들어감)
   - `JWT_SECRET_K1`: `openssl rand -base64 32`
3. `docker compose up -d`

첫 기동은 이미지 내려받기 + Oracle DDL 실행으로 몇 분 걸립니다. `docker compose ps`에서 oracle·mongo·redis·kafka가 `healthy`, kafka-init이 `Exited (0)`이면 준비 완료입니다.

## 확인

| 무엇 | 어떻게 | 정상 |
|---|---|---|
| Oracle 스키마 | `docker compose logs oracle \| grep "jejari schema created"` | 한 줄 나옴 |
| Oracle 기준 데이터 | `docker compose logs oracle \| grep "jejari seed loaded"` | 한 줄 나옴 (역·열차·좌석·운행·운임) |
| Mongo 인덱스 | `docker compose logs mongo \| grep "mongo init done"` | 한 줄 나옴 |
| Kafka 토픽 | `docker compose logs kafka-init` | 토픽 4개 목록 |
| 앱 | IDE에서 `JejariApplication` 실행 → `curl localhost:8080/actuator/health` | `"status":"UP"`, 로그에 `시간대 점검 통과` |
| Prometheus | http://localhost:9090/targets | jejari-app `UP` (앱 실행 중일 때) |
| Grafana | http://localhost:3000 (`.env`의 GRAFANA_ADMIN_*) | Prometheus 데이터소스 있음 |

## 자주 쓰는 명령

```bash
docker compose ps                 # 상태
docker compose logs -f oracle     # 로그
docker compose stop               # 멈춤 (데이터 유지)
docker compose down               # 컨테이너 삭제 (데이터 유지)
docker compose down -v            # 데이터까지 삭제 (Oracle 스키마·Mongo 인덱스를 처음부터 다시 만들 때)
```

## 주의

- **초기화 스크립트는 빈 볼륨에서 한 번만 돕니다.** ERD가 바뀌어 DDL을 다시 적용하려면 Oracle 볼륨을 지우고 다시 띄웁니다
  (`docker compose rm -sf oracle && docker volume rm jejari_oracle-data && docker compose up -d oracle`, 데이터 삭제됨).
  첫 기동 중 초기화가 실패했을 때도 같은 방법으로 볼륨을 지워야 다시 돕니다.
- 포트는 `127.0.0.1`에만 열려 있어 같은 네트워크의 다른 PC에서는 접속할 수 없습니다.
- Git Bash에서 `docker compose exec`로 `/opt/...` 같은 경로를 넘기면 Windows 경로로 바뀌어 실패할 수 있습니다. 앞에 `MSYS_NO_PATHCONV=1`을 붙이세요.
- 로컬 http에서 리프레시 쿠키를 쓰려면 `.env`에 `JWT_REFRESH_COOKIE_SECURE=false`.
