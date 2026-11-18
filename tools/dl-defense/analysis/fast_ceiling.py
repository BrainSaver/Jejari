# fast 0.35배 대 사람의 구별 한계 점검. tools/dl-defense 에서 `python analysis/fast_ceiling.py` (out/model_fastspec.pt·model_fast_s0.pt 필요)
import sys, subprocess, tempfile, math, csv, collections
sys.path.insert(0,'.')
import numpy as np, torch
from pathlib import Path
from jejari_dl import botcsv, training, metrics
from jejari_dl.schema import to_batch
torch.manual_seed(0)
def gen(seed, per, humans, lo, hi):
    with tempfile.TemporaryDirectory() as d:
        o=Path(d)/"a.csv"
        subprocess.run([sys.executable,"-I","attacks/gen_test_attack.py","--seed",str(seed),"--per-variant",str(per),"--humans",str(humans),"--variants","fast","--fast-scale-lo",str(lo),"--fast-scale-hi",str(hi),"--out",str(o)],check=True,capture_output=True)
        return botcsv.read_csv(o,True,True)
te=gen(790,300,3000,.35,.35)
y=np.array([s.label for s in te])
def curve(score,name):
    h=score[y==0]; out=[]
    for f in (0.005,0.01,0.015,0.03,0.05,0.10):
        t=np.quantile(h,1-f,method='higher'); out.append(f"{f:.1%}:{(score[y==1]>t).mean():.0%}")
    print(f"{name:<34} AUC {metrics.auc(score,y):.3f} | 사람 오탐별 탐지율 "+"  ".join(out))
spec=training.load(Path("out/model_fastspec.pt"))[0]
curve(training.predict(spec,te),"fast 전용 모델 (현재)")
main=training.load(Path("out/model_fast_s0.pt"))[0]
curve(training.predict(main,te),"주 모델")
with torch.no_grad():
    b=to_batch(te); raw=spec._raw_stats(torch.from_numpy(b.types),torch.from_numpy(b.dt),torch.from_numpy(b.length)).numpy()
curve(-raw[:,0],"속도 평균(z평균) 규칙 하나")
curve(-(raw[:,0]+raw[:,9])/2,"z평균+나머지z 평균")
# 더 풍부한 통계량 + 신경망 (학습: 시드 40개, 배율 0.2~0.6 vs 사람)
tr=[]
for sd in range(601,621): tr+=gen(sd,60,300,.2,.6)
mu=spec.type_mu.numpy()
def feats(s):
    ds=np.array(s.dt_ms[1:]); ty=np.array(s.types[1:])
    z=np.log1p(ds)-mu[ty]; n=len(z)
    ac=np.corrcoef(z[:-1],z[1:])[0,1] if n>3 else 0
    seat=z[ty==3]; oth=z[ty!=3]; ps=np.sort(z)
    return [z.mean(),z.std(),ac if np.isfinite(ac) else 0,z.max(),z.min(),np.median(z),(z**3).mean(),np.mean(seat) if len(seat) else 0,np.mean(oth) if len(oth) else 0,
            np.abs(np.diff(z)).mean(),n,ps[:3].mean(),ps[-3:].mean(),z[:3].mean(),z[3:].mean() if n>3 else 0,np.log(ds).std(),np.log(ds).min(),np.log(ds).max(),float((ds<400).mean()),float((ds<600).mean())]
Xtr=np.array([feats(s) for s in tr],dtype=np.float32); ytr=np.array([s.label for s in tr]); Xte=np.array([feats(s) for s in te],dtype=np.float32)
m,sd=Xtr.mean(0),Xtr.std(0)+1e-6
Xt=torch.tensor((Xtr-m)/sd);Yt=torch.tensor(ytr,dtype=torch.float32);Xe=torch.tensor((Xte-m)/sd)
net=torch.nn.Sequential(torch.nn.Linear(Xt.shape[1],64),torch.nn.GELU(),torch.nn.Linear(64,64),torch.nn.GELU(),torch.nn.Linear(64,1))
opt=torch.optim.AdamW(net.parameters(),1e-3,weight_decay=1e-2); w=torch.tensor((ytr==0).sum()/(ytr==1).sum(),dtype=torch.float32)
for ep in range(200):
    perm=torch.randperm(len(Xt))
    for i in range(0,len(Xt),256):
        idx=perm[i:i+256]; l=torch.nn.functional.binary_cross_entropy_with_logits(net(Xt[idx]).squeeze(1),Yt[idx],pos_weight=w); opt.zero_grad();l.backward();opt.step()
with torch.no_grad(): sc=net(Xe).squeeze(1).numpy()
curve(sc,"풍부한 통계량 20개 + 신경망(상한 추정)")
