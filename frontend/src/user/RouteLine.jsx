import { duration, hm } from './fmt.js';

/** 출발 → 도착 굵은 선 (시안 .route). from/to = {name, time} */
export default function RouteLine({ from, to, sm = true }) {
  return (
    <div className={'route' + (sm ? ' sm' : '')}>
      <div><div className="t">{hm(from?.time)}</div><div className="s">{from?.name}</div></div>
      <div className="mid"><i /><span>{duration(from?.time, to?.time)}</span></div>
      <div className="end"><div className="t">{hm(to?.time)}</div><div className="s">{to?.name}</div></div>
    </div>
  );
}
