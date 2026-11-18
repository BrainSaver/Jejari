"""평가 지표와 비교용 규칙 기준선.

규칙 기준선은 RiskPolicy.defaults()의 문턱(빠른 간격 200ms·20회, 변동계수 0.05·30개, 같은 좌석 5회,
동시 선점 계정 2개)을 이 가상 데이터에 맞게 옮긴 근사다.
같은 좌석 5회는 모델 입력에 좌석 번호가 없어서 시뮬레이터가 따로 적은 값(same_seat_fail)을 쓴다. 실제 엔진(RiskEngine)이 아니다.
"""
from __future__ import annotations

import numpy as np

from .schema import Subject


def rule_baseline_flag(s: Subject) -> bool:
    gaps = np.array(s.dt_ms[1:], dtype=float)
    gaps = gaps[gaps <= 600_000]
    if (gaps < 200).sum() >= 20:
        return True
    if len(gaps) >= 30 and gaps.mean() > 0 and gaps.std() / gaps.mean() <= 0.05:
        return True
    if s.same_seat_fail >= 5:        # SAME_SEAT_RETRY. 좌석 번호는 모델 입력에 없어 시뮬레이터가 따로 기록한 값
        return True
    return s.sync >= 2


def auc(scores: np.ndarray, labels: np.ndarray) -> float:
    """순위 기반 AUC (동점은 평균 순위)"""
    pos, neg = scores[labels == 1], scores[labels == 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    order = np.argsort(np.concatenate([pos, neg]), kind="mergesort")
    allv = np.concatenate([pos, neg])[order]
    ranks = np.empty(len(allv))
    i = 0
    while i < len(allv):
        j = i
        while j + 1 < len(allv) and allv[j + 1] == allv[i]:
            j += 1
        ranks[i:j + 1] = (i + j) / 2 + 1
        i = j + 1
    r = np.empty(len(allv))
    r[order] = ranks
    return float((r[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))


def report(subjects: list[Subject], probs: np.ndarray, threshold: float) -> dict:
    labels = np.array([s.label for s in subjects])
    flags = probs >= threshold
    rules = np.array([rule_baseline_flag(s) for s in subjects])
    humans = labels == 0
    fam = {}
    for f in sorted({s.family for s in subjects if s.label == 1}):
        m = np.array([s.family == f for s in subjects])
        fam[f] = {"n": int(m.sum()), "dl": float(flags[m].mean()), "rules": float(rules[m].mean())}
    return {"n_human": int(humans.sum()), "n_bot": int((~humans).sum()),
            "auc": auc(probs, labels), "threshold": threshold,
            "human_fpr_dl": float(flags[humans].mean()), "human_fpr_rules": float(rules[humans].mean()),
            "bot_detect_dl": float(flags[~humans].mean()), "bot_detect_rules": float(rules[~humans].mean()),
            "by_family": fam}


def format_report(r: dict, title: str) -> str:
    lines = [f"== {title} ==",
             f"사람 {r['n_human']}명 · 봇 {r['n_bot']}명 · AUC {r['auc']:.3f} · 문턱 {r['threshold']:.3f}",
             f"  사람 오탐률  딥러닝 {r['human_fpr_dl']:.1%}   규칙 기준선 {r['human_fpr_rules']:.1%}",
             f"  봇 탐지율    딥러닝 {r['bot_detect_dl']:.1%}   규칙 기준선 {r['bot_detect_rules']:.1%}",
             "  유형별 탐지율 (딥러닝 / 규칙 기준선)"]
    for f, v in r["by_family"].items():
        lines.append(f"    {f:<16} {v['dl']:>6.1%} / {v['rules']:>6.1%}   (n={v['n']})")
    return "\n".join(lines)
