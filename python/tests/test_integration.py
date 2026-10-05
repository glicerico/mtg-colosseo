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
