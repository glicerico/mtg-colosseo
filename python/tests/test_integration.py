"""End-to-end tests against a running server (skipped when none is reachable)."""
import pytest

from colosseo import ColosseoClient, ColosseoEnv, run_game, run_match
from colosseo.agents import HeuristicAgent, RandomAgent

from .conftest import DIALOG_DECK, SERVER, server_up

pytestmark = [pytest.mark.integration,
              pytest.mark.skipif(not server_up(), reason=f"no Colosseo server at {SERVER}")]


def test_decks_listed():
    decks = ColosseoClient(SERVER).decks()
    assert any(d["id"].startswith("fdn:") for d in decks)


def test_agent_vs_agent_game():
    result = run_game(RandomAgent(seed=1), HeuristicAgent(), "fdn:selesnya-pack", "fdn:rakdos-raiders",
                      server=SERVER, max_turns=30, record=False)
    assert result["turns"] >= 1
    assert not result.get("error")


def test_dialog_heavy_deck_survives_random_play():
    match = run_match(RandomAgent, RandomAgent, games=2, decks=DIALOG_DECK, server=SERVER, max_turns=20,
                      record=False, parallel=2)
    assert match.errors == 0 and match.played == 2


def test_env_loop_against_xmage():
    env = ColosseoEnv("fdn:gruul-stompers", opponent="xmage:1", opponent_deck="fdn:azorius-skies",
                      server=SERVER, max_turns=12, record=False)
    agent = HeuristicAgent()
    d = env.reset()
    steps, done, reward = 0, False, 0.0
    while d is not None and steps < 2000:
        d, reward, done, info = env.step(agent.decide(d))
        steps += 1
    assert done and reward in (-1.0, 0.0, 1.0)
    env.close()


def test_illegal_action_is_rejected():
    env = ColosseoEnv("fdn:gruul-stompers", opponent="xmage:1", server=SERVER, max_turns=5, record=False)
    d = env.reset()
    from colosseo import Action
    d2, _, _, info = env.step(Action({"choice": "not-an-option"}))
    assert info.get("rejected") and d2.id == d.id
    env.close()


class _Recorder(HeuristicAgent):
    """Plays like the heuristic and keeps every decision's battlefield."""

    def __init__(self):
        super().__init__()
        self.battlefields = []

    def decide(self, d):
        self.battlefields.append([c for p in (d.raw.get("state") or {}).get("players", []) for c in p.get("battlefield", [])])
        return super().decide(d)


def test_explanations_reach_browser_seats_only():
    """`explain` (printed vs current power/toughness, effects) is for the web UI: a seat of type human gets it,
    an agent's observation never does."""
    import threading
    from colosseo import play
    client = ColosseoClient(SERVER)
    game = client.create_game([{"type": "human", "deck": "fdn:gruul-stompers", "stop_policy": "all"},
                               {"type": "agent", "deck": "fdn:selesnya-pack"}],
                              seed=7, max_turns=8, record=False)
    tokens = {s["seat"]: s.get("token") for s in game["seats"]}
    human, agent = _Recorder(), _Recorder()
    threads = [threading.Thread(target=play, args=(a, game["game_id"], seat, SERVER), kwargs={"token": tokens[seat]})
               for seat, a in ((0, human), (1, agent))]
    for t in threads:
        t.start()
    for t in threads:
        t.join(300)
    human_creatures = [c for bf in human.battlefields for c in bf if "Creature" in (c.get("types") or [])]
    assert human_creatures, "no creature reached the battlefield"
    assert all("explain" in c and c["explain"]["printed"] for c in human_creatures if not c.get("hidden"))
    assert not any("explain" in c for bf in agent.battlefields for c in bf)


def _trace(game_id, tokens):
    """Plays both seats with the heuristic; returns each seat's decisions (prompt, options, answer, the log lines
    since its previous decision), with object ids replaced by their labels, and the game's result."""
    import re
    import threading
    from colosseo import play
    uuid = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    short = re.compile(r"\[[0-9a-f]{3}\]")  # log lines name objects by an id prefix, which differs between games

    class Tracer(HeuristicAgent):
        def __init__(self):
            super().__init__()
            self.trace = []
            self.explained = 0

        def decide(self, d):
            action = super().decide(d)
            st = d.raw.get("state") or {}
            self.explained += sum("explain" in c for p in st.get("players", []) for c in p.get("battlefield", []))
            raw = {k: v for k, v in d.raw.items() if k not in ("state", "game_id", "deadline_s", "time_left_s")}
            names = {}

            def collect(x):
                if isinstance(x, dict):
                    if isinstance(x.get("id"), str):
                        names.setdefault(x["id"], x.get("label") or x.get("name") or "?")
                    for v in x.values():
                        collect(v)
                elif isinstance(x, list):
                    for v in x:
                        collect(v)

            def unid(x):
                if isinstance(x, dict):
                    return {k: unid(v) for k, v in x.items()}
                if isinstance(x, list):
                    return [unid(v) for v in x]
                if isinstance(x, str):
                    return f"<{names.get(x, '?')}>" if uuid.match(x) else short.sub("[id]", x)
                return x

            collect(raw)
            self.trace.append(unid({"decision": raw, "answer": getattr(action, "payload", action)}))
            return action

    agents = [Tracer(), Tracer()]
    threads = [threading.Thread(target=play, args=(agents[s], game_id, s, SERVER), kwargs={"token": tokens[s]})
               for s in (0, 1)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(600)
    result = ColosseoClient(SERVER).game(game_id)["result"]
    lives = [p["life"] for p in result["players"]]
    return [a.trace for a in agents], [a.explained for a in agents], {**{k: result.get(k) for k in ("status", "reason", "winner_seat", "turns", "decisions")}, "life": lives}


def _watch(game_id, stop, seen, reconnect):
    """A spectator: counts the explained permanents it is sent. With reconnect, connects again and again, so its
    first state is built off the game thread while the game runs."""
    import asyncio
    import json
    import websockets
    url = SERVER.replace("http", "ws", 1) + f"/ws/game/{game_id}?spectate=1"

    async def run():
        while not stop.is_set():
            try:
                async with websockets.connect(url, max_size=None) as ws:
                    while not stop.is_set():
                        try:
                            msg = json.loads(await asyncio.wait_for(ws.recv(), 0.2 if reconnect else 1))
                        except asyncio.TimeoutError:
                            if reconnect:
                                break
                            continue
                        st = msg.get("state") or {}
                        seen[0] += sum("explain" in c for p in st.get("players", []) for c in p.get("battlefield", []))
                        if msg.get("type") == "game_over":
                            return
            except (OSError, websockets.ConnectionClosed):
                await asyncio.sleep(0.1)

    asyncio.run(run())


@pytest.mark.parametrize("seed", [11, 12])
def test_explanations_do_not_change_play(seed):
    """Explaining permanents only reads the engine: the same seeded game plays identically, decision by decision,
    whether no view is explained (agent seats, nobody watching) or many are (human-type seats, which get `explain`
    in every decision, and spectators, one of them reconnecting throughout)."""
    import threading
    deck = ["fdn:gruul-stompers", "fdn:selesnya-pack"]
    runs = []
    for seat_type, spectators in (("agent", 0), ("human", 2)):
        game = ColosseoClient(SERVER).create_game(
            # names and the seat options human and agent seats default differently, set alike
            [{"type": seat_type, "name": f"P{s}", "deck": deck[s], "stop_policy": "all", "auto_pass": True, "auto_pay": True,
              "yield_after_cast": False, "mulligan_keep_first": True} for s in (0, 1)],
            seed=seed, starting_seat=0, max_turns=20, pace_ms=5, record=False)
        tokens = {s["seat"]: s.get("token") for s in game["seats"]}
        stop, seen = threading.Event(), [0]
        watchers = [threading.Thread(target=_watch, args=(game["game_id"], stop, seen, i == 1), daemon=True)
                    for i in range(spectators)]
        for w in watchers:
            w.start()
        traces, explained, result = _trace(game["game_id"], tokens)
        stop.set()
        for w in watchers:
            w.join(10)
        runs.append((traces, result, explained, seen[0]))
    (plain, plain_result, none, _), (explained, explained_result, seats_explained, seen) = runs
    assert none == [0, 0]
    assert all(n > 0 for n in seats_explained) and seen > 0, "no explained permanents were sent"
    assert plain_result == explained_result and plain_result["status"] == "finished"
    for s in (0, 1):
        assert len(plain[s]) == len(explained[s]) > 20
        for i, (a, b) in enumerate(zip(plain[s], explained[s])):
            assert a == b, f"seat {s}, decision {i}"


class _Mulligans(HeuristicAgent):
    """Takes `n` mulligans, then keeps; records each opening-hand question in order: (kind, cards in hand,
    bottom_on_keep), the bottom choices as ("bottom", cards in hand, None); and the hand it starts the game with."""

    def __init__(self, n):
        super().__init__()
        self.n, self.taken, self.steps, self.opening = n, 0, [], None

    def decide(self, d):
        st = d.raw.get("state") or {}
        hand = next((p["hand_count"] for p in st.get("players", []) if p.get("id") == st.get("you")), None)
        if d.kind == "mulligan":
            self.steps.append(("mulligan", hand, d.raw.get("bottom_on_keep")))
            if self.taken < self.n:
                self.taken += 1
                return d.choose("mulligan")
            return d.choose("keep")
        if d.kind == "target" and "bottom of your library" in (d.prompt or ""):
            self.steps.append(("bottom", hand, None))
        elif self.opening is None:
            self.opening = hand
        return super().decide(d)


def _two_mulligans(seat_type):
    import threading
    from colosseo import play
    game = ColosseoClient(SERVER).create_game([{"type": seat_type, "deck": "fdn:azorius-skies", "stop_policy": "all"},
                                               {"type": "xmage", "deck": "fdn:gruul-stompers"}],
                                              seed=2026, starting_seat=0, max_turns=1, record=False)
    agent = _Mulligans(2)
    t = threading.Thread(target=play, args=(agent, game["game_id"], 0, SERVER),
                         kwargs={"token": game["seats"][0].get("token")})
    t.start()
    t.join(300)
    return agent


def test_human_seats_keep_before_putting_cards_on_the_bottom():
    """London mulligan, as MTG Arena and MTGO ask it: a human seat decides keep-or-mulligan looking at seven new
    cards each time, and puts cards on the bottom only once it keeps (as many as mulligans taken)."""
    agent = _two_mulligans("human")
    assert agent.steps == [("mulligan", 7, 0), ("mulligan", 7, 1), ("mulligan", 7, 2), ("bottom", 7, None),
                           ("bottom", 6, None)]
    assert agent.opening == 5


def test_agent_seats_keep_the_xmage_mulligan_order():
    """Agents' observations are unchanged: XMage's order (CR 103.5 as written), each mulligan's bottom cards
    right after its new seven, then keep-or-mulligan on the smaller hand; no bottom_on_keep."""
    agent = _two_mulligans("agent")
    assert agent.steps == [("mulligan", 7, None), ("bottom", 7, None), ("mulligan", 6, None), ("bottom", 7, None),
                           ("bottom", 6, None), ("mulligan", 5, None)]
    assert agent.opening == 5
