"""Typed-ish wrappers around the Colosseo JSON protocol.

The engine sends a ``decision`` message whenever a seat must choose something. Every decision has a
``kind``, a human readable ``prompt``, a list of ``options`` and the current game ``state`` as seen by
that seat. An agent answers with an :class:`Action`.

Decision kinds (see ``docs/protocol.md`` for the full reference):

=====================  =====================================================================
kind                   answer
=====================  =====================================================================
``priority``           ``choice``: ``"pass"``, ``"pass_turn"`` or the id of a legal action
``mulligan``           ``choice``: ``"keep"`` or ``"mulligan"``
``yes_no``             ``choice``: ``"yes"`` or ``"no"``
``target``             ``choice``: one option id (``"done"`` to finish) or ``choices``: [ids]
``choose_ability``     ``choice``: ability id or ``"cancel"``
``choose_mode``        ``choice``: mode id
``choose_choice``      ``choice``: option id (colors, creature types, card names, ...)
``trigger_order``      ``choice``: id of the triggered ability to put on the stack first
``amount``             ``amount``: integer in ``[min, max]``
``multi_amount``       ``amounts``: list of integers (one per item)
``choose_pile``        ``choice``: ``"pile1"`` or ``"pile2"``
``pay_mana``           ``choice``: mana source id, ``"pool:W"``..., ``"special"`` or ``"cancel"``
``declare_attackers``  ``attackers``: [{"attacker": id, "defender": id}]
``declare_blockers``   ``blocks``: [{"blocker": id, "attacker": id}]
=====================  =====================================================================
"""
from __future__ import annotations

from typing import Any, Dict, Iterable, Iterator, List, Optional, Sequence, Union

__all__ = ["Obj", "Option", "Action", "Decision", "State", "PlayerState"]


class Obj(dict):
    """A dict with attribute access (``card.name``). Missing keys return ``None``."""

    def __getattr__(self, key: str) -> Any:
        try:
            value = self[key]
        except KeyError:
            if key.startswith("__"):
                raise AttributeError(key)
            return None
        return wrap(value)

    def __setattr__(self, key: str, value: Any) -> None:
        self[key] = value


def wrap(value: Any) -> Any:
    if isinstance(value, dict) and not isinstance(value, Obj):
        return Obj(value)
    if isinstance(value, list):
        return [wrap(v) for v in value]
    return value


class Option(Obj):
    """One legal option of a decision (``id``, ``label`` and kind specific fields)."""

    @property
    def id(self) -> str:  # type: ignore[override]
        return self["id"]

    @property
    def label(self) -> str:
        return self.get("label", self["id"])

    def __repr__(self) -> str:
        return f"Option({self.id!r}, {self.label!r})"


class Action:
    """An answer to a decision. Build them with the helpers on :class:`Decision`."""

    def __init__(self, payload: Dict[str, Any], comment: Optional[str] = None):
        self.payload = dict(payload)
        self.comment = comment

    def with_comment(self, comment: str) -> "Action":
        """Attach a free-text rationale; it is shown to spectators and stored in game records."""
        return Action(self.payload, comment)

    def to_message(self, decision_id: int) -> Dict[str, Any]:
        msg = {"type": "action", "decision_id": decision_id, **self.payload}
        if self.comment:
            msg["comment"] = self.comment
        return msg

    def __repr__(self) -> str:
        return f"Action({self.payload!r})"


OptionRef = Union[str, Option, Dict[str, Any]]


def _oid(o: OptionRef) -> str:
    return o if isinstance(o, str) else o["id"]


class PlayerState(Obj):
    """A player in the observation. ``hand`` is only present for yourself (or omniscient spectators)."""

    def permanents(self, *types: str) -> List[Obj]:
        perms = [Obj(p) for p in self.get("battlefield", [])]
        if not types:
            return perms
        return [p for p in perms if any(t in (p.get("types") or []) for t in types)]

    def creatures(self) -> List[Obj]:
        return self.permanents("Creature")

    def lands(self) -> List[Obj]:
        return self.permanents("Land")

    def untapped_lands(self) -> List[Obj]:
        return [p for p in self.lands() if not p.get("tapped")]


class State(Obj):
    """The game state as seen by one seat."""

    @property
    def players(self) -> List[PlayerState]:  # type: ignore[override]
        return [PlayerState(p) for p in self.get("players", [])]

    @property
    def me(self) -> Optional[PlayerState]:
        you = self.get("you")
        for p in self.players:
            if p.get("id") == you:
                return p
        return None

    @property
    def opponent(self) -> Optional[PlayerState]:
        you = self.get("you")
        others = [p for p in self.players if p.get("id") != you]
        return others[0] if others else None

    def player(self, player_id: str) -> Optional[PlayerState]:
        for p in self.players:
            if p.get("id") == player_id:
                return p
        return None

    def find(self, object_id: str) -> Optional[Obj]:
        """Finds a permanent, hand/graveyard card or stack object by id."""
        for p in self.players:
            for zone in ("battlefield", "hand", "graveyard"):
                for c in p.get(zone, []) or []:
                    if c.get("id") == object_id:
                        return Obj(c)
        for s in self.get("stack", []):
            if s.get("id") == object_id:
                return Obj(s)
        for c in self.get("exile", []):
            if c.get("id") == object_id:
                return Obj(c)
        return None

    @property
    def is_my_turn(self) -> bool:
        return self.get("active_player") == self.get("you")


class Decision:
    """A decision the engine is waiting for. ``raw`` holds the full JSON message."""

    def __init__(self, message: Dict[str, Any]):
        self.raw = message
        self.id: int = message["decision_id"]
        self.game_id: str = message.get("game_id", "")
        self.seat: int = message.get("seat", -1)
        self.kind: str = message["kind"]
        self.prompt: str = message.get("prompt", "")
        self.options: List[Option] = [Option(o) for o in message.get("options", [])]
        self.state = State(message.get("state") or {})
        self.log: List[Obj] = [Obj(e) for e in message.get("log", [])]
        self.error: Optional[str] = message.get("error")
        #: set by the client when the engine rejected a previous answer to this decision
        self.rejection: Optional[str] = None

    # --- lookup -------------------------------------------------------------------------------

    def __getitem__(self, key: str) -> Any:
        return wrap(self.raw.get(key))

    def get(self, key: str, default: Any = None) -> Any:
        return wrap(self.raw.get(key, default))

    @property
    def option_ids(self) -> List[str]:
        return [o.id for o in self.options]

    def option(self, option_id: str) -> Optional[Option]:
        for o in self.options:
            if o.id == option_id:
                return o
        return None

    def options_where(self, **fields: Any) -> List[Option]:
        """Options whose fields match, e.g. ``d.options_where(action="cast")``."""
        return [o for o in self.options if all(o.get(k) == v for k, v in fields.items())]

    @property
    def min(self) -> Optional[int]:
        return self.raw.get("min")

    @property
    def max(self) -> Optional[int]:
        return self.raw.get("max")

    # --- answers ------------------------------------------------------------------------------

    def choose(self, option: OptionRef) -> Action:
        return Action({"choice": _oid(option)})

    def choose_many(self, options: Iterable[OptionRef]) -> Action:
        """Select several targets at once (target decisions); the engine finishes with "done"."""
        return Action({"choices": [_oid(o) for o in options]})

    def pass_priority(self) -> Action:
        return Action({"choice": "pass"})

    def amount(self, value: int) -> Action:
        return Action({"amount": int(value)})

    def amounts(self, values: Sequence[int]) -> Action:
        return Action({"amounts": [int(v) for v in values]})

    def attack(self, attackers: Iterable[Union[str, Dict[str, str]]]) -> Action:
        """``attackers``: ids (attacking the default defender) or {"attacker": id, "defender": id}."""
        pairs = [{"attacker": a} if isinstance(a, str) else dict(a) for a in attackers]
        return Action({"attackers": pairs})

    def block(self, blocks: Iterable[Union[Sequence[str], Dict[str, str]]]) -> Action:
        """``blocks``: (blocker_id, attacker_id) tuples or {"blocker": id, "attacker": id} dicts."""
        pairs = []
        for b in blocks:
            if isinstance(b, dict):
                pairs.append(dict(b))
            else:
                pairs.append({"blocker": b[0], "attacker": b[1]})
        return Action({"blocks": pairs})

    def default(self) -> Action:
        """The engine's safe default (pass, keep, no, no attack, first legal target, ...)."""
        payload = self.raw.get("default")
        if payload:
            return Action(payload)
        if self.kind == "declare_attackers":
            return self.attack([])
        if self.kind == "declare_blockers":
            return self.block([])
        if self.kind == "amount":
            return self.amount(self.raw.get("min", 0))
        if self.kind == "multi_amount":
            return self.amounts([it.get("default", it.get("min", 0)) for it in self.raw.get("items", [])])
        if self.options:
            return self.choose(self.options[0])
        return Action({"choice": "pass"})

    def __repr__(self) -> str:
        return f"Decision(#{self.id} {self.kind}: {self.prompt!r}, {len(self.options)} options)"

    def __iter__(self) -> Iterator[Option]:
        return iter(self.options)
