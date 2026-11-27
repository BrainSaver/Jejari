import { Collapse, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { subscribeRequestLog } from '../api/client.js';
import { seoulTime } from '../lib/time.js';

/**
 * 이 브라우저가 보낸 API 요청 (Ant Design 화면용). 평소엔 접어 두고 제목 줄에 건수·실패 수만 보인다.
 * traceId로 서버 로그를 찾는다 (응답 헤더 X-Trace-Id, 로그 MDC traceId)
 */
export default function RequestLogPanel({ rows = 10, filter }) {
  const [log, setLog] = useState([]);
  useEffect(() => subscribeRequestLog(setLog), []);
  const shown = (filter ? log.filter(filter) : log).slice(0, rows).map((r, i) => ({ ...r, key: i }));
  const bad = shown.filter((r) => !(r.status >= 200 && r.status < 300)).length;
  const columns = [
    { title: '시각', dataIndex: 'at', width: 100, render: (v) => <span className="adm-num">{seoulTime(v)}</span> },
    {
      title: '요청',
      dataIndex: 'path',
      ellipsis: true,
      render: (p, r) => (
        <span className="adm-mono">
          <span style={{ marginRight: 6, color: 'rgba(0,0,0,0.45)' }}>{r.method}</span>
          {p.replace('/api/v1', '')}
        </span>
      ),
    },
    {
      title: '상태',
      dataIndex: 'status',
      width: 90,
      align: 'right',
      render: (s) => <Typography.Text type={s >= 200 && s < 300 ? 'success' : 'danger'}>{s || '연결 실패'}</Typography.Text>,
    },
    { title: '시간', dataIndex: 'ms', width: 90, align: 'right', render: (v) => <span className="adm-num">{v}ms</span> },
    {
      title: '오류 번호',
      dataIndex: 'traceId',
      width: 160,
      render: (t) => (t ? <Typography.Text className="adm-mono" copyable={{ text: t }}>{t.slice(0, 12)}…</Typography.Text> : '-'),
    },
  ];
  return (
    <Collapse
      style={{ marginTop: 16, background: '#fff' }}
      items={[
        {
          key: 'log',
          label: (
            <span>
              개발자용 정보 (이 화면이 보낸 요청)
              <Typography.Text type="secondary" style={{ marginLeft: 12, fontSize: 13 }}>{shown.length}건</Typography.Text>
              <Typography.Text type={bad ? 'danger' : 'secondary'} style={{ marginLeft: 8, fontSize: 13 }}>
                실패 {bad}건
              </Typography.Text>
            </span>
          ),
          children: (
            <Table
              className="adm-fixed"
              size="small"
              columns={columns}
              dataSource={shown}
              pagination={false}
              scroll={{ y: 440 }}
              locale={{ emptyText: '아직 보낸 요청이 없어요' }}
            />
          ),
        },
      ]}
    />
  );
}
