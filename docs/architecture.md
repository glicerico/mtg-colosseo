# Architecture

## Components

**Engine (`engine/`, Java 17+).** Embeds XMage (built from source by `scripts/build_xmage.sh`, pinned to a tested
commit) and hosts games. No XMage server/client code is used: games are created directly
(`TwoPlayerDuel` + `TwoPlayerMatch`) and each runs on its own `GAME …` thread, exactly like XMage's own tests.

- `BridgePlayer` extends XMage's `HumanPlayer`. HumanPlayer implements every dialog the rules can raise
  (targets, modes, choices, amounts, piles, replacement effects, trigger ordering, …) by firing a
  `PlayerQueryEvent` and blocking until a response arrives - which is what makes full rules coverage possible
  without re-implementing player logic. BridgePlayer overrides the interactions that are clumsy as click
  streams: **priority** (an explicit list of legal actions from `getPlayable`), **declare attackers/blockers**
  (one decision with the full assignment, validated by XMage's own combat checks) and **mana payment**
  (automatic, ported from XMage's AI, with manual payment as fallback). It also records context (the current
  `Target`, source ability) so decisions can carry min/max and source info.
- `QueryTranslator` turns each `PlayerQueryEvent` into a `Decision` (kind, prompt, options, default) with a
  responder that validates answers and feeds them back through HumanPlayer's `setResponse*` methods.
- `StateView` builds observations from the live game with hidden-information rules (own hand only, face-down
  masking, no libraries).
- `GameSession` owns a game: seats, connections, the game log (from XMage's table events), state broadcasts
  to players/spectators, timeouts, turn limits, pacing, the abandon/deadline watchdog and JSONL recording.
  Answers are validated on the WebSocket thread and applied on a per-game responder thread; the game thread
  only ever waits. Each accepted answer produces two notices: the full one for the acting seat (and
  spectators allowed to see hands) and a redacted one for everyone else - options that refer to hidden
  objects are marked private when decisions are built (`Decision.markPrivate`, `StateView.isHidden`).
- `Connection` queues outgoing messages per client and writes them from a sender thread, so a slow or dead
  client never blocks a game thread (clients that fall thousands of messages behind are disconnected).
- `AccessPolicy` decides who may control seats, reveal hands, stop and create games (see below).
- `ColosseoServer` (Javalin) serves the REST API, the WebSocket and the static web UI.

XMage's MAD AI (`ComputerPlayer7`) plays `xmage` seats inside the engine.

**Web UI (`web/`).** Plain ES modules, no build step. It renders observations and maps clicks to answers of
the pending decision; card images come from Scryfall (`/cards/{set}/{number}?format=image`), with a text
frame fallback when offline.

**Python SDK (`python/`).** Protocol wrappers, the agent loop (`play`), runners (`run_game`, `run_match`,
`round_robin` + Elo), `ColosseoEnv`, a CLI and reference agents.

## Deployment and access control

`scripts/run_server.sh` binds to `127.0.0.1` and the server runs in **open** mode: anyone who can connect
(i.e. local processes) may control agent/human seats, watch with both hands visible and stop games. That is
the convenient setup for local research and exhibitions. Browsers are still held to same-origin requests and
an open loopback server only answers to loopback host names, so web pages you visit can't drive it.

Binding to another address (`HOST=0.0.0.0`, the Docker image) switches to **tokens** mode: creating a game
returns a 128-bit token per bridge seat and an owner token; a seat needs its token, revealing hands and
stopping need the owner token (or the API key). Add `--api-key` (`COLOSSEO_API_KEY`) to restrict who can
create games, `--max-games` to cap concurrent games (default 32) and `--cors-origin` for pages served
elsewhere. Deck file paths are disabled in tokens mode (`--deck-paths on` to allow them). Tokens travel
in URLs (WebSocket query strings, invitation links), so put a TLS-terminating proxy in front of a server on
an untrusted network. `--auth open` on a public address is possible for trusted networks and logs a warning.

Benchmarks on a local open server should create games with `"require_tokens": true` (the token rules then
apply to that game), so an agent can't attach to the other seat or watch with hands revealed.

## Adding decks and sets

XMage implements almost every Magic set, so "adding a set" is only about decks:

1. **Preconstructed decks**: put an XMage `.dck` file anywhere under `decks/` (sub-directories become id
   prefixes: `decks/blb/rabbits.dck` → `blb:rabbits`). Lines look like `2 [FDN:146] Savannah Lions`; `#`
   comments `# description: …` and `# tags: …` are shown in the lobby. Plain `4 Card Name` text decks also
   load via a file path. Use `GET /api/sets/{code}/cards` to browse a set's cards and collector numbers.
2. **Sealed**: `sealed:CODE` works for any set with boosters; add the code to `FEATURED_SETS` in
   `ColosseoServer` to list it in the lobby.
3. **Constructed/other formats**: decks of any size load (the minimum is 40 cards); the turn limit and
   starting life are game options.

Mechanics are covered by XMage; if a card misbehaves it's an XMage bug (update `XMAGE_REF` in
`scripts/build_xmage.sh` to pick up fixes, then `REBUILD_XMAGE=1 scripts/build.sh` and delete `data/db` so
the card database is rebuilt).

## Performance notes

- Agent-vs-agent games with the SDK run at a few hundred to a few thousand decisions per second; a typical
  game takes 1-5 s.
- MAD AI seats think for up to `skill × 3` seconds per decision and use several threads and ~1 GB of heap
  each while simulating. Budget memory accordingly when running AI games in parallel.
- The card database (`data/db`) is built on first start (~1 minute); later starts take ~10-20 s.
