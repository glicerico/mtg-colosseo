"""An agent that asks Claude for every decision.

Each decision becomes one Messages API call: the board rendered as text, the recent game log and the
question with its legal options. Structured outputs constrain the answer to the legal option ids, so the
model can't answer with an illegal move. The model's one-line comment is attached to the action; the
server shows it to spectators allowed to see hidden information (never to the opponent, unless the game
was created with ``public_comments``).

Requires ``pip install anthropic`` and credentials (``ANTHROPIC_API_KEY`` or an ``ant auth login`` profile).
"""
from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional

from ..agent import Agent
from ..protocol import Action, Decision
from ..render import render_decision, render_state
from .heuristic import HeuristicAgent

log = logging.getLogger("colosseo.llm")

SYSTEM_PROMPT = """You are playing Magic: The Gathering (a two-player limited game) inside MTG Colosseo, \
a rules engine that asks you one decision at a time. Play to win.

How decisions work:
- Each request shows the board from your point of view, the recent game log, and one decision with its legal options.
- Answer with the JSON schema provided. Use option ids exactly as given; they are the only legal answers.
- "comment" is one short sentence explaining your move to authorized spectators (your opponent never sees it).

Decision kinds:
- priority: cast a spell / play a land / activate an ability (by option id), or "pass". Passing with an empty stack moves the game to the next step; passing with objects on the stack lets the top one resolve. Mana is paid automatically. You get priority again after each action, so play one thing at a time. Play a land every turn when you can.
- mulligan: "keep" or "mulligan" (London mulligan: you then put cards on the bottom).
- declare_attackers: list the creatures that attack and which defender each attacks (an empty list means no attack). Summoning-sick and tapped creatures are not offered.
- declare_blockers: list blocker/attacker pairs (an empty list means no blocks). Unblocked attackers deal damage to you.
- target: pick one target per answer; you will be asked again for further targets. "done"/"Skip" finishes when allowed.
- yes_no, choose_mode, choose_choice, choose_ability, trigger_order, choose_pile, pay_mana: pick one option.
- amount: a number in the given range (for X spells stay within what you can afford).
- multi_amount: one number per item, respecting each item's range and the total.

Limited strategy reminders: develop your board on curve, trade removal for the opponent's best threats, \
attack when the opponent can't block profitably, keep blockers back when you are behind, and count lethal damage."""

_CHOICE_KINDS = {"priority", "mulligan", "yes_no", "target", "choose_ability", "choose_mode", "choose_choice",
                 "trigger_order", "choose_pile", "pay_mana"}


class ClaudeAgent(Agent):
    """Plays by asking Claude.

    :param model: Claude model id
    :param effort: ``low`` | ``medium`` | ``high`` | ``xhigh`` | ``max`` - thinking depth per decision
      (more effort = stronger but slower and costlier play)
    :param fallback: agent used when the API call fails or declines (default: :class:`HeuristicAgent`)
    :param trivial_to_fallback: answer forced/trivial decisions (a single legal option) without calling the API
    :param log_lines: how many recent game-log lines to include in each prompt
    """

    name = "claude"
    seat_options = {"stop_policy": "all"}

    def __init__(self, model: str = "claude-opus-5-5", effort: str = "medium", client: Any = None,
                 fallback: Optional[Agent] = None, trivial_to_fallback: bool = True, log_lines: int = 40,
                 max_tokens: int = 16000, server_fallbacks: bool = True):
        if client is None:
            import anthropic  # optional dependency: pip install "colosseo[llm]"
            client = anthropic.Anthropic()
        self.client = client
        self.model = model
        self.effort = effort
        self.fallback = fallback or HeuristicAgent()
        self.trivial_to_fallback = trivial_to_fallback
        self.log_lines = log_lines
        self.max_tokens = max_tokens
        self.server_fallbacks = server_fallbacks
        self.history: List[str] = []
        self._log_seen: set = set()
        self.calls = 0

    # --- game hooks ---------------------------------------------------------------------------

    def on_game_start(self, info: Dict[str, Any]) -> None:
        self.history = []
        self._log_seen = set()
        for e in info.get("log", []):
            self._add_log(e)

    def on_event(self, message: Dict[str, Any]) -> None:
        # Only the engine's game log enters the prompt. Opponent actions arrive redacted and opponent
        # comments are deliberately ignored: they are free text written by the other side.
        if message.get("type") == "log":
            self._add_log(message.get("entry", {}))

    def _add_log(self, e: Dict[str, Any]) -> None:
        key = e.get("i")
        if key is not None:
            if key in self._log_seen:
                return  # delivered both as a log event and with a decision
            self._log_seen.add(key)
        self.history.append(f"T{e.get('turn')}: {e.get('text')}")

    # --- decisions ----------------------------------------------------------------------------

    def decide(self, d: Decision) -> Action:
        for e in d.log:
            self._add_log(e)
        if self.trivial_to_fallback and self._trivial(d):
            return self.fallback.decide(d)
        try:
            data = self._ask(d)
        except Exception as e:  # noqa: BLE001 - API/network/parse problems must not stall the game
            log.warning("Claude call failed (%s); using fallback agent", e)
            return self.fallback.decide(d)
        if data is None:
            return self.fallback.decide(d)
        action = self._to_action(d, data)
        comment = (data.get("comment") or "").strip()
        return action.with_comment(comment[:300]) if comment else action

    @staticmethod
    def _trivial(d: Decision) -> bool:
        if d.kind in ("declare_attackers", "declare_blockers", "amount", "multi_amount"):
            return False
        return len(d.options) <= 1

    def _prompt(self, d: Decision) -> str:
        recent = "\n".join(self.history[-self.log_lines:])
        return (f"GAME STATE\n{render_state(d.state)}\n\n"
                f"RECENT LOG (oldest first)\n{recent}\n\n"
                f"{render_decision(d)}")

    def _schema(self, d: Decision) -> Dict[str, Any]:
        props: Dict[str, Any] = {"comment": {"type": "string"}}
        required = ["comment"]
        raw = d.raw
        if d.kind == "declare_attackers":
            attackers = [a["id"] for a in raw.get("attackers", [])]
            defenders = [x["id"] for x in raw.get("defenders", [])] or ["none"]
            props["attackers"] = {"type": "array", "items": {
                "type": "object",
                "properties": {"attacker": {"type": "string", "enum": attackers},
                               "defender": {"type": "string", "enum": defenders}},
                "required": ["attacker", "defender"], "additionalProperties": False}}
            required.append("attackers")
        elif d.kind == "declare_blockers":
            blockers = [b["id"] for b in raw.get("blockers", [])]
            attackers = [a["id"] for a in raw.get("attackers", [])]
            props["blocks"] = {"type": "array", "items": {
                "type": "object",
                "properties": {"blocker": {"type": "string", "enum": blockers},
                               "attacker": {"type": "string", "enum": attackers}},
                "required": ["blocker", "attacker"], "additionalProperties": False}}
            required.append("blocks")
        elif d.kind == "amount":
            props["amount"] = {"type": "integer"}
            required.append("amount")
        elif d.kind == "multi_amount":
            props["amounts"] = {"type": "array", "items": {"type": "integer"}}
            required.append("amounts")
        else:
            props["choice"] = {"type": "string", "enum": d.option_ids}
            required.append("choice")
        return {"type": "object", "properties": props, "required": required, "additionalProperties": False}

    def _ask(self, d: Decision) -> Optional[Dict[str, Any]]:
        self.calls += 1
        kwargs: Dict[str, Any] = dict(
            model=self.model,
            max_tokens=self.max_tokens,
            system=[{"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}],
            output_config={"effort": self.effort, "format": {"type": "json_schema", "schema": self._schema(d)}},
            messages=[{"role": "user", "content": self._prompt(d)}],
        )
        if self.server_fallbacks:
            # on a safety decline the API retries on Anthropic's recommended fallback model
            response = self.client.beta.messages.create(betas=["server-side-fallback-2026-07-01"],
                                                        fallbacks="default", **kwargs)
        else:
            response = self.client.messages.create(**kwargs)
        if response.stop_reason == "refusal":
            log.warning("Claude declined decision %s", d.id)
            return None
        text = next((b.text for b in response.content if b.type == "text"), None)
        if not text:
            return None
        return json.loads(text)

    def _to_action(self, d: Decision, data: Dict[str, Any]) -> Action:
        raw = d.raw
        if d.kind == "declare_attackers":
            legal = {a["id"]: set(a.get("defenders", [])) for a in raw.get("attackers", [])}
            pairs, seen = [], set()
            for p in data.get("attackers", []):
                att, dfn = p.get("attacker"), p.get("defender")
                if att in legal and att not in seen:
                    seen.add(att)
                    pairs.append({"attacker": att, **({"defender": dfn} if dfn in legal[att] else {})})
            return d.attack(pairs)
        if d.kind == "declare_blockers":
            legal = {b["id"]: set(b.get("attackers", [])) for b in raw.get("blockers", [])}
            blocks = [(p["blocker"], p["attacker"]) for p in data.get("blocks", [])
                      if p.get("blocker") in legal and p.get("attacker") in legal[p["blocker"]]]
            return d.block(blocks)
        if d.kind == "amount":
            lo, hi = raw.get("min", 0), raw.get("max", 0)
            hi = min(hi, raw.get("max_affordable", hi))
            return d.amount(max(lo, min(hi, int(data.get("amount", lo)))))
        if d.kind == "multi_amount":
            values = data.get("amounts") or []
            items = raw.get("items", [])
            ok = len(values) == len(items) and all(it["min"] <= v <= it["max"] for v, it in zip(values, items)) \
                and raw.get("total_min", 0) <= sum(values) <= raw.get("total_max", sum(values))
            return d.amounts(values) if ok else d.default()
        choice = data.get("choice")
        return d.choose(choice) if d.option(choice) else d.default()
