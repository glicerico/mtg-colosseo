// Card rendering: Scryfall images when available, a readable text frame otherwise.
import { h, manaSymbols, escapeRules } from './ui.js';

const IMAGES_KEY = 'colosseo.images';
const SCALE_KEY = 'colosseo.cardScale';
export const CARD_SCALE = { min: 0.6, max: 1.6, step: 0.05 };
const storedScale = parseFloat(localStorage.getItem(SCALE_KEY));
export const settings = {
  images: localStorage.getItem(IMAGES_KEY) !== 'off',
  // the player's card size, relative to the size that fits the window
  cardScale: Number.isFinite(storedScale) ? Math.min(CARD_SCALE.max, Math.max(CARD_SCALE.min, storedScale)) : 1,
};
export function setImages(on) {
  settings.images = on;
  localStorage.setItem(IMAGES_KEY, on ? 'on' : 'off');
}
export function setCardScale(v) {
  settings.cardScale = Math.min(CARD_SCALE.max, Math.max(CARD_SCALE.min, v));
  localStorage.setItem(SCALE_KEY, String(settings.cardScale));
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
    card.rules && card.rules.length, card.explain, extra, settings.images]);
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
    el.addEventListener('mouseleave', () => hidePreview());
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
  const fx = (card.explain && card.explain.effects) || [];
  if (fx.length) {
    const sources = [...new Set(fx.map((e) => e.source).filter(Boolean))];
    badges.appendChild(h('span', { class: 'badge badge-effect', title: `Affected by ${sources.join(', ') || 'an effect'} (hover for details)` },
      `✦${sources.length > 1 ? sources.length : ''}`));
  }
  for (const b of extra.badges || []) badges.appendChild(h('span', { class: 'badge badge-state' }, b));
  el.appendChild(badges);
  if (card.power !== undefined && card.power !== null && !card.hidden) {
    const change = ptChange(card);
    const printed = card.explain && card.explain.printed;
    el.appendChild(h('div', {
      class: 'card-pt' + (card.damage ? ' damaged' : '') + (change ? ` pt-${change}` : ''),
      title: change && printed ? `printed ${printed[0]}/${printed[1]} (hover for why)` : null,
    }, `${card.power}/${card.toughness}`));
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

// --- stat explanations ----------------------------------------------------------------------------

/** 'up', 'down' or 'mixed' when power/toughness differ from the printed values, else null. */
export function ptChange(card) {
  const x = card.explain;
  if (!x || !x.printed || card.power === undefined || card.power === null) return null;
  const dp = card.power - x.printed[0];
  const dt = card.toughness - x.printed[1];
  if (!dp && !dt) return null;
  if (dp >= 0 && dt >= 0) return 'up';
  if (dp <= 0 && dt <= 0) return 'down';
  return 'mixed';
}

const signed = (n) => (n >= 0 ? `+${n}` : `${n}`);
const ptText = (p, t) => `${signed(p)}/${signed(t)}`;

/** One effect, shortened for the list: "+3/+0", "first strike", "control changed", or the engine's text. */
function effectPart(e) {
  const text = (e.text || '').replace(/\.$/, '');
  if (e.layer === 'pt' && Number.isFinite(e.power) && Number.isFinite(e.toughness)) return ptText(e.power, e.toughness);
  if (e.layer === 'control') return 'control changed';
  const gains = text.match(/\b(gains?|loses?|has|have)\s+(.+?)(?:\s+(?:until|for as long as)\b.*)?$/i);
  if (e.layer === 'abilities' && gains) return (/^lose/i.test(gains[1]) ? 'loses ' : '') + gains[2];
  const dur = e.duration ? new RegExp(`\\s*${e.duration.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`, 'i') : null;
  return dur ? text.replace(dur, '') : text;
}

/** The effects acting on a card, one line per source: "+3/+0, first strike until end of turn (Sure Strike)". */
export function effectLines(card) {
  const bySource = new Map();
  for (const e of (card.explain && card.explain.effects) || []) {
    const key = `${e.source_id || e.source}|${e.duration}`;
    if (!bySource.has(key)) bySource.set(key, { source: e.source, duration: e.duration, parts: [] });
    const part = effectPart(e);
    if (part && !bySource.get(key).parts.includes(part)) bySource.get(key).parts.push(part);
  }
  return [...bySource.values()].map((g) => ({
    source: g.source,
    text: g.parts.join(', ') + (g.duration ? ` ${g.duration}` : ''),
  }));
}

/** Printed vs current power/toughness with what makes up the difference, and the effects acting on the card. */
export function explainView(card) {
  const x = card.explain;
  if (!x) return null;
  const rows = [];
  if (x.printed && card.power !== undefined && card.power !== null) {
    const change = ptChange(card);
    rows.push(h('div', { class: 'xp-pt' },
      h('span', { class: 'xp-now' + (change ? ` pt-${change}` : '') }, `${card.power}/${card.toughness}`),
      change ? h('span', { class: 'muted' }, ` printed ${x.printed[0]}/${x.printed[1]}`) : h('span', { class: 'muted' }, ' as printed')));
    if (x.set) rows.push(h('div', { class: 'xp-row' }, h('b', {}, `${x.set[0]}/${x.set[1]}`), ' base set by an effect'));
    if (x.counters) {
      const named = Object.entries(card.counters || {}).filter(([k]) => /^[+-]\d+\/[+-]\d+$/.test(k))
        .map(([k, n]) => `${n} ${k} counter${n > 1 ? 's' : ''}`).join(', ');
      rows.push(h('div', { class: 'xp-row' }, h('b', {}, ptText(x.counters[0], x.counters[1])), ` ${named || 'counters'}`));
    }
    if (x.effects_pt) {
      const declared = (x.effects || []).filter((e) => Number.isFinite(e.power));
      const sum = declared.reduce((a, e) => [a[0] + e.power, a[1] + e.toughness], [0, 0]);
      for (const e of declared) {
        rows.push(h('div', { class: 'xp-row' }, h('b', {}, ptText(e.power, e.toughness)), ` ${e.source}${e.duration ? `, ${e.duration}` : ''}`));
      }
      const rest = [x.effects_pt[0] - sum[0], x.effects_pt[1] - sum[1]];
      if (rest[0] || rest[1]) rows.push(h('div', { class: 'xp-row' }, h('b', {}, ptText(rest[0], rest[1])), ' other effects'));
    }
  }
  const lines = effectLines(card);
  if (lines.length) {
    rows.push(h('div', { class: 'xp-title' }, 'Effects'));
    for (const l of lines) rows.push(h('div', { class: 'xp-effect' }, h('b', {}, l.source || 'Effect'), `: ${l.text}`));
  }
  return rows.length ? h('div', { class: 'explain' }, rows) : null;
}

// --- hover preview ------------------------------------------------------------------------------

const preview = () => document.getElementById('preview');
let pinned = null;

/** Shows a card's preview; `pin` keeps it open (a click on a card) until another click. */
export function showPreview(card, pin = false) {
  if (pinned && !pin) return;
  if (!card || (card.hidden && !card.rules)) return;
  pinned = pin ? card : null;
  const p = preview();
  p.innerHTML = '';
  p.classList.toggle('pinned', !!pin);
  const explain = explainView(card);
  if (explain) p.appendChild(explain);
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

export function hidePreview(force = false) {
  if (pinned && !force) return;
  pinned = null;
  preview().classList.add('hidden');
  preview().classList.remove('pinned');
}

export function pinnedCard() {
  return pinned;
}
