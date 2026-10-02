"""Live checks for the platform guarantees asked for in issues #4-#12 (needs a running server)."""
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from colosseo import ColosseoClient, ColosseoError, outcome, play, run_game
from colosseo.agents import HeuristicAgent, RandomAgent

from .conftest import SERVER, server_up

pytestmark = [pytest.mark.integration,
              pytest.mark.skipif(not server_up(), reason=f"no Colosseo server at {SERVER}")]

MENACE_DECK = str(Path(__file__).parent / "decks" / "menace-test.dck")
FDN = ("fdn:azorius-skies", "fdn:rakdos-raiders")


@pytest.fixture
def client():
    return ColosseoClient(SERVER)


class AllOutAttacker(HeuristicAgent):
    name = "all-out"

    def decide(self, d):
        if d.kind == "declare_attackers":
            return d.attack([a["id"] for a in d["attackers"]])
        return super().decide(d)


class SingleBlocker(HeuristicAgent):
    """Blocks every attacker with one creature, ignoring menace: an illegal declaration it keeps repeating."""
    name = "single-blocker"
    rejections = 0
    min_blockers_seen = 0

    def decide(self, d):
        if d.kind == "declare_blockers":
            if d.rejection:
                SingleBlocker.rejections += 1
            used, blocks = set(), []
            for a in d["attackers"]:
                SingleBlocker.min_blockers_seen = max(SingleBlocker.min_blockers_seen, a.get("min_blockers") or 1)
                for b in d["blockers"]:
                    if b["id"] not in used and a["id"] in b["attackers"]:
                        used.add(b["id"])
                        blocks.append((b["id"], a["id"]))
                        break
            return d.block(blocks)
        return super().decide(d)


def test_tokens_are_the_default(client):
    """#4: a server started without --auth protects every game."""
    assert client.health()["auth"] == "tokens"
    g = client.create_game([{"type": "agent", "deck": FDN[0]}, {"type": "agent", "deck": FDN[1]}], max_turns=1, record=False)
    try:
        assert client.game(g["game_id"])["protected"] is True
        conn = client.connect_seat(g["game_id"], 1)  # no token
        list(conn.messages())
        assert conn.close_code == 4001
    finally:
        client.terminate(g["game_id"], g["owner_token"])


def test_repeated_illegal_blocks_fall_back_instead_of_looping(client):
    """#6 (and #8): menace attackers, a defender that always single-blocks."""
    SingleBlocker.rejections = 0
    result = run_game(AllOutAttacker(), SingleBlocker(), MENACE_DECK, MENACE_DECK, server=SERVER, starting_seat=0,
                      max_turns=10, record=False, max_decisions=3000, seed=3)
    assert result["status"] == "finished", result
    assert result["decisions"] < 3000
    assert SingleBlocker.min_blockers_seen == 2          # the requirement is visible in the decision
    assert SingleBlocker.rejections >= 1                 # re-asks say why
    fallbacks = result["players"][1]["fallbacks"]
    assert fallbacks.get("illegal_repeat", 0) >= 1, result["players"]


def test_keywords_in_observations(client):
    seen = {}

    class Watcher(AllOutAttacker):
        def decide(self, d):
            for p in (d.raw.get("state") or {}).get("players", []):
                for c in (p.get("battlefield") or []) + (p.get("hand") or []):
                    if c.get("name") == "Nullpriest of Oblivion":
                        seen["kw"] = c.get("keywords")
            return super().decide(d)

    run_game(Watcher(), HeuristicAgent(), MENACE_DECK, MENACE_DECK, server=SERVER, starting_seat=0, max_turns=4,
             record=False, seed=3)
    assert seen.get("kw") is not None and "menace" in seen["kw"] and "kicker {3}{B}" in seen["kw"]


def test_decision_limit_voids_the_game(client):
    """#7: a runaway game stops with an explicit unfinished outcome."""
    r = run_game(RandomAgent(seed=1), RandomAgent(seed=2), *FDN, server=SERVER, max_turns=30, record=False,
                 max_decisions=25)
    assert r["status"] == "limit" and outcome(r) == "unfinished" and "decision limit" in r["reason"]


def test_turn_limit_can_be_void(client):
    r = run_game(HeuristicAgent(), HeuristicAgent(), *FDN, server=SERVER, max_turns=2, record=False,
                 turn_limit_result="void")
    assert r["status"] == "turn_limit" and outcome(r) == "unfinished"


@pytest.mark.parametrize("seed", [9, 11, 21])
def test_time_bank_forfeits(client, seed):
    """#7: a seat that runs out of its time bank loses the game. Seeds 9 and 11 hit the clock while the seat is in
    a dialog XMage doesn't interrupt on concession (they used to hang the game)."""
    class Slow(HeuristicAgent):
        seat_options = {"time_bank_s": 1.0}

        def decide(self, d):
            time.sleep(0.7)
            return super().decide(d)

    r = run_game(Slow(), HeuristicAgent(), *FDN, server=SERVER, starting_seat=0, max_turns=20, record=False,
                 seed=seed, deadline_s=60)
    assert r["status"] == "finished", r
    assert r["winner_seat"] == 1 and r["reason"] == "forfeit"
    assert r["forfeit_seat"] == 0 and r["forfeit_reason"] == "time"
    assert r["players"][0]["clock_used_s"] >= 1.0


class Crashing(HeuristicAgent):
    name = "crashing"

    def decide(self, d):
        if d.kind == "priority" and len(d.options) > 2:
            raise RuntimeError("policy bug")
        return super().decide(d)


def test_agent_errors_are_marked_or_forfeit(client):
    """#5: fallbacks are recorded; strict mode forfeits instead."""
    lenient = run_game(Crashing(), HeuristicAgent(), *FDN, server=SERVER, starting_seat=0, max_turns=4, record=False)
    assert lenient["players"][0]["fallbacks"].get("agent_error", 0) >= 1
    strict = run_game(Crashing(), HeuristicAgent(), *FDN, server=SERVER, starting_seat=0, max_turns=4, record=False,
                      on_error="forfeit")
    assert strict["status"] == "finished" and strict["winner_seat"] == 1
    assert strict["forfeit_seat"] == 0 and strict["forfeit_reason"] == "agent_error"


def test_per_seat_record_and_version_pins(client):
    """#10: a seat's record holds only what it could see; records pin engine, deck and agent versions."""
    box = {}
    run_game(HeuristicAgent(), HeuristicAgent(), *FDN, server=SERVER, starting_seat=0, max_turns=3, record=True,
             on_created=box.update, client=client)
    gid, seat0 = box["game_id"], box["seats"][0]["token"]
    mine = client.record(gid, seat=0, token=seat0)
    config = mine[0]["data"]
    assert config["engine"]["xmage"] == "1.4.61" and config["engine"]["xmage_ref"] != "unknown"
    players = {p["seat"]: p for p in config["players"]}
    assert players[0]["decklist"] and players[0]["deck_hash"]
    assert "decklist" not in players[1] and players[1]["deck_hash"]
    assert players[0]["agent_id"] == "heuristic@1" and players[0].get("agent_hash")
    assert all(l["data"]["seat"] == 0 for l in mine if l["type"] == "decision")
    theirs = [l for l in mine if l["type"] == "action" and l["data"]["seat"] == 1]
    assert theirs and all(set(l["data"]) == {"seat", "decision_id", "kind", "summary"} for l in theirs)
    assert mine[-1]["type"] == "result"
    with pytest.raises(ColosseoError) as e:
        client.record(gid, token=seat0)  # the full record needs the owner token
    assert e.value.status == 403
    full = client.record(gid, token=box["owner_token"])
    assert {l["data"]["seat"] for l in full if l["type"] == "decision"} == {0, 1}


def test_rated_games_feed_the_leaderboard(client):
    """#11: ratings by agent identity (name@version); unfinished games never move them."""
    tag = uuid.uuid4().hex[:6]

    class A(HeuristicAgent):
        name, version = f"probe-{tag}", "1"

    class B(HeuristicAgent):
        name, version = f"probe-{tag}", "2"

    r = run_game(A(), B(), *FDN, server=SERVER, max_turns=3, record=True, rated=True, require_tokens=True)
    board = {row["agent_id"]: row for row in client.leaderboard()["ratings"]}
    a, b = board[f"probe-{tag}@1"], board[f"probe-{tag}@2"]
    assert a["games"] == b["games"] == 1
    if outcome(r) == "draw":
        assert a["draws"] == b["draws"] == 1
    else:
        assert a["wins"] + b["wins"] == 1


def test_same_seed_replays_the_same_game(client):
    """#12: shuffles and option order come from the seed, even with games running side by side.
    Compares the whole game - every decision (kind, prompt, options) and every action - not just the totals."""
    import re

    def norm(text):
        return re.sub(r"\[[0-9a-f]{3}\]", "", text or "")  # XMage shows short object ids in names

    def game(seed):
        box = {}
        run_game(RandomAgent(seed=1), HeuristicAgent(), *FDN, server=SERVER, seed=seed, starting_seat=0,
                 max_turns=10, record=True, on_created=box.update, client=client)
        trace = []
        for line in client.record(box["game_id"], token=box["owner_token"]):
            data = line["data"]
            if line["type"] == "decision":
                trace.append(("D", data["seat"], data["kind"], norm(data["prompt"]),
                              tuple(norm(o["label"]) for o in data["options"])))
            elif line["type"] == "action":
                trace.append(("A", data["seat"], norm(data["summary"])))
            elif line["type"] == "result":
                trace.append(("R", data["winner_seat"], data["turns"], tuple(p["life"] for p in data["players"])))
        return trace

    with ThreadPoolExecutor(4) as pool:
        a1, b1, a2, b2 = pool.map(game, [21, 22, 21, 22])
    assert len(a1) > 20 and a1 == a2 and b1 == b2
    assert a1 != b1


def test_unrecorded_rated_games_are_persisted(client):
    """Rated results are written to the ratings ledger even without a game record."""
    tag = uuid.uuid4().hex[:6]

    class A(HeuristicAgent):
        name, version = f"ledger-{tag}", "1"

    class B(HeuristicAgent):
        name, version = f"ledger-{tag}", "2"

    r = run_game(A(), B(), *FDN, server=SERVER, max_turns=2, record=False, rated=True)
    assert r["rating_persisted"] is True
    board = {row["agent_id"]: row for row in client.leaderboard()["ratings"]}
    assert board[f"ledger-{tag}@1"]["games"] == 1


class CrashOnBlocks(HeuristicAgent):
    name = "crash-on-blocks"

    def decide(self, d):
        if d.kind == "declare_blockers":
            raise RuntimeError("policy bug while blocking")
        return super().decide(d)


class SlowOnBlocks(HeuristicAgent):
    name = "slow-on-blocks"
    seat_options = {"time_bank_s": 1.0}

    def decide(self, d):
        if d.kind == "declare_blockers":
            time.sleep(1.5)
        return super().decide(d)


@pytest.mark.parametrize("defender,on_error,reason", [(CrashOnBlocks, "forfeit", "agent_error"),
                                                       (SlowOnBlocks, "default", "time")])
def test_forfeit_outside_priority_ends_the_game(client, defender, on_error, reason):
    """A seat that forfeits while answering a non-priority dialog (blocks during the opponent's attack) must not
    leave the game waiting for that dialog."""
    t0 = time.monotonic()
    r = run_game(AllOutAttacker(), defender(), MENACE_DECK, MENACE_DECK, server=SERVER, starting_seat=0,
                 max_turns=12, record=False, seed=3, deadline_s=90, on_error=on_error, reconnect_timeout=10)
    assert time.monotonic() - t0 < 60
    assert r["status"] == "finished", r
    assert r["forfeit_seat"] == 1 and r["forfeit_reason"] == reason and r["winner_seat"] == 0
