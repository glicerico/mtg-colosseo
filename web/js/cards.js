// Card rendering: Scryfall images when available, a readable text frame otherwise.
import { h, manaSymbols, escapeRules } from './ui.js';

const IMAGES_KEY = 'colosseo.images';
export const settings = {
  images: localStorage.getItem(IMAGES_KEY) !== 'off',
};
export function setImages(on) {
  settings.images = on;
  localStorage.setItem(IMAGES_KEY, on ? 'on' : 'off');
}

const failedImages = new Set();

export function imageUrl(card, version = 'normal') {
  if (!settings.images || !card || card.hidden || card.token || !card.set || !card.number) return null;
  const url = `https://api.scryfall.com/cards/${card.set.toLowerCase()}/${encodeURIComponent(card.number)}?format=image&version=${version}`;
  return failedImages.has(url) ? null : url;
}

function frameColor(card) {
  const c = card.colors || '';
  if ((card.types || []).includes('Land')) return 'land';
  if (c.length > 1) return 'gold';
  if (c.length === 0) return (card.types || []).includes('Artifact') ? 'artifact' : 'colorless';
  return c.toLowerCase();
}

export function typeLine(card) {
  const t = [...(card.supertypes || []), ...(card.types || [])].join(' ');
  const s = (card.subtypes || []).join(' ');
  return s ? `${t} — ${s}` : t;
}

function textFrame(card) {
  return h('div', { class: `card-frame frame-${frameColor(card)}` },
    h('div', { class: 'cf-head' }, h('span', { class: 'cf-name' }, card.name || ''), manaSymbols(card.mana_cost)),
    h('div', { class: 'cf-type' }, typeLine(card)),
    h('div', { class: 'cf-rules' }, (card.rules || []).slice(0, 6).map((r) => h('div', {}, escapeRules(r)))));
}

/**
 * Signature of everything that affects a card element, to skip needless DOM updates.
 */
function signature(card, extra) {
  return JSON.stringify([card.name, card.set, card.number, card.hidden, card.power, card.toughness, card.tapped,
    card.damage, card.counters, card.attacking, card.blocking, card.summoning_sick, card.face_down, card.token,
    card.rules && card.rules.length, extra, settings.images]);
}

/**
 * Creates or updates a card element (keyed by card id in `cache`).
 * `extra`: {badges: [text], classes: [..]} additional UI state.
 */
export function renderCard(cache, card, extra = {}) {
  let el = cache.get(card.id);
  const sig = signature(card, extra);
  if (el && el._sig === sig) return el;
  if (!el) {
    el = h('div', { class: 'card', dataset: { id: card.id } });
    el.addEventListener('mouseenter', () => showPreview(el._card));
    el.addEventListener('mouseleave', hidePreview);
    cache.set(card.id, el);
  }
  el._card = card;
  el._sig = sig;
  el.innerHTML = '';
  if (card.hidden && !card.name) {
    el.appendChild(h('div', { class: 'card-back' }));
  } else if (card.hidden) {
    el.appendChild(h('div', { class: 'card-back' }, h('span', { class: 'back-label' }, card.name)));
  } else {
    el.appendChild(textFrame(card));
    const url = imageUrl(card);
    if (url) {
      const img = h('img', { class: 'card-img', src: url, loading: 'lazy', alt: card.name, draggable: 'false' });
      img.addEventListener('load', () => img.classList.add('loaded'));
      img.addEventListener('error', () => { failedImages.add(url); img.remove(); });
      el.appendChild(img);
    }
  }
  const badges = h('div', { class: 'card-badges' });
  const counters = card.counters || {};
  for (const [name, n] of Object.entries(counters)) {
    if (name === 'loyalty') continue;
    badges.appendChild(h('span', { class: 'badge badge-counter', title: `${n} ${name} counter(s)` },
      name === '+1/+1' ? `+${n}` : name === '-1/-1' ? `-${n}` : `${n} ${name}`));
  }
  if (card.damage) badges.appendChild(h('span', { class: 'badge badge-damage', title: 'damage' }, `✹${card.damage}`));
  for (const b of extra.badges || []) badges.appendChild(h('span', { class: 'badge badge-state' }, b));
  el.appendChild(badges);
  if (card.power !== undefined && card.power !== null && !card.hidden) {
    el.appendChild(h('div', { class: 'card-pt' + (card.damage ? ' damaged' : '') }, `${card.power}/${card.toughness}`));
  } else if (card.loyalty !== undefined && card.loyalty !== null) {
    el.appendChild(h('div', { class: 'card-pt loyalty' }, String(card.loyalty)));
  }
  el.className = 'card';
  if (card.tapped) el.classList.add('tapped');
  if (card.summoning_sick) el.classList.add('sick');
  if (card.attacking) el.classList.add('attacking');
  if (card.blocking) el.classList.add('blocking');
  for (const c of extra.classes || []) el.classList.add(c);
  return el;
}

export function cardBack(count) {
  return h('div', { class: 'card small' }, h('div', { class: 'card-back' }, count !== undefined ? h('span', { class: 'back-label' }, count) : null));
}

// --- hover preview ------------------------------------------------------------------------------

const preview = () => document.getElementById('preview');

export function showPreview(card) {
  if (!card || (card.hidden && !card.rules)) return;
  const p = preview();
  p.innerHTML = '';
  const url = imageUrl(card, 'large');
  if (url) {
    const img = h('img', { src: url, alt: card.name });
    img.addEventListener('error', () => img.remove());
    p.appendChild(img);
  }
  p.appendChild(h('div', { class: 'preview-text' },
    h('div', { class: 'pv-head' }, h('b', {}, card.name || ''), ' ', manaSymbols(card.mana_cost)),
    h('div', { class: 'pv-type' }, typeLine(card),
      card.power !== undefined && card.power !== null ? `  ${card.power}/${card.toughness}` : ''),
    (card.rules || []).map((r) => h('div', { class: 'pv-rule' }, escapeRules(r))),
    card.set ? h('div', { class: 'pv-set' }, `${card.set} #${card.number}`) : null));
  p.classList.remove('hidden');
}

export function hidePreview() {
  preview().classList.add('hidden');
}
