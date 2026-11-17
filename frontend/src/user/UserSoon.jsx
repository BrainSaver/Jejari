import { Link } from 'react-router';
import Icon from '../admin/Icon.jsx';

/** 아직 만들지 않은 사용자 화면 자리 */
export default function UserSoon({ id, title, owner, children }) {
  return (
    <>
      <header className="top"><h1>{title}</h1></header>
      <main className="body narrow">
        <p className="guide"><Icon name="info" /><span>이 화면은 준비 중이에요. [열차 찾기]로 돌아가 다른 열차를 볼 수 있어요.</span></p>
        {children}
        <div className="card mut" style={{ fontSize: 14 }}>
          시안 <code>docs/ui/{id}.html</code> · 담당 {owner}
        </div>
        <Link className="btn ghost" to="/">열차 찾기로 돌아가기</Link>
      </main>
    </>
  );
}
