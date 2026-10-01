// REST + WebSocket helpers for the Colosseo server (same origin), and the game secrets this browser holds.

export async function api(path, options = {}) {
  const { token, ...rest } = options;
  const init = { ...rest, headers: { Accept: 'application/json', ...(rest.headers || {}) } };
  if (rest.body && typeof rest.body !== 'string') {
    init.body = JSON.stringify(rest.body);
    init.headers['Content-Type'] = 'application/json';
  }
  if (token) init.headers.Authorization = `Bearer ${token}`;
  const res = await fetch(path, init);
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!res.ok) {
    const err = new Error((data && data.error) || `${res.status} ${res.statusText}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

export function wsUrl(path) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.host}${path}`;
}

// --- secrets --------------------------------------------------------------------------------------
// Creating a game returns its seat tokens and owner token. They are kept in localStorage so that a
// refresh or a later "Rejoin" keeps working; invite links carry a seat token in the URL fragment.

const SECRETS_PREFIX = 'colosseo.game.';
const API_KEY = 'colosseo.apikey';
const MAX_GAMES_KEPT = 100;

function readJson(key) {
  try { return JSON.parse(localStorage.getItem(key)) || null; } catch { return null; }
}

export function gameSecrets(gameId) {
  return readJson(SECRETS_PREFIX + gameId) || { seats: {} };
}

function writeSecrets(gameId, secrets) {
  try {
    localStorage.setItem(SECRETS_PREFIX + gameId, JSON.stringify({ ...secrets, saved: Date.now() }));
    pruneSecrets();
  } catch { /* storage full or disabled: the session still works until reload */ }
}

function pruneSecrets() {
  const entries = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k && k.startsWith(SECRETS_PREFIX)) entries.push([k, (readJson(k) || {}).saved || 0]);
  }
  entries.sort((a, b) => b[1] - a[1]).slice(MAX_GAMES_KEPT).forEach(([k]) => localStorage.removeItem(k));
}

/** Stores the secrets from a POST /api/games response. */
export function rememberCreatedGame(res) {
  const seats = {};
  for (const s of res.seats || []) if (s.token) seats[s.seat] = s.token;
  writeSecrets(res.game_id, { owner: res.owner_token, seats });
}

/** Stores a token from an invite link: a seat token (seat >= 0) or the owner token (seat < 0). */
export function rememberToken(gameId, seat, token) {
  const s = gameSecrets(gameId);
  if (seat >= 0) s.seats = { ...(s.seats || {}), [seat]: token };
  else s.owner = token;
  writeSecrets(gameId, s);
}

export function forgetToken(gameId, seat) {
  const s = gameSecrets(gameId);
  if (seat >= 0 && s.seats) delete s.seats[seat];
  else delete s.owner;
  writeSecrets(gameId, s);
}

export const seatToken = (gameId, seat) => (gameSecrets(gameId).seats || {})[seat] || null;
export const ownerToken = (gameId) => gameSecrets(gameId).owner || null;

export function apiKey() {
  try { return localStorage.getItem(API_KEY) || ''; } catch { return ''; }
}

export function setApiKey(key) {
  try { if (key) localStorage.setItem(API_KEY, key); else localStorage.removeItem(API_KEY); } catch { /* ignore */ }
}

// --- endpoints ------------------------------------------------------------------------------------

export const getHealth = () => api('/api/health');
export const getDecks = (full = false) => api('/api/decks' + (full ? '?full=1' : ''));
export const getDeck = (id) => api('/api/decks/' + encodeURIComponent(id));
export const getSets = () => api('/api/sets');
export const getGames = () => api('/api/games');
export const getGame = (id) => api('/api/games/' + id);

export async function createGame(config) {
  const res = await api('/api/games', { method: 'POST', body: config, token: apiKey() || undefined });
  rememberCreatedGame(res);
  return res;
}

export const terminateGame = (id) => api(`/api/games/${id}/terminate`, { method: 'POST', token: ownerToken(id) || apiKey() || undefined });
