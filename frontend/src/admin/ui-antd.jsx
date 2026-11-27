import { Alert, Typography } from 'antd';

/** 서버 오류: 메시지 + 오류 코드 + traceId (관리자는 코드와 traceId로 원인을 찾는다). ui.jsx ErrorBox의 Ant Design판 */
export function ApiError({ error, onClose, style }) {
  if (!error) return null;
  return (
    <Alert
      type="error"
      showIcon
      style={style}
      closable={onClose ? { onClose } : false}
      message={<b>{error.message}</b>}
      description={
        <span style={{ fontSize: 12 }}>
          <code>{error.code}</code>
          {error.status ? ` · HTTP ${error.status}` : ''}
          {error.traceId && (
            <>
              {' · 오류 번호 '}
              <Typography.Text className="adm-mono" copyable>{error.traceId}</Typography.Text>
            </>
          )}
        </span>
      }
    />
  );
}
