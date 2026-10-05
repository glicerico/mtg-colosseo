// Game board: renders the observation and turns clicks into protocol actions.
import { wsUrl, getGame, seatToken, ownerToken, forgetToken } from './api.js';
import { h, modal, toast, closeAllModals, manaSymbols, escapeRules } from './ui.js';
import { renderCard, cardBack, showPreview, hidePreview, pinnedCard, settings, setImages, setCardScale, CARD_SCALE } from './cards.js';

const STEPS = [
  ['UNTAP', 'Untap'], ['UPKEEP', 'Upkeep'], ['DRAW', 'Draw'], ['PRECOMBAT_MAIN', 'Main 1'],
  ['BEGIN_COMBAT', 'Combat'], ['DECLARE_ATTACKERS', 'Attackers'], ['DECLARE_BLOCKERS', 'Blockers'],
  ['FIRST_COMBAT_DAMAGE', 'First strike'], ['COMBAT_DAMAGE', 'Damage'], ['END_COMBAT', 'End combat'],
  ['POSTCOMBAT_MAIN', 'Main 2'], ['END_TURN', 'End'], ['CLEANUP', 'Cleanup'],
];

const KIND_TITLES = {
  choose_mode: 'Choose mode', choose_ability: 'Choose ability', choose_choice: 'Choose',
  trigger_order: 'Order triggers', choose_pile: 'Choose a pile', amount: 'Choose a number',
  multi_amount: 'Distribute', target: 'Choose',
};

const isLand = (c) => (c.types || []).includes('Land') && !(c.types || []).includes('Creature');
const isCreature = (c) => (c.types || []).includes('Creature');

// Card width limits (px) and how many card widths the board needs vertically: the opponent's land/other row
// (lands are .72 wide) and creature row, ours, and our hand (.95 wide), each card 1.395 times as tall as wide.
const CARD_MIN = 48;
const CARD_MAX = 330;
const BOARD_CARD_HEIGHTS = (0.72 + 1.1 + 1.1 + 0.72 + 0.95) * 1.395;

export class GameView {
  constructor(root, gameId, seat) {
    this.root = root;
    this.gameId = gameId;
    this.seat = seat; // -1 = spectator
    this.state = null;
    this.decision = null;
    this.sent = null;
    this.result = null;
    this.game = null;
    this.log = [];
    this.cache = new Map();
    this.local = { attackers: new Map(), blocks: new Map(), blocker: null };
    this.reveal = localStorage.getItem('colosseo.reveal') === '1';
    this.mayReveal = true; // until the server says otherwise
    this.waiting = null; // spectator: {seat, kind, prompt}
    this.lastError = null;
    this.retryMs = 1000;
    this.destroyed = false;
    this.pollTimer = null;
    this.groups = new Map(); // wrappers for token stacks and attachment groups, by key
    this.keyHandler = (e) => this.onKey(e);
    document.addEventListener('keydown', this.keyHandler);
    this.resizeHandler = () => this.fit();
    window.addEventListener('resize', this.resizeHandler);
    this.build();
    this.fit();
    this.connect();
  }

  destroy() {
    this.destroyed = true;
    document.removeEventListener('keydown', this.keyHandler);
    window.removeEventListener('resize', this.resizeHandler);
    for (const v of ['--card-w', '--ui']) document.documentElement.style.removeProperty(v);
    clearInterval(this.pollTimer);
    if (this.ws) this.ws.close();
    document.getElementById('conn-status').textContent = '';
  }

  get isPlayer() { return this.seat >= 0; }

  // --- layout -------------------------------------------------------------------------------------

  build() {
    this.root.className = 'game-page';
    // per player: creatures nearest the middle; then lands and the other permanents (artifacts, enchantments,
    // planeswalkers, ...) side by side; then the player and the hand
    this.el = {
      topInfo: h('div', { class: 'player-info top' }),
      topHand: h('div', { class: 'hand-row top' }),
      topLands: h('div', { class: 'zone lands', title: 'Lands' }),
      topOthers: h('div', { class: 'zone others', title: 'Other permanents' }),
      topCreatures: h('div', { class: 'zone creatures' }),
      phase: h('div', { class: 'phase-bar' }),
      stack: h('div', { class: 'stack-area' }),
      center: h('div', { class: 'center-msg' }),
      botCreatures: h('div', { class: 'zone creatures' }),
      botLands: h('div', { class: 'zone lands', title: 'Lands' }),
      botOthers: h('div', { class: 'zone others', title: 'Other permanents' }),
      botInfo: h('div', { class: 'player-info bottom' }),
      botHand: h('div', { class: 'hand-row bottom' }),
      prompt: h('div', { class: 'prompt-bar' }),
      header: h('div', { class: 'side-header' }),
      log: h('div', { class: 'log' }),
      banner: h('div', { class: 'banner hidden' }),
    };
    const e = this.el;
    const permRow = (lands, others) => h('div', { class: 'perm-row' }, lands, others);
    const board = h('div', { class: 'board' },
      e.banner,
      h('div', { class: 'side top' }, h('div', { class: 'info-hand' }, e.topInfo, e.topHand),
        permRow(e.topLands, e.topOthers), e.topCreatures),
      h('div', { class: 'middle' }, e.phase, h('div', { class: 'middle-row' }, e.center, e.stack)),
      h('div', { class: 'side bottom' }, e.botCreatures, permRow(e.botLands, e.botOthers),
        h('div', { class: 'info-hand' }, e.botInfo, e.botHand)),
      e.prompt);
    board.addEventListener('click', (ev) => this.onBoardClick(ev));
    this.board = board;
    this.root.append(board, h('aside', { class: 'sidebar' }, e.header, e.log));
    this.renderHeader();
  }

  // --- connection ----------------------------------------------------------------------------------

  connect() {
    // seats need their token on protected games; spectators send the owner token (if this browser created
    // the game or opened an owner link) so that they may show both hands
    const token = this.isPlayer ? seatToken(this.gameId, this.seat) : ownerToken(this.gameId);
    const tq = token ? `&token=${encodeURIComponent(token)}` : '';
    const path = this.isPlayer
      ? `/ws/game/${this.gameId}?seat=${this.seat}${tq}`
      : `/ws/game/${this.gameId}?spectate=1${this.reveal ? '&reveal=1' : ''}${tq}`;
    const ws = new WebSocket(wsUrl(path));
    this.ws = ws;
    const status = document.getElementById('conn-status');
    status.textContent = 'connecting…';
    ws.onopen = () => { status.textContent = this.isPlayer ? `seat ${this.seat}` : 'spectating'; this.retryMs = 1000; };
    ws.onclose = (ev) => {
      if (this.destroyed || ws !== this.ws) return;
      if (ev.code === 4001 || ev.code === 4003 || ev.code === 4004) {
        // refused (bad or missing token, unknown game, ...): retrying can't help
        if (ev.code === 4001 && this.isPlayer) forgetToken(this.gameId, this.seat);
        status.textContent = 'access denied';
        this.showFatal(ev.code, this.lastError || ev.reason);
        return;
      }
      status.textContent = 'disconnected';
      if (!this.result) {
        setTimeout(() => { if (!this.destroyed) this.connect(); }, this.retryMs);
        this.retryMs = Math.min(this.retryMs * 2, 15000);
      }
    };
    ws.onmessage = (m) => this.onMessage(JSON.parse(m.data));
  }

  showFatal(code, message) {
    const e = this.el;
    const hint = code === 4004 ? 'This game does not exist (any more) on this server.'
      : code === 4001 ? (this.isPlayer
        ? 'This seat is protected. Open the invite link you were given for it (it contains the seat token), or ask the game creator for one.'
        : 'This game is protected.')
        : 'The server refused this connection.';
    e.banner.classList.remove('hidden');
    e.banner.classList.add('error');
    e.banner.replaceChildren(h('b', {}, 'Can’t join this game. '), hint,
      message ? h('div', { class: 'muted' }, `Server: ${message}`) : null,
      h('div', {}, h('a', { class: 'btn small', href: '#/' }, 'Back to lobby'), ' ',
        this.isPlayer ? h('a', { class: 'btn small', href: `#/watch/${this.gameId}` }, 'Watch instead') : null));
    this.el.center.textContent = '';
    const controls = e.header.querySelector('.side-controls');
    if (controls) controls.replaceChildren();
  }

  send(msg) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  answer(payload) {
    const d = this.decision;
    if (!d) return;
    this.send({ type: 'action', decision_id: d.decision_id, ...payload });
    this.sent = d;
    this.decision = null;
    this.local = { attackers: new Map(), blocks: new Map(), blocker: null };
    closeAllModals();
    this.render();
  }

  onMessage(msg) {
    switch (msg.type) {
      case 'hello':
        this.game = msg.game;
        this.lastError = null;
        if (!this.isPlayer) {
          this.mayReveal = !!msg.may_reveal;
          this.reveal = !!msg.reveal;
        }
        if (msg.state) this.state = msg.state;
        this.log = [];
        this.el.log.innerHTML = '';
        (msg.log || []).forEach((e) => this.addLog(e));
        this.renderHeader();
        this.checkAgentSeat();
        this.render();
        break;
      case 'state':
        this.state = msg.state;
        this.render();
        break;
      case 'decision':
        if (this.sent && this.sent.decision_id === msg.decision_id) return; // already answered
        this.decision = msg;
        this.state = msg.state || this.state;
        this.local = { attackers: new Map(), blocks: new Map(), blocker: null };
        this.render();
        this.autoOpen();
        break;
      case 'decision_pending':
        this.waiting = msg;
        this.renderPrompt();
        break;
      case 'action':
        if (!this.isPlayer || msg.seat !== this.seat || msg.comment) this.addAction(msg);
        if (this.waiting && this.waiting.decision_id === msg.decision_id) { this.waiting = null; this.renderPrompt(); }
        break;
      case 'log':
        this.addLog(msg.entry);
        break;
      case 'info':
        toast(msg.message, 'info');
        break;
      case 'error':
        this.lastError = msg.message;
        if (!this.isPlayer && /^revealing hands requires/.test(msg.message || '')) {
          this.mayReveal = false;
          this.reveal = false;
          this.renderHeader();
          break;
        }
        if (msg.code === 'stale') break; // an answer for a decision that is no longer pending
        if (!this.game) break; // refused before joining: the connection closes and showFatal explains
        if (this.sent && msg.decision_id === this.sent.decision_id) {
          this.decision = this.sent;
          this.sent = null;
          this.render();
        }
        toast(msg.message, 'error', 6000);
        break;
      case 'game_over':
        this.result = msg.result;
        if (msg.state) this.state = msg.state;
        this.decision = null;
        this.render();
        this.showResult();
        break;
      default:
        break;
    }
  }

  // --- log -----------------------------------------------------------------------------------------

  addLog(entry) {
    const last = this.log[this.log.length - 1];
    if (!last || last.turn !== entry.turn) {
      this.el.log.appendChild(h('div', { class: 'log-turn' }, `Turn ${entry.turn}`));
    }
    this.log.push(entry);
    this.el.log.appendChild(h('div', { class: 'log-line' }, entry.text));
    this.scrollLog();
  }

  addAction(msg) {
    const name = this.seatName(msg.seat);
    this.el.log.appendChild(h('div', { class: 'log-action' },
      h('b', {}, name), `: ${msg.summary || msg.kind}`,
      msg.comment ? h('div', { class: 'log-comment' }, `“${msg.comment}”`) : null));
    this.scrollLog();
  }

  scrollLog() {
    const l = this.el.log;
    if (l.scrollHeight - l.scrollTop - l.clientHeight < 200) l.scrollTop = l.scrollHeight;
  }

  seatName(seat) {
    const s = this.game && this.game.seats && this.game.seats[seat];
    return s ? s.name : `Seat ${seat}`;
  }

  // --- header / banners --------------------------------------------------------------------------

  renderHeader() {
    const e = this.el;
    const g = this.game;
    const title = g ? g.seats.map((s) => s.name).join(' vs ') : this.gameId;
    const controls = [];
    if (this.isPlayer) {
      const full = h('input', { type: 'checkbox', checked: g && g.seats[this.seat] && g.seats[this.seat].stop_policy === 'all' });
      full.addEventListener('change', () => this.send({ type: 'settings', stop_policy: full.checked ? 'all' : 'arena' }));
      controls.push(h('label', { class: 'toggle', title: 'Stop at every step where you could act' }, full, ' Full control'));
      controls.push(h('button', { class: 'btn small danger', onclick: () => this.confirmConcede() }, 'Concede'));
    } else {
      const rev = h('input', { type: 'checkbox', checked: this.reveal && this.mayReveal, disabled: !this.mayReveal });
      rev.addEventListener('change', () => {
        this.reveal = rev.checked;
        localStorage.setItem('colosseo.reveal', this.reveal ? '1' : '0');
        this.send({ type: 'settings', reveal: this.reveal }); // the server answers with the matching view
      });
      controls.push(h('label', {
        class: 'toggle' + (this.mayReveal ? '' : ' disabled'),
        title: this.mayReveal ? 'Show both hands (and the agents’ comments)' : 'Only the game’s creator can show hands on a protected game',
      }, rev, ' Show hands'));
    }
    const img = h('input', { type: 'checkbox', checked: settings.images });
    img.addEventListener('change', () => { setImages(img.checked); this.cache.clear(); this.groups.clear(); this.render(); });
    controls.push(h('label', { class: 'toggle' }, img, ' Images'));
    const size = h('input', {
      type: 'range', class: 'card-size', min: CARD_SCALE.min, max: CARD_SCALE.max, step: CARD_SCALE.step,
      value: settings.cardScale, 'aria-label': 'Card size',
    });
    const pct = h('span', { class: 'card-size-value' }, `${Math.round(settings.cardScale * 100)}%`);
    size.addEventListener('input', () => {
      setCardScale(parseFloat(size.value));
      pct.textContent = `${Math.round(settings.cardScale * 100)}%`;
      this.fit();
    });
    size.addEventListener('dblclick', () => { size.value = 1; size.dispatchEvent(new Event('input')); });
    controls.push(h('label', { class: 'toggle size-control', title: 'Card size (100% fits the window; double-click to reset)' },
      'Cards ', size, pct));
    e.header.replaceChildren(
      h('div', { class: 'side-title' }, h('a', { href: '#/' }, '←'), ' ', title),
      h('div', { class: 'side-sub mono' }, `game ${this.gameId}`, this.isPlayer ? ` · seat ${this.seat}` : ' · spectator'),
      h('div', { class: 'side-controls' }, controls));
  }

  checkAgentSeat() {
    // when playing against an external agent, show how to connect it until it does
    const g = this.game;
    if (!g || !this.isPlayer) return;
    const other = g.seats.find((s) => s.seat !== this.seat);
    if (!other || other.type !== 'agent') { this.el.banner.classList.add('hidden'); return; }
    const update = async () => {
      try {
        const info = await getGame(this.gameId);
        const seat = info.seats.find((s) => s.seat === other.seat);
        if (seat && seat.connected) {
          this.el.banner.classList.add('hidden');
          clearInterval(this.pollTimer);
        } else {
          const token = seatToken(this.gameId, other.seat);
          this.el.banner.classList.remove('hidden');
          this.el.banner.replaceChildren(
            h('b', {}, 'Waiting for an agent to take seat ' + other.seat + '. '),
            'Connect one with the Python SDK:',
            h('pre', {}, `python -c "from colosseo import play; from colosseo.agents import HeuristicAgent; ` +
              `play(HeuristicAgent(), '${this.gameId}', ${other.seat}, '${location.origin}'${token ? `, token='${token}'` : ''})"`),
            token ? null : h('div', { class: 'muted' }, 'This browser does not hold the seat token; on a protected game ask the game’s creator for it.'));
        }
      } catch { /* ignore */ }
    };
    clearInterval(this.pollTimer);
    update();
    this.pollTimer = setInterval(update, 2000);
  }

  confirmConcede() {
    modal('Concede?', h('p', {}, 'You will lose this game.'), [
      { label: 'Cancel' },
      { label: 'Concede', cls: 'danger', onClick: () => this.send({ type: 'concede' }) },
    ]);
  }

  showResult() {
    const r = this.result || {};
    let title;
    if (r.status === 'terminated') title = 'Game stopped';
    else if (r.status === 'abandoned') title = 'Game abandoned';
    else if (r.status === 'timeout') title = 'Game timed out';
    else if (r.status === 'limit') title = 'Game stopped (limit reached)';
    else if (r.status === 'turn_limit') title = 'Turn limit reached (void)';
    else if (r.error) title = 'Game ended with an error';
    else if (r.winner_seat === null || r.winner_seat === undefined) title = 'Draw';
    else if (this.isPlayer) title = r.winner_seat === this.seat ? 'Victory!' : 'Defeat';
    else title = `${r.winner} wins`;
    modal(title, h('div', { class: 'result' },
      h('p', {}, `${r.turns || 0} turns · ${r.decisions || 0} decisions · ${Math.round(r.duration_s || 0)} s`),
      (r.players || []).map((p) => h('div', {}, `${p.name} (${p.type}) — life ${p.life}${p.won ? ' — winner' : ''}`)),
      r.reason === 'forfeit' ? h('p', { class: 'muted' }, `${this.seatName(r.forfeit_seat)} forfeited (${({ time: 'out of time', agent_error: 'agent error', concede: 'conceded' })[r.forfeit_reason] || r.forfeit_reason})`)
        : r.reason && r.reason !== r.error ? h('p', { class: 'muted' }, r.reason) : null,
      r.error ? h('p', { class: 'error' }, r.error) : null),
    [{ label: 'View board' }, { label: 'Back to lobby', cls: 'primary', onClick: () => { location.hash = '#/'; } }]);
  }

  // --- rendering -----------------------------------------------------------------------------------

  players() {
    const st = this.state;
    if (!st) return [null, null];
    const ps = st.players || [];
    let bottom;
    if (st.you) bottom = ps.find((p) => p.id === st.you);
    if (!bottom) bottom = ps.find((p) => p.seat === 0) || ps[0];
    const top = ps.find((p) => p !== bottom) || null;
    return [top, bottom];
  }

  nameOf(id) {
    if (!id || !this.state) return '?';
    for (const p of this.state.players || []) {
      if (p.id === id) return p.name;
      for (const z of ['battlefield', 'hand', 'graveyard']) {
        const c = (p[z] || []).find((x) => x.id === id);
        if (c) return c.name;
      }
    }
    const s = (this.state.stack || []).find((x) => x.id === id);
    return s ? s.name : '?';
  }

  render() {
    if (!this.state) {
      this.el.center.textContent = this.result ? '' : 'Waiting for the game to start…';
      this.renderPrompt();
      return;
    }
    this.el.center.textContent = '';
    this.computeInteraction();
    const [top, bottom] = this.players();
    this.renderPlayer(top, 'top');
    this.renderPlayer(bottom, 'bottom');
    this.renderPhase();
    this.renderStack();
    this.renderPrompt();
    this.squeeze();
    const pin = pinnedCard();
    if (pin) {
      // keep a pinned explanation current (stats change while it is open)
      const now = (this.state.players || []).flatMap((q) => q.battlefield || []).find((c) => c.id === pin.id);
      if (now) showPreview(now, true); else hidePreview(true);
    }
  }

  /** Builds per-object highlight classes, badges and click handlers for the pending decision. */
  computeInteraction() {
    const extras = new Map();
    const add = (id, cls, badge) => {
      if (!extras.has(id)) extras.set(id, { classes: [], badges: [] });
      const x = extras.get(id);
      if (cls) x.classes.push(cls);
      if (badge) x.badges.push(badge);
    };
    this.clickable = new Map();
    this.offBoard = [];
    // combat badges from the state
    for (const g of (this.state.combat && this.state.combat.groups) || []) {
      for (const b of g.blockers || []) add(b, null, `blocks ${this.nameOf(g.attackers[0])}`);
      for (const a of g.attackers || []) if (g.blocked && !(g.blockers || []).length) add(a, null, 'blocked');
    }
    const d = this.decision;
    if (d && this.isPlayer) {
      const visible = this.visibleIds();
      const optionsBySource = new Map();
      switch (d.kind) {
        case 'priority':
          for (const o of d.options) {
            if (o.id === 'pass' || o.id === 'pass_turn') continue;
            const src = o.source_id;
            if (src && visible.has(src)) {
              if (!optionsBySource.has(src)) optionsBySource.set(src, []);
              optionsBySource.get(src).push(o);
            } else {
              this.offBoard.push(o);
            }
          }
          for (const [src, opts] of optionsBySource) {
            add(src, 'playable');
            this.clickable.set(src, (el) => {
              if (opts.length === 1) this.answer({ choice: opts[0].id });
              else this.menu(el, opts.map((o) => ({ label: o.label, run: () => this.answer({ choice: o.id }) })));
            });
          }
          break;
        case 'declare_attackers':
          for (const a of d.attackers || []) {
            const on = this.local.attackers.has(a.id);
            add(a.id, on ? 'atk-selected' : 'selectable', on ? 'attacking' : null);
            this.clickable.set(a.id, (el) => this.toggleAttacker(a, el));
          }
          break;
        case 'declare_blockers': {
          const assigned = this.local.blocks;
          for (const b of d.blockers || []) {
            const target = assigned.get(b.id);
            const pending = this.local.blocker === b.id;
            add(b.id, pending ? 'blk-pending' : target ? 'blk-selected' : 'selectable', target ? `blocks ${this.nameOf(target)}` : null);
            this.clickable.set(b.id, () => this.clickBlocker(b));
          }
          if (this.local.blocker) {
            const legal = ((d.blockers || []).find((b) => b.id === this.local.blocker) || {}).attackers || [];
            for (const a of legal) {
              add(a, 'target-candidate');
              this.clickable.set(a, () => this.assignBlock(a));
            }
          }
          for (const a of d.attackers || []) if (!this.clickable.has(a.id)) add(a.id, 'enemy-attacker');
          break;
        }
        case 'target':
        case 'pay_mana':
          for (const o of d.options) {
            if (['done', 'cancel', 'special'].includes(o.id) || o.id.startsWith('pool:')) continue;
            if (visible.has(o.id)) {
              add(o.id, o.selected ? 'target-selected' : 'target-candidate');
              this.clickable.set(o.id, () => this.answer({ choice: o.id }));
            } else {
              this.offBoard.push(o);
            }
          }
          break;
        default:
          break;
      }
    }
    this.extras = extras;
  }

  visibleIds() {
    const ids = new Set();
    for (const p of this.state.players || []) {
      ids.add(p.id);
      for (const c of p.battlefield || []) ids.add(c.id);
      for (const c of p.hand || []) ids.add(c.id);
    }
    for (const s of this.state.stack || []) ids.add(s.id);
    return ids;
  }

  cardEl(card) {
    const x = this.extras.get(card.id) || {};
    const el = renderCard(this.cache, card, x);
    el.classList.remove('attached', 'foreign'); // set again by itemEl when it still is
    if (this.clickable.has(card.id)) el.classList.add('clickable');
    return el;
  }

  renderZone(container, cards) {
    const els = cards.map((c) => this.cardEl(c));
    // keyed update: keep existing nodes (images stay loaded), reorder as needed
    const current = Array.from(container.children);
    els.forEach((el, i) => { if (current[i] !== el) container.insertBefore(el, container.children[i] || null); });
    while (container.children.length > els.length) container.lastChild.remove();
  }

  renderPlayer(p, where) {
    const e = this.el;
    const info = where === 'top' ? e.topInfo : e.botInfo;
    const hand = where === 'top' ? e.topHand : e.botHand;
    if (!p) { info.replaceChildren(); return; }

    const x = this.extras.get(p.id) || { classes: [] };
    const active = this.state.active_player === p.id;
    const prio = this.state.priority_player === p.id;
    const pool = Object.entries(p.mana_pool || {}).filter(([, n]) => n > 0);
    const counters = Object.entries(p.counters || {});
    info.className = `player-info ${where}` + (active ? ' active' : '') + (x.classes.length ? ' ' + x.classes.join(' ') : '')
      + (this.clickable.has(p.id) ? ' clickable' : '');
    info.dataset.id = p.id;
    info.replaceChildren(
      h('div', { class: 'avatar' + (prio ? ' priority' : '') }, h('div', { class: 'life' }, p.life)),
      h('div', { class: 'pinfo' },
        h('div', { class: 'pname' }, p.name, active ? h('span', { class: 'turn-dot', title: 'active player' }) : null),
        h('div', { class: 'pcounts' },
          h('span', { title: 'library' }, `📚 ${p.library_count}`),
          h('span', { title: 'hand' }, `✋ ${p.hand_count}`),
          h('button', { class: 'linkish', onclick: (ev) => { ev.stopPropagation(); this.showZone(`${p.name}'s graveyard`, p.graveyard || []); } },
            `⚰ ${(p.graveyard || []).length}`),
          this.exileOf(p).length ? h('button', { class: 'linkish', onclick: (ev) => { ev.stopPropagation(); this.showZone(`Exiled (${p.name})`, this.exileOf(p)); } },
            `☄ ${this.exileOf(p).length}`) : null),
        pool.length ? h('div', { class: 'pool' }, 'pool ', pool.map(([k, n]) => manaSymbols(`{${k}}`.repeat(n)))) : null,
        counters.length ? h('div', { class: 'pcounters' }, counters.map(([k, n]) => `${k}: ${n}`).join(', ')) : null));

    // hand
    if (p.hand) {
      const sorted = [...p.hand].sort((a, b) => (isLand(b) - isLand(a)) || ((a.mana_value || 0) - (b.mana_value || 0)));
      hand.classList.remove('backs');
      this.renderZone(hand, sorted);
    } else {
      hand.classList.add('backs');
      hand.replaceChildren(...Array.from({ length: Math.min(p.hand_count, 10) }, () => cardBack()));
    }

    this.renderBattlefield(p, where);
  }

  /**
   * Lays out one player's battlefield: creatures in one row, lands and the other permanents in their own areas.
   * Auras and equipment stay with what they are attached to (whoever controls them); identical tokens are
   * stacked with a count.
   */
  renderBattlefield(p, where) {
    const e = this.el;
    const all = (this.state.players || []).flatMap((q) => q.battlefield || []);
    const onField = new Set(all.map((c) => c.id));
    const isAttached = (c) => c.attached_to && onField.has(c.attached_to) && c.attached_to !== c.id;
    const attachmentsOf = (c) => all.filter((a) => a.attached_to === c.id && isAttached(a));
    const bf = (p.battlefield || []).filter((c) => !isAttached(c));
    const creatures = bf.filter(isCreature);
    const lands = bf.filter((c) => isLand(c)).sort((a, b) => (a.name > b.name ? 1 : a.name < b.name ? -1 : (a.tapped - b.tapped)));
    const others = bf.filter((c) => !isCreature(c) && !isLand(c));
    // lands with the same name and tapped state are drawn as a pile
    lands.forEach((c, i) => {
      if (i > 0 && lands[i - 1].tapped === c.tapped && lands[i - 1].name === c.name && !attachmentsOf(c).length) this.addExtra(c.id, 'stacked');
    });
    const lands2 = where === 'top' ? e.topLands : e.botLands;
    const others2 = where === 'top' ? e.topOthers : e.botOthers;
    const creatures2 = where === 'top' ? e.topCreatures : e.botCreatures;
    this.renderItems(creatures2, this.items(creatures, attachmentsOf, p));
    this.renderItems(lands2, this.items(lands, attachmentsOf, p, false));
    this.renderItems(others2, this.items(others, attachmentsOf, p));
    others2.classList.toggle('empty', !others.length);
  }

  /** Display items for a row: a card, a card with its attachments, or a stack of identical tokens. */
  items(cards, attachmentsOf, owner, stackTokens = true) {
    const out = [];
    const stacks = new Map();
    for (const c of cards) {
      const atts = attachmentsOf(c);
      if (atts.length) {
        out.push({ key: `att:${c.id}`, host: c, attachments: atts, owner });
        continue;
      }
      if (stackTokens && c.token) {
        const x = this.extras.get(c.id) || {};
        const k = JSON.stringify([c.name, c.power, c.toughness, c.tapped, c.damage, c.counters, c.summoning_sick,
          c.attacking, c.blocking, c.explain, x.classes, x.badges, this.clickable.has(c.id)]);
        if (stacks.has(k)) { stacks.get(k).members.push(c); continue; }
        const item = { key: null, host: c, members: [c], owner };
        stacks.set(k, item);
        out.push(item);
        continue;
      }
      out.push({ key: null, host: c, owner });
    }
    for (const it of out) if (it.members && it.members.length > 1) it.key = `stk:${it.host.id}`;
    return out;
  }

  itemEl(it) {
    const host = this.cardEl(it.host);
    if (!it.key) return host;
    let w = this.groups.get(it.key);
    if (!w) {
      w = h('div', { dataset: { group: it.key } });
      this.groups.set(it.key, w);
    }
    let kids;
    if (it.attachments) {
      w.className = 'attach-group';
      kids = [...it.attachments.map((a) => {
        const el = this.cardEl(a);
        el.classList.add('attached');
        el.classList.toggle('foreign', a.controller !== it.host.controller);
        el.title = a.controller !== it.host.controller ? `${a.name} (controlled by ${this.nameOf(a.controller)})` : '';
        return el;
      }), host];
    } else {
      w.className = 'token-stack';
      w.dataset.count = it.members.length;
      w.title = `${it.members.length} × ${it.host.name}`;
      let badge = w.querySelector(':scope > .stack-count');
      if (!badge) badge = h('span', { class: 'stack-count' });
      badge.textContent = `×${it.members.length}`;
      kids = [host, badge];
    }
    if (kids.some((k, i) => w.children[i] !== k) || w.children.length !== kids.length) w.replaceChildren(...kids);
    return w;
  }

  renderItems(container, items) {
    const els = items.map((it) => this.itemEl(it));
    const current = Array.from(container.children);
    els.forEach((el, i) => { if (current[i] !== el) container.insertBefore(el, container.children[i] || null); });
    while (container.children.length > els.length) container.lastChild.remove();
  }

  // --- sizing ----------------------------------------------------------------------------------------

  /**
   * Sizes cards to the window: the largest width at which both players' rows and the hand fit the board's
   * height (and a row of creatures its width), times the player's card-size setting, within CARD_MIN..CARD_MAX.
   * --ui scales the rest of the interface (text, buttons, player panels) on large screens.
   */
  fit() {
    if (!this.board) return;
    const root = document.documentElement.style;
    const ui = Math.min(2.2, Math.max(0.85, Math.min(window.innerWidth / 1920, window.innerHeight / 1080)));
    root.setProperty('--ui', ui.toFixed(3));
    const H = this.board.clientHeight;
    const W = this.board.clientWidth;
    if (!H || !W) return;
    const fixed = 250 * ui; // prompt bar, phase bar, opponent's info row, gaps and paddings
    const auto = Math.min((H - fixed) / BOARD_CARD_HEIGHTS, (W - 24) / 10.5);
    const w = Math.round(Math.min(CARD_MAX, Math.max(CARD_MIN, auto * settings.cardScale)));
    root.setProperty('--card-w', `${w}px`);
    this.squeeze();
  }

  /** Shrinks the cards of any row that would not fit its width, instead of wrapping it onto a second line. */
  squeeze() {
    for (const row of this.root.querySelectorAll('.zone, .hand-row.bottom')) {
      row.style.removeProperty('--fit');
      if (!row.children.length) continue;
      const over = row.scrollWidth / Math.max(1, row.clientWidth);
      if (over > 1.001) row.style.setProperty('--fit', Math.max(0.35, 0.98 / over).toFixed(3));
    }
  }
  addExtra(id, cls) {
    if (!this.extras.has(id)) this.extras.set(id, { classes: [], badges: [] });
    this.extras.get(id).classes.push(cls);
  }

  exileOf(p) {
    return (this.state.exile || []).filter((c) => c.owner === p.id);
  }

  renderPhase() {
    const st = this.state;
    const active = (st.players || []).find((p) => p.id === st.active_player);
    this.el.phase.replaceChildren(
      h('span', { class: 'turn-label' }, `Turn ${st.turn}`, active ? ` · ${active.name}` : ''),
      ...STEPS.map(([key, label]) => h('span', { class: 'step' + (st.step === key ? ' current' : '') }, label)));
  }

  renderStack() {
    const stack = this.state.stack || [];
    if (!stack.length) {
      this.el.stack.replaceChildren();
      this.el.stack.classList.add('empty');
      return;
    }
    this.el.stack.classList.remove('empty');
    this.el.stack.replaceChildren(h('div', { class: 'stack-title' }, 'Stack'),
      ...stack.map((s, i) => {
        const card = { ...s, hidden: false };
        const el = this.cardEl(card);
        el.classList.add('on-stack');
        const who = (this.state.players || []).find((p) => p.id === s.controller);
        return h('div', { class: 'stack-item' + (i === 0 ? ' top' : '') }, el,
          h('div', { class: 'stack-info' },
            h('div', {}, h('b', {}, s.name)),
            h('div', { class: 'muted' }, who ? who.name : ''),
            (s.targets || []).length ? h('div', {}, '→ ', s.targets.map((t) => this.nameOf(t)).join(', ')) : null,
            s.kind === 'ability' ? h('div', { class: 'small-rules' }, (s.rules || []).join(' ')) : null));
      }));
  }

  // --- prompt bar ------------------------------------------------------------------------------------

  renderPrompt() {
    const bar = this.el.prompt;
    const d = this.decision;
    if (this.result) {
      bar.replaceChildren(h('span', { class: 'prompt-text' }, 'Game over'),
        h('button', { class: 'btn', onclick: () => this.showResult() }, 'Result'),
        h('a', { class: 'btn primary', href: '#/' }, 'Lobby'));
      bar.className = 'prompt-bar';
      return;
    }
    if (!this.isPlayer) {
      const w = this.waiting;
      bar.className = 'prompt-bar spectator';
      bar.replaceChildren(h('span', { class: 'prompt-text' },
        w ? `${this.seatName(w.seat)} is deciding: ${w.prompt}` : 'Spectating'));
      return;
    }
    if (!d) {
      bar.className = 'prompt-bar idle';
      const opp = this.players()[0];
      bar.replaceChildren(h('span', { class: 'prompt-text muted' },
        this.sent ? 'Waiting…' : opp ? `Waiting for ${opp.name}…` : 'Waiting…'));
      return;
    }
    bar.className = 'prompt-bar active';
    const text = h('span', { class: 'prompt-text' }, d.prompt);
    if (d.rejection && d.rejection !== d.error) text.appendChild(h('div', { class: 'error' }, d.rejection));
    if (d.error) text.appendChild(h('div', { class: 'error' }, d.error));
    if (d.kind === 'declare_blockers') {
      const needy = (d.attackers || []).filter((a) => (a.min_blockers || 1) > 1);
      if (needy.length) {
        text.appendChild(h('div', { class: 'muted' },
          needy.map((a) => `${a.name} needs ${a.min_blockers}+ blockers`).join(' · ')));
      }
    }
    const buttons = [];
    const btn = (label, fn, cls = '') => buttons.push(h('button', { class: 'btn ' + cls, onclick: fn }, label));
    switch (d.kind) {
      case 'priority': {
        const stack = this.state.stack || [];
        for (const o of this.offBoard) btn(o.label, () => this.answer({ choice: o.id }), 'small');
        btn('Pass turn', () => this.answer({ choice: 'pass_turn' }), 'subtle');
        btn(stack.length ? 'Resolve' : 'Pass', () => this.answer({ choice: 'pass' }), 'primary');
        if (!this.clickable.size && !this.offBoard.length) text.appendChild(h('span', { class: 'muted' }, ' (nothing to play)'));
        else text.appendChild(h('span', { class: 'hint' }, ' — click a highlighted card to play it'));
        break;
      }
      case 'mulligan':
        text.textContent = `${d.prompt} (hand of ${d.hand_size})`;
        btn('Mulligan', () => this.answer({ choice: 'mulligan' }));
        btn('Keep', () => this.answer({ choice: 'keep' }), 'primary');
        break;
      case 'declare_attackers': {
        const n = this.local.attackers.size;
        text.appendChild(h('span', { class: 'hint' }, ' — click creatures to toggle attackers'));
        btn('All', () => { for (const a of d.attackers || []) if (!this.local.attackers.has(a.id)) this.local.attackers.set(a.id, this.defaultDefender(a)); this.render(); }, 'small');
        if (n) btn('Clear', () => { this.local.attackers.clear(); this.render(); }, 'small');
        btn(n ? `Attack with ${n}` : 'No attack', () => this.answer({
          attackers: Array.from(this.local.attackers, ([attacker, defender]) => (defender ? { attacker, defender } : { attacker })),
        }), 'primary');
        break;
      }
      case 'declare_blockers': {
        const n = this.local.blocks.size;
        text.appendChild(h('span', { class: 'hint' }, this.local.blocker
          ? ' — now click the attacker to block'
          : ' — click one of your creatures, then the attacker it blocks'));
        if (n) btn('Clear', () => { this.local.blocks.clear(); this.local.blocker = null; this.render(); }, 'small');
        btn(n ? `Confirm ${n} block${n > 1 ? 's' : ''}` : 'No blocks', () => this.answer({
          blocks: Array.from(this.local.blocks, ([blocker, attacker]) => ({ blocker, attacker })),
        }), 'primary');
        break;
      }
      case 'target':
        if (this.offBoard.length) btn(`Choose from ${this.offBoard.length} card${this.offBoard.length > 1 ? 's' : ''}…`, () => this.targetPicker(), 'small');
        if (d.options.find((o) => o.id === 'done')) btn(d.options.find((o) => o.id === 'done').label, () => this.answer({ choice: 'done' }), 'primary');
        if (this.clickable.size) text.appendChild(h('span', { class: 'hint' }, ' — click a highlighted target'));
        break;
      case 'pay_mana':
        for (const o of d.options.filter((o) => o.id.startsWith('pool:') || o.id === 'special')) btn(o.label, () => this.answer({ choice: o.id }), 'small');
        btn('Cancel', () => this.answer({ choice: 'cancel' }));
        text.appendChild(h('span', { class: 'hint' }, ' — click lands to tap them'));
        break;
      case 'yes_no':
        for (const o of d.options) btn(o.label, () => this.answer({ choice: o.id }), o.id === 'yes' ? 'primary' : '');
        break;
      default:
        btn('Choose…', () => this.autoOpen(true), 'primary');
    }
    bar.replaceChildren(text, h('span', { class: 'prompt-buttons' }, buttons));
  }

  defaultDefender(a) {
    const defs = a.defenders || [];
    const player = defs.find((id) => (this.state.players || []).some((p) => p.id === id));
    return player || defs[0] || null;
  }

  toggleAttacker(a, el) {
    if (this.local.attackers.has(a.id)) {
      this.local.attackers.delete(a.id);
      this.render();
      return;
    }
    const defs = a.defenders || [];
    if (defs.length > 1) {
      this.menu(el, defs.map((id) => ({ label: `Attack ${this.nameOf(id)}`, run: () => { this.local.attackers.set(a.id, id); this.render(); } })));
    } else {
      this.local.attackers.set(a.id, defs[0] || null);
      this.render();
    }
  }

  clickBlocker(b) {
    if (this.local.blocks.has(b.id)) {
      this.local.blocks.delete(b.id);
      this.local.blocker = null;
    } else if (this.local.blocker === b.id) {
      this.local.blocker = null;
    } else if ((b.attackers || []).length === 1) {
      this.local.blocks.set(b.id, b.attackers[0]);
      this.local.blocker = null;
    } else {
      this.local.blocker = b.id;
    }
    this.render();
  }

  assignBlock(attackerId) {
    if (!this.local.blocker) return;
    this.local.blocks.set(this.local.blocker, attackerId);
    this.local.blocker = null;
    this.render();
  }

  // --- clicks & keys ---------------------------------------------------------------------------------

  onBoardClick(ev) {
    const target = ev.target.closest('[data-id]');
    if (!target) { hidePreview(true); return; }
    const id = target.dataset.id;
    const fn = this.clickable && this.clickable.get(id);
    if (fn) {
      ev.stopPropagation();
      hidePreview(true);
      fn(target);
      return;
    }
    // a click on a card that has nothing to do pins (or unpins) its explanation
    const card = target.classList.contains('card') && target._card;
    if (!card) return;
    const pinned = pinnedCard();
    if (pinned && pinned.id === card.id) hidePreview(true);
    else showPreview(card, true);
  }

  onKey(ev) {
    if (!this.decision || document.querySelector('.modal-overlay')) return;
    if (ev.target && ['INPUT', 'SELECT', 'TEXTAREA'].includes(ev.target.tagName)) return;
    if (ev.key === ' ' || ev.key === 'Enter') {
      const primary = this.el.prompt.querySelector('.btn.primary');
      if (primary) { ev.preventDefault(); primary.click(); }
    }
  }

  menu(anchor, items) {
    document.querySelectorAll('.popmenu').forEach((m) => m.remove());
    const rect = anchor.getBoundingClientRect();
    const menu = h('div', { class: 'popmenu' }, items.map((it) => h('button', {
      class: 'popmenu-item', onclick: (e) => { e.stopPropagation(); menu.remove(); it.run(); },
    }, it.label)));
    menu.style.left = `${Math.max(8, Math.min(window.innerWidth - 320, rect.left))}px`;
    menu.style.top = `${Math.max(8, rect.top - 8)}px`;
    document.body.appendChild(menu);
    menu.style.transform = 'translateY(-100%)';
    const close = (e) => { if (!menu.contains(e.target)) { menu.remove(); document.removeEventListener('mousedown', close); } };
    setTimeout(() => document.addEventListener('mousedown', close), 0);
  }

  showZone(title, cards) {
    const cache = new Map();
    modal(title, h('div', { class: 'zone-view' }, cards.length ? cards.map((c) => renderCard(cache, c)) : h('p', { class: 'muted' }, 'Empty')), [], { wide: true });
  }

  // --- dialogs ------------------------------------------------------------------------------------

  /** Opens the modal for decision kinds that need one (called when a decision arrives). */
  autoOpen(force = false) {
    const d = this.decision;
    if (!d || !this.isPlayer) return;
    if (d.kind === 'target') {
      const onBoard = d.options.some((o) => this.clickable.has(o.id));
      if ((d.cards && d.cards.length) || (!onBoard && this.offBoard.length)) this.targetPicker();
      return;
    }
    if (['priority', 'mulligan', 'declare_attackers', 'declare_blockers', 'yes_no', 'pay_mana'].includes(d.kind) && !force) return;
    closeAllModals();
    switch (d.kind) {
      case 'amount': return this.amountDialog(d);
      case 'multi_amount': return this.multiAmountDialog(d);
      case 'choose_pile': return this.pileDialog(d);
      default: return this.listDialog(d);
    }
  }

  listDialog(d) {
    let filter = '';
    const list = h('div', { class: 'choice-list' });
    const fill = () => {
      list.replaceChildren(...d.options
        .filter((o) => !filter || o.label.toLowerCase().includes(filter))
        .slice(0, 300)
        .map((o) => h('button', { class: 'choice', onclick: () => this.answer({ choice: o.id }) }, escapeRules(o.label))));
    };
    fill();
    const body = [h('p', {}, d.prompt)];
    if (d.searchable) {
      const search = h('input', { class: 'input', placeholder: 'Search…' });
      search.addEventListener('input', () => { filter = search.value.toLowerCase(); fill(); });
      body.push(search);
      setTimeout(() => search.focus(), 50);
    }
    body.push(list);
    modal(KIND_TITLES[d.kind] || 'Choose', h('div', {}, body), [], { dismissable: true });
  }

  targetPicker() {
    const d = this.decision;
    if (!d) return;
    const cache = new Map();
    const cards = new Map((d.cards || []).map((c) => [c.id, c]));
    const findCard = (id) => {
      if (cards.has(id)) return cards.get(id);
      for (const p of this.state.players || []) {
        for (const z of ['graveyard', 'hand', 'battlefield']) {
          const c = (p[z] || []).find((x) => x.id === id);
          if (c) return c;
        }
      }
      return (this.state.exile || []).find((x) => x.id === id);
    };
    const tiles = [];
    const shown = new Set();
    for (const o of d.options) {
      if (o.id === 'done') continue;
      const c = findCard(o.id);
      shown.add(o.id);
      if (c) {
        const el = renderCard(cache, c, { classes: [o.selected ? 'target-selected' : 'target-candidate', 'clickable'] });
        el.addEventListener('click', () => this.answer({ choice: o.id }));
        tiles.push(h('div', { class: 'pick-tile' }, el, o.selected ? h('small', {}, 'selected') : null));
      } else {
        tiles.push(h('button', { class: 'choice', onclick: () => this.answer({ choice: o.id }) }, o.label));
      }
    }
    // cards that are shown but can't be chosen (e.g. "look at the top 3, pick a creature")
    const unselectable = (d.cards || []).filter((c) => c.selectable === false && !shown.has(c.id));
    const buttons = [];
    const done = d.options.find((o) => o.id === 'done');
    if (done) buttons.push({ label: done.label, cls: 'primary', onClick: () => this.answer({ choice: 'done' }) });
    else buttons.push({ label: 'Back to board' });
    modal(d.prompt, h('div', {},
      d.min !== undefined ? h('p', { class: 'muted' }, `Choose ${d.min === d.max ? d.min : `${d.min}–${d.max}`}` + (d.chosen && d.chosen.length ? ` (${d.chosen.length} chosen)` : '')) : null,
      h('div', { class: 'zone-view' }, tiles),
      unselectable.length ? h('div', {}, h('p', { class: 'muted' }, 'Not selectable:'),
        h('div', { class: 'zone-view dim' }, unselectable.map((c) => renderCard(cache, c)))) : null),
    buttons, { wide: true });
  }

  amountDialog(d) {
    const max = d.max_affordable !== undefined ? Math.min(d.max, d.max_affordable) : Math.min(d.max, 99);
    const input = h('input', { class: 'input', type: 'number', min: d.min, max: d.max, value: d.announce_x ? max : d.min });
    const range = h('input', { type: 'range', min: d.min, max: Math.max(d.min, max), value: input.value });
    range.addEventListener('input', () => { input.value = range.value; });
    input.addEventListener('input', () => { range.value = input.value; });
    modal(KIND_TITLES.amount, h('div', {}, h('p', {}, d.prompt),
      h('div', { class: 'inline' }, range, input),
      d.max_affordable !== undefined ? h('p', { class: 'muted' }, `You can afford up to ${d.max_affordable}.`) : null),
    [{ label: 'OK', cls: 'primary', onClick: () => {
      const v = parseInt(input.value, 10);
      if (Number.isNaN(v) || v < d.min || v > d.max) { toast(`Enter a number between ${d.min} and ${d.max}`, 'error'); return false; }
      this.answer({ amount: v });
      return true;
    } }], { dismissable: false });
  }

  multiAmountDialog(d) {
    const inputs = (d.items || []).map((it) => h('input', { class: 'input small', type: 'number', min: it.min, max: it.max, value: it.default }));
    const total = h('span', { class: 'muted' });
    const update = () => { total.textContent = `total ${inputs.reduce((a, i) => a + (parseInt(i.value, 10) || 0), 0)} (allowed ${d.total_min}–${d.total_max})`; };
    inputs.forEach((i) => i.addEventListener('input', update));
    update();
    const buttons = [{ label: 'OK', cls: 'primary', onClick: () => { this.answer({ amounts: inputs.map((i) => parseInt(i.value, 10) || 0) }); return true; } }];
    if (d.can_cancel) buttons.unshift({ label: 'Cancel', onClick: () => this.answer({ choice: 'cancel' }) });
    modal(d.prompt || 'Distribute', h('div', {},
      (d.items || []).map((it, idx) => h('div', { class: 'form-row' }, h('label', {}, it.label), inputs[idx])), total),
    buttons, { dismissable: false });
  }

  pileDialog(d) {
    const cache = new Map();
    const pile = (key, label) => h('div', { class: 'pile' }, h('h4', {}, label),
      h('div', { class: 'zone-view' }, (d[key] || []).map((c) => renderCard(cache, c))),
      h('button', { class: 'btn primary', onclick: () => this.answer({ choice: key }) }, `Choose ${label}`));
    modal(d.prompt, h('div', { class: 'piles' }, pile('pile1', 'Pile 1'), pile('pile2', 'Pile 2')), [], { wide: true, dismissable: false });
  }
}
