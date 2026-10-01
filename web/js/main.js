// Hash router for the single page app.
import { renderLobby, renderDecks, renderAgents, renderLeaderboard } from './lobby.js';
import { GameView } from './game.js';
import { rememberToken } from './api.js';
import { closeAllModals } from './ui.js';
import { hidePreview } from './cards.js';

let current = null;

function route() {
  if (current && current.destroy) current.destroy();
  current = null;
  closeAllModals();
  hidePreview();
  const app = document.getElementById('app');
  app.innerHTML = '';
  app.className = '';
  const [hash, query] = (location.hash.replace(/^#/, '') || '/').split('?');
  const parts = hash.split('/').filter(Boolean);
  document.querySelectorAll('.topbar nav a').forEach((a) => a.classList.toggle('active', a.getAttribute('href') === '#' + hash));
  const game = (parts[0] === 'game' || parts[0] === 'watch') && parts[1] ? parts[1] : null;
  const seat = parts[0] === 'game' ? (parts[2] === 'seat' ? parseInt(parts[3] || '0', 10) : 0) : -1;
  const token = new URLSearchParams(query || '').get('token');
  if (game && token) {
    // invite link: keep the token for reconnects, then drop it from the address bar
    rememberToken(game, seat, token);
    history.replaceState(null, '', `#${hash}`);
  }
  if (game) {
    current = new GameView(app, game, seat);
  } else if (parts[0] === 'decks') {
    current = renderDecks(app);
  } else if (parts[0] === 'leaderboard') {
    current = renderLeaderboard(app);
  } else if (parts[0] === 'agents') {
    current = renderAgents(app);
  } else {
    current = renderLobby(app);
  }
}

window.addEventListener('hashchange', route);
route();
