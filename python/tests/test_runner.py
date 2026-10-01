"""Result handling, reconnection and match scheduling, with a fake server (no network)."""
import copy
import threading

import pytest

import colosseo.runner as runner
from colosseo import Agent, ColosseoError, elo_ratings, match_schedule, outcome, play, run_match
from colosseo.runner import MatchResult


# --- fakes -------------------------------------------------------------------------------------

class FakeConn:
    def __init__(self, messages, close_code=None, close_reason=""):
        self._messages = list(messages)
        self.sent = []
        self.close_code = close_code
        self.close_reason = close_reason

    def messages(self):
        yield from self._messages

    @property
    def refused(self):
        return self.close_code in (4001, 4003, 4004)

    def send(self, msg):
        self.sent.append(msg)

    def close(self):
        pass


class FakeClient:
    def __init__(self, connections, game_infos):
        self.connections = list(connections)
        self.game_infos = list(game_infos)
        self.connects = 0
        self.terminated = []

    def connect_seat(self, game_id, seat, token=None):
        self.connects += 1
        if not self.connections:
            raise ColosseoError("connection refused")
        return self.connections.pop(0)

    def game(self, game_id):
        info = self.game_infos.pop(0) if len(self.game_infos) > 1 else self.game_infos[0]
        if isinstance(info, Exception):
            raise info
        return info

    def terminate(self, game_id, token=None):
        self.terminated.append((game_id, token))
        return {}


class CountingAgent(Agent):
    name = "counting"

    def __init__(self):
        self.decided = []
        self.ended = []

    def decide(self, d):
        self.decided.append(d.id)
        return d.default()

    def on_game_end(self, result):
        self.ended.append(result)


RUNNING = {"id": "g1", "status": "running"}
FINISHED = {"status": "finished", "winner_seat": 1, "winner": "B", "draw": False, "turns": 9}


def decision_msg(raw, did):
    msg = copy.deepcopy(raw)
    msg["decision_id"] = did
    return msg


# --- outcome -----------------------------------------------------------------------------------

@pytest.mark.parametrize("result,expected", [
    ({"status": "finished", "winner_seat": 0, "draw": False}, "win"),
    ({"status": "finished", "winner_seat": None, "draw": True}, "draw"),
    ({"status": "terminated", "winner_seat": None, "draw": False, "error": "terminated"}, "unfinished"),
    ({"status": "abandoned", "winner_seat": None, "draw": False}, "unfinished"),
    ({"status": "interrupted", "winner_seat": None, "draw": False}, "unfinished"),
    ({}, "unfinished"),
    (None, "unfinished"),
    # older servers: no status field, a draw must still be explicit
    ({"winner_seat": None, "draw": True}, "draw"),
    ({"winner_seat": None}, "unfinished"),
    ({"winner_seat": None, "draw": True, "error": "boom"}, "unfinished"),
])
def test_outcome(result, expected):
    assert outcome(result) == expected


# --- play(): disconnects -------------------------------------------------------------------------

def test_lost_connection_is_interrupted_not_a_draw():
    client = FakeClient([FakeConn([{"type": "hello", "game": RUNNING}])], [RUNNING])
    agent = CountingAgent()
    result = play(agent, "g1", 0, client=client, reconnect_timeout=0.3)
    assert result["status"] == "interrupted"
    assert result["draw"] is False and result["winner_seat"] is None
    assert outcome(result) == "unfinished"
    assert agent.ended == [result]
    assert client.connects >= 2  # it tried to reconnect


def test_game_finishing_during_recovery_is_recorded_once(raw_decisions):
    first = FakeConn([{"type": "hello", "game": RUNNING}, decision_msg(raw_decisions["priority"], 5)])
    client = FakeClient([first], [RUNNING, {**RUNNING, "status": "finished", "result": FINISHED}])
    agent = CountingAgent()
    result = play(agent, "g1", 0, client=client, reconnect_timeout=5)
    assert result == FINISHED
    assert agent.ended == [FINISHED]
    assert agent.decided == [5]


def test_answer_is_resent_after_reconnect(raw_decisions):
    d = decision_msg(raw_decisions["priority"], 7)
    first = FakeConn([{"type": "hello", "game": RUNNING}, d])
    second = FakeConn([{"type": "hello", "game": RUNNING}, d,
                       {"type": "game_over", "result": FINISHED}])
    client = FakeClient([first, second], [RUNNING])
    agent = CountingAgent()
    result = play(agent, "g1", 0, client=client, reconnect_timeout=5)
    assert result == FINISHED
    assert agent.decided == [7]  # asked once
    assert first.sent == second.sent and first.sent[0]["decision_id"] == 7  # same answer sent again
    assert len(agent.ended) == 1


def test_stale_errors_do_not_reask_the_agent(raw_decisions):
    d = decision_msg(raw_decisions["priority"], 3)
    conn = FakeConn([d, {"type": "error", "decision_id": 3, "code": "stale", "message": "decision 3 was already answered"},
                     {"type": "game_over", "result": FINISHED}])
    agent = CountingAgent()
    play(agent, "g1", 0, client=FakeClient([conn], [RUNNING]))
    assert agent.decided == [3]


def test_refused_connection_is_an_error_without_retries():
    client = FakeClient([FakeConn([{"type": "error", "message": "invalid token for seat 0"}], 4001, "bad token")], [RUNNING])
    agent = CountingAgent()
    result = play(agent, "g1", 0, client=client, reconnect_timeout=30)
    assert result["status"] == "error" and "bad token" in result["error"]
    assert client.connects == 1


# --- scoring -----------------------------------------------------------------------------------

def _match_with(results):
    m = MatchResult("A", "B")
    for i, r in enumerate(results):
        m.record({**r, "index": i, "a_seat": 0})
    return m


def test_interrupted_games_are_errors_not_draws():
    m = _match_with([{"status": "interrupted", "winner_seat": None, "draw": False, "error": "connection lost"},
                     {}])
    assert (m.wins_a, m.wins_b, m.draws, m.errors) == (0, 0, 0, 2)
    assert elo_ratings([m]) == {"A": 1500.0, "B": 1500.0}


def test_real_draws_still_count():
    m = _match_with([{"status": "finished", "winner_seat": None, "draw": True},
                     {"status": "finished", "winner_seat": 0, "draw": False}])
    assert (m.wins_a, m.draws, m.errors) == (1, 1, 0)
    ratings = elo_ratings([m])
    assert ratings["A"] > 1500 > ratings["B"]


def test_run_match_counts_interrupted_games_as_errors(monkeypatch):
    def fake_run_game(*args, **kwargs):
        return {"status": "interrupted", "winner_seat": None, "draw": False, "error": "connection lost"}
    monkeypatch.setattr(runner, "run_game", fake_run_game)
    m = run_match("xmage", "xmage:2", games=2, decks="d", seed=1, client=object())
    assert (m.draws, m.errors) == (0, 2)


# --- scheduling --------------------------------------------------------------------------------

def test_schedule_is_reproducible_and_balanced():
    decks = ["a", "b", "c", "d"]
    s1 = match_schedule(8, decks, seed=123)
    s2 = match_schedule(8, decks, seed=123)
    assert s1 == s2
    assert match_schedule(8, decks, seed=124) != s1
    assert sum(g.a_starts for g in s1) == 4
    for first, second in zip(s1[0::2], s1[1::2]):
        assert (first.deck_a, first.deck_b) == (second.deck_a, second.deck_b)
        assert first.a_starts != second.a_starts
    assert len({g.seed for g in s1}) == 8
    assert [g.a_seat for g in s1] == [0, 1] * 4


def test_schedule_with_deck_swaps():
    s = match_schedule(4, [("x", "y")], seed=5, swap_decks=True)
    assert [(g.deck_a, g.a_starts) for g in s] == [("x", True), ("x", False), ("y", True), ("y", False)]


def test_run_match_passes_the_schedule_to_every_game(monkeypatch):
    calls = []
    lock = threading.Lock()

    def fake_run_game(p0, p1, deck0, deck1, **kwargs):
        with lock:
            calls.append((deck0, deck1, kwargs["seed"], kwargs["starting_seat"], kwargs["names"][0]))
        return {"status": "finished", "winner_seat": kwargs["starting_seat"], "draw": False, "seed": kwargs["seed"]}

    monkeypatch.setattr(runner, "run_game", fake_run_game)
    serial = run_match("xmage", "xmage:2", games=6, decks=["a", "b", "c"], seed=42, client=object())
    serial_calls = sorted(calls)
    calls.clear()
    parallel = run_match("xmage", "xmage:2", games=6, decks=["a", "b", "c"], seed=42, parallel=3, client=object())
    assert sorted(calls) == serial_calls
    assert serial.schedule == parallel.schedule
    assert [g["index"] for g in parallel.games] == list(range(6))
    for planned, game in zip(serial.schedule, serial.games):
        assert game["seed"] == planned.seed and game["starting_seat"] == planned.starting_seat
    # the starting player won every game: with balanced starts that is an even match
    assert serial.wins_a == serial.wins_b == 3


def test_run_match_records_a_generated_seed(monkeypatch):
    monkeypatch.setattr(runner, "run_game", lambda *a, **k: {"status": "finished", "winner_seat": 0, "draw": False})
    m = run_match("xmage", "xmage:2", games=2, decks="d", client=object())
    assert isinstance(m.seed, int)
    assert match_schedule(2, "d", seed=m.seed) == m.schedule
