// Hash router for the single page app.
import { renderLobby, renderDecks, renderAgents } from './lobby.js';
import { GameView } from './game.js';
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
  const hash = location.hash.replace(/^#/, '') || '/';
  const parts = hash.split('/').filter(Boolean);
  document.querySelectorAll('.topbar nav a').forEach((a) => a.classList.toggle('active', a.getAttribute('href') === '#' + hash));
  if (parts[0] === 'game' && parts[1]) {
    const seat = parts[2] === 'seat' ? parseInt(parts[3] || '0', 10) : 0;
    current = new GameView(app, parts[1], seat);
  } else if (parts[0] === 'watch' && parts[1]) {
    current = new GameView(app, parts[1], -1);
  } else if (parts[0] === 'decks') {
    current = renderDecks(app);
  } else if (parts[0] === 'agents') {
    current = renderAgents(app);
  } else {
    current = renderLobby(app);
  }
}

window.addEventListener('hashchange', route);
route();
