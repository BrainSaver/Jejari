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
# 학습용: 생성 (파이썬 기본 모듈만, 몇 초)
python3 -I tools/ml/generate.py --members 5000 --seed 7 --out data/ml/gen-s7
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
`strategy`(수법 또는 대회 종류), `group`, `ci`, `cards`(결제 수단 해시들), `created_days_ago`, `source`
(`generator` 또는 `live:{태그}` — **학습·시험을 섞지 않는 기준**).

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
| 기타 | `first_hold_after_open_log` `account_age_days_log` |

## 아직 없는 것

- **모델 학습·비교 스크립트** — Isolation Forest·작은 딥러닝 모델은 scikit-learn 등이 필요합니다. 위험 점수 담당(강다인)과
  어떤 모델을 쓸지 정한 뒤 붙입니다
- **마우스 궤적** — 행동 로그에 아직 없습니다 (`replay_event`는 ADM-11, P2). 생기면 특징을 더합니다
- **사람 수법의 숫자** — 지금은 지어낸 분포입니다. 대회 날 사람 데이터가 모이면 그 분포로 다시 맞춥니다
