// Lobby (create/watch games), deck browser and agent connection guide.
import { getDecks, getSets, getGames, createGame, terminateGame, getHealth, apiKey, setApiKey, seatToken, ownerToken } from './api.js';
import { h, toast, colorPips, manaSymbols } from './ui.js';
import { settings, setImages, showPreview, hidePreview } from './cards.js';

const PREFS_KEY = 'colosseo.lobby';

function loadPrefs() {
  try { return JSON.parse(localStorage.getItem(PREFS_KEY)) || {}; } catch { return {}; }
}
function savePrefs(p) { localStorage.setItem(PREFS_KEY, JSON.stringify(p)); }

async function deckOptions() {
  const [decks, sets] = await Promise.all([getDecks(true), getSets().catch(() => [])]);
  const opts = decks.map((d) => ({ id: d.id, label: d.name, colors: d.colors, description: d.description }));
  for (const s of sets) opts.push({ id: s.deck, label: `Random sealed deck (${s.name})`, colors: '', description: `Six ${s.name} boosters, auto-built into a 2-color deck` });
  return opts;
}

function deckSelect(options, value, onChange) {
  const sel = h('select', { class: 'input', onchange: (e) => onChange && onChange(e.target.value) },
    h('option', { value: '__random__' }, 'Random preconstructed deck'),
    options.map((o) => h('option', { value: o.id, selected: o.id === value }, `${o.label}${o.colors ? ` (${o.colors})` : ''}`)));
  if (value && !options.find((o) => o.id === value) && value !== '__random__') sel.value = options[0] && options[0].id;
  if (value === '__random__') sel.value = '__random__';
  return sel;
}

function pickDeck(value, options) {
  if (value !== '__random__') return value;
  const pre = options.filter((o) => !o.id.startsWith('sealed:'));
  return pre[Math.floor(Math.random() * pre.length)].id;
}

function statusBadge(status) {
  return h('span', { class: `status status-${status}` }, status);
}

export function renderLobby(app) {
  app.className = 'page lobby';
  const prefs = loadPrefs();
  let timer = null;
  let destroyed = false;

  const playCard = h('section', { class: 'panel' }, h('h2', {}, 'Play'), h('p', { class: 'muted' }, 'Loading decks…'));
  const watchCard = h('section', { class: 'panel' }, h('h2', {}, 'Watch AI vs AI'));
  const gamesCard = h('section', { class: 'panel games-panel' }, h('h2', {}, 'Games'), h('div', { class: 'games-list' }, 'Loading…'));
  app.append(
    h('div', { class: 'hero' },
      h('h1', {}, 'MTG Colosseo'),
      h('p', {}, 'Magic: The Gathering for humans and agents, powered by the XMage rules engine. ',
        'Play limited decks against XMage’s AI or your own agents, or watch agents battle it out.')),
    h('div', { class: 'lobby-grid' }, playCard, watchCard, gamesCard));

  let health = {};
  Promise.all([deckOptions(), getHealth().catch(() => ({}))]).then(([options, h0]) => {
    health = h0 || {};
    if (destroyed) return;
    // --- play form -----------------------------------------------------------------------------
    const name = h('input', { class: 'input', value: prefs.name || 'Planeswalker', maxlength: 24 });
    const myDeck = deckSelect(options, prefs.myDeck || options[0].id);
    const oppType = h('select', { class: 'input' },
      h('option', { value: 'xmage' }, 'XMage AI (MAD)'),
      h('option', { value: 'agent' }, 'External agent (connect via SDK)'));
    oppType.value = prefs.oppType || 'xmage';
    const skill = h('input', { class: 'input', type: 'range', min: 1, max: 6, value: prefs.skill || 2 });
    const skillLabel = h('span', { class: 'muted nowrap' }, `skill ${skill.value}`);
    skill.addEventListener('input', () => { skillLabel.textContent = `skill ${skill.value}`; });
    const oppDeck = deckSelect(options, prefs.oppDeck || '__random__');
    const starting = h('select', { class: 'input' },
      h('option', { value: '-1' }, 'Random'), h('option', { value: '0' }, 'Me'), h('option', { value: '1' }, 'Opponent'));
    const fullControl = h('input', { type: 'checkbox', checked: !!prefs.fullControl });
    const skillRow = h('div', { class: 'form-row' }, h('label', {}, 'AI strength'), h('div', { class: 'inline' }, skill, skillLabel));
    const syncSkill = () => { skillRow.style.display = oppType.value === 'xmage' ? '' : 'none'; };
    oppType.addEventListener('change', syncSkill);
    syncSkill();

    const start = h('button', { class: 'btn primary big' }, 'Start game');
    start.addEventListener('click', async () => {
      start.disabled = true;
      const p = { name: name.value, myDeck: myDeck.value, oppType: oppType.value, skill: skill.value, oppDeck: oppDeck.value, fullControl: fullControl.checked };
      savePrefs(p);
      const opp = oppType.value === 'xmage'
        ? { type: 'xmage', name: `XMage AI`, skill: parseInt(skill.value, 10), deck: pickDeck(oppDeck.value, options) }
        : { type: 'agent', name: 'Agent', deck: pickDeck(oppDeck.value, options) };
      try {
        const res = await createGame({
          seats: [{ type: 'human', name: name.value || 'Human', deck: pickDeck(myDeck.value, options), stop_policy: fullControl.checked ? 'all' : 'arena' }, opp],
          starting_seat: parseInt(starting.value, 10),
          title: `${name.value} vs ${opp.name}`,
        });
        location.hash = `#/game/${res.game_id}/seat/0`;
      } catch (e) {
        toast(e.message, 'error');
        start.disabled = false;
      }
    });
    // servers started with --api-key only let key holders create games
    const keyRow = () => {
      if (!health.api_key_required) return null;
      const key = h('input', { class: 'input', type: 'password', value: apiKey(), placeholder: 'required by this server', autocomplete: 'off' });
      key.addEventListener('change', () => setApiKey(key.value.trim()));
      return h('div', { class: 'form-row' }, h('label', { title: 'The server was started with --api-key' }, 'API key'), key);
    };
    playCard.replaceChildren(h('h2', {}, 'Play'),
      keyRow(),
      h('div', { class: 'form-row' }, h('label', {}, 'Your name'), name),
      h('div', { class: 'form-row' }, h('label', {}, 'Your deck'), myDeck),
      h('div', { class: 'form-row' }, h('label', {}, 'Opponent'), oppType),
      skillRow,
      h('div', { class: 'form-row' }, h('label', {}, 'Opponent deck'), oppDeck),
      h('div', { class: 'form-row' }, h('label', {}, 'Goes first'), starting),
      h('div', { class: 'form-row' }, h('label', { title: 'Stop at every step where you could act (otherwise: main phases, combat and responses only)' }, 'Full control'), fullControl),
      h('div', { class: 'form-row' }, h('label', {}, 'Card images'),
        h('input', { type: 'checkbox', checked: settings.images, onchange: (e) => setImages(e.target.checked) })),
      start);

    // --- watch form ----------------------------------------------------------------------------
    const d1 = deckSelect(options, '__random__');
    const d2 = deckSelect(options, '__random__');
    const s1 = h('input', { class: 'input small', type: 'number', min: 1, max: 6, value: 2 });
    const s2 = h('input', { class: 'input small', type: 'number', min: 1, max: 6, value: 2 });
    const pace = h('select', { class: 'input' },
      h('option', { value: '0' }, 'Full speed'), h('option', { value: '300' }, 'Fast'),
      h('option', { value: '800', selected: true }, 'Normal'), h('option', { value: '1500' }, 'Slow'));
    const watchBtn = h('button', { class: 'btn primary' }, 'Start exhibition');
    watchBtn.addEventListener('click', async () => {
      watchBtn.disabled = true;
      try {
        const res = await createGame({
          seats: [
            { type: 'xmage', name: 'XMage AI #1', skill: parseInt(s1.value, 10), deck: pickDeck(d1.value, options) },
            { type: 'xmage', name: 'XMage AI #2', skill: parseInt(s2.value, 10), deck: pickDeck(d2.value, options) },
          ],
          pace_ms: parseInt(pace.value, 10),
          title: 'Exhibition',
        });
        location.hash = `#/watch/${res.game_id}`;
      } catch (e) {
        toast(e.message, 'error');
        watchBtn.disabled = false;
      }
    });
    watchCard.replaceChildren(h('h2', {}, 'Watch AI vs AI'),
      h('p', { class: 'muted' }, 'Two XMage AIs play each other. Agent games started from Python show up in the list and can be watched too.'),
      keyRow(),
      h('div', { class: 'form-row' }, h('label', {}, 'Deck 1'), d1),
      h('div', { class: 'form-row' }, h('label', {}, 'AI 1 skill'), s1),
      h('div', { class: 'form-row' }, h('label', {}, 'Deck 2'), d2),
      h('div', { class: 'form-row' }, h('label', {}, 'AI 2 skill'), s2),
      h('div', { class: 'form-row' }, h('label', {}, 'Pace'), pace),
      watchBtn);
  }).catch((e) => {
    // servers started with --api-key only let key holders create games
    const keyRow = () => {
      if (!health.api_key_required) return null;
      const key = h('input', { class: 'input', type: 'password', value: apiKey(), placeholder: 'required by this server', autocomplete: 'off' });
      key.addEventListener('change', () => setApiKey(key.value.trim()));
      return h('div', { class: 'form-row' }, h('label', { title: 'The server was started with --api-key' }, 'API key'), key);
    };
    playCard.replaceChildren(h('h2', {}, 'Play'),
      keyRow(), h('p', { class: 'error' }, `Can't load decks: ${e.message}`));
  });

  // --- games list ------------------------------------------------------------------------------
  const refresh = async () => {
    try {
      const games = await getGames();
      const list = gamesCard.querySelector('.games-list');
      if (!games.length) {
        list.replaceChildren(h('p', { class: 'muted' }, 'No games yet.'));
      } else {
        list.replaceChildren(h('table', { class: 'games' },
          h('tr', {}, h('th', {}, 'Game'), h('th', {}, 'Players'), h('th', {}, 'Status'), h('th', {}, 'Turn'), h('th', {})),
          games.slice(0, 30).map((g) => {
            const res = g.result || {};
            let outcome = '';
            if (g.result) {
              if (res.status && res.status !== 'finished') outcome = res.reason || res.status;
              else if (res.winner_seat !== null && res.winner_seat !== undefined) outcome = `${res.winner} won`;
              else if (res.draw) outcome = 'draw';
              else outcome = res.error || 'no result';
            }
            // protected games: only offer what this browser holds a token for
            const humanSeat = g.seats.find((s) => s.type === 'human' && (!g.protected || seatToken(g.id, s.seat)));
            const canStop = !g.protected || ownerToken(g.id) || apiKey();
            return h('tr', {},
              h('td', { class: 'mono' }, g.id),
              h('td', {}, g.seats.map((s, i) => h('span', { class: 'seat-chip' }, `${s.name}`, h('small', {}, ` ${s.type}`), i === 0 ? ' vs ' : ''))),
              h('td', {}, statusBadge(g.status), outcome ? h('small', { class: 'muted' }, ` ${outcome}`) : null),
              h('td', {}, g.turn),
              h('td', { class: 'actions' },
                g.status === 'running' && humanSeat ? h('a', { class: 'btn small', href: `#/game/${g.id}/seat/${humanSeat.seat}` }, 'Rejoin') : null,
                h('a', { class: 'btn small', href: `#/watch/${g.id}` }, g.status === 'running' ? 'Watch' : 'View'),
                g.status === 'running' && canStop ? h('button', {
                  class: 'btn small danger',
                  onclick: async () => {
                    try { await terminateGame(g.id); } catch (e) { toast(e.message, 'error'); }
                    refresh();
                  },
                }, 'Stop') : null,
                g.protected ? h('span', { class: 'muted', title: 'Protected game: seats and hands need tokens' }, ' 🔒') : null));
          })));
      }
    } catch (e) {
      gamesCard.querySelector('.games-list').replaceChildren(h('p', { class: 'error' }, `Server unreachable: ${e.message}`));
    }
  };
  refresh();
  timer = setInterval(refresh, 3000);
  return { destroy() { destroyed = true; clearInterval(timer); } };
}

// --- deck browser --------------------------------------------------------------------------------

export function renderDecks(app) {
  app.className = 'page';
  const list = h('div', { class: 'deck-grid' }, 'Loading…');
  app.append(h('h1', {}, 'Decks'),
    h('p', { class: 'muted' }, 'Preconstructed 40-card limited decks. Add your own as XMage .dck files in the decks/ directory; "sealed:SET" decks are generated from six random boosters.'),
    list);
  getDecks(true).then((decks) => {
    list.replaceChildren(...decks.map((d) => {
      const groups = {};
      for (const c of d.cards || []) {
        const t = (c.types || '').includes('CREATURE') || (c.types || '').includes('Creature') ? 'Creatures'
          : (c.types || '').toLowerCase().includes('land') ? 'Lands' : 'Spells';
        (groups[t] = groups[t] || []).push(c);
      }
      return h('section', { class: 'panel deck-card' },
        h('h3', {}, colorPips(d.colors), ' ', d.name, h('small', { class: 'muted mono' }, ` ${d.id}`)),
        h('p', { class: 'muted' }, d.description),
        ['Creatures', 'Spells', 'Lands'].filter((g) => groups[g]).map((g) => h('div', { class: 'deck-group' },
          h('h4', {}, `${g} (${groups[g].reduce((a, c) => a + c.count, 0)})`),
          groups[g].sort((a, b) => (a.mana_value || 0) - (b.mana_value || 0)).map((c) => {
            const row = h('div', { class: 'deck-line' }, h('span', { class: 'count' }, c.count), ' ', c.name, ' ', manaSymbols(c.mana_cost));
            row.addEventListener('mouseenter', () => showPreview({ name: c.name, set: c.set, number: c.number, mana_cost: c.mana_cost, rules: [] }));
            row.addEventListener('mouseleave', hidePreview);
            return row;
          }))));
    }));
  }).catch((e) => list.replaceChildren(h('p', { class: 'error' }, e.message)));
  return null;
}

// --- agent guide ------------------------------------------------------------------------------

export function renderAgents(app) {
  app.className = 'page docs';
  const origin = location.origin;
  const code = (text) => h('pre', {}, h('code', {}, text));
  app.append(
    h('h1', {}, 'Connecting agents'),
    h('p', {}, 'Agents talk to the Colosseo over a small JSON protocol on a WebSocket. Every choice the rules engine needs ',
      '(priority, targets, attackers, blockers, modes, amounts, …) arrives as a ', h('code', {}, 'decision'),
      ' message with its legal options; the agent answers with an ', h('code', {}, 'action'), '. The Python SDK wraps all of it.'),
    h('h2', {}, 'Python in 10 lines'),
    code(`pip install -e python/          # from the repository root

from colosseo import Agent, run_match

class MyAgent(Agent):
    name = "my-agent"
    def decide(self, d):
        if d.kind == "priority":
            lands = d.options_where(action="play_land")
            if lands:
                return d.choose(lands[0])
        return d.default()           # the engine's safe default for every decision

print(run_match(MyAgent, "xmage:2", games=10, server="${origin}"))`),
    h('h2', {}, 'Command line'),
    code(`python -m colosseo --server ${origin} decks
python -m colosseo --server ${origin} play --agent heuristic --opponent xmage:2
python -m colosseo --server ${origin} tournament random heuristic xmage:1 --games 6`),
    h('h2', {}, 'Play against your agent'),
    h('p', {}, 'In the lobby choose "External agent" as opponent. The game waits for your agent to connect to seat 1 ',
      '(the game page shows the exact command, including the seat token protected servers require):'),
    code(`from colosseo import play
from colosseo.agents import HeuristicAgent
play(HeuristicAgent(), game_id="<id shown in the game>", seat=1, server="${origin}", token="<seat token>")`),
    h('h2', {}, 'Access control'),
    h('p', {}, 'Creating a game returns a token per agent/human seat and an owner token. A server bound to localhost runs ',
      'in open mode (tokens optional); any other server, or a game created with ', h('code', {}, '"require_tokens": true'),
      ', requires the seat token to control a seat and the owner token to show both hands or stop the game. ',
      'Opponents and ordinary spectators never see cards picked from hidden zones nor agents’ comments.'),
    h('h2', {}, 'Raw protocol'),
    code(`POST ${origin}/api/games
{"seats": [{"type": "agent", "deck": "fdn:azorius-skies"},
           {"type": "xmage", "deck": "fdn:gruul-stompers", "skill": 2}]}

WebSocket ${origin.replace('http', 'ws')}/ws/game/<game_id>?seat=0&token=<seat token>
<- {"type": "decision", "decision_id": 7, "kind": "priority", "options": [...], "state": {...}}
-> {"type": "action", "decision_id": 7, "choice": "<option id>"}`),
    h('p', {}, 'See docs/protocol.md in the repository for every decision kind and message.'));
  return null;
}
