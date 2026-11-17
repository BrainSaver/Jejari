import { duration, hm } from './fmt.js';

/**
 * 출발 → 도착 굵은 선 (시안 .route). from/to = {name, time}
 * ride면 선 위를 작은 기차가 출발역에서 도착역으로 한 번 달린다 (판매 중이면 마우스를 올릴 때 다시)
 */
export default function RouteLine({ from, to, sm = true, ride = false, replay = false }) {
  return (
    <div className={'route' + (sm ? ' sm' : '')}>
      <div><div className="t">{hm(from?.time)}</div><div className="s">{from?.name}</div></div>
      <div className="mid">
        <i>{ride && <MiniTrain replay={replay} />}</i>
        <span>{duration(from?.time, to?.time)}</span>
      </div>
      <div className="end"><div className="t">{hm(to?.time)}</div><div className="s">{to?.name}</div></div>
    </div>
  );
}

/** 선 위를 달리는 작은 기차 + 지나간 자리가 황금색으로 차오르는 꼬리 */
export function MiniTrain({ replay }) {
  return (
    <>
      <span className="trail" aria-hidden="true" />
      <span className={'mini-train' + (replay ? ' replay' : '')} aria-hidden="true">
        <svg viewBox="0 0 30 22">
          <rect x="1" y="3" width="26" height="13" rx="6" fill="var(--sign)" stroke="var(--ink)" strokeWidth="2" />
          <rect x="6" y="6" width="6" height="4" rx="1" fill="var(--ink)" />
          <rect x="15" y="6" width="6" height="4" rx="1" fill="var(--ink)" />
          <circle cx="8" cy="18" r="2.6" fill="var(--ink)" />
          <circle cx="20" cy="18" r="2.6" fill="var(--ink)" />
        </svg>
      </span>
    </>
  );
}
