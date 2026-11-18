# 위험 점수 모델 비교용 데이터 도구

봇 대회 제안서([`docs/강동균/봇-대회-제안.md`](../../docs/강동균/봇-대회-제안.md))의 "모델 비교"에 쓰는 도구입니다.
**생성 데이터로 학습하고, 대회 데이터로 시험합니다.** 두 데이터는 같은 특징 추출기를 거칩니다.

> **공격자는 대회가 끝날 때까지 `generate.py`를 보지 않습니다.** 생성 수법 목록이 들어 있어서,
> 대회 봇이 이걸 닮으면 시험이 쉬워집니다.

## 흐름

```
generate.py ─┐                                   ┌→ 학습 (생성 데이터)
             ├→ events.jsonl + members.jsonl → features.py → features.csv
export_live.sh┘   (행동 로그 모양 그대로)                     └→ 시험 (대회 데이터)
```

```bash
# 학습용: 생성 (파이썬 기본 모듈만, 계정 6,000개에 1초 남짓)
python3 -I tools/ml/generate.py --members 6000 --seed 7 --out data/ml/gen-s7
python3 -I tools/ml/features.py data/ml/gen-s7

# 시험용: 대회 데이터 꺼내기 (docker compose가 떠 있는 집계자 PC에서)
tools/ml/export_live.sh <대회 태그> data/ml/contest-<태그>
python3 -I tools/ml/features.py data/ml/contest-<태그>
```

`data/`는 git에 올리지 않습니다 (`.gitignore`). 생성 데이터는 시드만 있으면 똑같이 다시 만들 수 있습니다.

## 데이터 모양

**events.jsonl** — MongoDB `behavior_log` 문서와 같은 필드 (`member_id`, `ci_hash`, `device_hash`, `ip_hash`, `event_type`,
`ts`, `interval_ms`, `response_status`, `meta`). 생성기도 실제 로그 모양을 따릅니다:
결제(`PAY`)·취소(`CANCEL`)에는 예약 번호만 있고 매수는 선점(`SEAT_HOLD`) 로그에서 찾습니다.

**members.jsonl** — 계정 하나에 한 줄: `member_id`, `label`(정답: NORMAL / MACRO / RESELLER / CANCEL_ABUSER),
`strategy`(수법 또는 대회 종류), `family`(수법 계열), `group`, `ci`, `cards`(결제 수단 해시들), `created_days_ago`, `source`
(`generator` 또는 `live:{태그}` — **학습·시험을 섞지 않는 기준**).

## 생성 데이터의 폭

- 사람 17가지 · 봇 21가지 수법. 사흘 동안 운행 12편, 판매 오픈마다 몰림
- **오탐 함정을 일부러 넣었습니다**: 공용 IP·공용 PC·VPN·저장소가 막힌 브라우저·단체 여행 총무·오픈 순간 연타하는 사람.
  "IP 공유 = 봇", "기기 ID가 자주 바뀜 = 봇" 같은 지름길을 모델이 배우지 못하게
- **봇 계열**(`family`): speed · retry · multi_account · evasive · cancel · hoard. 생성 데이터 안에서도
  **한 계열을 통째로 빼고 학습 → 그 계열로 시험**해 보면, 대회 전에 "처음 보는 수법"에 얼마나 버티는지 미리 볼 수 있습니다
- 수법 목록은 `generate.py`에만 있습니다 (공격자는 대회 전에 보지 않음)

## 특징 (features.csv)

간격·비율·횟수만 씁니다. 좌석 번호·열차 ID 같은 날값과 규칙 엔진의 문턱 값은 넣지 않습니다
(좌석 구조나 한도가 바뀌어도 다시 학습할 일이 적게, 모델이 규칙을 다시 외우지 않게).

| 묶음 | 특징 |
|---|---|
| 양 | `n_events` `n_view` `n_hold` `n_pay` `n_cancel` `browse_before_hold` |
| 간격 | `int_median_s` `int_p10_s` `int_cv` `int_cv_short`(10초 미만 간격만) `frac_int_lt_200ms` `frac_int_lt_1s` |
| 실패 | `hold_fail_ratio` `max_same_seat_fail` `limit_rejects` |
| 구매·취소 | `seats_paid_ratio`(한도 대비) `cancel_ratio` `min_cancel_after_pay_min_log` |
| 연결 | `accounts_on_device` `accounts_on_card` `accounts_on_ip` `min_sync_gap_s_log` `device_missing` |
| 여러 운행·회전 | `n_runs` `distinct_devices` `distinct_ips` |
| 좌석 묶기·넘기기 | `hold_unpaid_ratio` `handoff_in` `handoff_out` (남이 취소한 좌석을 60초 안에 잡음 / 내 좌석을 남이 잡음) |
| 기타 | `first_hold_after_open_log`(운행별 첫 이벤트 기준) `account_age_days_log` |

## 모델 비교 (`compare.py`)

```bash
pip install scikit-learn
python3 tools/ml/compare.py data/ml/gen-s7/features.csv --live data/ml/contest-<태그>/features.csv
```

Isolation Forest(라벨 없이) · 그래디언트 부스팅 · 작은 신경망(MLP)을 같은 문턱(사람 오탐 5%)으로 비교합니다.
사람은 묶음 단위로 학습 60 · 검증 20 · 시험 20으로 나눕니다. `--live`는 대회 데이터에 **적용만** 합니다(학습 안 함).

### 첫 결과 (2026-11-18, 생성 데이터 seed 7 · 6,000명, 리허설 데이터)

| | Isolation Forest | 그래디언트 부스팅 | 신경망 (MLP) |
|---|---|---|---|
| 섞어서 나누기 — 봇 탐지 / 사람 오탐 | 28.7% / 6.8% | 100% / 3.8% | 99.6% / 3.9% |
| **계열 하나 빼기** — 처음 보는 계열 탐지 / 오탐 | 36.1% / 6.8% | **78.9% / 4.1%** | 42.5% / 4.0% |
| 계열별 (부스팅) | | cancel 82 · evasive **61** · hoard 100 · multi_account 100 · retry 88 · speed **64** | |
| 리허설 botc (기기 ID 고친 뒤 5명의) | 0/5 | 5/5 | 5/5 |
| (참고) 같은 botc를 지금 규칙 엔진은 | | 0/5 — 케이스 없음 | |

**읽는 법**
- 섞어서 나누면 100%에 가깝지만 **계열 하나를 빼면 79%로 떨어진다** — 생성 데이터로 시험하면 안 되는 이유가 숫자로 보인다
- 처음 보는 수법에 가장 버티는 건 **그래디언트 부스팅**. 신경망은 이 정도 데이터·특징에서는 오히려 약하다 (42%)
- Isolation Forest는 "튀는" 봇(연타·같은 좌석 반복)만 잡고 사람처럼 보이는 봇은 거의 못 잡는다
- **리허설 botc 5/5는 믿으면 안 되는 숫자다.** botc와 생성기를 같은 사람(강동균)이 만들었고, 생성기의 `b_distributed`는
  botc를 본뜬 수법이다. 잡힌 이유도 "새 계정 + 운행을 둘러보지 않음 + 이벤트 4개뿐"인데, 실제 화면에서 사람이
  몇 개의 이벤트를 남기는지는 생성기가 지어낸 값이다. **실제 사람이 이벤트를 적게 남기면 그대로 오탐이 된다**
  → 대회 날 사람 데이터가 꼭 필요하다
- 처음 돌렸을 때 기기 ID를 고치기 전 리허설(c1)도 전부 잡혔는데, 생성 데이터에서 "기기 정보 없음"은 봇뿐이라 배운 탓이었다.
  데이터를 모으는 방식이 바뀌면(헤더 추가) 모델도 그 차이를 학습해 버린다

## 쓰는 모델 — 그래디언트 부스팅 + Isolation Forest (`train.py` · `score.py`)

```bash
python3 tools/ml/train.py data/ml/model data/ml/g7/features.csv data/ml/g8/features.csv data/ml/g9/features.csv
python3 tools/ml/score.py data/ml/model/model.pkl data/ml/contest-<태그>/features.csv   # → scores.csv (점수·표시·근거)
```

- 본체는 그래디언트 부스팅, Isolation Forest는 안전망. 두 점수를 사람 기준 백분위로 바꿔 큰 쪽을 쓴다 (0~100)
- "계열 하나 빼기"에서 부스팅 혼자 74% → 둘을 합치면 87% (속도형 29 → 89%, 재시도형 73 → 98%)
- 문턱은 합친 점수로 사람 검증 세트 오탐 5%. 계정마다 **사람과 가장 다른 특징 3개**를 근거로 낸다
- 학습은 생성 데이터만 받는다 (`source`가 generator가 아니면 멈춤)

**모델에서 뺀 특징** — 대회 환경이 만드는 가짜 신호라서 (리허설에서 확인)

| 특징 | 이유 |
|---|---|
| `account_age_days_log` | 대회에서는 사람·봇 모두 그날 가입한다 |
| `accounts_on_ip` · `distinct_ips` · `min_sync_gap_ip_s_log` | 로컬 대회는 모두 한 PC(한 IP)에서 들어온다. 사람 3명이 봇과 같이 매겨지자 "한 IP에 8계정"으로 100점 오탐됐다. 실제 운영처럼 사람마다 IP가 다르면 `ML_USE_IP=1` |

**판매 오픈 시각**: 실제 데이터는 `export_live.sh`가 DB의 `sale_open_at`을 `runs.jsonl`로 꺼낸다. 없으면 운행의 첫 이벤트를 쓰는데,
그건 같이 넣은 데이터에 따라 바뀐다 (사람만 매기면 0초, 봇과 같이 매기면 40분).

## 실제 사람 데이터로 맞춘 것 (`human_calibration.json`)

캐글은 로그인 키가 필요하고 이 환경의 네트워크가 막아, 공개 데이터 중 받을 수 있는 것을 썼다:
**UCI Online Shoppers Purchasing Intention** (Sakar et al. 2019, CC BY 4.0) — 실제 쇼핑몰 세션 12,330개 중 구매로 끝난 1,908개.
쇼핑몰과 예매는 도메인이 달라 절대값은 쓰지 않고 **사람 사이의 퍼짐**만 생성기에 입혔다:
사람마다 속도 차이(로그 표준편차 0.53), 둘러보는 양의 차이(0.99). 원본 CSV는 저장소에 넣지 않는다.

## 아직 없는 것

- **실제 사람 데이터** — 오탐률을 믿을 수 있으려면 필요 (대회 날)
- **마우스 궤적** — 행동 로그에 아직 없습니다 (`replay_event`는 ADM-11, P2). 생기면 특징을 더합니다
- **사람 수법의 숫자** — 지금은 지어낸 분포입니다. 대회 날 사람 데이터가 모이면 그 분포로 다시 맞춥니다
