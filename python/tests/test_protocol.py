from colosseo import Action, Decision, render_decision, render_state


def test_decision_basics(decisions):
    d = decisions["priority"]
    assert d.kind == "priority"
    assert "pass" in d.option_ids
    assert d.option("pass").label.startswith("Pass")
    casts = d.options_where(action="cast") + d.options_where(action="play_land")
    assert casts, "fixture should offer something to play"
    msg = d.choose(casts[0]).with_comment("why").to_message(d.id)
    assert msg == {"type": "action", "decision_id": d.id, "choice": casts[0].id, "comment": "why"}


def test_state_views(decisions):
    s = decisions["priority"].state
    assert s.me is not None and s.opponent is not None
    assert s.me.id == s["you"]
    assert "hand" in s.me and "hand" not in s.opponent  # hidden information
    for land in s.me.lands():
        assert "Land" in land.types


def test_every_kind_has_a_default(decisions):
    for kind, d in decisions.items():
        action = d.default()
        assert isinstance(action, Action), kind
        assert action.payload, kind


def test_action_builders(decisions):
    att = decisions["declare_attackers"]
    first = att["attackers"][0]
    assert att.attack([first["id"]]).payload == {"attackers": [{"attacker": first["id"]}]}
    blk = decisions["declare_blockers"]
    b = blk["blockers"][0]
    assert blk.block([(b["id"], "x")]).payload == {"blocks": [{"blocker": b["id"], "attacker": "x"}]}
    assert decisions["amount"].amount(3).payload == {"amount": 3}
    assert decisions["multi_amount"].amounts([1, 2]).payload == {"amounts": [1, 2]}


def test_render_is_text(decisions):
    for kind, d in decisions.items():
        text = render_decision(d)
        assert kind in text
        assert isinstance(render_state(d.state), str)


def test_obj_missing_attribute_is_none():
    d = Decision({"decision_id": 1, "kind": "yes_no", "options": [{"id": "yes", "label": "Yes"}], "state": {}})
    assert d.state.me is None
    assert d.options[0].nonexistent is None
