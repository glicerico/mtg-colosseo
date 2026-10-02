# Writing agents (Python SDK)

```bash
pip install -e python/          # or: pip install -e "python/[llm]" for ClaudeAgent
scripts/run_server.sh           # in another terminal (or colosseo.server.launch() from Python)
```

## The interface

```python
from colosseo import Agent, Decision, Action

class MyAgent(Agent):
    name = "my-agent"                    # shown in the UI and game records
    seat_options = {"stop_policy": "all"}  # optional seat settings (see protocol.md)

    def on_game_start(self, hello): ...  # optional
    def decide(self, d: Decision) -> Action: ...
    def on_event(self, message): ...     # optional: state updates, log lines, (redacted) opponent actions
    def on_game_end(self, result): ...   # optional: result["status"], ["winner_seat"], ["draw"], ...
```

`decide` is called for every decision of your seat. Everything you need is on the `Decision`:

| attribute | |
|---|---|
| `d.kind` | `priority`, `mulligan`, `yes_no`, `target`, `choose_mode`, `choose_ability`, `choose_choice`, `trigger_order`, `amount`, `multi_amount`, `choose_pile`, `pay_mana`, `declare_attackers`, `declare_blockers` |
| `d.prompt` | the engine's question as text |
| `d.options` | legal options (`o.id`, `o.label`, plus fields like `o.action`, `o.source_id`, `o.controller`) |
| `d["attackers"]`, `d["blockers"]`, `d.min`, `d.max`, … | kind-specific fields (see protocol.md) |
| `d.state` | the observation: `d.state.me`, `d.state.opponent`, `.hand`, `.creatures()`, `.lands()`, `d.state.find(id)`, `d.state["stack"]`, `d.state["combat"]` |
| `d.log` | game-log lines since your previous decision |
| `d.rejection` | set if the engine rejected your previous answer to this decision |

Answers:

```python
d.choose(option_or_id)            # most kinds
d.choose_many([id1, id2])         # several targets at once
d.pass_priority()
d.attack(["id", {"attacker": "id", "defender": "planeswalker id"}])
d.block([("blocker id", "attacker id")])
d.amount(3); d.amounts([2, 1])
d.default()                        # always legal
action.with_comment("why")        # private rationale: shown to you, revealing spectators and the record
```

Priority semantics: after casting a spell you get priority again (the spell is on the stack); passing lets it
resolve. With `stop_policy="all"` and `auto_pass=True` (agent defaults) you are asked at every priority where
you have a legal non-mana action. Mana is paid automatically.

## Running games

```python
from colosseo import run_game, run_match, round_robin
from colosseo.agents import RandomAgent, HeuristicAgent

run_game(MyAgent(), "xmage:2", "fdn:azorius-skies", "fdn:gruul-stompers", pace_ms=400)  # watch it in the lobby
m = run_match(MyAgent, HeuristicAgent, games=50, parallel=4, seed=7)  # random deck pairings, balanced starts
print(m, m.score_a, m.seed)
matches, elo = round_robin({"mine": MyAgent, "heuristic": HeuristicAgent, "mad": "xmage:1"}, games=10, seed=7)
```

Pass agent *classes* (a fresh instance per game) or *instances* (shared across games - useful for learning
agents; don't use the same instance for both seats of one game).

### Scoring and failures

Only games the rules engine finished are scored. `colosseo.outcome(result)` returns `"win"`, `"draw"` or
`"unfinished"`; a draw needs an explicit `draw: true` from a finished game (turn limit, both players losing).
If an agent's connection drops (while waiting or while sending an answer), `play()` reconnects for up to
`reconnect_timeout` seconds (60 by default; a later outage gets a fresh window only if the game visibly
moved on in between - a new decision, an accepted action or a new log entry, not a replay of the pending
decision) and re-sends an answer that may have been lost; a game that ended meanwhile is reported once,
with its real result. If the game can't be recovered the result has `status: "interrupted"`. As soon as
one seat ends unfinished (or its worker crashes), `run_game` checks the server: a game that finished after
all - even one that ends just as the stop request arrives - keeps its real result, otherwise the server game
is stopped right away so the other seat isn't left waiting (if the stop can't be confirmed, the result says
`server_status: "stop_requested"`). `run_match` counts unfinished games in `errors` - never as draws, and they never change Elo.
Server-side, games whose agent/human seat stays disconnected for `abandon_timeout_s` (600 s by default) are
stopped with `status: "abandoned"`; `deadline_s` bounds a game's wall-clock time.

### Schedules and reproducibility

`run_match` plans every game before playing (`colosseo.match_schedule`): deck pairings, seats, who starts and
an engine seed per game, all derived from `seed` (generated and stored in `MatchResult.seed` when you don't
pass one). The plan is identical for any `parallel` value and is stored in `MatchResult.schedule`; each game
result carries its `index`, `seed`, `starting_seat`, `a_seat`, `deck_a` and `deck_b`. Games come in pairs
with the same decks and the starting player reversed, so use an even number of games; `swap_decks=True`
makes blocks of four where the players also swap decks. Match games are created with
`require_tokens=True`, so agents can't interfere with each other's seats even on a `--auth open` server.

A game's seed reproduces it: shuffles, the starting player, the order options are listed in, and with
deterministic agents every decision - also when other games run at the same time (XMage is patched to give
each game its own random generator, see `scripts/xmage-patches`). Not reproducible: XMage's own AI (its search
is time-bounded and multi-threaded) and agents that are random or time-dependent themselves. Object ids differ
between replays; compare games by names and positions, not ids.

### Agent identity, strictness and ratings

Set `name` and `version` on your agent class: records and ratings key on `agent_id` (`name@version`), and
each record also stores a hash of the agent's source file (`agent_hash`), so results of different versions
never mix. `run_match` is strict by default (`on_error="forfeit"`): an exception in `decide` concedes that
game. Defaults the SDK or the engine substitute for an agent's own answer are marked as fallbacks
(`"agent_error"`, `"rejected"`, `"timeout"`, `"illegal_repeat"`), counted per seat in each result
(`players[i]["fallbacks"]`) and in `MatchResult.fallbacks_a/_b`. Pass `rated=True` (or `colosseo match --rated`)
to put the games on the server's leaderboard.

Per-game limits (game options): `max_decisions` (default 10000) and `max_record_mb` (100) stop runaway
games as void (`status: "limit"`); `turn_limit_result="void"` makes reaching `max_turns` void instead of a
draw; a seat's `time_bank_s` (in `seat_options`) is a chess clock - running out forfeits the game. Strict-mode
concessions survive reconnects like answers do: a replayed decision gets the concession again, the agent is
never asked twice. Rated results are kept in a ratings ledger, so the leaderboard survives restarts even for
games played with `record=False`.

When the engine refuses a whole declaration (e.g. a menace attacker blocked by a single creature), it asks again
with `d.rejection` explaining why; `declare_blockers` decisions list each attacker's `min_blockers`, and every
object in the observation carries its engine-derived `keywords` (`"flying"`, `"menace"`, `"ward {2}"`, ...).
Repeating the same illegal declaration three times makes the engine declare no blocks (a fallback).

Playing against your agent yourself: create a game with a `human` seat and an `agent` seat
(`python -m colosseo play --agent human --opponent my_module:MyAgent` prints the invitation URL), or pick
"External agent" in the lobby and connect with `colosseo.play(MyAgent(), game_id, seat=1, token=...)` (the
game page shows the command with the seat token), or from a shell / container:
`python -m colosseo agent <game_id> --seat 1 --token <seat token> --agent my_module:MyAgent`.

### Servers that require tokens

`run_game`/`run_match` handle tokens for you (they create the games). When you connect to a game someone
else created, you need that seat's token: `play(agent, game_id, seat, server, token="…")`. Watching with both
hands visible needs the game's owner token: `client.spectate(game_id, reveal=True, token=owner_token)`. If the
server was started with `--api-key`, pass `ColosseoClient(server, api_key="…")` / `client=` to the runners, or
set `COLOSSEO_API_KEY`.

## Environment API

```python
from colosseo import ColosseoEnv
env = ColosseoEnv(deck="fdn:dimir-tricksters", opponent="xmage:1", opponent_deck="sealed:FDN", max_turns=40)
d = env.reset()
while d is not None:
    d, reward, done, info = env.step(policy(d))   # reward +1/-1 at the end, 0 for draws and unfinished games
```

`info["outcome"]` at the end tells a real draw from an interrupted episode (`"unfinished"`).

The opponent can also be an `Agent` (self-play: it runs in a background thread).

## Datasets

Every game writes `data/games/<id>.jsonl` on the server: a `config` line (with the engine, XMage, deck and
agent versions), then alternating `decision` (the full message, including the observation) and `action`
lines (plus `fallback` lines), then `result`; every action line precedes the decision it leads to. That is a complete (observation, legal options, chosen action)
trace for both seats - convenient for behaviour cloning from XMage's AI or from human play.

A full record contains both players' hidden information. To give an agent its own games, export one seat's
view: `client.record(game_id, seat=0, token=seat_token)` or `python -m colosseo record <game_id> --seat 0 --token
...` - its decisions and actions, the opponent's actions as public notices, the opponent's deck only as a hash.

## Other languages

The SDK is a thin layer over [the protocol](protocol.md): POST `/api/games`, open the seat WebSocket, answer
each `decision` with an `action`. A minimal client is ~30 lines in any language.
