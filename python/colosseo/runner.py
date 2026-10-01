"""Running games, matches and tournaments between agents and XMage's AI."""
from __future__ import annotations

import itertools
import logging
import queue
import random
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple, Union

from .agent import Agent, GameResult, is_terminal, outcome, play
from .client import ColosseoClient, ColosseoError, DEFAULT_SERVER

__all__ = ["AgentSpec", "run_game", "run_match", "round_robin", "MatchResult", "ScheduledGame", "match_schedule",
           "elo_ratings", "wait_for_server"]

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
             record: bool = True, title: Optional[str] = None, deadline_s: Optional[float] = None,
             reconnect_timeout: float = 60.0, client: Optional[ColosseoClient] = None,
             on_created: Optional[Callable[[Dict[str, Any]], None]] = None,
             **options: Any) -> GameResult:
    """Plays one game and returns its result.

    ``player0``/``player1`` are agents or built-ins (``"xmage"``, ``"xmage:5"``, ``"human"``).
    Agent seats are driven from threads in this process; XMage seats run inside the server.
    ``seed`` and ``starting_seat`` are passed to the engine (the engine picks and records a seed when none
    is given). ``deadline_s`` stops the game server-side after that many seconds. Extra keyword arguments
    are passed to the server as game options (e.g. ``require_tokens=True``).

    If the game can't be followed to its end (connection lost and not recovered), the server-side game is
    stopped and the returned result is unfinished (``status`` is not ``"finished"``; see
    :func:`~colosseo.agent.outcome`).
    """
    client = client or ColosseoClient(server)
    p0, p1 = _instantiate(player0), _instantiate(player1)
    seats = [_seat(p0, deck0, names[0]), _seat(p1, deck1 or deck0, names[1])]
    game_options: Dict[str, Any] = {"starting_seat": starting_seat, "max_turns": max_turns, "pace_ms": pace_ms,
                                    "record": record, **options}
    if seed is not None:
        game_options["seed"] = seed
    if title:
        game_options["title"] = title
    if deadline_s:
        game_options["deadline_s"] = deadline_s
    created = client.create_game(seats, **game_options)
    game_id = created["game_id"]
    owner_token = created.get("owner_token")
    if on_created:
        on_created(created)
    for s in created["seats"]:
        if s["type"] == "human":
            url = server.rstrip("/") + (s.get("url") or f"/#/game/{game_id}/seat/{s['seat']}")
            log.warning("seat %s is human: open %s", s["seat"], url)

    # Seat workers report through a queue so that the first unrecoverable seat triggers cleanup right away:
    # the other seat is usually still connected, waiting for a move that will never come.
    outcomes: "queue.Queue[GameResult]" = queue.Queue()
    workers = 0
    for index, player in enumerate((p0, p1)):
        if isinstance(player, Agent):
            token = created["seats"][index].get("token")

            def work(a: Agent = player, i: int = index, tk: Optional[str] = token) -> None:
                try:
                    r = play(a, game_id, i, server, tk, reconnect_timeout=reconnect_timeout, client=client)
                except Exception as e:  # noqa: BLE001 - e.g. an exception from an agent hook
                    log.error("game %s: seat %s worker failed: %r", game_id, i, e)
                    r = {"status": "error", "error": f"seat {i} worker failed: {e!r}", "winner_seat": None,
                         "draw": False}
                outcomes.put(r)

            threading.Thread(target=work, name=f"colosseo-{game_id}-seat{index}", daemon=True).start()
            workers += 1

    results: List[GameResult] = []
    server_result: Optional[GameResult] = None
    server_status: Optional[str] = None
    if workers:
        results.append(outcomes.get())
        if not is_terminal(results[0]):
            server_result, server_status = _reconcile(client, game_id, owner_token)
        # the remaining seat ends promptly (game over, or the game was just stopped); never wait forever
        give_up = time.monotonic() + reconnect_timeout + 15
        while len(results) < workers:
            try:
                results.append(outcomes.get(timeout=max(0.0, give_up - time.monotonic())))
            except queue.Empty:
                log.warning("game %s: a seat worker did not finish; giving up on it", game_id)
                break
    else:
        # no agent seat in this process (e.g. xmage vs xmage): wait for the server
        results.append(_wait_for_result(client, game_id, reconnect_timeout))

    # one result per game: the server's terminal result, else a seat's terminal one (both seats normally
    # report the same), else the unfinished one
    terminal = server_result or next((r for r in results if is_terminal(r)), None)
    result = dict(terminal or results[0])
    if terminal is None:
        if server_status is None:
            server_result, server_status = _reconcile(client, game_id, owner_token)
            if server_result:
                result = dict(server_result)
        if server_status and not server_result:
            result.setdefault("server_status", server_status)
    result["game_id"] = game_id
    return result


def _reconcile(client: ColosseoClient, game_id: str, owner_token: Optional[str],
               settle_s: float = 10.0) -> Tuple[Optional[GameResult], Optional[str]]:
    """After a seat failed: returns the server's terminal result if the game finished after all, otherwise
    stops the still-running server game. Returns (terminal result or None, server status).

    The game can finish on its own between the status check and the stop request, so the stop response
    (and, if it isn't conclusive yet, a few more status checks within ``settle_s``) decides: a finished
    result is kept, a stopped game stays unfinished, and an unconfirmed stop is reported as
    ``"stop_requested"`` rather than assumed.
    """
    try:
        info = client.game(game_id)
        if not info.get("result") and info.get("status") in ("running", "created"):
            info = client.terminate(game_id, owner_token) or {}
            give_up = time.monotonic() + settle_s
            while not info.get("result") and time.monotonic() < give_up:
                time.sleep(0.5)
                info = client.game(game_id)
        result = info.get("result")
        if result and is_terminal(result):
            return dict(result), info.get("status")
        if result:
            return None, result.get("status") or info.get("status")
        return None, "stop_requested" if info.get("status") in ("running", "created", None) else info.get("status")
    except ColosseoError as e:
        log.warning("game %s: can't reconcile with the server: %s", game_id, e)
        return None, None


def _wait_for_result(client: ColosseoClient, game_id: str, unreachable_timeout: float) -> GameResult:
    failing_since: Optional[float] = None
    while True:
        try:
            info = client.game(game_id)
            failing_since = None
            if info.get("result"):
                return info["result"]
        except ColosseoError as e:
            now = time.monotonic()
            failing_since = failing_since or now
            if e.status == 404 or now - failing_since > unreachable_timeout:
                return {"status": "interrupted", "error": f"lost track of the game: {e}", "winner_seat": None,
                        "draw": False}
        time.sleep(0.5)


@dataclass(frozen=True)
class ScheduledGame:
    """One planned game of a match: who sits where with which deck, who starts and the engine seed."""
    index: int
    deck_a: str
    deck_b: str
    #: seat (0/1) played by player A
    a_seat: int
    #: seat that takes the first turn
    starting_seat: int
    seed: int

    @property
    def a_starts(self) -> bool:
        return self.starting_seat == self.a_seat


def match_schedule(games: int, decks: Union[str, Sequence[str], Sequence[Tuple[str, str]]], *,
                   seed: int, swap_seats: bool = True, swap_decks: bool = False) -> List[ScheduledGame]:
    """Plans a match deterministically from ``seed`` (independent of parallel execution).

    Games come in blocks that share a deck pairing: within a block the starting player is reversed, so
    A and B each start once with the same decks (paired comparison); with ``swap_decks`` the block has four
    games and the players also swap decks. With ``swap_seats`` the players alternate seats from game to
    game (seat 0 vs 1 matters to nothing in the rules, but it keeps seat-dependent code honest).
    ``decks``: one deck id (mirror), a list of deck ids (each block draws a random ordered pair) or a list
    of ``(deck_a, deck_b)`` pairs used in turn. Every game gets its own seed derived from ``seed``.
    """
    rng = random.Random(seed)
    if isinstance(decks, str):
        pairs: Optional[List[Tuple[str, str]]] = [(decks, decks)]
        pool: List[str] = []
    elif decks and isinstance(decks[0], (tuple, list)):
        pairs = [(str(p[0]), str(p[1])) for p in decks]  # type: ignore[index]
        pool = []
    else:
        pairs, pool = None, [str(d) for d in decks]  # type: ignore[union-attr]
        if not pool:
            raise ValueError("no decks to schedule")
    block_size = 4 if swap_decks else 2
    schedule: List[ScheduledGame] = []
    block = -1
    deck_a = deck_b = ""
    for i in range(games):
        if i % block_size == 0:
            block += 1
            if pairs is not None:
                deck_a, deck_b = pairs[block % len(pairs)]
            else:
                deck_a, deck_b = rng.choice(pool), rng.choice(pool)
        k = i % block_size
        a_starts = k % 2 == 0
        swapped = k >= 2
        a_seat = i % 2 if swap_seats else 0
        starting = a_seat if a_starts else 1 - a_seat
        da, db = (deck_b, deck_a) if swapped else (deck_a, deck_b)
        schedule.append(ScheduledGame(i, da, db, a_seat, starting, rng.randrange(2 ** 62)))
    return schedule


@dataclass
class MatchResult:
    """Outcome of a series of games between two players (``a`` and ``b``).

    Only games the engine finished count as wins or draws; games that were interrupted, stopped or
    crashed count as ``errors`` and are excluded from scores and ratings.
    """
    a: str
    b: str
    wins_a: int = 0
    wins_b: int = 0
    draws: int = 0
    errors: int = 0
    games: List[GameResult] = field(default_factory=list)
    #: the match seed (generated when none was given, so the schedule can be reproduced)
    seed: Optional[int] = None
    schedule: List[ScheduledGame] = field(default_factory=list)

    @property
    def played(self) -> int:
        return len(self.games)

    @property
    def score_a(self) -> float:
        """Match score for ``a`` in [0, 1] (draws count half)."""
        n = self.wins_a + self.wins_b + self.draws
        return (self.wins_a + 0.5 * self.draws) / n if n else 0.5

    def record(self, game: GameResult) -> None:
        """Adds a game result (with ``a_seat``) to the tallies."""
        self.games.append(game)
        kind = outcome(game)
        if kind == "unfinished":
            self.errors += 1
        elif kind == "draw":
            self.draws += 1
        elif game.get("winner_seat") == game.get("a_seat"):
            self.wins_a += 1
        else:
            self.wins_b += 1

    def __str__(self) -> str:
        return (f"{self.a} vs {self.b}: {self.wins_a}-{self.wins_b}"
                + (f" ({self.draws} draws)" if self.draws else "")
                + (f" [{self.errors} unfinished]" if self.errors else "")
                + f" over {self.played} games")


def run_match(player_a: AgentSpec, player_b: AgentSpec, *, games: int = 10,
              decks: Union[str, Sequence[str], Sequence[Tuple[str, str]], None] = None,
              server: str = DEFAULT_SERVER, swap_seats: bool = True, swap_decks: bool = False, parallel: int = 1,
              seed: Optional[int] = None, client: Optional[ColosseoClient] = None,
              **game_options: Any) -> MatchResult:
    """Plays a series of games following :func:`match_schedule`.

    The schedule (deck pairings, seats, who starts, engine seeds) is computed up front from ``seed``, so
    the same seed gives the same plan whatever ``parallel`` is; it is stored in ``MatchResult.schedule``
    and every game result carries its ``index``, ``seed``, ``starting_seat``, ``a_seat`` and decks.
    Use an even number of games so that A and B start equally often. ``decks`` defaults to every deck on
    the server. Agent classes/factories get a fresh instance per game; agent instances are shared.

    Gameplay itself is only best-effort reproducible: XMage shares one RNG between concurrent games and
    its AI is time-bounded (see docs/agents.md).

    Match games are created with ``require_tokens=True`` (unless you pass ``require_tokens=False``): even on
    an open local server, an agent can then neither control nor watch the other seat with hands revealed.
    """
    game_options.setdefault("require_tokens", True)
    client = client or ColosseoClient(server)
    if seed is None:
        seed = random.SystemRandom().randrange(2 ** 62)
    if decks is None:
        decks = sorted(d["id"] for d in client.decks())
    plan = match_schedule(games, decks, seed=seed, swap_seats=swap_seats, swap_decks=swap_decks)

    name_a, name_b = _spec_name(player_a), _spec_name(player_b)
    if name_a == name_b:
        name_a, name_b = name_a + " (A)", name_b + " (B)"
    match = MatchResult(name_a, name_b, seed=seed, schedule=plan)
    lock = threading.Lock()
    done: List[GameResult] = []

    def one(g: ScheduledGame) -> None:
        players = [player_a, player_b] if g.a_seat == 0 else [player_b, player_a]
        seat_decks = [g.deck_a, g.deck_b] if g.a_seat == 0 else [g.deck_b, g.deck_a]
        seat_names = [name_a, name_b] if g.a_seat == 0 else [name_b, name_a]
        try:
            result = run_game(players[0], players[1], seat_decks[0], seat_decks[1], server=server,
                              names=seat_names, seed=g.seed, starting_seat=g.starting_seat, client=client,
                              **game_options)
        except Exception as e:  # noqa: BLE001
            log.error("game %d failed: %s", g.index, e)
            result = {"status": "error", "error": str(e), "winner_seat": None, "draw": False}
        # the engine reports the seed and starting seat it actually used; the plan is kept next to them
        result.update({"index": g.index, "a_seat": g.a_seat, "deck_a": g.deck_a, "deck_b": g.deck_b,
                       "seed": result.get("seed", g.seed), "planned_seed": g.seed,
                       "starting_seat": result.get("starting_seat", g.starting_seat),
                       "planned_starting_seat": g.starting_seat})
        with lock:
            done.append(result)
            log.info("game %d/%d done (%s)", len(done), games, outcome(result))

    if parallel <= 1:
        for g in plan:
            one(g)
    else:
        with ThreadPoolExecutor(parallel) as pool:
            list(pool.map(one, plan))
    for result in sorted(done, key=lambda r: r["index"]):
        match.record(result)
    return match


def elo_ratings(matches: Sequence[MatchResult], k: float = 24.0, base: float = 1500.0) -> Dict[str, float]:
    """Sequential Elo over every finished game of the given matches, in schedule order (a simple
    leaderboard metric). Unfinished games (interrupted, stopped, crashed) never change ratings."""
    ratings: Dict[str, float] = {}
    for m in matches:
        ratings.setdefault(m.a, base)
        ratings.setdefault(m.b, base)
        for g in sorted(m.games, key=lambda r: r.get("index", 0)):
            kind = outcome(g)
            if kind == "unfinished":
                continue
            score_a = 0.5 if kind == "draw" else (1.0 if g.get("winner_seat") == g.get("a_seat") else 0.0)
            ra, rb = ratings[m.a], ratings[m.b]
            expected_a = 1.0 / (1.0 + 10 ** ((rb - ra) / 400.0))
            ratings[m.a] = ra + k * (score_a - expected_a)
            ratings[m.b] = rb + k * ((1 - score_a) - (1 - expected_a))
    return dict(sorted(ratings.items(), key=lambda kv: -kv[1]))


def round_robin(players: Dict[str, AgentSpec], *, games: int = 10, **match_options: Any) -> Tuple[List[MatchResult], Dict[str, float]]:
    """Every pair of players plays a match. Returns the matches and Elo ratings.

    ``players`` maps display names to specs, e.g. ``{"random": RandomAgent, "mad": "xmage:3"}``.
    With a ``seed``, every pairing plays the same schedule (common random numbers).
    """
    if match_options.get("seed") is None:
        match_options["seed"] = random.SystemRandom().randrange(2 ** 62)
    matches = []
    for (name_a, spec_a), (name_b, spec_b) in itertools.combinations(players.items(), 2):
        m = run_match(spec_a, spec_b, games=games, **match_options)
        m.a, m.b = name_a, name_b
        log.info("%s", m)
        matches.append(m)
    return matches, elo_ratings(matches)
