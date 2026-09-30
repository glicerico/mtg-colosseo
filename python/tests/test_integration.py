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
