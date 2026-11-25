import { Link, useLocation } from 'react-router';
import Icon from '../admin/Icon.jsx';

/**
 * 없는 주소. 예전엔 홈으로 말없이 옮겨서 주소를 잘못 친 건지 알 수 없었다.
 * 서버도 이런 주소에는 404 상태로 이 화면을 돌려준다 (GlobalExceptionHandler).
 */
export default function NotFound() {
  const { pathname } = useLocation();
  return (
    <main className="body narrow" style={{ paddingTop: 40 }}>
      <p className="guide"><Icon name="info" /><span>주소가 바뀌었거나 잘못 입력된 것 같아요. 아래 버튼으로 열차를 찾을 수 있어요.</span></p>
      <h1 style={{ fontSize: 26, margin: '0 0 8px' }}>없는 페이지예요</h1>
      <p className="mut" style={{ margin: '0 0 20px', wordBreak: 'break-all' }}>찾는 주소: <span className="num">{pathname}</span></p>
      <Link className="btn ink" to="/" style={{ alignSelf: 'flex-start', textDecoration: 'none' }}>열차 찾기로 가기</Link>
    </main>
  );
}
