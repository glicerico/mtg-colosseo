"""Plain-text rendering of observations and decisions (for LLM prompts, logs and debugging)."""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from .protocol import Decision, State

__all__ = ["card_line", "render_state", "render_decision"]


def _pt(c: Dict[str, Any]) -> str:
    if c.get("power") is None:
        return ""
    return f" {c['power']}/{c['toughness']}"


def card_line(c: Dict[str, Any], rules: bool = True, short_ids: bool = False) -> str:
    """One line describing a card or permanent, e.g. ``[3f2a] Serra Angel {3}{W}{W} Creature 4/4 (tapped) -- Flying, Vigilance``."""
    if c.get("hidden"):
        return f"[{_id(c.get('id'), short_ids)}] {c.get('name', 'hidden card')}"
    flags = []
    if c.get("tapped"):
        flags.append("tapped")
    if c.get("summoning_sick"):
        flags.append("summoning sick")
    if c.get("attacking"):
        flags.append("attacking")
    if c.get("blocking"):
        flags.append("blocking")
    if c.get("damage"):
        flags.append(f"{c['damage']} damage")
    if c.get("token"):
        flags.append("token")
    counters = c.get("counters") or {}
    for name, n in counters.items():
        flags.append(f"{n} {name} counter{'s' if n != 1 else ''}")
    if c.get("attached_to"):
        flags.append(f"attached to [{_id(c['attached_to'], short_ids)}]")
    types = " ".join((c.get("supertypes") or []) + (c.get("types") or []))
    sub = " ".join(c.get("subtypes") or [])
    if sub:
        types += " - " + sub
    cost = c.get("mana_cost") or ""
    text = f"[{_id(c.get('id'), short_ids)}] {c.get('name')}"
    if cost:
        text += f" {cost}"
    if types:
        text += f" ({types}{_pt(c)})"
    if flags:
        text += " [" + ", ".join(flags) + "]"
    if rules and c.get("rules"):
        text += " -- " + " / ".join(c["rules"])
    return text


def _id(value: Optional[str], short: bool) -> str:
    if not value:
        return "?"
    return value[:8] if short else value


def render_state(state: State, rules: bool = True, short_ids: bool = False) -> str:
    """Multi-line summary of the board from the viewer's perspective."""
    lines: List[str] = []
    me, opp = state.me, state.opponent
    active = state.player(state.get("active_player") or "")
    lines.append(f"Turn {state.get('turn')} - {state.get('step')} - active player: {active.name if active else '?'}")
    for label, p in (("OPPONENT", opp), ("YOU", me)):
        if p is None:
            continue
        pool = p.get("mana_pool") or {}
        pool_txt = "".join(f"{{{k}}}" * v for k, v in pool.items() if v)
        counters = p.get("counters") or {}
        extra = ""
        if counters:
            extra = " " + ", ".join(f"{k}: {v}" for k, v in counters.items())
        lines.append(f"== {label}: {p.name} - life {p.life}, library {p.library_count}, hand {p.hand_count}"
                     + (f", mana pool {pool_txt}" if pool_txt else "") + extra)
        if p.get("hand") is not None:
            lines.append("  Hand:")
            for c in p.get("hand") or []:
                lines.append("    " + card_line(c, rules, short_ids))
        lands = [c for c in p.get("battlefield") or [] if "Land" in (c.get("types") or [])]
        others = [c for c in p.get("battlefield") or [] if "Land" not in (c.get("types") or [])]
        if lands:
            untapped = sum(1 for c in lands if not c.get("tapped"))
            names = ", ".join(sorted({c.get("name") for c in lands}))
            lines.append(f"  Lands: {len(lands)} ({untapped} untapped): {names}")
        if others:
            lines.append("  Battlefield:")
            for c in others:
                lines.append("    " + card_line(c, rules, short_ids))
        gy = p.get("graveyard") or []
        if gy:
            lines.append("  Graveyard: " + ", ".join(c.get("name", "?") for c in gy))
    stack = state.get("stack") or []
    if stack:
        lines.append("== STACK (top first):")
        for s in stack:
            owner = state.player(s.get("controller") or "")
            targets = s.get("targets") or []
            tgt = f" targeting {', '.join('[' + _id(t, short_ids) + ']' for t in targets)}" if targets else ""
            lines.append(f"  [{_id(s.get('id'), short_ids)}] {s.get('name')} ({owner.name if owner else '?'}){tgt}"
                         + (" -- " + " / ".join(s.get("rules") or []) if rules else ""))
    combat = (state.get("combat") or {}).get("groups") or []
    if combat:
        lines.append("== COMBAT:")
        for g in combat:
            att = ", ".join(_name(state, a) for a in g.get("attackers", []))
            blk = ", ".join(_name(state, b) for b in g.get("blockers", [])) or "unblocked"
            lines.append(f"  {att} -> {_name(state, g.get('defender'))}; blocked by: {blk}")
    return "\n".join(lines)


def _name(state: State, object_id: Optional[str]) -> str:
    if not object_id:
        return "?"
    p = state.player(object_id)
    if p is not None:
        return p.name
    o = state.find(object_id)
    return f"{o.get('name')} [{object_id[:8]}]" if o else object_id[:8]


def render_decision(decision: Decision, short_ids: bool = False) -> str:
    """The question and its options as text (ids included so an answer can reference them)."""
    lines = [f"DECISION ({decision.kind}): {decision.prompt}"]
    if decision.error:
        lines.append(f"Note: {decision.error}")
    if decision.rejection:
        lines.append(f"Your previous answer was rejected: {decision.rejection}")
    raw = decision.raw
    if decision.kind == "declare_attackers":
        lines.append("Creatures that can attack (id: name, legal defenders):")
        for a in raw.get("attackers", []):
            must = " MUST ATTACK" if a.get("must_attack") else ""
            lines.append(f"  {_id(a['id'], short_ids)}: {a.get('name')} {a.get('power')}/{a.get('toughness')}"
                         f" -> {', '.join(_id(d, short_ids) for d in a.get('defenders', []))}{must}")
        lines.append("Defenders: " + ", ".join(f"{_id(d['id'], short_ids)}={d.get('name')}" for d in raw.get("defenders", [])))
        return "\n".join(lines)
    if decision.kind == "declare_blockers":
        lines.append("Attacking creatures:")
        for a in raw.get("attackers", []):
            lines.append(f"  {_id(a['id'], short_ids)}: {a.get('name')} {a.get('power')}/{a.get('toughness')}")
        lines.append("Your possible blockers (id: name -> attackers it can block):")
        for b in raw.get("blockers", []):
            lines.append(f"  {_id(b['id'], short_ids)}: {b.get('name')} {b.get('power')}/{b.get('toughness')}"
                         f" -> {', '.join(_id(x, short_ids) for x in b.get('attackers', []))}")
        return "\n".join(lines)
    if decision.kind == "amount":
        lines.append(f"Choose a number between {raw.get('min')} and {raw.get('max')}"
                     + (f" (you can afford at most {raw['max_affordable']})" if "max_affordable" in raw else ""))
        return "\n".join(lines)
    if decision.kind == "multi_amount":
        lines.append(f"Distribute a total between {raw.get('total_min')} and {raw.get('total_max')}:")
        for i, it in enumerate(raw.get("items", [])):
            lines.append(f"  item {i}: {it.get('label')} (min {it.get('min')}, max {it.get('max')}, default {it.get('default')})")
        return "\n".join(lines)
    if decision.kind == "target" and raw.get("cards"):
        lines.append("Cards shown:")
        for c in raw["cards"]:
            sel = "" if c.get("selectable", True) else " (not selectable)"
            lines.append("  " + card_line(c, rules=True, short_ids=short_ids) + sel)
    if decision.kind == "target":
        lines.append(f"Select between {raw.get('min', '?')} and {raw.get('max', '?')} targets; already chosen: {raw.get('chosen', [])}")
    lines.append("Options:")
    for o in decision.options:
        extra = []
        for key in ("action", "zone", "controller", "mana_cost"):
            if o.get(key):
                value = o[key]
                if key == "controller":
                    p = decision.state.player(value)
                    value = p.name if p else value
                extra.append(f"{key}={value}")
        if o.get("selected"):
            extra.append("already selected")
        lines.append(f"  {o.id}: {o.label}" + (f" ({', '.join(extra)})" if extra else ""))
    return "\n".join(lines)
