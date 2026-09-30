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
    def on_event(self, message): ...     # optional: state updates, log lines, opponent actions
    def on_game_end(self, result): ...   # optional
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
action.with_comment("why")        # shown to spectators, stored in the record
```

Priority semantics: after casting a spell you get priority again (the spell is on the stack); passing lets it
resolve. With `stop_policy="all"` and `auto_pass=True` (agent defaults) you are asked at every priority where
you have a legal non-mana action. Mana is paid automatically.

## Running games

```python
from colosseo import run_game, run_match, round_robin
from colosseo.agents import RandomAgent, HeuristicAgent

run_game(MyAgent(), "xmage:2", "fdn:azorius-skies", "fdn:gruul-stompers", pace_ms=400)  # watch it in the lobby
m = run_match(MyAgent, HeuristicAgent, games=50, parallel=4)          # random deck pairings, seats alternate
print(m, m.score_a)
matches, elo = round_robin({"mine": MyAgent, "heuristic": HeuristicAgent, "mad": "xmage:1"}, games=10)
```

Pass agent *classes* (a fresh instance per game) or *instances* (shared across games - useful for learning
agents; don't use the same instance for both seats of one game).

Playing against your agent yourself: create a game with a `human` seat and an `agent` seat
(`python -m colosseo play --agent human --opponent my_module:MyAgent` prints the URL), or pick
"External agent" in the lobby and connect with `colosseo.play(MyAgent(), game_id, seat=1)`.

## Environment API

```python
from colosseo import ColosseoEnv
env = ColosseoEnv(deck="fdn:dimir-tricksters", opponent="xmage:1", opponent_deck="sealed:FDN", max_turns=40)
d = env.reset()
while d is not None:
    d, reward, done, info = env.step(policy(d))   # reward +1/-1 at the end
```

The opponent can also be an `Agent` (self-play: it runs in a background thread).

## Datasets

Every game writes `data/games/<id>.jsonl` on the server: a `config` line, then alternating `decision`
(the full message, including the observation) and `action` lines, then `result`. That is a complete
(observation, legal options, chosen action) trace for both seats - convenient for behaviour cloning from
XMage's AI or from human play.

## Other languages

The SDK is a thin layer over [the protocol](protocol.md): POST `/api/games`, open the seat WebSocket, answer
each `decision` with an `action`. A minimal client is ~30 lines in any language.
