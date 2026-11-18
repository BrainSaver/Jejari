#!/usr/bin/env python3
"""공격 생성기(gen_bot_data.py)가 만든 CSV로 학습하고, 새 시드로 다시 만든 공격 데이터로 시험한다.

CSV에는 봇만 있어서 사람은 같은 어휘로 시뮬레이션해 음성으로 넣는다 (botcsv.gen_human, 가정).
학습 데이터 = CSV 봇 + (선택) 기존 가상 봇 + 사람(CSV 어휘 사람 + 기존 가상 사람)

    python train_csv.py --csv bot_events.csv --gen gen_bot_data.py
    python train_csv.py --csv bot_events.csv --gen gen_bot_data.py --loo     # 처음 보는 공격 유형 평가

--gen 을 주면 그 생성기를 다른 시드로 다시 돌려 시험 데이터를 만든다 (replay 유형은 시드마다 템플릿이 달라진다).
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from jejari_dl import botcsv, metrics, sim, training  # noqa: E402


def fresh_attack(gen: str, seed: int, per_type: int = 40, humans: int = 0) -> list:
    """사용자가 만든 생성기를 표준 라이브러리만으로 새 시드로 돌려 CSV를 받는다 (humans>0이면 합성 사람도, v2 생성기)"""
    with tempfile.TemporaryDirectory() as d:
        out = Path(d) / "attack.csv"
        cmd = [sys.executable, "-I", gen, "--tag", f"x{seed}", "--per-type", str(per_type),
               "--seed", str(seed), "--out", str(out)]
        if humans > 0:
            cmd += ["--humans", str(humans)]
        subprocess.run(cmd, check=True, capture_output=True, timeout=120)
        return botcsv.read_csv(out)


def evaluate(model, thr, test) -> dict:
    p = training.predict(model, test)
    y = np.array([s.label for s in test])
    fam = np.array([s.family for s in test])
    flags = p >= thr
    res = {"auc": metrics.auc(p, y), "thr": thr, "by_type": {}, "fpr": {}}
    for f in sorted(set(fam[y == 1])):
        res["by_type"][f] = (float(flags[fam == f].mean()), int((fam == f).sum()))
    for f in sorted(set(fam[y == 0])):                      # 사람 출처별 오탐률
        res["fpr"][f] = (float(flags[fam == f].mean()), int((fam == f).sum()))
    res["fpr_all"] = float(flags[y == 0].mean())
    res["detect"] = float(flags[y == 1].mean())
    return res


def show(res, title):
    print(f"== {title} ==")
    print(f"  AUC {res['auc']:.3f} · 문턱 {res['thr']:.3f} · 봇 탐지율 {res['detect']:.1%} · "
          f"사람 오탐률 {res['fpr_all']:.1%}")
    print("  사람 출처별 오탐률  " + " · ".join(f"{f} {r:.1%}(n={n})" for f, (r, n) in res["fpr"].items()))
    for f, (r, n) in res["by_type"].items():
        print(f"    {f:<13} {r:>6.1%}  (n={n})")


def build_train(bots_csv, humans_given, args, seed, use_sim_bots=True):
    humans = list(humans_given) + botcsv.make_humans(args.n_human, seed + 1) + sim.make_dataset(args.n_sim_human, 0, seed + 2)
    bots = list(bots_csv) * args.csv_repeat                    # 적은 CSV 봇을 여러 번 (매번 간격 배율 증강이 달라짐)
    if use_sim_bots:
        bots += [s for s in sim.make_dataset(0, args.n_sim_bot, seed + 3) if s.label == 1]
    return humans, bots


def split(items, rng, frac=0.2):
    idx = rng.permutation(len(items))
    k = int(len(items) * frac)
    return [items[i] for i in idx[k:]], [items[i] for i in idx[:k]]


def fit_and_threshold(humans, bots, args, seed):
    rng = np.random.default_rng(seed)
    h_tr, h_va = split(humans, rng)
    b_tr, b_va = split(bots, rng)
    cfg = training.TrainConfig(epochs=args.epochs, seed=seed)
    model = training.fit(h_tr + b_tr, h_va + b_va, cfg, log=None if args.quiet else print)
    # 문턱은 CSV 어휘 사람(시험할 도메인)의 검증 점수로 고른다. 가상 사람을 섞으면 오탐 0%인 쪽이 평균을 낮춰
    # 문턱이 너무 높게 잡힌다 (실제 오탐이 목표의 2배가 되는 원인이었다)
    src = args.thr_source
    if src == "auto":
        src = "human_synth" if any(s.family == "human_synth" for s in h_va) else "csv_human"
    pool = [s for s in h_va if s.family == src] or h_va
    thr = training.pick_threshold(training.predict(model, pool), args.max_fpr)
    return model, thr, cfg


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="공격 CSV 이벤트 로그 (label 1=봇, 0=사람; 봇만 있어도 됨)")
    ap.add_argument("--gen", default=None, help="공격 생성기 스크립트. 있으면 새 시드로 시험 데이터를 만든다")
    ap.add_argument("--out", default="out/model_csv.pt")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--n-human", type=int, default=5000, help="직접 만든 CSV 어휘 사람 수 (CSV에 사람이 있으면 0으로)")
    ap.add_argument("--n-sim-human", type=int, default=5000, help="기존 가상 사람 수 (0이면 빼기)")
    ap.add_argument("--extra-train-seeds", default="", help="생성기를 이 시드들로 더 돌려 학습 데이터를 늘린다 (시험 시드와 겹치면 안 됨)")
    ap.add_argument("--gen-humans", type=int, default=0, help="생성기가 합성 사람을 같이 만들게 한다(v2). 학습·시험 모두 적용")
    ap.add_argument("--n-sim-bot", type=int, default=1200, help="0이면 기존 가상 봇은 빼고 CSV 봇만 쓴다")
    ap.add_argument("--csv-repeat", type=int, default=4)
    ap.add_argument("--max-fpr", type=float, default=0.015, help="검증 사람 기준 허용 오탐률 (목표 2%에 여유를 둔 값)")
    ap.add_argument("--thr-source", default="auto", help="문턱을 고를 검증 사람의 출처 (family 이름)")
    ap.add_argument("--n-test-human", type=int, default=1000, help="시험용 CSV 어휘 사람 수 (많을수록 오탐률이 안정적)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--test-seeds", default="11,12")
    ap.add_argument("--loo", action="store_true")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--summary-only", action="store_true", help="시드별 표 없이 요약만")
    a = ap.parse_args(argv)

    everything = botcsv.read_csv(Path(a.csv))
    for seed in [int(x) for x in a.extra_train_seeds.split(",") if x.strip()]:
        if not a.gen:
            sys.exit("--extra-train-seeds 는 --gen 이 필요합니다")
        if str(seed) in a.test_seeds.split(","):
            sys.exit(f"시드 {seed}는 시험 시드와 겹칩니다")
        everything += fresh_attack(a.gen, seed, humans=a.gen_humans)
    bots_csv = [s for s in everything if s.label == 1]
    humans_given = [s for s in everything if s.label == 0]
    if not bots_csv:
        sys.exit("CSV에 봇(label=1)이 없습니다")
    types = sorted({s.family for s in bots_csv})
    print(f"봇 {len(bots_csv)}세션 ({', '.join(types)}) · 데이터에 든 사람 {len(humans_given)}세션 · "
          f"이벤트 수 {min(len(s.types) for s in everything)}~{max(len(s.types) for s in everything)}")
    if not a.gen:
        print("--gen 이 없어 새 시드 시험은 건너뜁니다")

    def test_set(seed):
        bots, hum = [], []
        if a.gen:
            fresh = fresh_attack(a.gen, seed, humans=a.gen_humans)
            bots = [s for s in fresh if s.label == 1]
            hum = [s for s in fresh if s.label == 0]
        if not hum:                                          # 생성기에 사람이 없으면 직접 만든 사람으로 잰다
            hum = botcsv.make_humans(a.n_test_human, 9000 + seed) + sim.make_dataset(500, 0, 9100 + seed)
        return bots, hum

    if a.loo:
        if not a.gen:
            sys.exit("--loo 는 --gen 이 필요합니다")
        print("\n처음 보는 공격 유형 평가: 그 유형만 빼고 학습, 기존 가상 봇은 쓰지 않음")
        seed0 = int(a.test_seeds.split(",")[0])
        tb, th = test_set(seed0)
        rows = []
        for held in types:
            humans, bots = build_train([s for s in bots_csv if s.family != held], humans_given, a, a.seed,
                                       use_sim_bots=False)
            model, thr, _ = fit_and_threshold(humans, bots, a, a.seed)
            res = evaluate(model, thr, [s for s in tb if s.family == held] + th)
            rows.append((held, res))
            print(f"  [{held}] 빼고 학습 → 처음 본 {held} 탐지율 {res['detect']:.1%}, 사람 오탐 {res['fpr_all']:.1%}",
                  flush=True)
        print(f"\n{'처음 본 유형':<14}{'탐지율':>8}{'사람 오탐':>10}")
        for held, r in rows:
            print(f"{held:<14}{r['detect']:>8.1%}{r['fpr_all']:>10.1%}")
        print(f"{'평균':<14}{np.mean([r['detect'] for _, r in rows]):>8.1%}"
              f"{np.mean([r['fpr_all'] for _, r in rows]):>10.1%}")
        return

    humans, bots = build_train(bots_csv, humans_given, a, a.seed, use_sim_bots=a.n_sim_bot > 0)
    print(f"학습 풀: 사람 {len(humans)} · 봇 {len(bots)} (공격 {len(bots_csv)}×{a.csv_repeat}"
          f"{' + 가상 봇 ' + str(a.n_sim_bot) if a.n_sim_bot > 0 else ''})")
    model, thr, cfg = fit_and_threshold(humans, bots, a, a.seed)
    training.save(model, Path(a.out), thr, cfg, {"data": "attack csv (+simulated humans)", "max_fpr": a.max_fpr})
    print(f"저장: {a.out}")
    if a.gen:
        fprs, per_type, aucs = [], {}, []
        for seed in [int(x) for x in a.test_seeds.split(",")]:
            tb, th = test_set(seed)
            res = evaluate(model, thr, tb + th)
            fprs.append(res["fpr_all"])
            aucs.append(res["auc"])
            for f, (r, _) in res["by_type"].items():
                per_type.setdefault(f, []).append(r)
            if not a.summary_only:
                show(res, f"새 시드 {seed}로 다시 만든 공격 + 새 사람 (학습에 안 쓴 데이터)")
        print(f"\n== 새 시드 {len(fprs)}개 요약 (학습에 안 쓴 데이터) ==")
        print(f"사람 오탐률 시드별 평균 {np.mean(fprs):.2%} · 최대 {max(fprs):.2%} · AUC 평균 {np.mean(aucs):.3f}")
        print("유형별 탐지율 평균(최저): " + " · ".join(f"{f} {np.mean(v):.0%}({min(v):.0%})" for f, v in per_type.items()))
        print(f"봇 탐지율 평균 {np.mean([np.mean(v) for v in per_type.values()]):.1%}")


if __name__ == "__main__":
    main()
