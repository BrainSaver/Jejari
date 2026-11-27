import { useEffect, useRef, useState } from 'react';
import { RUN_STATUS, ko } from './labels.js';

/**
 * 두 단계 버튼: 처음 누르면 "한 번 더 누르면 …"으로 바뀌고 4초 안에 다시 눌러야 실행 (alert·confirm 대신, DESIGN.md)
 */
export function ConfirmButton({ children, confirm, onConfirm, className = 'btn ghost sm', disabled }) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const t = useRef(null);
  useEffect(() => () => clearTimeout(t.current), []);
  const click = async () => {
    if (!armed) {
      setArmed(true);
      t.current = setTimeout(() => setArmed(false), 4000);
      return;
    }
    clearTimeout(t.current);
    setArmed(false);
    setBusy(true);
    try {
      await onConfirm();
    } finally {
      setBusy(false);
    }
  };
  return (
    <button type="button" className={className + (armed ? ' armed' : '')} onClick={click} disabled={disabled || busy}>
      {busy ? '처리 중' : armed ? confirm : children}
    </button>
  );
}

/** 서버 오류: 메시지 + 오류 코드 + traceId (관리자는 코드와 traceId로 원인을 찾는다) */
export function ErrorBox({ error, onClose }) {
  if (!error) return null;
  return (
    <div className="alert stop" role="alert">
      <b>{error.message}</b>
      <div style={{ fontSize: 12, marginTop: 2 }}>
        <code>{error.code}</code>
        {error.status ? ` · HTTP ${error.status}` : ''}
        {error.traceId && <> · 오류 번호 <code>{error.traceId}</code></>}
        {onClose && (
          <button type="button" className="btn ghost sm" style={{ marginLeft: 8, minHeight: 24 }} onClick={onClose}>닫기</button>
        )}
      </div>
    </div>
  );
}

/** 운행 상태 배지. 색만으로 구분하지 않고 글자도 같이 (DESIGN.md) */
export function RunStatus({ status }) {
  const cls = { ON_SALE: 'go', SCHEDULED: 'amber', CLOSED: '', DEPARTED: 'sky' }[status] ?? '';
  return <span className={'st ' + cls} title={status}>{ko(RUN_STATUS, status)}</span>;
}
