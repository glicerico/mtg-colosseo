"""A step/reset environment for reinforcement learning style loops.

>>> env = ColosseoEnv(deck="fdn:azorius-skies", opponent="xmage:1", opponent_deck="fdn:gruul-stompers")
>>> decision = env.reset()
>>> while decision is not None:
...     action = my_policy(decision)           # returns decision.choose(...), .attack(...), ...
...     decision, reward, done, info = env.step(action)

The environment controls seat 0. The opponent is XMage's AI (``"xmage"`` / ``"xmage:N"``) running in the
server, or any :class:`~colosseo.agent.Agent` (driven from a background thread).
Reward is +1 for a win, -1 for a loss and 0 otherwise (draws, unfinished games).
"""
from __future__ import annotations

import threading
from typing import Any, Dict, List, Optional, Tuple, Union

from .agent import Agent, play
from .client import ColosseoClient, DEFAULT_SERVER, SeatConnection
from .protocol import Action, Decision

__all__ = ["ColosseoEnv"]


class ColosseoEnv:
    def __init__(self, deck: str, opponent: Union[str, Agent] = "xmage:1", opponent_deck: Optional[str] = None, *,
                 server: str = DEFAULT_SERVER, name: str = "env-agent", seat_options: Optional[Dict[str, Any]] = None,
                 **game_options: Any):
        self.client = ColosseoClient(server)
        self.server = server
        self.deck = deck
        self.opponent = opponent
        self.opponent_deck = opponent_deck or deck
        self.name = name
        self.seat_options = seat_options or {"stop_policy": "all"}
        self.game_options = {"record": True, **game_options}
        self.conn: Optional[SeatConnection] = None
        self.game_id: Optional[str] = None
        self.result: Optional[Dict[str, Any]] = None
        self._opponent_thread: Optional[threading.Thread] = None
        self._decision: Optional[Decision] = None

    def reset(self) -> Optional[Decision]:
        """Starts a new game and returns the first decision."""
        self.close()
        me = {"type": "agent", "deck": self.deck, "name": self.name, **self.seat_options}
        if isinstance(self.opponent, Agent):
            opp = {"type": "agent", "deck": self.opponent_deck, "name": self.opponent.name,
                   **(getattr(self.opponent, "seat_options", {}) or {})}
        elif self.opponent.startswith("xmage"):
            skill = int(self.opponent.split(":", 1)[1]) if ":" in self.opponent else 1
            opp = {"type": "xmage", "deck": self.opponent_deck, "skill": skill}
        else:
            raise ValueError("opponent must be an Agent or 'xmage[:skill]'")
        created = self.client.create_game([me, opp], **self.game_options)
        self.game_id = created["game_id"]
        self.result = None
        if isinstance(self.opponent, Agent):
            token = created["seats"][1].get("token")
            self._opponent_thread = threading.Thread(
                target=play, args=(self.opponent, self.game_id, 1, self.server, token), daemon=True)
            self._opponent_thread.start()
        self.conn = self.client.connect_seat(self.game_id, 0, created["seats"][0].get("token"))
        decision, _events = self._next()
        return decision

    def step(self, action: Action) -> Tuple[Optional[Decision], float, bool, Dict[str, Any]]:
        """Answers the current decision; returns (next decision, reward, done, info)."""
        if self.conn is None or self._decision is None:
            raise RuntimeError("call reset() first (or the game is over)")
        self.conn.send(action.to_message(self._decision.id))
        decision, events = self._next(previous=self._decision)
        info: Dict[str, Any] = {"events": events, "game_id": self.game_id}
        if decision is None:
            info["result"] = self.result
            return None, self._reward(), True, info
        if decision.rejection:
            info["rejected"] = decision.rejection
        return decision, 0.0, False, info

    def _next(self, previous: Optional[Decision] = None) -> Tuple[Optional[Decision], List[Dict[str, Any]]]:
        events: List[Dict[str, Any]] = []
        assert self.conn is not None
        while True:
            msg = self.conn.recv()
            kind = msg.get("type")
            if kind == "decision":
                if previous is not None and msg["decision_id"] == previous.id:
                    continue
                self._decision = Decision(msg)
                return self._decision, events
            if kind == "error" and previous is not None and msg.get("decision_id") == previous.id:
                previous.rejection = msg.get("message")
                self._decision = previous
                return previous, events
            if kind == "game_over":
                self.result = msg.get("result")
                self._decision = None
                return None, events
            events.append(msg)

    def _reward(self) -> float:
        if not self.result or self.result.get("winner_seat") is None:
            return 0.0
        return 1.0 if self.result["winner_seat"] == 0 else -1.0

    def close(self) -> None:
        if self.conn is not None:
            if self._decision is not None and self.game_id:
                try:
                    self.client.terminate(self.game_id)
                except Exception:  # noqa: BLE001
                    pass
            self.conn.close()
            self.conn = None
        self._decision = None
