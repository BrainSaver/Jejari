import { Link, useLocation, useParams } from 'react-router';
import Icon from '../admin/Icon.jsx';

/**
 * 좌석을 잡은 뒤 결제(U-06)로 넘어가는 자리. 결제 화면은 길동현·강동균 담당이라 아직 없고,
 * 여기서는 서버가 돌려준 선점 결과(예약 번호·결제 기한·금액)를 그대로 보여 준다.
 */
export default function PayNext() {
  const { reservationId } = useParams();
  const { state } = useLocation();
  const h = state?.hold;
  return (
    <>
      <header className="top narrow"><h1>결제</h1></header>
      <main className="body narrow">
        <ol className="flow" aria-label="예매 단계">
          <li className="done"><b>✓</b>줄 서기</li><li className="done"><b>✓</b>좌석 고르기</li>
          <li className="now" aria-current="step"><b>3</b>결제</li><li><b>4</b>확정</li>
        </ol>
        <div className="alert go"><Icon name="info" /><span>좌석을 잡았어요. 결제 기한 안에 결제하면 예매가 끝나요.</span></div>
        <div className="card">
          <div className="kv"><span>예약 번호</span><span className="num">{reservationId}</span></div>
          {h && <div className="kv"><span>좌석</span><span>{state.picked.map((p) => `${p.carNo}호차 ${p.seatNo}`).join(', ')}</span></div>}
          {h && <div className="kv"><span>결제 기한</span><span className="num">{h.payDeadlineAt?.slice(11, 16)}까지</span></div>}
          {h && <div className="kv total"><span>결제할 금액</span><span className="num">{h.totalAmount.toLocaleString()}원</span></div>}
        </div>
        <div className="card mut" style={{ fontSize: 14 }}>결제 화면(U-06)은 준비 중이에요 · 시안 <code>docs/ui/U-06.html</code> · 담당 길동현·강동균</div>
        <Link className="btn ghost" to="/">열차 찾기로 돌아가기</Link>
      </main>
    </>
  );
}
