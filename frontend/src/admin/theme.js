/**
 * Ant Design 테마. 값은 사이트 토큰(styles/tokens.css)과 같다. 색을 바꾸려면 tokens.css와 여기를 같이 고친다.
 *   먹(--ink) 남색 = 주 색, 황금(--sign) = 강조(선택된 메뉴), 솔잎(--go)·인장(--stop)·호박(--amber-d) = 상태,
 *   한지(--paper) = 바탕, --card = 카드, --line = 테두리
 */
export const C = {
  ink: '#162436',
  ink2: '#243852',
  mut: '#57534E',
  faint: '#6B635A',
  line: '#D5CBB8',
  line2: '#EFE8D5',
  paper: '#F6F1E3',
  card: '#FFFDF8',
  sign: '#F2B705',
  signT: '#FEF7DC',
  go: '#15803D',
  goT: '#DCFCE7',
  amber: '#F2B705',
  amberD: '#8A4B00',
  amberT: '#FEF3D0',
  stop: '#B91C1C',
  stopT: '#FEE2E2',
  blue: '#1D4ED8',
};

const FONT = '"Noto Sans KR", system-ui, -apple-system, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif';

export const theme = {
  cssVar: true,
  // 꾸밈 없이 Ant Design 기본 모양(흰 바탕·회색 테두리·각진 모서리)에 주 색만 남색(--ink)으로
  token: {
    colorPrimary: C.ink,
    colorInfo: C.ink,
    colorSuccess: C.go,
    colorWarning: C.amberD,
    colorError: C.stop,
    colorLink: C.ink,
    colorBgLayout: '#F5F5F5',
    fontFamily: FONT,
    fontSize: 14,
    borderRadius: 4,
    controlHeight: 32,
    // 남색이 짙어 선택·호버 바탕을 자동 계산하면 회색이 된다 → 옅은 남색으로 직접 지정 (목록·드롭다운·메뉴 선택 공통)
    controlItemBgActive: '#E8EDF4',
    controlItemBgActiveHover: '#DFE6F0',
    controlItemBgHover: '#F5F5F5',
  },
  components: {
    Layout: { siderBg: C.ink, headerBg: '#FFFFFF', bodyBg: '#F5F5F5', headerHeight: 56, headerPadding: '0 24px' },
    Menu: {
      darkItemBg: C.ink,
      darkSubMenuItemBg: C.ink,
      darkItemColor: 'rgba(255,255,255,0.72)',
      darkItemHoverColor: '#FFFFFF',
      darkItemSelectedBg: '#2F4A6D',
      darkItemSelectedColor: '#FFFFFF',
      darkGroupTitleColor: 'rgba(255,255,255,0.45)',
      itemBorderRadius: 4,
      // 밝은 메뉴(운영 설정의 분류) 선택: 남색이 너무 짙어 기본 계산값이 회색이 되므로 옅은 남색을 직접 준다
      itemSelectedBg: '#E8EDF4',
      itemSelectedColor: C.ink,
    },
    Select: { optionSelectedBg: '#E8EDF4', optionActiveBg: '#F5F5F5' },
    Segmented: { itemSelectedColor: C.ink },
    Table: { cellPaddingBlock: 10, rowSelectedBg: '#E8EDF4', rowSelectedHoverBg: '#DFE6F0' },
  },
};
