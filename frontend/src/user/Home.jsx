import { Link } from 'react-router';

/** 사용자 첫 화면 자리 (U-03부터 시안을 옮긴다). 지금은 틀만 */
export default function Home() {
  return (
    <div style={{ maxWidth: 760, margin: '0 auto', padding: '48px 16px' }}>
      <div className="saek" />
      <h1 className="serif" style={{ fontSize: 40, margin: '24px 0 8px' }}>제자리</h1>
      <p className="mut">명절 열차, 공정하게 내 자리. 사용자 화면은 시안(docs/ui/U-*.html)을 차례로 옮기는 중이에요.</p>
      <p><Link to="/admin">운영 콘솔로 가기</Link></p>
    </div>
  );
}
