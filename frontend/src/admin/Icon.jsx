// 2px 선 아이콘 (시안 docs/ui/A-0x.html의 SVG 세트). 이모지는 쓰지 않는다 (DESIGN.md)
const PATHS = {
  pulse: <path d="M3 12h4l2.5-6 5 12 2.5-6h4" />,
  shield: <><path d="M12 3l7.5 3v5.5c0 4.5-3.2 8-7.5 9.5-4.3-1.5-7.5-5-7.5-9.5V6z" /><path d="M12 8.5v4M12 15.5v.3" /></>,
  folder: <path d="M3.5 6.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z" />,
  train: <><rect x="6" y="3.5" width="12" height="13" rx="3" /><path d="M6 11h12M9 20.5l1.5-4M15 20.5l-1.5-4M9 14v.3M15 14v.3" /></>,
  calendar: <><rect x="3.5" y="5" width="17" height="15" rx="2.5" /><path d="M3.5 10h17M8 3v4M16 3v4" /></>,
  gear: <><circle cx="12" cy="12" r="3" /><path d="M12 2.8v2.6M12 18.6v2.6M21.2 12h-2.6M5.4 12H2.8M18.5 5.5l-1.8 1.8M7.3 16.7l-1.8 1.8M18.5 18.5l-1.8-1.8M7.3 7.3L5.5 5.5" /></>,
  out: <path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M10 16l-4-4 4-4M6 12h10" />,
  external: <path d="M14 4h6v6M20 4l-9 9M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" />,
  refresh: <path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7" />,
  // 사용자 화면 (시안 docs/ui/U-*.html)
  search: <><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /></>,
  ticket: <><path d="M3.5 7.5h17v3a1.8 1.8 0 0 0 0 3.6v3.4h-17v-3.4a1.8 1.8 0 0 0 0-3.6z" /><path d="M14.5 8v1.4M14.5 11.3v1.4M14.5 14.6V16" /></>,
  clock: <><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>,
  bell: <><path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z" /><path d="M10 20.5a2 2 0 0 0 4 0" /></>,
  person: <><circle cx="12" cy="8.5" r="3.6" /><path d="M5 20c1-3.6 3.8-5.2 7-5.2s6 1.6 7 5.2" /></>,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v5.5M12 7.8v.3" /></>,
  swap: <path d="M4 8h16M20 8l-3-3M20 8l-3 3M20 16H4M4 16l3-3M4 16l3 3" />,
  card: <><rect x="3" y="5.5" width="18" height="13" rx="2" /><path d="M3 10h18M7 15h4" /></>,
  phone: <path d="M6.5 3.5h3l1.5 4-2 1.5a11 11 0 0 0 6 6l1.5-2 4 1.5v3a2 2 0 0 1-2 2A16.5 16.5 0 0 1 4.5 5.5a2 2 0 0 1 2-2z" />,
};

export default function Icon({ name }) {
  return (
    <svg className="ic" viewBox="0 0 24 24" aria-hidden="true">
      {PATHS[name]}
    </svg>
  );
}

export function Seal({ size = 30 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <path d="M2.5 3.5 Q2 2 3.5 2 H28 Q30.5 2.2 30 4 L29.6 28 Q30 30.4 27.5 30 H4 Q1.8 30.2 2.2 27.6 Z" fill="#B41A23" />
      <rect x="5" y="5" width="22" height="22" fill="none" stroke="#fff" strokeWidth="1.4" />
      <text x="16" y="22.2" textAnchor="middle" fontFamily="Nanum Myeongjo,serif" fontWeight="800" fontSize="16" fill="#fff">제</text>
    </svg>
  );
}
