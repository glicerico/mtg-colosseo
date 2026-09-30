// REST + WebSocket helpers for the Colosseo server (same origin).

export async function api(path, options = {}) {
  const init = { headers: { Accept: 'application/json' }, ...options };
  if (options.body && typeof options.body !== 'string') {
    init.body = JSON.stringify(options.body);
    init.headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(path, init);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new Error((data && data.error) || `${res.status} ${res.statusText}`);
  }
  return data;
}

export function wsUrl(path) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.host}${path}`;
}

export const getDecks = (full = false) => api('/api/decks' + (full ? '?full=1' : ''));
export const getDeck = (id) => api('/api/decks/' + encodeURIComponent(id));
export const getSets = () => api('/api/sets');
export const getGames = () => api('/api/games');
export const getGame = (id) => api('/api/games/' + id);
export const createGame = (config) => api('/api/games', { method: 'POST', body: config });
export const terminateGame = (id) => api(`/api/games/${id}/terminate`, { method: 'POST' });
