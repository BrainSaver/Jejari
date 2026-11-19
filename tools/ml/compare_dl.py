#!/usr/bin/env python3
"""대회 로그에 두 위험 점수 모델을 같이 돌려 표 한 장을 낸다 — tools/ml(강동균) vs tools/dl-defense(김성원).

    tools/ml/export_live.sh <태그> data/ml/contest-<태그>
    python3 tools/ml/compare_dl.py data/ml/contest-<태그>                     # 김성원 모델은 origin/SeongWon에서 꺼냄
    python3 tools/ml/compare_dl.py data/ml/contest-<태그> --dl-dir <dl-defense 폴더>

→ <폴더>/compare.md (표 + 미리 정한 기준에 따른 판정). 아무것도 학습하지 않고 점수만 매긴다.

왜 이렇게 하나 (docs/강동균/모델-비교-김성원-20261119.md)
  - 김성원 모델은 main에 넣지 않는다. 블라인드 공격자(길동현)가 탐지 방법을 읽지 않게 실행할 때만 브랜치에서 꺼내
    임시 폴더에서 돌린다
  - 김성원 모델의 realdata.py는 화면 자동 조회(meta.auto = true)를 빼지 않아 실제 사람을 봇으로 본다 (리허설 12/12).
    그 코드를 고치지 않고, 넣기 전에 이 스크립트가 자동 이벤트를 뺀다. tools/ml/features.py는 원래 뺀다
  - 판정 기준은 결과를 보기 전에 정했다 (JUDGE). 결과를 보고 기준을 고르지 않는다
"""
from __future__ import annotations

import argparse
import csv
import glob
import json
import os
import subprocess
import sys
import tempfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# 모델을 만든 사람의 봇 (loadtest/contest/README.md 배정표). 자기 봇은 수법을 알고 만든 모델이라 탐지율에서 뺀다
OWN_BOTS = {"tools/ml": {"botc", "botx"}, "dl-defense": {"bota"}}
BLIND_BOTS = {"bota", "botb"}          # 블라인드 공격자(김성원·길동현). botx(약한 블라인드)는 강동균 몫이라 tools/ml에서 빠진다
MAX_HUMAN_FP = 0.05                    # 기준 1: 실제 사람 오탐 5% 이하
MIN_HUMANS = 30                        # 이보다 적으면 오탐률을 믿기 어렵다고 표시

JUDGE = f"""\
## 판정 기준 (2026-11-19, 결과를 보기 전에 정함)

1. **실제 사람(human) 오탐 {MAX_HUMAN_FP:.0%} 이하**인 모델만 후보. 사람이 {MIN_HUMANS}명보다 적으면 판정을 미루고 2차 대회 데이터를 더한다
2. 후보 중 **남의 블라인드 봇**(bota·botb에서 그 모델을 만든 사람의 봇을 뺀 것) 탐지율이 높은 쪽
3. 두 모델을 OR로 합쳐도 사람 오탐이 {MAX_HUMAN_FP:.0%} 이하이고 2번 탐지율이 더 높으면 합친 쪽
   (김성원 모델은 "같은 타이밍 템플릿" 봇을 잡는 보조 점수로 넣는다. ERD model_score.by에 DL 추가 필요)
4. 동률이면 지금 연결 준비가 된 tools/ml
5. 김성원 모델은 **s0**(README의 기본 예시)으로 판정한다. s1·s2는 흔들림을 보는 참고값 (결과를 보고 좋은 시드를 고르지 않는다)
"""


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"실패: {' '.join(cmd)}\n{r.stderr[-2000:]}")
    return r.stdout


def load_members(folder):
    with open(os.path.join(folder, "members.jsonl"), encoding="utf-8") as f:
        return {d["member_id"]: d for d in (json.loads(l) for l in f if l.strip())}


def score_ours(folder, model):
    if not os.path.exists(os.path.join(folder, "features.csv")):
        run([sys.executable, "-I", os.path.join(HERE, "features.py"), folder])
    run([sys.executable, os.path.join(HERE, "score.py"), model, os.path.join(folder, "features.csv")])
    with open(os.path.join(folder, "scores.csv"), encoding="utf-8") as f:
        return {int(r["member_id"]): r["flag"] == "1" for r in csv.DictReader(f)}


def get_dl_dir(args, tmp):
    if args.dl_dir:
        return os.path.abspath(args.dl_dir)
    tar = subprocess.run(["git", "-C", ROOT, "archive", args.dl_ref, "tools/dl-defense"], capture_output=True)
    if tar.returncode != 0:
        sys.exit(f"{args.dl_ref}에서 tools/dl-defense를 꺼내지 못함 (git fetch origin SeongWon 먼저):\n{tar.stderr.decode()[-500:]}")
    subprocess.run(["tar", "-x", "-C", tmp], input=tar.stdout, check=True)
    return os.path.join(tmp, "tools", "dl-defense")


def score_dl(folder, dl_dir, tmp):
    """시드마다 {member_id: tier}. 자동 이벤트를 뺀 로그를 넣는다"""
    src, dst = os.path.join(folder, "events.jsonl"), os.path.join(tmp, "events.noauto.jsonl")
    dropped = 0
    with open(src, encoding="utf-8") as f, open(dst, "w", encoding="utf-8") as w:
        for line in f:
            if line.strip() and (json.loads(line).get("meta") or {}).get("auto"):
                dropped += 1
                continue
            w.write(line)
    out = {}
    for spec in sorted(glob.glob(os.path.join(dl_dir, "models", "union_s*.json"))):
        name = os.path.basename(spec)[len("union_"):-len(".json")]
        tiers = {}
        for line in run([sys.executable, "score.py", "--union", spec, "--input", dst], cwd=dl_dir).splitlines():
            r = json.loads(line)
            if r["key"].startswith("m:"):
                tiers[int(r["key"][2:])] = r["tier"]
        out[name] = tiers
    if not out:
        sys.exit(f"{dl_dir}/models/union_s*.json 이 없음")
    return out, dropped


def pct(xs):
    return (100 * sum(xs) / len(xs), len(xs)) if xs else (None, 0)


def show(v, n=None):
    t = "-" if v is None else f"{v:.1f}%"
    return t if n is None else f"{t} ({n})"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("folder", help="export_live.sh가 만든 폴더 (events.jsonl · members.jsonl)")
    ap.add_argument("--model", default=os.path.join(ROOT, "data", "ml", "model", "model.pkl"))
    ap.add_argument("--dl-dir", help="tools/dl-defense 폴더 (없으면 --dl-ref에서 꺼냄)")
    ap.add_argument("--dl-ref", default="origin/SeongWon")
    a = ap.parse_args()

    mem = load_members(a.folder)
    ours = score_ours(a.folder, a.model)
    with tempfile.TemporaryDirectory() as tmp:
        dl, dropped = score_dl(a.folder, get_dl_dir(a, tmp), tmp)

    first = sorted(dl)[0]
    models = {"tools/ml": ("tools/ml", lambda m: ours.get(m, False))}
    for s in sorted(dl):
        models[f"dl-defense {s} 강한 단계"] = ("dl-defense", lambda m, s=s: dl[s].get(m, 0) == 2)
    models[f"dl-defense {first} 약한 단계 이상"] = ("dl-defense", lambda m: dl[first].get(m, 0) >= 1)
    models[f"OR (tools/ml · dl-defense {first} 강한)"] = (
        "both", lambda m: ours.get(m, False) or dl[first].get(m, 0) == 2)

    humans = [m for m, d in mem.items() if d["label"] == "NORMAL"]
    bots = [m for m, d in mem.items() if d["label"] != "NORMAL"]
    kind = {m: d.get("strategy", "") for m, d in mem.items()}

    def others_blind(owner):
        own = OWN_BOTS.get(owner, set()) if owner != "both" else OWN_BOTS["tools/ml"] | OWN_BOTS["dl-defense"]
        return [m for m in bots if kind[m] in BLIND_BOTS and kind[m] not in own]

    lines = [f"# 모델 비교 · {os.path.basename(os.path.abspath(a.folder))}", "",
             f"사람 {len(humans)} · 봇 {len(bots)} · 김성원 모델에 넣기 전 뺀 자동 조회 {dropped}건", "",
             "| 모델 | 사람 오탐 | 봇 전체 | 남의 블라인드 봇 | 블라인드 봇 전체 |", "|---|---|---|---|---|"]
    stat = {}
    for name, (owner, f) in models.items():
        fp, nh = pct([f(m) for m in humans])
        db, nb = pct([f(m) for m in bots])
        ob, no = pct([f(m) for m in others_blind(owner)])
        bb, nbb = pct([f(m) for m in bots if kind[m] in BLIND_BOTS])
        stat[name] = (fp, ob, no)
        lines.append(f"| {name} | {show(fp, nh)} | {show(db, nb)} | {show(ob, no)} | {show(bb, nbb)} |")

    lines += ["", "### 종류별 탐지 (사람은 오탐)", "", "| 종류 | 수 | " + " | ".join(models) + " |",
              "|---|---|" + "---|" * len(models)]
    by = defaultdict(list)
    for m in mem:
        by[kind[m]].append(m)
    for k in sorted(by):
        cells = [show(pct([f(m) for m in by[k]])[0]) for _, f in models.values()]
        lines.append(f"| {k} | {len(by[k])} | " + " | ".join(cells) + " |")

    lines += ["", JUDGE, "## 판정", ""]
    judged = {"tools/ml": stat["tools/ml"], f"dl-defense {first}": stat[f"dl-defense {first} 강한 단계"]}
    orn = next(n for n in stat if n.startswith("OR"))
    if len(humans) < MIN_HUMANS:
        lines.append(f"- **보류**: 사람 {len(humans)}명 (< {MIN_HUMANS}). 오탐률이 한두 명에 크게 흔들린다. 2차 대회 데이터를 더해 다시 돌린다. 아래는 참고")
    if any(v[2] == 0 for v in judged.values()):
        lines.append("- **기준 2를 쓸 수 없음**: 남의 블라인드 봇(bota·botb)이 없는 모델이 있다. 블라인드 봇이 든 대회 데이터로 다시 돌린다")
    else:
        ok = {n: v for n, v in judged.items() if v[0] is not None and v[0] <= 100 * MAX_HUMAN_FP}
        if not ok:
            lines.append(f"- 기준 1을 넘는 모델이 없다 (모두 사람 오탐 > {MAX_HUMAN_FP:.0%}). 어느 모델도 보조 의견으로 올리지 않는다")
        else:
            best = max(ok, key=lambda n: (ok[n][1], n == "tools/ml"))
            lines.append(f"- 기준 1 통과: {', '.join(ok)}")
            lines.append(f"- 기준 2: **{best}** (남의 블라인드 봇 {show(ok[best][1], ok[best][2])})")
            fp, ob, _ = stat[orn]
            if fp is not None and fp <= 100 * MAX_HUMAN_FP and ob > ok[best][1]:
                lines.append(f"- 기준 3: **OR이 낫다** (사람 오탐 {show(fp)}, 탐지 {show(ob)}) → 두 모델을 합쳐 연결")
            else:
                lines.append(f"- 기준 3: OR은 채택 안 함 (사람 오탐 {show(fp)}, 탐지 {show(ob)})")

    report = "\n".join(lines) + "\n"
    with open(os.path.join(a.folder, "compare.md"), "w", encoding="utf-8") as f:
        f.write(report)
    print(report)


if __name__ == "__main__":
    main()
