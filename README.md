# MTG Colosseo

A research platform where **agents and humans play Magic: The Gathering** against each other.

- **Real rules**: games run on [XMage](https://github.com/magefree/mage)'s rules engine (tens of thousands of
  implemented cards, full stack/priority/combat/replacement-effect handling). Colosseo embeds it headlessly.
- **One protocol for everyone**: every choice the engine needs - priority, targets, attackers, blockers, modes,
  X values, damage assignment, mulligans, ... - becomes a JSON *decision* with its legal options. Humans answer
  through the web UI, agents through a WebSocket (a Python SDK is included).
- **Plug-in agents**: write `decide(decision) -> action` in Python (or speak the protocol from any language),
  then play against XMage's AI, other agents or yourself. Matches, round-robin tournaments with Elo, a
  step/reset environment and JSONL game records for training data come built in.
- **Arena-style web client**: click to play cards, click to attack/block, targets and dialogs as popups,
  card images from Scryfall, spectator mode (with both hands and the agents' one-line rationales for the
  game's creator).
- **Fair by construction**: observations and opponent notices never reveal hidden cards, agents' comments stay
  private, seats and revealing views are protected by per-game tokens when the server is exposed, and
  interrupted games are never scored.
- **Limited to start**: eight 40-card two-color decks from *Foundations* (FDN) plus randomly generated sealed
  decks (FDN and Bloomburrow). Any XMage set works - adding a deck is dropping an XMage `.dck` file in `decks/`.

```
            ┌──────────────── Colosseo server (Java, engine/) ─────────────────┐
 browser ──►│ static web UI  ─┐                                                 │
 (human)    │                 │   REST  /api/...          XMage rules engine     │
            │ WebSocket /ws/game/{id}?seat=N ─► GameSession ─► BridgePlayer ──┐  │
 agent  ──► │  (decisions ⇄ actions)          (game thread)   (per seat)      │  │
 (any lang) │                                                 XMage MAD AI ◄──┘  │
            └──────────────────────────────────────────────────────────────────┘
```

## Quick start

Requirements: JDK 17+ (21 recommended), Maven 3.9, git, Python 3.9+. About 3 GB of disk.

```bash
scripts/build.sh          # builds XMage from source into ~/.m2 (first time: ~5 min), then the engine
scripts/run_server.sh     # http://localhost:7070  (first start builds the card database: ~1 min)
```

Open http://localhost:7070, pick a deck and play against XMage's AI, or start an AI-vs-AI exhibition and watch.

The server listens on `127.0.0.1` in *open* mode (no tokens needed - for local use). To let other machines
connect, bind to all interfaces: the server then requires the per-game tokens returned when a game is created
(the lobby keeps them; invitation links carry them), and `COLOSSEO_API_KEY` restricts who may create games:

```bash
HOST=0.0.0.0 COLOSSEO_API_KEY=change-me scripts/run_server.sh
```

Or with Docker (token mode, since the container listens on all interfaces):

```bash
docker build -t mtg-colosseo .
docker run -p 127.0.0.1:7070:7070 -e COLOSSEO_API_KEY=change-me -v colosseo-data:/app/data mtg-colosseo
```

See [docs/architecture.md](docs/architecture.md#deployment-and-access-control) for the access-control model.

## Writing an agent

```bash
pip install -e python/            # add ".[llm]" for the Claude agent
```

```python
from colosseo import Agent, run_match

class MyAgent(Agent):
    name = "my-agent"

    def decide(self, d):
        if d.kind == "priority":
            lands = d.options_where(action="play_land")
            if lands:
                return d.choose(lands[0])
            spells = d.options_where(action="cast")
            if spells:
                return d.choose(max(spells, key=lambda o: d.state.find(o.source_id).mana_value or 0))
        if d.kind == "declare_attackers":
            return d.attack([a["id"] for a in d["attackers"]])   # all in
        return d.default()                                       # safe default for anything else

print(run_match(MyAgent, "xmage:2", games=10, seed=1))          # vs XMage's AI, random deck pairings
```

A `Decision` carries `kind`, `prompt`, `options` (each with an `id` and `label`), the observation `state`
(board, your hand, graveyards, stack, combat, life totals - opponents' hands and libraries stay hidden)
and new game-log lines. Answers are built with `d.choose(...)`, `d.choose_many(...)`, `d.attack(...)`,
`d.block(...)`, `d.amount(...)`, `d.amounts(...)` or `d.default()`. Illegal answers are rejected and the
agent is asked again.

Included agents (`colosseo.agents`): `RandomAgent`, `HeuristicAgent` (a readable rule-based baseline) and
`ClaudeAgent` (asks Claude for each decision via structured outputs; needs `ANTHROPIC_API_KEY` or an
`ant auth login` profile). Built-in opponents: `"xmage"` / `"xmage:N"` (XMage's MAD simulation AI, skill 1-10)
and `"human"` (a seat played in the browser).

```bash
python -m colosseo decks
python -m colosseo play --agent heuristic --opponent xmage:2 --pace-ms 500   # prints a URL to watch it
python -m colosseo match heuristic random --games 20 --parallel 4
python -m colosseo tournament random heuristic xmage:1 --games 10            # round robin + Elo
python -m colosseo play --agent human --opponent my_pkg.agents:MyAgent       # play against your agent
```

Reinforcement-learning style loop:

```python
from colosseo import ColosseoEnv
env = ColosseoEnv(deck="fdn:azorius-skies", opponent="xmage:1", opponent_deck="fdn:rakdos-raiders")
decision = env.reset()
while decision is not None:
    decision, reward, done, info = env.step(my_policy(decision))
```

Baseline ladder from small runs (random FDN deck pairings): `HeuristicAgent` beats `RandomAgent` roughly
7-3, and XMage's MAD AI at skill 1 wins about 80-90% of games against `HeuristicAgent` and essentially all
against `RandomAgent` - plenty of headroom for research agents.

More: [docs/agents.md](docs/agents.md) (SDK guide), [docs/protocol.md](docs/protocol.md) (wire protocol for
other languages), [docs/architecture.md](docs/architecture.md) (engine internals, adding sets and decks).

## Repository layout

| path | contents |
|------|----------|
| `engine/` | Java server embedding XMage: `BridgePlayer` (decisions), `QueryTranslator`, `StateView` (observations), `GameSession`, REST/WebSocket (`ColosseoServer`) |
| `web/` | browser client (plain ES modules, no build step) |
| `python/` | `colosseo` SDK, reference agents, CLI, tests |
| `decks/` | preconstructed decks (XMage `.dck` format) |
| `scripts/` | build XMage, build engine, run server |
| `data/` | runtime: card database and `games/*.jsonl` records (git-ignored) |

## Research notes

- **Game records**: every game writes `data/games/<id>.jsonl` with the config, each decision (including the
  full observation), each answer and the result - ready for imitation learning. Disable with `"record": false`.
- **Decision granularity**: priority is only asked when the seat has a legal non-mana action (`auto_pass`,
  default on); mana is paid automatically (`auto_pay`). Seats can opt into asking at every priority
  (`"auto_pass": false`) and manual payment (`"auto_pay": false`).
- **Timeouts**: `timeout_s` on a seat applies the decision's default action when an agent is too slow;
  `deadline_s` bounds a whole game and `abandon_timeout_s` stops games whose agent disconnected.
- **Scoring**: only games the engine finished count (`colosseo.outcome`); interrupted, stopped or crashed
  games are reported as errors, never as draws, and don't change Elo.
- **Determinism**: matches follow a schedule computed from their seed (deck pairings, seats, alternating
  starting player, one engine seed per game), identical with any parallelism and stored with the results.
  Gameplay is best-effort reproducible: XMage's RNG is shared by concurrent games and its AI is time-bounded.
- **Resources**: MAD AI games are CPU and memory hungry - give the JVM ~1 GB per concurrently running AI
  game (`JAVA_OPTS=-Xmx8g scripts/run_server.sh`).

## License

GPL-3.0, like XMage. Card names and images are property of Wizards of the Coast; images are loaded by the
browser directly from Scryfall.
