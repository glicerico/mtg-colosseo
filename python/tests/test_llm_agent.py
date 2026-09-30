"""ClaudeAgent against a fake Anthropic client (no network)."""
import json
from types import SimpleNamespace

from colosseo.agents.llm import ClaudeAgent


class FakeMessages:
    def __init__(self, reply):
        self.reply = reply
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if isinstance(self.reply, Exception):
            raise self.reply
        if self.reply == "refusal":
            return SimpleNamespace(stop_reason="refusal", content=[])
        text = json.dumps(self.reply(kwargs) if callable(self.reply) else self.reply)
        return SimpleNamespace(stop_reason="end_turn", content=[SimpleNamespace(type="text", text=text)])


def fake_client(reply):
    messages = FakeMessages(reply)
    return SimpleNamespace(messages=messages, beta=SimpleNamespace(messages=messages)), messages


def schema_of(kwargs):
    return kwargs["output_config"]["format"]["schema"]


def test_choice_is_constrained_to_option_ids(decisions):
    d = decisions["priority"]
    client, calls = fake_client(lambda kw: {"comment": "play it", "choice": schema_of(kw)["properties"]["choice"]["enum"][-1]})
    action = ClaudeAgent(client=client).decide(d)
    kw = calls.calls[0]
    assert kw["model"] == "claude-opus-5-5"
    assert kw["betas"] == ["server-side-fallback-2026-07-01"] and kw["fallbacks"] == "default"
    assert set(schema_of(kw)["properties"]["choice"]["enum"]) == set(d.option_ids)
    assert "DECISION (priority)" in kw["messages"][0]["content"]
    assert action.payload["choice"] == d.option_ids[-1]
    assert action.comment == "play it"


def test_attack_answer_is_filtered(decisions):
    d = decisions["declare_attackers"]
    a = d["attackers"][0]
    reply = {"comment": "go", "attackers": [{"attacker": a["id"], "defender": a["defenders"][0]},
                                             {"attacker": "bogus", "defender": "x"}]}
    client, _ = fake_client(reply)
    action = ClaudeAgent(client=client).decide(d)
    assert action.payload == {"attackers": [{"attacker": a["id"], "defender": a["defenders"][0]}]}


def test_refusal_and_errors_fall_back(decisions):
    d = decisions["priority"]
    for reply in ("refusal", RuntimeError("network down")):
        client, _ = fake_client(reply)
        action = ClaudeAgent(client=client).decide(d)
        assert action.payload["choice"] in d.option_ids


def test_trivial_decisions_skip_the_api(decisions):
    d = decisions["priority"]
    d.options = d.options[:1]
    client, calls = fake_client({"comment": "", "choice": "pass"})
    ClaudeAgent(client=client).decide(d)
    assert calls.calls == []
