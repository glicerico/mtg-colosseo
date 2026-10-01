# Colosseo protocol

Everything a client needs to create games, play a seat or spectate. Humans (web UI) and agents use exactly the
same messages. All payloads are JSON.

## REST

| method & path | description |
|---|---|
| `GET /api/health` | `{"ok", "version", "games", "running", "max_games", "auth": "open"\|"tokens", "api_key_required", "deck_paths"}` |
| `GET /api/decks[?full=1]` | deck list (`full=1` adds card lists, colors, size) |
| `GET /api/decks/{id}` | one deck with its cards |
| `GET /api/sets` | sets offered for random sealed decks: `[{"code", "name", "deck": "sealed:FDN"}]` |
| `GET /api/sets/{code}/cards` | every card of a set (name, number, rarity, cost, types, P/T, rules) |
| `GET /api/cards?name=...` | card lookup by exact name |
| `GET /api/games` | game summaries, newest first |
| `POST /api/games` | create and start a game (below); needs the API key if the server has one |
| `GET /api/games/{id}` | summary: status, turn, seats (with `connected`, `waiting_for_decision`), `protected`, result |
| `POST /api/games/{id}/terminate` | stop a running game; needs the owner token on protected games |

Credentials (seat token, owner token, API key) are sent as `Authorization: Bearer <secret>`, an
`X-Colosseo-Token` header or a `token` query parameter. Browsers must send `POST`s from the server's own
origin (or one allowed with `--cors-origin`); `POST /api/games` requires `Content-Type: application/json`.
Status codes: `401` API key missing/wrong, `403` token missing/wrong or cross-site request, `404` unknown
game, `415` not JSON, `429` too many running games (`--max-games`).

### Creating a game

```json
{
  "seats": [
    {"name": "me",  "type": "agent", "deck": "fdn:azorius-skies"},
    {"name": "MAD", "type": "xmage", "deck": "sealed:FDN", "skill": 3}
  ],
  "starting_seat": -1,
  "max_turns": 60,
  "pace_ms": 0,
  "seed": 42,
  "record": true,
  "title": "optional",
  "require_tokens": false,
  "public_comments": false,
  "abandon_timeout_s": 600,
  "deadline_s": 0
}
```

Seat `type`: `agent` or `human` (controlled over the WebSocket - they differ only in defaults) or `xmage`
(XMage's MAD AI, runs in the server, `skill` 1-10: search depth / think time).

`deck`: a deck id from `/api/decks`, a path to an XMage `.dck`/`.txt` deck file on the server (only when
the server allows deck paths: by default in open mode), or `sealed:SET` (six boosters of SET, auto-built into
a 40-card two-color deck).

Options for `agent`/`human` seats:

| key | default (agent / human) | meaning |
|---|---|---|
| `auto_pass` | `true` / `true` | pass priority automatically when there is no legal non-mana action |
| `stop_policy` | `"all"` / `"arena"` | `all`: ask at every priority with legal actions. `arena`: only in your main phases, during combat and when the opponent puts something on the stack |
| `yield_after_cast` | `false` / `true` | pass automatically right after casting/activating (let it resolve) |
| `auto_pay` | `true` / `true` | pay mana costs automatically; otherwise `pay_mana` decisions are raised |
| `timeout_s` | `0` | apply the decision's default after this many seconds (0 = wait forever) |

Game options:

| key | default | meaning |
|---|---|---|
| `starting_seat` | `-1` | seat that takes the first turn (-1: random, drawn from the game's seed) |
| `max_turns` | `60` | the game is a draw after this turn |
| `pace_ms` | `0` | sleep after every engine update while someone watches (watchable AI games) |
| `seed` | random | seeds XMage's RNG; when absent the server picks one. Both are recorded in the config record and the result (best effort, see below) |
| `record` | `true` | write `data/games/<id>.jsonl` |
| `require_tokens` | `false` | protect this game with tokens even on an open server (see Access control) |
| `public_comments` | `false` | send agents' `comment`s to the opponent and every spectator |
| `abandon_timeout_s` | server's `--abandon-timeout` (600) | stop the game (`status: "abandoned"`) when an agent/human seat has had no connection for this long; 0 = never |
| `deadline_s` | `0` | stop the game (`status: "timeout"`) after this many seconds; 0 = no deadline |

Response (`201`) - the only place the game's secrets are returned:

```json
{"game_id": "3f2a91c0d4e5", "game": {...summary...},
 "owner_token": "…",
 "seats": [{"seat": 0, "name": "me", "type": "agent", "token": "…",
            "ws": "/ws/game/3f2a91c0d4e5?seat=0&token=…", "url": "/#/game/3f2a91c0d4e5/seat/0?token=…"},
           {"seat": 1, "name": "MAD", "type": "xmage"}],
 "spectate_ws": "/ws/game/3f2a91c0d4e5?spectate=1",
 "reveal_ws": "/ws/game/3f2a91c0d4e5?spectate=1&reveal=1&token=…",
 "watch_url": "/#/watch/3f2a91c0d4e5", "owner_url": "/#/watch/3f2a91c0d4e5?token=…"}
```

`url` is an invitation link for the web client (a human opening it gets the seat); `owner_url` lets the
creator watch with both hands visible and stop the game. Keep both private.

### Access control

| server mode | who may control a seat | who may reveal hands / stop the game |
|---|---|---|
| `open` (default when bound to `127.0.0.1`/`localhost`) | anyone who can connect | anyone who can connect |
| `tokens` (default for any other address, or `--auth tokens`) | holders of that seat's `token` | holders of the `owner_token` or the API key |

A game created with `"require_tokens": true` follows the `tokens` rules on an open server (useful for
benchmarks: an agent can neither take over nor peek at the other seat). Independently, a server started with
`--api-key KEY` (or `COLOSSEO_API_KEY`) only lets key holders create games. An open server bound to
loopback rejects requests whose `Host` is not a loopback name (DNS rebinding); every server rejects
cross-origin browser requests and WebSocket connections unless allowed with `--cors-origin`.

### Hidden information

- Observations (`state`) show your own hand, never the opponent's hand or any library, and mask face-down
  objects you don't control.
- Opponents and ordinary spectators receive a redacted `action` notice: options that refer to hidden cards
  (the card put on the bottom after a mulligan, a card searched for, discarded from hand, or cast from hand
  while the attempt could still fail) are replaced by labels such as `"a hidden card"` or
  `"Cast a spell from hand"`; what actually becomes public shows up in the next `state` and the game log.
- `comment`s are private: only the acting seat and spectators allowed to reveal hands see them (unless the
  game sets `public_comments`). Agents should not put anything in a comment they would not show the
  creator of the game.
- `decision_pending` notices to ordinary spectators carry a generic prompt (`"is choosing"`), never the
  decision's own prompt.
- The final state sent with `game_over` keeps the same rules.

## WebSocket

- play a seat: `ws://host:7070/ws/game/{id}?seat=N[&token=SEAT_TOKEN]`
- spectate: `ws://host:7070/ws/game/{id}?spectate=1[&reveal=1&token=OWNER_TOKEN]` (`reveal` shows both
  hands and the agents' comments; on protected games it needs the owner token, otherwise the spectator
  gets the normal view and an `error`)

Refused connections are closed with code `4001` (missing or wrong token), `4003` (seat not controllable,
or a forbidden origin) or `4004` (unknown game), after an `error` message. Don't retry them.

The game does not wait for clients to connect: it runs until a seat has to decide something, then waits for
that seat's answer (forever, unless `timeout_s` is set). Several connections may attach to one seat (e.g. an
agent plus a human watching its view); any of them can answer.

### Server → client

| type | payload |
|---|---|
| `hello` | `game` (summary), `seat` (-1 for spectators), `role`, `state` (latest observation, if any), `log` (recent entries); spectators also get `reveal` and `may_reveal` |
| `decision` | a decision to answer (below). Re-sent on connect if one is pending; ignore ids you already answered |
| `state` | `state`: new observation after the engine updated (steps, resolutions, casts) |
| `log` | `entry`: `{"i", "turn", "text"}` - the game log (plain text) |
| `action` | an answer was accepted: `seat`, `decision_id`, `kind`, `summary`, optional `comment` (agent rationale). Redacted for opponents and ordinary spectators (see Hidden information) |
| `decision_pending` | spectators only: `seat`, `decision_id`, `kind`, `prompt` (generic unless the spectator may reveal hands) |
| `info` | `message`: an engine notice for this seat (e.g. why a block was illegal) |
| `error` | `message`, optional `decision_id` and `code`: `"invalid"` = your answer was rejected and the decision stays pending (answer again); `"stale"` = the answer was for a decision that is no longer pending (e.g. a resend after reconnecting; ignore it) |
| `game_over` | `result` (below) |

`result`: `status` (`"finished"` when the rules engine ended the game; `"terminated"`, `"abandoned"`,
`"timeout"` or `"error"` otherwise), `winner_seat`, `winner`, `draw` (true only for a finished game without a
winner, e.g. the turn limit - `reason: "turn_limit"`), `reason`/`error` for unfinished games, `turns`,
`decisions`, `duration_s`, `seed`, `starting_seat`, `players`. Score only `finished` games: an unfinished game
has no winner and is not a draw.

### Client → server

| type | payload |
|---|---|
| `action` | `decision_id` + the answer fields (below) + optional `comment` |
| `concede` | - |
| `settings` | seats: `stop_policy`, `yield_after_cast`; spectators: `reveal` (allowed only for spectators who may reveal; a `token` field can carry the owner token) |
| `ping` | answered with `pong` |

## Decisions

```json
{
  "type": "decision", "game_id": "3f2a91c0", "seat": 0, "decision_id": 57,
  "kind": "priority",
  "prompt": "Play spells and abilities",
  "options": [
    {"id": "pass", "label": "Pass priority", "action": "pass"},
    {"id": "pass_turn", "label": "Pass until end of turn", "action": "pass_turn"},
    {"id": "9b1c…", "label": "Cast Serra Angel", "action": "cast", "source_id": "4e0f…",
     "source_name": "Serra Angel", "zone": "HAND", "mana_cost": "{3}{W}{W}"}
  ],
  "default": {"choice": "pass"},
  "state": {...},
  "log": [{"i": 120, "turn": 7, "text": "XMage AI casts Shivan Dragon"}]
}
```

`default` is always a legal answer (pass, keep, "no", no attack/blocks, the first legal target, minimum
amount, engine default distribution, cancel payment). `log` holds the log lines since this seat's previous
decision.

| kind | extra fields | answer |
|---|---|---|
| `priority` | options have `action` (`cast`, `play_land`, `activate`, `special`, `pass`, `pass_turn`), `source_id`, `source_name`, `zone`, `mana_cost` | `{"choice": id}` |
| `mulligan` | `hand_size` | `{"choice": "keep" \| "mulligan"}` |
| `yes_no` | `source_id` | `{"choice": "yes" \| "no"}` (labels may say e.g. "Pay") |
| `target` | `min`, `max`, `required`, `chosen`, `zone`, `source_id`, `source_name`; options describe objects (`kind`: player/permanent/card/spell, `name`, `controller`, P/T...); `cards` (full card objects when choosing from hidden zones: library searches, scry, opponent's hand) | `{"choice": id}` one target at a time (`selected` options toggle off; `"done"` finishes when allowed) or `{"choices": [id, …]}` to pick several and finish |
| `choose_mode` | - | `{"choice": mode id}` |
| `choose_ability` | - | `{"choice": ability id \| "cancel"}` |
| `choose_choice` | `required`, `searchable` (long lists, e.g. card names) | `{"choice": key}` (`"__cancel__"` if not required) |
| `trigger_order` | - | `{"choice": ability id}` - put first on the stack |
| `amount` | `min`, `max`; for X costs `announce_x` and `max_affordable` | `{"amount": n}` |
| `multi_amount` | `items` (`label`, `min`, `max`, `default`), `total_min`, `total_max`, `can_cancel` | `{"amounts": [n, …]}` |
| `choose_pile` | `pile1`, `pile2` (cards) | `{"choice": "pile1" \| "pile2"}` |
| `pay_mana` | options: mana sources (`kind: source`), `pool:W`… (`kind: pool`), `special`, `cancel` | `{"choice": id}` (only when `auto_pay` is off or automatic payment failed) |
| `declare_attackers` | `attackers` (each with `defenders` it may attack, `must_attack`), `defenders`, `error` | `{"attackers": [{"attacker": id, "defender": id?}]}` (empty list = no attack; `defender` defaults to the opposing player) |
| `declare_blockers` | `blockers` (each with the `attackers` it can block), `attackers`, `error` | `{"blocks": [{"blocker": id, "attacker": id}]}` (empty = no blocks) |

Answers are validated against the options; an invalid answer produces an `error` and the decision stays
pending. Combat declarations that break rules the options can't express (e.g. "can't block alone",
menace) are re-asked with `error` set.

## Observation (`state`)

```json
{
  "turn": 7, "phase": "PRECOMBAT_MAIN", "step": "PRECOMBAT_MAIN",
  "active_player": "…", "priority_player": "…", "you": "…",
  "players": [{
    "id": "…", "name": "me", "seat": 0, "life": 17, "library_count": 26, "hand_count": 4,
    "lands_played": 1, "is_active": true, "has_priority": true, "has_lost": false, "has_won": false,
    "counters": {"poison": 0}, "mana_pool": {"W": 0, "U": 0, "B": 0, "R": 0, "G": 0, "C": 0},
    "hand": [card…],                 // only for yourself (and omniscient spectators)
    "graveyard": [card…],
    "battlefield": [permanent…]
  }, …],
  "stack": [{"id", "kind": "spell"|"ability", "name", "controller", "targets": [id…], "rules", …}],   // top first
  "combat": {"attacking_player": "…", "groups": [{"attackers": [id], "blockers": [id], "defender": id, "blocked": false}]},
  "exile": [card + "owner", "exile_zone"], "revealed": [{"name", "cards"}], "looked_at": [{"name", "cards"}],
  "command": [{"id", "name", "controller"}]
}
```

Cards: `id`, `name`, `set`, `number`, `mana_cost` ("{2}{W}"), `mana_value`, `types`, `subtypes`,
`supertypes`, `colors` ("WU"), `rules` (plain text, current - including gained abilities), `power`/`toughness`
(creatures, current values), `loyalty`, `counters`, `owner`, `token`. Permanents add `controller`, `tapped`,
`summoning_sick`, `damage`, `attacking`, `blocking`, `attached_to`, `attachments`, `face_down`, `transformed`.
Face-down objects you don't control appear as `{"id", "name": "Face-down creature", "hidden": true, …}`.
