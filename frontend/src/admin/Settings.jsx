import { Alert, Button, Card, Col, Collapse, Descriptions, Empty, Input, Popconfirm, Row, Select, Table, Tag, Timeline, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useOutletContext } from 'react-router';
import { del, get, post, put } from '../api/client.js';
import LoadTestPanel from './LoadTestPanel.jsx';
import RequestLogPanel from './RequestLogPanel.jsx';
import { ConfirmButton, ErrorBox } from './ui.jsx';
import { ApiError } from './ui-antd.jsx';
import { POLICY_GROUP, ROLE, VALUE_TYPE, ko } from './labels.js';
import { hasRole } from './useAdminAuth.js';

/**
 * A-07 운영 설정 (ADM-08). app_policy 값을 묶음(queue·reservation·risk …)별로 보고 바꾼다.
 *   GET /api/v1/admin/policies                목록 (DB 값 + Redis policy:cache 값)
 *   GET /api/v1/admin/policies/{key}/history  최근 변경 20건
 *   PUT /api/v1/admin/policies/{key}          {value, version} — SUPERVISOR, 읽은 버전이 옛것이면 409
 *
 * 백엔드가 보이게: 버전(낙관적 잠금), Redis 캐시 값이 DB와 다르면 표시, 저장하면 "커밋 → 캐시 갱신 → 각 앱 1초 안에 반영"을 적는다.
 * TTL·임계값을 코드에 박지 않고 여기서 바꾸는 것이 프로젝트 규칙이다 (CLAUDE.md "app_policy에서 읽습니다").
 *
 * 인증 제한 임시 해제 (ERD v39, docs/강동균/설계안-인증-시도제한-임시해제.md):
 *   GET/POST/DELETE /api/v1/admin/auth-relax — IP는 서버가 해시로 바꿔 넣으므로 아래 표에서 직접 고치지 않는다
 */
export default function Settings() {
  const claims = useOutletContext();
  const canWrite = hasRole(claims, 'SUPERVISOR');
  const [list, setList] = useState(null);
  const [group, setGroup] = useState('');
  const [q, setQ] = useState('');
  const [sel, setSel] = useState(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState(null);
  // 임시 해제 상태: 해제 칸과 부하 시험 칸이 같이 쓴다 (부하 시험 칸이 해제하면 해제 칸도 바로 바뀜)
  const [relax, setRelax] = useState(null);
  const loadRelax = () => get('/api/v1/admin/auth-relax').then(setRelax).catch(() => setRelax(null));
  useEffect(() => {
    loadRelax();
    const t = setInterval(loadRelax, 60_000); // 남은 시간 표시
    return () => clearInterval(t);
  }, []);

  const load = () => get('/api/v1/admin/policies').then(setList).catch(setError);
  useEffect(() => {
    load();
  }, []);

  const groups = useMemo(() => {
    const c = {};
    (list || []).forEach((p) => (c[p.group] = (c[p.group] || 0) + 1));
    return Object.entries(c).sort();
  }, [list]);
  const shown = (list || []).filter((p) => (!group || p.group === group) && (!q || p.key.includes(q) || (p.description || '').includes(q)));
  const cur = list?.find((p) => p.key === sel) || null;
  useEffect(() => setPage(1), [group, q]);

  const wide = useWide(1200);
  const columns = [
    {
      title: '설정',
      dataIndex: 'key',
      width: wide ? '60%' : '62%',
      ellipsis: true,
      render: (k, p) => (
        <div style={{ lineHeight: 1.25, overflow: 'hidden', textOverflow: 'ellipsis' }} title={p.description ? `${p.description}\n${k}` : k}>
          <div style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.description || k}</div>
          {p.description && <div className="adm-mono" style={{ fontSize: 11.5, color: 'var(--faint, #8c8c8c)', overflow: 'hidden', textOverflow: 'ellipsis' }}>{k}</div>}
        </div>
      ),
    },
    {
      title: '값',
      dataIndex: 'value',
      width: wide ? '22%' : undefined,
      ellipsis: { showTitle: true },
      render: (v, p) => (
        <>
          <span className="adm-num">{v}</span>
          {p.cachedValue != null && p.cachedValue !== p.value && (
            <Tag color="warning" style={{ marginLeft: 6 }} title={`서버에 아직 반영되지 않았어요 (지금 서버 값 ${p.cachedValue})`}>적용 안 됨</Tag>
          )}
        </>
      ),
    },
    { title: '버전', dataIndex: 'version', width: 64, align: 'right', render: (v) => <span className="adm-num">{v}</span> },
  ].filter(Boolean);

  return (
    <>
      <div className="adm-title">
        <h1>운영 설정</h1>
        <Typography.Text type="secondary">
          정책 {list?.length ?? '—'}개
        </Typography.Text>
      </div>
      {!canWrite && <Alert type="info" showIcon style={{ marginBottom: 16 }} message={`지금 권한(${ko(ROLE, claims?.adm)})은 보기만 돼요. 저장은 총괄 관리자만 할 수 있어요.`} />}
      {error && <ApiError error={error} onClose={() => setError(null)} style={{ marginBottom: 16 }} />}

      <Collapse
        style={{ marginBottom: 16, background: '#fff' }}
        items={[
          {
            key: 'relax',
            label: (
              <span>
                인증 제한 임시 해제
                <Typography.Text type={relax?.active ? 'warning' : 'secondary'} style={{ marginLeft: 12, fontSize: 13 }}>
                  {relax ? (relax.active ? `해제 중 · ${relax.until?.replace('T', ' ').slice(5, 16)}까지` : '평소') : '정책 없음'}
                </Typography.Text>
              </span>
            ),
            children: (
              <div className="admin embed">
                <AuthRelax canWrite={canWrite} st={relax} setSt={setRelax} onChanged={load} />
              </div>
            ),
          },
          {
            key: 'k6',
            label: (
              <span>
                접속 몰림 시험
                <Typography.Text type="secondary" style={{ marginLeft: 12, fontSize: 13 }}>이 PC에서 실행</Typography.Text>
              </span>
            ),
            children: (
              <div className="admin embed">
                <LoadTestPanel canWrite={canWrite} relax={relax} onRelaxChanged={() => { loadRelax(); load(); }} />
              </div>
            ),
          },
        ]}
      />

      <Row gutter={16} wrap={false} style={{ alignItems: 'stretch' }} className="adm-row">
        <Col flex="auto" style={{ minWidth: 0 }}>
          <Card className="adm-work">
            <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
              <Select
                aria-label="분류"
                style={{ width: 220, flex: 'none' }}
                value={group}
                onChange={setGroup}
                showSearch
                optionFilterProp="search"
                options={[
                  { value: '', label: `전체 분류 (${list?.length ?? 0})`, search: '전체' },
                  ...groups.map(([g, n]) => ({ value: g, label: `${ko(POLICY_GROUP, g)} · ${g} (${n})`, search: `${ko(POLICY_GROUP, g)} ${g}` })),
                ]}
              />
              <Input.Search
                allowClear
                placeholder="키·설명 찾기 (예: rate_relax)"
                aria-label="키·설명 찾기"
                value={q}
                onChange={(e) => setQ(e.target.value)}
              />
            </div>
            <Table
              className="adm-fixed"
              size="middle"
              rowKey="key"
              loading={!list && !error}
              columns={columns}
              dataSource={shown}
              scroll={{ y: 440 }}
              pagination={{ current: page, pageSize: 10, onChange: setPage, showSizeChanger: false, size: 'small', showTotal: (t, [a, b]) => `${a}–${b} / ${t}` }}
              rowClassName={(p) => (p.key === sel ? 'ant-table-row-selected' : '')}
              onRow={(p) => ({ onClick: () => setSel(p.key) })}
              locale={{ emptyText: '맞는 설정이 없어요' }}
            />
          </Card>
        </Col>
        <Col flex="320px">
          <Card className="adm-work" title={cur ? <span className="adm-mono" style={{ fontSize: 14 }}>{cur.key}</span> : '설정 편집'}>
            {cur ? <Editor key={cur.key} p={cur} canWrite={canWrite} onSaved={load} /> : <Empty style={{ marginTop: 120 }} description="표에서 설정을 고르세요" />}
          </Card>
        </Col>
      </Row>
      <RequestLogPanel filter={(r) => r.path.includes('/admin/policies') || r.path.includes('/admin/auth-relax')} />
    </>
  );
}

function Editor({ p, canWrite, onSaved }) {
  const [value, setValue] = useState(p.value);
  // 저장 뒤 목록을 다시 읽으면 버전·값이 바뀐다. 칸은 새 값으로 맞추되 "저장했어요" 안내는 남긴다
  useEffect(() => setValue(p.value), [p.version]);
  const [hist, setHist] = useState(null);
  const [error, setError] = useState(null);
  const [saved, setSaved] = useState(null);
  useEffect(() => {
    get(`/api/v1/admin/policies/${p.key}/history`).then(setHist).catch(() => setHist([]));
  }, [p.key, p.version]);

  const save = async () => {
    setError(null);
    try {
      const r = await put(`/api/v1/admin/policies/${p.key}`, { value, version: p.version });
      setSaved(r);
      onSaved();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <Typography.Paragraph type="secondary" style={{ fontSize: 13 }}>{p.description}</Typography.Paragraph>
      <Descriptions
        size="small"
        column={1}
        style={{ marginBottom: 12 }}
        items={[
          { key: 't', label: '형식', children: ko(VALUE_TYPE, p.valueType) },
          { key: 'v', label: '지금 값', children: <span className="adm-num">{p.value}</span> },
          { key: 'ver', label: '버전', children: <span className="adm-num">{p.version}</span> },
          {
            key: 'c',
            label: '적용',
            children: p.cachedValue == null || p.cachedValue === p.value ? '적용됨' : <Typography.Text type="warning">아직 적용 안 됨 (지금 서버 값 {p.cachedValue})</Typography.Text>,
          },
          { key: 'u', label: '마지막 변경', children: `${p.updatedAt ? p.updatedAt.replace('T', ' ').slice(0, 16) : '—'}${p.updatedBy ? ` · 관리자 #${p.updatedBy}` : ''}` },
        ]}
      />
      {saved && <Alert type="success" showIcon style={{ marginBottom: 12 }} message={`저장했어요. 버전 ${saved.version}. 1초 안에 모든 서버에 반영돼요.`} />}
      {error && <ApiError error={error} onClose={() => setError(null)} style={{ marginBottom: 12 }} />}
      {error?.code === 'POLICY_VERSION_CONFLICT' && <Button size="small" style={{ marginBottom: 12 }} onClick={onSaved}>다시 읽기</Button>}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <Input aria-label="새 값" value={value} onChange={(e) => setValue(e.target.value)} disabled={!canWrite} />
        <Popconfirm
          title="이 값으로 저장할까요?"
          description={`${p.value} → ${value}`}
          okText="저장"
          cancelText="그만두기"
          onConfirm={save}
          disabled={!canWrite || value === p.value}
        >
          <Button type="primary" disabled={!canWrite || value === p.value}>저장</Button>
        </Popconfirm>
      </div>
      <Typography.Text type="secondary" style={{ display: 'block', fontSize: 13 }}>변경 이력</Typography.Text>
      {hist && !hist.length ? (
        <Typography.Text type="secondary">아직 바꾼 적이 없어요 (시드 값).</Typography.Text>
      ) : (
        <Timeline
          style={{ marginTop: 8 }}
          items={(hist || []).map((h) => ({
            key: h.historyId,
            children: (
              <span style={{ fontSize: 13 }}>
                <span className="adm-num" style={{ color: 'var(--faint)' }}>v{h.version}</span>{' '}
                <span className="adm-num">{h.oldValue ?? '—'}</span> → <span className="adm-num">{h.newValue}</span>
                <div style={{ fontSize: 12, color: 'var(--mut)' }}>{h.changedAt?.replace('T', ' ').slice(5, 16)}{h.changedBy ? ` · 관리자 #${h.changedBy}` : ''}</div>
              </span>
            ),
          }))}
        />
      )}
    </>
  );
}

/**
 * 인증 시도 제한 임시 해제. 접속 몰림 시험으로 공용 서버에 계정을 많이 만들 때 그 PC의 IP만 정한 시각까지 가입·로그인 한도를 높인다.
 * 종료 시각이 지나면 아무도 안 눌러도 평소 한도로 돌아간다 (서버가 읽을 때마다 시각을 비교).
 */
function AuthRelax({ canWrite, st, setSt, onChanged }) {
  const [ip, setIp] = useState('');
  const [hours, setHours] = useState(3);
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  if (!st) return null; // v39 정책이 없거나 회원 인증이 꺼진 서버

  const run = async (fn) => {
    setError(null);
    try {
      setSt(await fn());
      onChanged();
    } catch (e) {
      setError(e);
    }
  };
  const start = () => run(() => post('/api/v1/admin/auth-relax', { ip: ip.trim() || null, hours: Number(hours), reason }));
  const stop = () => run(() => del('/api/v1/admin/auth-relax'));
  const left = st.remainingMin >= 60 ? `${Math.floor(st.remainingMin / 60)}시간 ${st.remainingMin % 60}분` : `${st.remainingMin}분`;

  return (
    <section className="panel" style={{ marginBottom: 16 }}>
      <h2><span className="grow">인증 제한 임시 해제</span>
        {st.active ? <span className="st amber">해제 중</span> : <span className="st plain">평소</span>}
      </h2>
      {st.active ? (
        <div className="alert amber">
          <b>{st.until?.replace('T', ' ').slice(0, 16)}까지 해제 중</b> (남은 {left}) · IP {st.ipCount}개{st.includesMe ? ' (지금 이 PC 포함)' : ''} · 사유: {st.reason}
          <div style={{ marginTop: 4 }}>
            해제 IP만 가입 시간당 {st.signupPerHour} · 로그인 분당 {st.loginPerMin} (평소 {st.normalSignupPerHour} · {st.normalLoginPerMin}).
            시각이 지나면 저절로 평소로 돌아가요. 관리자 로그인·비회원 조회는 풀리지 않아요.
          </div>
        </div>
      ) : (
        <p className="sub">
          접속 몰림 시험으로 공용 서버에 계정을 많이 만들 때 그 PC의 IP만 최대 {st.maxHours}시간 동안 가입·로그인 한도를 높여요
          (평소 IP당 가입 시간당 {st.normalSignupPerHour} · 로그인 분당 {st.normalLoginPerMin}). 시각이 지나면 저절로 평소로 돌아가요.
          원본 IP는 저장하지 않고 가린 값만 남겨요. <b>봇 대회 중에는 켜지 마세요</b> (같은 와이파이의 봇도 같이 풀려요).
        </p>
      )}
      <ErrorBox error={error} onClose={() => setError(null)} />
      {canWrite && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <label className="field" style={{ margin: 0 }}>
            <span className="lbl">IP (비우면 지금 이 PC)</span>
            <input value={ip} onChange={(e) => setIp(e.target.value)} placeholder="예: 203.0.113.7" style={{ width: 170 }} />
          </label>
          <label className="field" style={{ margin: 0 }}>
            <span className="lbl">시간</span>
            <select value={hours} onChange={(e) => setHours(e.target.value)} style={{ width: 100 }}>
              {[1, 3, 6, 12, 24].filter((h) => h <= st.maxHours).map((h) => <option key={h} value={h}>{h}시간</option>)}
            </select>
          </label>
          <label className="field" style={{ margin: 0, flex: 1, minWidth: 200 }}>
            <span className="lbl">사유 (5자 이상)</span>
            <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="예: 접속 몰림 시험 (시나리오 11) 공용 서버 측정" />
          </label>
          <ConfirmButton className="btn tall" confirm={`한 번 더 누르면 ${hours}시간 해제`} onConfirm={start} disabled={reason.trim().length < 5}>
            {st.active ? 'IP 추가·연장' : '해제 시작'}
          </ConfirmButton>
          {st.active && <ConfirmButton className="btn ghost tall" confirm="한 번 더 누르면 지금 끝내기" onConfirm={stop}>지금 끝내기</ConfirmButton>}
        </div>
      )}
    </section>
  );
}

/** 화면 폭이 min 이상인지. 좁으면(1280 등) 설정 표에서 설명 칸을 빼고 키를 넓게 보인다 (설명은 오른쪽 편집 칸에 있음) */
function useWide(min) {
  const q = `(min-width: ${min}px)`;
  const [ok, setOk] = useState(() => window.matchMedia(q).matches);
  useEffect(() => {
    const m = window.matchMedia(q);
    const on = () => setOk(m.matches);
    m.addEventListener('change', on);
    return () => m.removeEventListener('change', on);
  }, [q]);
  return ok;
}
