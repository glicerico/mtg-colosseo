"""Agent interface and the loop that connects an agent to a seat."""
from __future__ import annotations

import logging
import time
import traceback
from typing import Any, Dict, Optional

from .client import ColosseoClient, ColosseoError, DEFAULT_SERVER
from .protocol import Action, Decision

__all__ = ["Agent", "play", "GameResult", "outcome", "is_terminal"]

log = logging.getLogger("colosseo")

GameResult = Dict[str, Any]


def outcome(result: Optional[GameResult]) -> str:
    """Classifies a game result: ``"win"`` (``winner_seat`` is set), ``"draw"`` or ``"unfinished"``.

    Only a game the rules engine ended by itself counts: results with a non-``finished`` ``status``
    (``terminated``, ``abandoned``, ``timeout``, ``error``, or ``interrupted`` from :func:`play`), an
    ``error``, or neither a winner nor an explicit ``draw: true`` are ``"unfinished"`` and must not be scored.
    """
    if not result:
        return "unfinished"
    status = result.get("status")
    if status is not None and status != "finished":
        return "unfinished"
    if result.get("error"):
        return "unfinished"
    if result.get("winner_seat") is not None:
        return "win"
    if result.get("draw") is True:
        return "draw"
    return "unfinished"


def is_terminal(result: Optional[GameResult]) -> bool:
    return outcome(result) != "unfinished"


class Agent:
    """Base class for agents.

    Implement :meth:`decide`; every other hook is optional. One agent instance plays one seat at a time
    (the runner creates separate instances when the same agent class plays both seats).

    ``decide`` receives a :class:`~colosseo.protocol.Decision` and returns an
    :class:`~colosseo.protocol.Action` built with the decision's helpers, e.g. ``decision.choose(opt)``,
    ``decision.attack([...])`` or ``decision.default()``.
    """

    #: shown in the lobby and game records
    name: str = "agent"

    def on_game_start(self, info: Dict[str, Any]) -> None:
        """Called once with the server's ``hello`` message (game id, seats, initial state)."""

    def decide(self, decision: Decision) -> Action:
        raise NotImplementedError

    def on_event(self, message: Dict[str, Any]) -> None:
        """Every non-decision message: ``state`` updates, ``log`` entries, opponent ``action`` notices, ``info``.

        Opponent actions are redacted by the server (cards chosen from hidden zones read "a hidden card").
        """

    def on_game_end(self, result: GameResult) -> None:
        """Called with the final result (``status``, ``winner_seat``, ``draw``, ``turns``, ...).

        Also called when the connection could not be recovered, with ``status == "interrupted"``.
        """


def play(agent: Agent, game_id: str, seat: int, server: str = DEFAULT_SERVER,
         token: Optional[str] = None, max_retries: int = 3, reconnect_timeout: float = 60.0,
         client: Optional[ColosseoClient] = None) -> GameResult:
    """Plays one seat of an existing game until it ends. Blocks; returns the game result.

    If the engine rejects an answer (illegal choice), the agent is asked again with
    ``decision.rejection`` set; after ``max_retries`` rejections the decision's default is used.
    Exceptions raised by the agent are logged and answered with the default action.

    If the connection drops, ``play`` reconnects for up to ``reconnect_timeout`` seconds and re-sends an
    answer that may have been lost. A game that ended meanwhile is returned (once) with its real result.
    When the game can't be recovered, the result has ``status == "interrupted"`` (or ``"error"`` if the
    server refused the connection): it has no winner, is not a draw and must not be scored (see
    :func:`outcome`). The server-side game keeps running until it is stopped or abandoned.
    """
    client = client or ColosseoClient(server)
    answered: Dict[int, Dict[str, Any]] = {}  # decision id -> message sent
    state = {"pending": None, "retries": 0, "started": False}
    give_up_at: Optional[float] = None
    delay = 0.5
    problem = "connection lost"

    while True:
        conn = None
        try:
            conn = client.connect_seat(game_id, seat, token)
        except Exception as e:  # noqa: BLE001 - network errors while (re)connecting
            problem = f"can't connect: {e}"
        if conn is not None:
            try:
                result = _session(agent, conn, seat, answered, state, max_retries)
            finally:
                conn.close()
            if result is not None:
                return result
            if conn.refused:
                result = _unfinished(game_id, "error", f"server refused seat {seat}: {conn.close_reason or conn.close_code}")
                log.error("seat %s: %s", seat, result["error"])
                agent.on_game_end(result)
                return result
            detail = " ".join(str(x) for x in (conn.close_code, conn.close_reason) if x)
            problem = "connection lost" + (f" ({detail})" if detail else "")
            delay = 0.5  # the connection worked: start backing off from scratch

        # the game may have ended while we were not listening
        try:
            info = client.game(game_id)
        except ColosseoError as e:
            if e.status == 404:
                result = _unfinished(game_id, "error", "game no longer exists on the server")
                agent.on_game_end(result)
                return result
            info = None
        if info and info.get("result"):
            result = dict(info["result"])
            agent.on_game_end(result)
            return result

        now = time.monotonic()
        if give_up_at is None:
            give_up_at = now + reconnect_timeout
            log.warning("seat %s: %s, reconnecting", seat, problem)
        if now >= give_up_at:
            result = _unfinished(game_id, "interrupted", f"{problem}; no recovery within {reconnect_timeout:g}s")
            log.error("seat %s: %s", seat, result["error"])
            agent.on_game_end(result)
            return result
        time.sleep(min(delay, max(0.0, give_up_at - now)))
        delay = min(delay * 2, 5.0)


def _unfinished(game_id: str, status: str, reason: str) -> GameResult:
    return {"status": status, "error": reason, "reason": reason, "winner_seat": None, "winner": None,
            "draw": False, "game_id": game_id}


def _session(agent: Agent, conn, seat: int, answered: Dict[int, Dict[str, Any]], state: Dict[str, Any],
             max_retries: int) -> Optional[GameResult]:
    """Handles one connection. Returns the result when the game is over, None when the connection closed."""
    this_connection: set = set()
    for msg in conn.messages():
        kind = msg.get("type")
        if kind == "decision":
            did = msg["decision_id"]
            if did in this_connection:
                continue  # duplicate delivery on this connection
            if did in answered:
                # re-delivered after a reconnect: our answer may have been lost, send it again (a stale
                # answer is harmlessly rejected by the server)
                this_connection.add(did)
                conn.send(answered[did])
                continue
            state["pending"] = Decision(msg)
            state["retries"] = 0
            _answer(agent, conn, state["pending"], answered, this_connection)
        elif kind == "error":
            pending: Optional[Decision] = state["pending"]
            did = msg.get("decision_id")
            stale = msg.get("code") == "stale" or (
                msg.get("code") is None and any(s in (msg.get("message") or "") for s in ("stale", "already answered", "no decision pending")))
            if pending is not None and did == pending.id and not stale:
                state["retries"] += 1
                pending.rejection = msg.get("message")
                log.warning("seat %s: action rejected (%s)", seat, pending.rejection)
                if state["retries"] >= max_retries:
                    message = pending.default().to_message(pending.id)
                    conn.send(message)
                    answered[pending.id] = message
                else:
                    _answer(agent, conn, pending, answered, this_connection)
            else:
                log.debug("seat %s: server error: %s", seat, msg.get("message"))
        elif kind == "hello":
            if not state["started"]:
                state["started"] = True
                agent.on_game_start(msg)
        elif kind == "game_over":
            result = dict(msg.get("result") or {})
            agent.on_game_end(result)
            return result
        else:
            agent.on_event(msg)
    return None


def _answer(agent: Agent, conn, decision: Decision, answered: Dict[int, Dict[str, Any]], this_connection: set) -> None:
    try:
        action = agent.decide(decision)
        if action is None:
            action = decision.default()
    except Exception:  # never let a buggy agent freeze the game
        log.error("agent %s failed on %r:\n%s", getattr(agent, "name", agent), decision, traceback.format_exc())
        action = decision.default()
    message = action.to_message(decision.id)
    answered[decision.id] = message
    this_connection.add(decision.id)
    conn.send(message)
