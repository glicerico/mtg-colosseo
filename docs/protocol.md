# Colosseo protocol

Everything a client needs to create games, play a seat or spectate. Humans (web UI) and agents use exactly the
same messages. All payloads are JSON.

## REST

| method & path | description |
|---|---|
| `GET /api/health` | `{"ok": true, "version": ..., "games": n}` |
| `GET /api/decks[?full=1]` | deck list (`full=1` adds card lists, colors, size) |
| `GET /api/decks/{id}` | one deck with its cards |
| `GET /api/sets` | sets offered for random sealed decks: `[{"code", "name", "deck": "sealed:FDN"}]` |
| `GET /api/sets/{code}/cards` | every card of a set (name, number, rarity, cost, types, P/T, rules) |
| `GET /api/cards?name=...` | card lookup by exact name |
| `GET /api/games` | game summaries, newest first |
| `POST /api/games` | create and start a game (below) |
| `GET /api/games/{id}` | summary: status, turn, seats (with `connected`, `waiting_for_decision`), result |
| `POST /api/games/{id}/terminate` | stop a running game |

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
  "title": "optional"
}
```

Seat `type`: `agent` or `human` (controlled over the WebSocket - they differ only in defaults) or `xmage`
(XMage's MAD AI, runs in the server, `skill` 1-10: search depth / think time).

`deck`: a deck id from `/api/decks`, a path to an XMage `.dck`/`.txt` deck file on the server, or
`sealed:SET` (six boosters of SET, auto-built into a 40-card two-color deck).

Options for `agent`/`human` seats:

| key | default (agent / human) | meaning |
|---|---|---|
| `auto_pass` | `true` / `true` | pass priority automatically when there is no legal non-mana action |
| `stop_policy` | `"all"` / `"arena"` | `all`: ask at every priority with legal actions. `arena`: only in your main phases, during combat and when the opponent puts something on the stack |
| `yield_after_cast` | `false` / `true` | pass automatically right after casting/activating (let it resolve) |
| `auto_pay` | `true` / `true` | pay mana costs automatically; otherwise `pay_mana` decisions are raised |
| `timeout_s` | `0` | apply the decision's default after this many seconds (0 = wait forever) |

Game options: `starting_seat` (-1 random), `max_turns` (the game is a draw after this turn), `pace_ms`
(sleep after every engine update while someone watches - for watchable AI games), `seed` (seeds XMage's RNG;
best effort), `record` (write `data/games/<id>.jsonl`).

Response (`201`):

```json
{"game_id": "3f2a91c0", "game": {...summary...},
 "seats": [{"seat": 0, "name": "me", "type": "agent", "token": "…", "ws": "/ws/game/3f2a91c0?seat=0&token=…"},
           {"seat": 1, "name": "MAD", "type": "xmage"}],
 "spectate_ws": "/ws/game/3f2a91c0?spectate=1"}
```

Tokens are only enforced when the server runs with `--require-tokens`.

## WebSocket

- play a seat: `ws://host:7070/ws/game/{id}?seat=N[&token=…]`
- spectate: `ws://host:7070/ws/game/{id}?spectate=1[&reveal=1]` (`reveal` shows both hands)

The game does not wait for clients to connect: it runs until a seat has to decide something, then waits for
that seat's answer (forever, unless `timeout_s` is set). Several connections may attach to one seat (e.g. an
agent plus a human watching its view); any of them can answer.

### Server → client

| type | payload |
|---|---|
| `hello` | `game` (summary), `seat` (-1 for spectators), `role`, `state` (latest observation, if any), `log` (recent entries) |
| `decision` | a decision to answer (below). Re-sent on connect if one is pending; ignore ids you already answered |
| `state` | `state`: new observation after the engine updated (steps, resolutions, casts) |
| `log` | `entry`: `{"i", "turn", "text"}` - the game log (plain text) |
| `action` | an answer was accepted: `seat`, `decision_id`, `kind`, `summary`, optional `comment` (agent rationale) |
| `decision_pending` | spectators only: `seat`, `decision_id`, `kind`, `prompt` |
| `info` | `message`: an engine notice for this seat (e.g. why a block was illegal) |
| `error` | `message`, optional `decision_id`: your answer was rejected - the decision stays pending |
| `game_over` | `result`: `winner_seat` (null = draw), `winner`, `turns`, `decisions`, `duration_s`, `players`, `error` |

### Client → server

| type | payload |
|---|---|
| `action` | `decision_id` + the answer fields (below) + optional `comment` |
| `concede` | - |
| `settings` | seats: `stop_policy`, `yield_after_cast`; spectators: `reveal` |
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
