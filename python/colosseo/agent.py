"""Agent interface and the loop that connects an agent to a seat."""
from __future__ import annotations

import logging
import traceback
from typing import Any, Dict, Optional

from .client import ColosseoClient, DEFAULT_SERVER
from .protocol import Action, Decision

__all__ = ["Agent", "play", "GameResult"]

log = logging.getLogger("colosseo")

GameResult = Dict[str, Any]


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
        """Every non-decision message: ``state`` updates, ``log`` entries, opponent ``action`` notices, ``info``."""

    def on_game_end(self, result: GameResult) -> None:
        """Called with the final result (``winner_seat``, ``turns``, ...)."""


def play(agent: Agent, game_id: str, seat: int, server: str = DEFAULT_SERVER,
         token: Optional[str] = None, max_retries: int = 3) -> GameResult:
    """Plays one seat of an existing game until it ends. Blocks; returns the game result.

    If the engine rejects an answer (illegal choice), the agent is asked again with
    ``decision.rejection`` set; after ``max_retries`` rejections the decision's default is used.
    Exceptions raised by the agent are logged and answered with the default action.
    """
    client = ColosseoClient(server)
    conn = client.connect_seat(game_id, seat, token)
    answered: set = set()
    pending: Optional[Decision] = None
    retries = 0
    try:
        for msg in conn.messages():
            kind = msg.get("type")
            if kind == "decision":
                if msg["decision_id"] in answered:
                    continue  # duplicate delivery (e.g. on connect)
                pending = Decision(msg)
                retries = 0
                _answer(agent, conn, pending, answered)
            elif kind == "error":
                did = msg.get("decision_id")
                if pending is not None and did == pending.id:
                    retries += 1
                    answered.discard(pending.id)
                    pending.rejection = msg.get("message")
                    log.warning("seat %s: action rejected (%s)", seat, pending.rejection)
                    if retries >= max_retries:
                        conn.send(pending.default().to_message(pending.id))
                        answered.add(pending.id)
                    else:
                        _answer(agent, conn, pending, answered)
                else:
                    log.debug("seat %s: server error: %s", seat, msg.get("message"))
            elif kind == "hello":
                agent.on_game_start(msg)
                if msg.get("game", {}).get("result"):
                    pass
            elif kind == "game_over":
                result = msg.get("result", {})
                agent.on_game_end(result)
                return result
            else:
                agent.on_event(msg)
    finally:
        conn.close()
    # connection lost: ask the server for the outcome
    return client.game(game_id).get("result") or {}


def _answer(agent: Agent, conn, decision: Decision, answered: set) -> None:
    try:
        action = agent.decide(decision)
        if action is None:
            action = decision.default()
    except Exception:  # never let a buggy agent freeze the game
        log.error("agent %s failed on %r:\n%s", getattr(agent, "name", agent), decision, traceback.format_exc())
        action = decision.default()
    conn.send(action.to_message(decision.id))
    answered.add(decision.id)
