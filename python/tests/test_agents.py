import pytest

from colosseo.agents import HeuristicAgent, RandomAgent


def check_legal(d, action):
    p = action.payload
    ids = set(d.option_ids)
    if "choice" in p:
        assert p["choice"] in ids, (d.kind, p)
    if "choices" in p:
        assert set(p["choices"]) <= ids, (d.kind, p)
    if "attackers" in p:
        legal = {a["id"]: set(a.get("defenders", [])) for a in d.raw["attackers"]}
        for pair in p["attackers"]:
            assert pair["attacker"] in legal
            if "defender" in pair:
                assert pair["defender"] in legal[pair["attacker"]]
    if "blocks" in p:
        legal = {b["id"]: set(b.get("attackers", [])) for b in d.raw["blockers"]}
        for pair in p["blocks"]:
            assert pair["attacker"] in legal[pair["blocker"]]
    if "amount" in p:
        assert d.raw["min"] <= p["amount"] <= d.raw["max"]
    if "amounts" in p:
        assert len(p["amounts"]) == len(d.raw["items"])


@pytest.mark.parametrize("agent_cls", [RandomAgent, HeuristicAgent])
def test_agents_answer_every_kind_legally(decisions, agent_cls):
    agent = agent_cls()
    for _ in range(20):
        for kind, d in decisions.items():
            check_legal(d, agent.decide(d))


def test_heuristic_plays_a_land_first(decisions):
    d = decisions["priority"]
    lands = d.options_where(action="play_land")
    if not lands or d.state.get("step") not in ("PRECOMBAT_MAIN", "POSTCOMBAT_MAIN") or not d.state.is_my_turn \
            or d.state.get("stack"):
        pytest.skip("fixture has no land drop")
    assert HeuristicAgent().decide(d).payload["choice"] in {o.id for o in lands}
