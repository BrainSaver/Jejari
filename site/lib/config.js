// 사이트 공통 설정. 이름은 레포의 이름 브랜치와 같아야 하며 바꾸지 않는다.
export const MEMBERS = {
  SeongWon: '김성원',
  DongGyun: '강동균',
  DongHyeon: '길동현',
  DaIn: '강다인',
  YoungJun: '조영준',
};
export const NAMES = Object.keys(MEMBERS);
export const ADMIN = 'DongHyeon'; // PIN 초기화 권한
export const MAIN = 'main';
export const REFS = [MAIN, ...NAMES];

export const PIN_RE = /^\d{4}$/;
export const PIN_MAX_FAIL = 5;
export const PIN_LOCK_SEC = 600;
export const SESSION_SEC = 7 * 24 * 3600;
export const PBKDF2_ITER = 100000; // Workers의 PBKDF2 최대 반복 수

// 사이트에서 읽고 고칠 수 있는 경로. 이 밖의 파일은 저장을 거부한다.
export const EDITABLE = [
  /^README\.md$/,
  /^docs\/기능설계서\.md$/,
  /^docs\/DB설계서\.md$/,
  /^docs\/concurrency\.md$/,
  /^docs\/ui\/[UA]-\d{2}\.(md|html)$/,
  /^docs\/erd\/exports\/[A-Za-z]+-\d{8}\.json$/,
];
export const READABLE = [...EDITABLE, /^docs\/erd\/jejari-erd\.html$/, /^docs\/ui\/index\.json$/];

// 커밋 메시지 범위 (경로 → 범위 이름)
export function scopeOf(path) {
  if (path === 'README.md') return '기획서';
  if (path.includes('기능설계서')) return '기능설계';
  if (path.includes('DB설계서')) return 'DB설계';
  if (path.includes('concurrency')) return '동시성';
  if (path.startsWith('docs/ui/')) return '화면설계';
  if (path.startsWith('docs/erd/')) return 'erd';
  return '문서';
}

export function repoOf(env) { return env.GITHUB_REPO || 'brainsaver/jejari'; }
export function apiOf(env) { return (env.GITHUB_API || 'https://api.github.com').replace(/\/$/, ''); }
export function adminOf(env) { return env.ADMIN_NAME || ADMIN; }
