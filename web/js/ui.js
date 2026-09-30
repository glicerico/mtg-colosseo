// Small UI primitives: element builder, modals, toasts, card preview.

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

export function toast(message, kind = 'info', ms = 4000) {
  const root = document.getElementById('toasts');
  const el = h('div', { class: `toast toast-${kind}` }, message);
  root.appendChild(el);
  setTimeout(() => el.classList.add('fade'), ms - 400);
  setTimeout(() => el.remove(), ms);
}

let modalStack = [];

/**
 * Shows a modal. `content` is a Node; `buttons` = [{label, cls, onClick}] (onClick returning false keeps it open).
 */
export function modal(title, content, buttons = [], { dismissable = true, wide = false } = {}) {
  const root = document.getElementById('modal-root');
  const close = () => {
    overlay.remove();
    modalStack = modalStack.filter((m) => m !== overlay);
  };
  const footer = h('div', { class: 'modal-footer' },
    buttons.map((b) => h('button', {
      class: 'btn ' + (b.cls || ''),
      disabled: b.disabled,
      onclick: () => { if (b.onClick && b.onClick() === false) return; close(); },
    }, b.label)));
  const box = h('div', { class: 'modal' + (wide ? ' modal-wide' : '') },
    h('div', { class: 'modal-title' }, title,
      dismissable ? h('button', { class: 'modal-x', title: 'Close', onclick: close }, '×') : null),
    h('div', { class: 'modal-body' }, content),
    buttons.length ? footer : null);
  const overlay = h('div', { class: 'modal-overlay', onclick: (e) => { if (dismissable && e.target === overlay) close(); } }, box);
  root.appendChild(overlay);
  modalStack.push(overlay);
  return { close, box };
}

export function closeAllModals() {
  modalStack.forEach((m) => m.remove());
  modalStack = [];
}

export function manaSymbols(cost) {
  if (!cost) return null;
  const wrap = h('span', { class: 'mana-cost' });
  for (const m of cost.matchAll(/\{([^}]+)\}/g)) {
    const sym = m[1].replace('/', '');
    wrap.appendChild(h('span', { class: `ms ms-${sym.toLowerCase()}`, title: m[0] }, sym));
  }
  return wrap;
}

export function colorPips(colors) {
  return h('span', { class: 'pips' }, (colors || '').split('').map((c) => h('span', { class: `pip pip-${c.toLowerCase()}` })));
}

export function escapeRules(text) {
  // mana symbols inside rules text get small icons
  const frag = document.createDocumentFragment();
  let last = 0;
  for (const m of text.matchAll(/\{([^}]+)\}/g)) {
    frag.appendChild(document.createTextNode(text.slice(last, m.index)));
    const sym = m[1].replace('/', '');
    frag.appendChild(h('span', { class: `ms ms-inline ms-${sym.toLowerCase()}` }, sym));
    last = m.index + m[0].length;
  }
  frag.appendChild(document.createTextNode(text.slice(last)));
  return frag;
}
