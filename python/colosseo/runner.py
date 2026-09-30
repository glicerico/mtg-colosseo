"""Running games, matches and tournaments between agents and XMage's AI."""
from __future__ import annotations

import itertools
import logging
import random
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple, Union

from .agent import Agent, GameResult, play
from .client import ColosseoClient, DEFAULT_SERVER

__all__ = ["AgentSpec", "run_game", "run_match", "round_robin", "MatchResult", "elo_ratings", "wait_for_server"]

log = logging.getLogger("colosseo")

#: an Agent instance, an Agent class/factory (a fresh instance per game), or a built-in seat:
#: ``"xmage"`` / ``"xmage:5"`` (XMage MAD AI with skill 1-10) or ``"human"`` (play in the web UI)
AgentSpec = Union[Agent, Callable[[], Agent], str]


def wait_for_server(server: str = DEFAULT_SERVER, timeout: float = 180.0) -> None:
    """Blocks until the server answers /api/health (the first start builds the card database)."""
    client = ColosseoClient(server, timeout=5)
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            client.health()
            return
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1)
    raise TimeoutError(f"Colosseo server not reachable at {server}: {last}")


def _instantiate(spec: AgentSpec) -> Union[Agent, str]:
    if isinstance(spec, (Agent, str)):
        return spec
    return spec()


def _spec_name(spec: AgentSpec) -> str:
    if isinstance(spec, str):
        return spec
    if isinstance(spec, Agent):
        return spec.name
    return getattr(spec, "name", None) or getattr(spec, "__name__", "agent")


def _seat(player: Union[Agent, str], deck: str, name: Optional[str]) -> Dict[str, Any]:
    if isinstance(player, str):
        if player.startswith("xmage"):
            skill = int(player.split(":", 1)[1]) if ":" in player else 3
            return {"type": "xmage", "deck": deck, "skill": skill, "name": name or f"XMage AI (skill {skill})"}
        if player == "human":
            return {"type": "human", "deck": deck, "name": name or "Human"}
        raise ValueError(f"unknown built-in player {player!r} (use 'xmage', 'xmage:N' or 'human')")
    seat = {"type": "agent", "deck": deck, "name": name or player.name}
    seat.update(getattr(player, "seat_options", {}) or {})
    return seat


def run_game(player0: AgentSpec, player1: AgentSpec, deck0: str, deck1: Optional[str] = None, *,
             server: str = DEFAULT_SERVER, names: Sequence[Optional[str]] = (None, None),
             starting_seat: int = -1, max_turns: int = 60, pace_ms: int = 0, seed: Optional[int] = None,
             record: bool = True, title: Optional[str] = None,
             on_created: Optional[Callable[[Dict[str, Any]], None]] = None) -> GameResult:
    """Plays one game and returns its result.

    ``player0``/``player1`` are agents or built-ins (``"xmage"``, ``"xmage:5"``, ``"human"``).
    Agent seats are driven from threads in this process; XMage seats run inside the server.
    """
    client = ColosseoClient(server)
    p0, p1 = _instantiate(player0), _instantiate(player1)
    seats = [_seat(p0, deck0, names[0]), _seat(p1, deck1 or deck0, names[1])]
    options: Dict[str, Any] = {"starting_seat": starting_seat, "max_turns": max_turns, "pace_ms": pace_ms,
                               "record": record}
    if seed is not None:
        options["seed"] = seed
    if title:
        options["title"] = title
    created = client.create_game(seats, **options)
    game_id = created["game_id"]
    if on_created:
        on_created(created)
    for s in created["seats"]:
        if s["type"] == "human":
            log.warning("seat %s is human: open %s/#/game/%s/seat/%s", s["seat"], server, game_id, s["seat"])

    results: List[GameResult] = []
    threads = []
    for index, player in enumerate((p0, p1)):
        if isinstance(player, Agent):
            token = created["seats"][index].get("token")
            t = threading.Thread(target=lambda a=player, i=index, tk=token: results.append(play(a, game_id, i, server, tk)),
                                 name=f"colosseo-{game_id}-seat{index}", daemon=True)
            threads.append(t)
            t.start()
    for t in threads:
        t.join()
    if not results:
        # no agent seat in this process (e.g. xmage vs xmage): wait for the server
        while True:
            info = client.game(game_id)
            if info.get("result"):
                results.append(info["result"])
                break
            time.sleep(0.5)
    result = dict(results[0])
    result["game_id"] = game_id
    return result


@dataclass
class MatchResult:
    """Outcome of a series of games between two players (``a`` and ``b``)."""
    a: str
    b: str
    wins_a: int = 0
    wins_b: int = 0
    draws: int = 0
    errors: int = 0
    games: List[GameResult] = field(default_factory=list)

    @property
    def played(self) -> int:
        return len(self.games)

    @property
    def score_a(self) -> float:
        """Match score for ``a`` in [0, 1] (draws count half)."""
        n = self.wins_a + self.wins_b + self.draws
        return (self.wins_a + 0.5 * self.draws) / n if n else 0.5

    def __str__(self) -> str:
        return (f"{self.a} vs {self.b}: {self.wins_a}-{self.wins_b}"
                + (f" ({self.draws} draws)" if self.draws else "")
                + (f" [{self.errors} errors]" if self.errors else "")
                + f" over {self.played} games")


def run_match(player_a: AgentSpec, player_b: AgentSpec, *, games: int = 10,
              decks: Union[str, Sequence[str], Sequence[Tuple[str, str]], None] = None,
              server: str = DEFAULT_SERVER, swap_seats: bool = True, parallel: int = 1,
              seed: Optional[int] = None, **game_options: Any) -> MatchResult:
    """Plays a series of games.

    ``decks``: one deck id (mirror), a list of deck ids (each game draws a random pair) or a list of
    (deck_a, deck_b) pairs used in turn. Defaults to every deck on the server. With ``swap_seats``,
    players alternate seats (and therefore who plays first half of the time).
    Agent classes/factories get a fresh instance per game; agent instances are shared.
    """
    rng = random.Random(seed)
    client = ColosseoClient(server)
    if decks is None:
        decks = [d["id"] for d in client.decks()]
    if isinstance(decks, str):
        pairs = [(decks, decks)]
    elif decks and isinstance(decks[0], (tuple, list)):
        pairs = [tuple(p) for p in decks]  # type: ignore[misc]
    else:
        pairs = None

    name_a, name_b = _spec_name(player_a), _spec_name(player_b)
    if name_a == name_b:
        name_a, name_b = name_a + " (A)", name_b + " (B)"
    match = MatchResult(name_a, name_b)
    lock = threading.Lock()

    def one(i: int) -> None:
        if pairs is not None:
            deck_a, deck_b = pairs[i % len(pairs)]
        else:
            deck_a, deck_b = rng.choice(decks), rng.choice(decks)  # type: ignore[arg-type]
        a_seat = (i % 2) if swap_seats else 0
        players = [player_a, player_b] if a_seat == 0 else [player_b, player_a]
        seat_decks = [deck_a, deck_b] if a_seat == 0 else [deck_b, deck_a]
        seat_names = [name_a, name_b] if a_seat == 0 else [name_b, name_a]
        try:
            result = run_game(players[0], players[1], seat_decks[0], seat_decks[1], server=server,
                              names=seat_names, **game_options)
        except Exception as e:  # noqa: BLE001
            log.error("game %d failed: %s", i, e)
            with lock:
                match.errors += 1
            return
        result["a_seat"] = a_seat
        with lock:
            match.games.append(result)
            winner = result.get("winner_seat")
            if result.get("error"):
                match.errors += 1
            elif winner is None:
                match.draws += 1
            elif winner == a_seat:
                match.wins_a += 1
            else:
                match.wins_b += 1
        log.info("game %d/%d: %s", i + 1, games, match)

    if parallel <= 1:
        for i in range(games):
            one(i)
    else:
        with ThreadPoolExecutor(parallel) as pool:
            list(pool.map(one, range(games)))
    return match


def elo_ratings(matches: Sequence[MatchResult], k: float = 24.0, base: float = 1500.0) -> Dict[str, float]:
    """Sequential Elo over every game of the given matches (a simple leaderboard metric)."""
    ratings: Dict[str, float] = {}
    for m in matches:
        ratings.setdefault(m.a, base)
        ratings.setdefault(m.b, base)
        for g in m.games:
            if g.get("error"):
                continue
            winner = g.get("winner_seat")
            score_a = 0.5 if winner is None else (1.0 if winner == g.get("a_seat") else 0.0)
            ra, rb = ratings[m.a], ratings[m.b]
            expected_a = 1.0 / (1.0 + 10 ** ((rb - ra) / 400.0))
            ratings[m.a] = ra + k * (score_a - expected_a)
            ratings[m.b] = rb + k * ((1 - score_a) - (1 - expected_a))
    return dict(sorted(ratings.items(), key=lambda kv: -kv[1]))


def round_robin(players: Dict[str, AgentSpec], *, games: int = 10, **match_options: Any) -> Tuple[List[MatchResult], Dict[str, float]]:
    """Every pair of players plays a match. Returns the matches and Elo ratings.

    ``players`` maps display names to specs, e.g. ``{"random": RandomAgent, "mad": "xmage:3"}``.
    """
    matches = []
    for (name_a, spec_a), (name_b, spec_b) in itertools.combinations(players.items(), 2):
        m = run_match(spec_a, spec_b, games=games, **match_options)
        m.a, m.b = name_a, name_b
        log.info("%s", m)
        matches.append(m)
    return matches, elo_ratings(matches)
