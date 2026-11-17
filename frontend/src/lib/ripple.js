// 버튼을 누르면 누른 자리에서 물결이 퍼진다. 버튼마다 코드를 붙이지 않게 문서 전체에서 한 번만 듣는다.
// "움직임 줄이기"를 켠 사람에게는 만들지 않는다.
const SELECTOR = '.user .btn, .user .chip, .user .swap, .admin .btn';
const reduce = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

export function installRipple() {
  document.addEventListener('pointerdown', (e) => {
    const el = e.target.closest?.(SELECTOR);
    if (!el || el.disabled || el.classList.contains('off') || reduce()) return;
    const r = el.getBoundingClientRect();
    const size = Math.max(r.width, r.height) * 2.2;
    const dot = document.createElement('span');
    dot.className = 'jj-ripple';
    dot.style.width = dot.style.height = size + 'px';
    dot.style.left = e.clientX - r.left - size / 2 + 'px';
    dot.style.top = e.clientY - r.top - size / 2 + 'px';
    el.appendChild(dot);
    setTimeout(() => dot.remove(), 600);
  });
}
