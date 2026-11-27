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
  token: {
    colorPrimary: C.ink,
    colorInfo: C.ink,
    colorSuccess: C.go,
    colorWarning: C.amberD,
    colorError: C.stop,
    colorLink: C.ink,
    colorText: C.ink,
    colorTextSecondary: C.mut,
    colorTextTertiary: C.faint,
    colorBorder: C.line,
    colorBorderSecondary: C.line2,
    colorBgLayout: C.paper,
    colorBgContainer: C.card,
    colorBgElevated: C.card,
    colorFillAlter: '#FAF6EA',
    fontFamily: FONT,
    fontSize: 14,
    borderRadius: 8,
    controlHeight: 36,
  },
  components: {
    Layout: { siderBg: C.ink, headerBg: C.card, bodyBg: C.paper, headerHeight: 56, headerPadding: '0 24px' },
    Menu: {
      darkItemBg: C.ink,
      darkSubMenuItemBg: C.ink,
      darkItemColor: '#C8D3E4',
      darkItemHoverColor: '#FFFFFF',
      darkItemSelectedBg: C.sign,
      darkItemSelectedColor: C.ink,
      darkGroupTitleColor: '#8FA1BE',
      itemSelectedBg: C.signT,
      itemSelectedColor: C.ink,
      itemHeight: 40,
      itemBorderRadius: 8,
    },
    Table: { headerBg: '#FAF6EA', headerColor: C.mut, rowSelectedBg: C.signT, rowSelectedHoverBg: '#FCF0C4', cellPaddingBlock: 10 },
    Card: { headerFontSize: 15 },
    Segmented: { itemSelectedBg: C.ink, itemSelectedColor: '#FFFFFF', trackBg: '#EFE8D5' },
    Statistic: { contentFontSize: 28 },
  },
};
