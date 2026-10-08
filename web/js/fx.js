// Visual steps drawn over the board for a moment: the card being cast and its targets, attacks and blocks, damage,
// cards moving between zones, a caption naming the step's log line. Nothing here changes the board: the board is
// rendered from the engine's state for the step, and this layer only points at it.
import { h } from './ui.js';

const SVG = 'http://www.w3.org/2000/svg';

export class Fx {
  constructor(board) {
    this.board = board;
    this.svg = document.createElementNS(SVG, 'svg');
    this.svg.setAttribute('class', 'fx-arrows');
    const defs = document.createElementNS(SVG, 'defs');
    for (const kind of ['target', 'attack', 'block', 'damage', 'source']) {
      const m = document.createElementNS(SVG, 'marker');
      m.setAttribute('id', `fx-head-${kind}`);
      m.setAttribute('viewBox', '0 0 10 10');
      m.setAttribute('refX', '8');
      m.setAttribute('refY', '5');
      m.setAttribute('markerWidth', '5');
      m.setAttribute('markerHeight', '5');
      m.setAttribute('orient', 'auto-start-reverse');
      const p = document.createElementNS(SVG, 'path');
      p.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
      p.setAttribute('class', `fx-head fx-${kind}`);
      m.appendChild(p);
      defs.appendChild(m);
    }
    this.svg.appendChild(defs);
    this.layer = h('div', { class: 'fx-layer' });
    this.layer.appendChild(this.svg);
    board.appendChild(this.layer);
    this.marked = new Set();
    this.timers = new Set();
  }

  /** Removes every overlay now (the next step starts, or the player skips). */
  clear() {
    for (const t of this.timers) clearTimeout(t);
    this.timers.clear();
    for (const el of [...this.layer.children]) if (el !== this.svg) el.remove();
    for (const el of [...this.svg.children]) if (el.tagName !== 'defs') el.remove();
    for (const [el, cls] of this.marked) el.classList.remove(cls);
    this.marked.clear();
  }

  later(ms, fn) {
    const t = setTimeout(() => { this.timers.delete(t); fn(); }, ms);
    this.timers.add(t);
  }

  /** An element's box relative to the board. */
  rect(el) {
    if (!el) return null;
    const b = this.board.getBoundingClientRect();
    const r = el.getBoundingClientRect();
    if (!r.width && !r.height) return null;
    return { x: r.left - b.left, y: r.top - b.top, w: r.width, h: r.height, cx: r.left - b.left + r.width / 2, cy: r.top - b.top + r.height / 2 };
  }

  /** A curved arrow between two boxes (kind: target, attack, block, damage, source), for ms milliseconds. */
  arrow(from, to, kind, ms) {
    if (!from || !to) return;
    const [x1, y1, x2, y2] = edgePoints(from, to);
    const dx = x2 - x1;
    const dy = y2 - y1;
    const bend = Math.min(80, Math.hypot(dx, dy) * 0.18);
    const len = Math.hypot(dx, dy) || 1;
    const qx = (x1 + x2) / 2 - (dy / len) * bend;
    const qy = (y1 + y2) / 2 + (dx / len) * bend;
    const path = document.createElementNS(SVG, 'path');
    path.setAttribute('d', `M ${x1} ${y1} Q ${qx} ${qy} ${x2} ${y2}`);
    path.setAttribute('class', `fx-arrow fx-${kind}`);
    path.setAttribute('marker-end', `url(#fx-head-${kind})`);
    path.style.setProperty('--fx-ms', `${ms}ms`);
    this.svg.appendChild(path);
    this.later(ms, () => path.remove());
  }

  /** A number rising from a box: damage (red), life gained (green) or lost (red). */
  float(box, text, kind, ms) {
    if (!box) return;
    const el = h('div', { class: `fx-float fx-${kind}` }, text);
    el.style.left = `${box.cx}px`;
    el.style.top = `${box.cy}px`;
    el.style.setProperty('--fx-ms', `${ms}ms`);
    this.layer.appendChild(el);
    this.later(ms, () => el.remove());
  }

  /** Adds a class to a board element for ms milliseconds (a pulse on a trigger's source, a ring on a target). */
  mark(el, cls, ms) {
    if (!el) return;
    el.classList.add(cls);
    this.marked.add([el, cls]);
    this.later(ms, () => el.classList.remove(cls));
  }

  /**
   * The card being cast or the ability's source, large, near the stack, with a title and a line of detail; returns
   * its box (arrows to the targets start there).
   */
  spotlight(cardEl, title, detail, ms, beside) {
    const el = h('div', { class: 'fx-spotlight' }, h('div', { class: 'fx-title' }, title), cardEl,
      detail ? h('div', { class: 'fx-detail' }, detail) : null);
    el.style.setProperty('--fx-ms', `${ms}ms`);
    // left of the stack (beside: its box), which shows the same object
    if (beside) el.style.right = `${Math.max(16, this.board.clientWidth - beside.x + 16)}px`;
    this.layer.appendChild(el);
    this.later(ms, () => el.remove());
    return this.rect(cardEl);
  }

  /** A card (or card back) flying from one box to another: a card moving between zones. */
  ghost(cardEl, from, to, ms) {
    if (!from || !to) return;
    const w = Math.max(40, Math.min(from.w || 80, 200));
    cardEl.style.setProperty('--card-w', `${w}px`);
    const el = h('div', { class: 'fx-ghost' }, cardEl);
    el.style.width = `${w}px`;
    el.style.left = `${from.cx - w / 2}px`;
    el.style.top = `${from.cy - w * 0.7}px`;
    this.layer.appendChild(el);
    const scale = Math.max(0.25, Math.min(1.2, (to.w || w) / w));
    const anim = el.animate([
      { transform: 'translate(0, 0) scale(1)', opacity: 1 },
      { transform: `translate(${to.cx - from.cx}px, ${to.cy - from.cy}px) scale(${scale})`, opacity: 0.85 },
    ], { duration: Math.max(200, ms * 0.8), easing: 'cubic-bezier(.3,.7,.3,1)', fill: 'forwards' });
    anim.onfinish = () => this.later(Math.max(0, ms * 0.2), () => el.remove());
    this.later(ms + 50, () => el.remove());
  }

  /** The step's caption at the top of the board, with a link to its log line(s). */
  caption(text, links, onLink, ms) {
    const el = h('div', { class: 'fx-caption' }, h('span', {}, text),
      ...links.map((i) => h('button', { class: 'fx-log-link', title: 'Show this in the log', onclick: (e) => { e.stopPropagation(); onLink(i); } }, `log ${i}`)));
    el.style.setProperty('--fx-ms', `${ms}ms`);
    this.layer.appendChild(el);
    this.later(ms, () => el.remove());
  }
}

/** Where an arrow between two boxes starts and ends: on their edges, along the line between their centres. */
function edgePoints(a, b) {
  const clip = (box, tx, ty) => {
    const dx = tx - box.cx;
    const dy = ty - box.cy;
    if (!dx && !dy) return [box.cx, box.cy];
    const sx = dx ? (box.w / 2) / Math.abs(dx) : Infinity;
    const sy = dy ? (box.h / 2) / Math.abs(dy) : Infinity;
    const s = Math.min(sx, sy, 1) * 0.92;
    return [box.cx + dx * s, box.cy + dy * s];
  };
  const [x1, y1] = clip(a, b.cx, b.cy);
  const [x2, y2] = clip(b, a.cx, a.cy);
  return [x1, y1, x2, y2];
}
