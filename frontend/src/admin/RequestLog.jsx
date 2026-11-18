import { useEffect, useState } from 'react';
import { subscribeRequestLog } from '../api/client.js';
import { seoulTime } from '../lib/time.js';

/** 이 브라우저가 보낸 API 요청 (관리자 화면 공통). traceId로 서버 로그를 찾는다 */
export default function RequestLog({ rows = 8, filter }) {
  const [log, setLog] = useState([]);
  useEffect(() => subscribeRequestLog(setLog), []);
  const shown = (filter ? log.filter(filter) : log).slice(0, rows);
  return (
    <section className="panel">
      <h2>이 화면이 보낸 요청</h2>
      <p className="sub">traceId로 서버 로그를 찾을 수 있어요 (응답 헤더 X-Trace-Id, 로그 MDC traceId)</p>
      <table className="reqlog">
        <thead>
          <tr><th>시각</th><th>요청</th><th className="n">상태</th><th className="n">시간</th><th>traceId</th></tr>
        </thead>
        <tbody>
          {shown.map((r, i) => (
            <tr key={i}>
              <td className="num">{seoulTime(r.at)}</td>
              <td><code>{r.method}</code> {r.path.replace('/api/v1', '')}</td>
              <td className={'n ' + (r.status >= 200 && r.status < 300 ? 'ok' : 'bad')}>{r.status || '연결 실패'}</td>
              <td className="n num">{r.ms}ms</td>
              <td><code title={r.traceId || ''}>{r.traceId ? r.traceId.slice(0, 12) + '…' : '-'}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
