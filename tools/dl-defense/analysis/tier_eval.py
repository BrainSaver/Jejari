# 모델 시드 0·1·2 (주 + fast 전용) 조합을 단계형으로 보정하고 v3 17변형·시험 공격(새 시드)으로 시험. tools/dl-defense 에서 실행
"""모델 시드 0·1·2의 (주 + 전용) 조합을 단계형으로 보정하고, v3 17변형·시험 공격(새 시드)을 시험한다."""
import sys, subprocess, tempfile, json; sys.path.insert(0,'.')
import numpy as np
from pathlib import Path
from jejari_dl import training, botcsv
import train_csv as T
T.PER_FLAG='per-variant'; T.DEVICE_CTX=True; T.DUP_CTX=True
G="/root/.claude/uploads/2265a741-6306-5831-ad45-2b74b11d3654/319d05b3-gen_bot_data_v3.py"
def gen_attack(seed,variants,lo,hi,humans=400,per=60):
    with tempfile.TemporaryDirectory() as d:
        o=Path(d)/"a.csv"
        subprocess.run([sys.executable,"-I","attacks/gen_test_attack.py","--seed",str(seed),"--per-variant",str(per),"--humans",str(humans),"--variants",variants,"--fast-scale-lo",str(lo),"--fast-scale-hi",str(hi),"--out",str(o)],check=True,capture_output=True)
        return botcsv.read_csv(o,True,True)
cal=[s for sd in range(401,409) for s in T.fresh_attack(G,sd,per_type=30,humans=400) if s.label==0]
v3=[s for sd in range(31,41) for s in T.fresh_attack(G,sd,per_type=30,humans=400)]
v3y=np.array([s.label for s in v3]); v3f=np.array([s.family for s in v3])
atk={"pool":gen_attack(783,"pool",.35,.35,per=80),"fast0.35":gen_attack(784,"fast",.35,.35,per=100),"fast0.5":gen_attack(785,"fast",.5,.5,per=100),"fast0.65":gen_attack(786,"fast",.65,.65,per=100),"fast0.8":gen_attack(787,"fast",.8,.8,per=100)}
ay={k:np.array([s.label for s in v]) for k,v in atk.items()}
rows=[]
for sd,(mp,sp) in {0:("out/model_fast_s0.pt","out/model_fastspec.pt"),1:("out/model_fast_s1.pt","out/model_fastspec_s1.pt"),2:("out/model_fast_s2.pt","out/model_fastspec_s2.pt")}.items():
    if not (Path(mp).exists() and Path(sp).exists()): print("없음:",mp,sp); continue
    models=[training.load(Path(m))[0] for m in (mp,sp)]
    pr=lambda subs:[training.predict(m,subs) for m in models]
    cps=pr(cal)
    hard=training.fit_union_thresholds(cps,0.015,0.005); soft=training.fit_union_thresholds(cps,0.05,0.04); soft=[min(a,b) for a,b in zip(soft,hard)]
    t3=training.assign_tier(pr(v3),hard,soft)
    r={"seed":sd,"v3_h2":(t3[v3y==0]==2).mean(),"v3_h1":(t3[v3y==0]>=1).mean(),"v3_b2":(t3[v3y==1]==2).mean(),"v3_b1":(t3[v3y==1]>=1).mean()}
    ev={}
    for f in sorted(set(v3f[v3y==1])):
        if f.startswith('evasive'): ev[f.split('/')[1]]=((t3[v3f==f]==2).mean(),(t3[v3f==f]>=1).mean())
    r["ev"]=ev
    for k,subs in atk.items():
        t=training.assign_tier(pr(subs),hard,soft); r[k]=((t[ay[k]==1]==2).mean(),(t[ay[k]==1]>=1).mean(),(t[ay[k]==0]==2).mean(),(t[ay[k]==0]>=1).mean())
    rows.append(r)
print(f"보정 사람 {len(cal)}명 · v3 시험 10시드 (사람 {int((v3y==0).sum())}명 · 봇 {int((v3y==1).sum())}세션) · 시험 공격 새 시드 (파일마다 사람 400명)")
print("강한 단계 오탐 1.5% / 약한 단계 오탐 5% 로 보정. 값: 강한(2) / 약한 이상(1+)")
print("모델 시드 | v3 사람 | v3 봇 탐지 | pool | fast0.35 | fast0.5 | fast0.65 | fast0.8 | 시험공격 사람(2 / 1+)")
for r in rows:
    hh=np.mean([r[k][2] for k in atk]); h1=np.mean([r[k][3] for k in atk])
    print(f"{r['seed']} | {r['v3_h2']:.2%} / {r['v3_h1']:.2%} | {r['v3_b2']:.1%} / {r['v3_b1']:.1%} | "+" | ".join(f"{r[k][0]:.0%} / {r[k][1]:.0%}" for k in atk)+f" | {hh:.2%} / {h1:.2%}")
    print("    v3 evasive: "+" · ".join(f"{k} {a:.0%}/{b:.0%}" for k,(a,b) in r['ev'].items()))
if len(rows)>1:
    m=lambda f: np.mean([f(r) for r in rows])
    print(f"평균 | {m(lambda r:r['v3_h2']):.2%} / {m(lambda r:r['v3_h1']):.2%} | {m(lambda r:r['v3_b2']):.1%} / {m(lambda r:r['v3_b1']):.1%} | "+" | ".join(f"{m(lambda r,k=k:r[k][0]):.0%} / {m(lambda r,k=k:r[k][1]):.0%}" for k in atk))
