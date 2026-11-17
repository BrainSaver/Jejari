import { useOutletContext } from 'react-router';
import { Who } from './AdminLayout.jsx';

/** 아직 만들지 않은 관리자 화면 자리 */
export default function Soon({ id, title, owner }) {
  const claims = useOutletContext();
  return (
    <>
      <div className="head">
        <h1>{title}</h1>
        <span style={{ flex: 1 }} />
        <Who claims={claims} />
      </div>
      <div className="soon-box">
        아직 만들지 않은 화면이에요. 시안은 <code>docs/ui/{id}.html</code>, 설명은 <code>docs/ui/{id}.md</code>에 있어요. 담당: {owner}
      </div>
    </>
  );
}
