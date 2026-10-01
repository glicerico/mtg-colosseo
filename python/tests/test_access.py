"""Hidden information, access control and game lifecycle against a running server.

The server under test is expected to run in its default local mode (``--auth open``); games created with
``require_tokens`` get the same protection a ``--auth tokens`` server gives every game.
"""
import threading
import time

import pytest

from colosseo import ColosseoClient, ColosseoError, outcome
from colosseo.client import SeatConnection

from .conftest import SERVER, server_up

pytestmark = [pytest.mark.integration,
              pytest.mark.skipif(not server_up(), reason=f"no Colosseo server at {SERVER}")]

AGENTS = [{"type": "agent", "deck": "fdn:azorius-skies"}, {"type": "agent", "deck": "fdn:rakdos-raiders"}]


@pytest.fixture
def client():
    return ColosseoClient(SERVER)


def collect(conn: SeatConnection, sink: list, answer=None, stop: threading.Event = None):
    for msg in conn.messages():
        sink.append(msg)
        if msg["type"] == "decision" and answer:
            answer(conn, msg)
        if msg["type"] == "game_over" or (stop and stop.is_set()):
            return


def first(messages, **match):
    return next((m for m in messages if all(m.get(k) == v for k, v in match.items())), None)


def test_private_selection_and_comments_stay_private(client):
    """London mulligan: the card put on the bottom is hidden from the opponent and plain spectators."""
    g = client.create_game(AGENTS, starting_seat=0, max_turns=2, record=False)
    gid = g["game_id"]
    s0 = client.connect_seat(gid, 0, g["seats"][0]["token"])
    s1 = client.connect_seat(gid, 1, g["seats"][1]["token"])
    spec = client.spectate(gid)
    seen = {"s0": [], "s1": [], "spec": []}
    state = {"mulligans": 0, "bottomed": None}

    def seat0(conn, d):
        if d["kind"] == "mulligan":
            state["mulligans"] += 1
            choice = "mulligan" if state["mulligans"] == 1 else "keep"
            conn.send({"decision_id": d["decision_id"], "choice": choice, "comment": "secret plan"})
        elif d["kind"] == "target" and state["bottomed"] is None:
            opt = next(o for o in d["options"] if o["id"] != "done")
            state["bottomed"] = opt["label"]
            conn.send({"decision_id": d["decision_id"], "choice": opt["id"], "comment": "bottom " + opt["label"]})
        else:
            conn.send({"decision_id": d["decision_id"], **d["default"]})

    def seat1(conn, d):
        conn.send({"decision_id": d["decision_id"], **d["default"]})

    threads = [threading.Thread(target=collect, args=a, daemon=True)
               for a in [(s0, seen["s0"], seat0), (s1, seen["s1"], seat1), (spec, seen["spec"])]]
    for t in threads:
        t.start()
    for t in threads:
        t.join(120)
    for c in (s0, s1, spec):
        c.close()

    card = state["bottomed"]
    assert card, "seat 0 was never asked to put a card on the bottom"
    mine = first(seen["s0"], type="action", seat=0, kind="target")
    assert mine["summary"] == card and mine["comment"] == "bottom " + card
    for viewer in ("s1", "spec"):
        acks = [m for m in seen[viewer] if m["type"] == "action" and m["seat"] == 0]
        target_ack = first(acks, kind="target")
        assert target_ack is not None and card not in target_ack["summary"]
        assert all("comment" not in m for m in acks), f"{viewer} received seat 0's comments"
    pending = [m for m in seen["spec"] if m["type"] == "decision_pending" and m["seat"] == 0]
    assert pending and all("bottom of your library" not in m["prompt"] for m in pending)


def test_protected_game_requires_tokens(client):
    g = client.create_game(AGENTS, require_tokens=True, max_turns=2, record=False, abandon_timeout_s=60)
    gid, owner = g["game_id"], g["owner_token"]
    try:
        assert client.game(gid)["protected"] is True
        # seats need their own token
        for token in (None, "wrong", owner, g["seats"][1]["token"]):
            conn = client.connect_seat(gid, 0, token)
            list(conn.messages())
            assert conn.close_code == 4001, token
        ok = client.connect_seat(gid, 0, g["seats"][0]["token"])
        assert ok.recv(timeout=10)["type"] == "hello"
        ok.close()

        # revealing hands needs the owner token, both on connect and through settings
        spec = client.spectate(gid, reveal=True)
        hello = spec.recv(timeout=10)
        assert hello["reveal"] is False and hello["may_reveal"] is False
        assert spec.recv(timeout=10)["type"] == "error"
        spec.settings(reveal=True)
        assert next(m for m in spec.messages() if m["type"] == "error")
        spec.close()
        spec = client.spectate(gid, reveal=True, token=owner)
        assert spec.recv(timeout=10)["reveal"] is True
        spec.close()

        # stopping the game needs the owner token
        with pytest.raises(ColosseoError) as e:
            client.terminate(gid)
        assert e.value.status == 403
        client.terminate(gid, owner)
        result = wait_result(client, gid)
        assert result["status"] == "terminated" and result["draw"] is False
        assert outcome(result) == "unfinished"
    finally:
        try:
            client.terminate(gid, owner)
        except ColosseoError:
            pass


def test_abandoned_game_is_stopped(client):
    g = client.create_game(AGENTS, abandon_timeout_s=1, record=False)
    result = wait_result(client, g["game_id"], timeout=30)
    assert result["status"] == "abandoned" and result["winner_seat"] is None and result["draw"] is False
    assert "disconnected" in result["reason"]


def test_seed_and_starting_seat_are_recorded(client):
    g = client.create_game([{"type": "xmage", "deck": "fdn:azorius-skies", "skill": 1},
                            {"type": "xmage", "deck": "fdn:rakdos-raiders", "skill": 1}],
                           seed=1234, starting_seat=1, max_turns=1, record=False)
    result = wait_result(client, g["game_id"], timeout=120)
    assert result["seed"] == 1234 and result["starting_seat"] == 1
    g2 = client.create_game(AGENTS, max_turns=1, record=False)
    client.terminate(g2["game_id"], g2["owner_token"])
    assert isinstance(wait_result(client, g2["game_id"])["seed"], int)


def test_cross_site_requests_are_refused(client):
    import json
    import urllib.request
    req = urllib.request.Request(SERVER + "/api/games", method="POST", data=json.dumps({"seats": AGENTS}).encode(),
                                 headers={"Content-Type": "application/json", "Origin": "https://evil.example"})
    with pytest.raises(urllib.error.HTTPError) as e:
        urllib.request.urlopen(req, timeout=10)
    assert e.value.code == 403
    req = urllib.request.Request(SERVER + "/api/games", method="POST", data=json.dumps({"seats": AGENTS}).encode(),
                                 headers={"Content-Type": "text/plain"})
    with pytest.raises(urllib.error.HTTPError) as e:
        urllib.request.urlopen(req, timeout=10)
    assert e.value.code == 415


def wait_result(client, gid, timeout=20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        info = client.game(gid)
        if info.get("result"):
            return info["result"]
        time.sleep(0.3)
    raise AssertionError(f"game {gid} did not end within {timeout}s")
