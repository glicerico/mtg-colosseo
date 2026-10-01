"""A rule-based baseline: plays lands, curves out, uses removal on the biggest threat, attacks and
blocks with simple combat math. Deliberately simple and readable - a starting point for your own agent."""
from __future__ import annotations

import re
from typing import Dict, List, Optional

from ..agent import Agent
from ..protocol import Action, Decision, Obj, Option, State

HARMFUL = re.compile(r"destroy|exile target|deals? \d+ damage|damage to (any|target)|gets -\d|-\d/-\d|"
                     r"return target (nonland )?(creature|permanent)[^.]*owner's hand|tap target|can't block|"
                     r"can't attack|loses all abilities|counter target|discards?|sacrifice|fights?", re.I)
BENEFICIAL = re.compile(r"gets \+|\+1/\+1 counter|gains? (flying|indestructible|hexproof|first strike|lifelink|"
                        r"trample|deathtouch|double strike|vigilance)|double strike|untap target", re.I)
COMBAT_TRICK = re.compile(r"target creature (you control )?gets \+|gains? (indestructible|first strike|hexproof)", re.I)
COUNTERSPELL = re.compile(r"counter target", re.I)
MANA_SYMBOL = re.compile(r"\{([^}]+)\}")


def mana_count(cost: str) -> int:
    total = 0
    for sym in MANA_SYMBOL.findall(cost or ""):
        total += int(sym) if sym.isdigit() else (0 if sym == "X" else 1)
    return total


def text_of(card: Optional[Dict]) -> str:
    if not card:
        return ""
    return " ".join(card.get("rules") or [])


def has_keyword(card: Optional[Dict], keyword: str) -> bool:
    """Engine-reported keywords; falls back to the rules text for older servers."""
    if not card:
        return False
    if card.get("keywords") is not None:
        return any(k == keyword or k.startswith(keyword + " ") for k in card["keywords"])
    return keyword in text_of(card).lower()


def is_creature(card: Optional[Dict]) -> bool:
    return bool(card) and "Creature" in (card.get("types") or [])


def value(card: Optional[Dict]) -> float:
    """Crude card value: stats plus a bonus for evasion and keywords."""
    if not card:
        return 0.0
    v = float(card.get("mana_value") or 0)
    if is_creature(card):
        v += (card.get("power") or 0) + 0.5 * (card.get("toughness") or 0)
        t = text_of(card).lower()
        for kw in ("flying", "deathtouch", "lifelink", "first strike", "double strike", "trample", "menace"):
            if kw in t:
                v += 1.0
    return v


class HeuristicAgent(Agent):
    """Simple, fast, deterministic baseline."""

    name = "heuristic"
    version = "1"

    def decide(self, d: Decision) -> Action:
        handler = getattr(self, "_" + d.kind, None)
        if handler is None:
            return d.default()
        action = handler(d)
        return action if action is not None else d.default()

    # --- priority --------------------------------------------------------------------------------

    def _priority(self, d: Decision) -> Optional[Action]:
        s = d.state
        me, opp = s.me, s.opponent
        stack = s.get("stack") or []
        step = s.get("step")
        my_turn = s.is_my_turn
        options = [o for o in d.options if o.id not in ("pass", "pass_turn")]

        def card(o: Option) -> Optional[Obj]:
            return s.find(o.get("source_id") or "")

        # respond to the opponent's spell with a counterspell
        if stack and stack[0].get("controller") != s.get("you"):
            top = stack[0]
            for o in options:
                if o.get("action") == "cast" and COUNTERSPELL.search(text_of(card(o))):
                    kind_ok = ("creature" not in text_of(card(o)).lower()) or is_creature(top)
                    noncreature_only = "noncreature" in text_of(card(o)).lower()
                    if kind_ok and not (noncreature_only and is_creature(top)) and (top.get("mana_value") or 0) >= 2:
                        return d.choose(o)
            return d.pass_priority()
        if stack:
            return d.pass_priority()

        # combat tricks when our creatures are in a fight
        if step == "DECLARE_BLOCKERS":
            groups = (s.get("combat") or {}).get("groups") or []
            mine_fighting = any(g.get("blockers") for g in groups)
            if mine_fighting:
                for o in options:
                    if o.get("action") == "cast" and COMBAT_TRICK.search(text_of(card(o))):
                        return d.choose(o)
            return d.pass_priority()

        if my_turn and step in ("PRECOMBAT_MAIN", "POSTCOMBAT_MAIN"):
            # 1. land
            lands = [o for o in options if o.get("action") == "play_land"]
            if lands:
                return d.choose(self._best_land(lands, s))
            # 2. removal on the best opposing creature
            opp_creatures = opp.creatures() if opp else []
            if opp_creatures:
                for o in sorted(options, key=lambda o: -(card(o) or {}).get("mana_value", 0)):
                    c = card(o)
                    if o.get("action") == "cast" and not is_creature(c) and HARMFUL.search(text_of(c)) \
                            and not COUNTERSPELL.search(text_of(c)):
                        return d.choose(o)
            # 3. biggest creature / permanent we can cast (pre-combat for haste and blockers info is fine)
            casts = [o for o in options if o.get("action") == "cast"]
            creatures = [o for o in casts if is_creature(card(o))]
            if creatures:
                return d.choose(max(creatures, key=lambda o: (card(o) or {}).get("mana_value", 0)))
            others = [o for o in casts if not COMBAT_TRICK.search(text_of(card(o)))
                      and not COUNTERSPELL.search(text_of(card(o)))
                      and not HARMFUL.search(text_of(card(o)))]
            if others and step == "POSTCOMBAT_MAIN":
                return d.choose(max(others, key=lambda o: (card(o) or {}).get("mana_value", 0)))
            return d.pass_priority()

        # opponent's end step: flash creatures
        if not my_turn and step == "END_TURN":
            for o in options:
                if o.get("action") == "cast" and is_creature(card(o)):
                    return d.choose(o)
        return d.pass_priority()

    def _best_land(self, lands: List[Option], s: State) -> Option:
        me = s.me
        hand = (me.get("hand") or []) if me else []
        need: Dict[str, int] = {}
        for c in hand:
            for sym in MANA_SYMBOL.findall(c.get("mana_cost") or ""):
                if sym in "WUBRG":
                    need[sym] = need.get(sym, 0) + 1
        have: Dict[str, int] = {}
        for land in (me.lands() if me else []):
            for sym in MANA_SYMBOL.findall(text_of(land)):
                if sym in "WUBRG":
                    have[sym] = have.get(sym, 0) + 1

        def score(o: Option) -> float:
            land = s.find(o.get("source_id") or "") or {}
            t = text_of(land)
            produced = set(sym for sym in MANA_SYMBOL.findall(t) if sym in "WUBRG")
            sc = sum(need.get(c, 0) / (1 + have.get(c, 0)) for c in produced)
            if "enters tapped" in t.lower():
                sc -= 0.5
            return sc

        return max(lands, key=score)

    # --- simple dialogs ----------------------------------------------------------------------------

    def _mulligan(self, d: Decision) -> Action:
        me = d.state.me
        hand = (me.get("hand") or []) if me else []
        lands = sum(1 for c in hand if "Land" in (c.get("types") or []))
        size = len(hand)
        if size <= 5 or 2 <= lands <= 5:
            return d.choose("keep")
        return d.choose("mulligan")

    def _yes_no(self, d: Decision) -> Action:
        prompt = d.prompt
        if prompt.lower().startswith("pay"):
            me = d.state.me
            available = len(me.untapped_lands()) if me else 0
            cost = mana_count(prompt)
            stack = d.state.get("stack") or []
            base = (stack[0].get("mana_value") or 0) if stack and stack[0].get("controller") == d.state.get("you") else 0
            return d.choose("yes" if available >= cost + base else "no")
        return d.choose("yes")

    def _amount(self, d: Decision) -> Action:
        if d.raw.get("announce_x"):
            return d.amount(d.raw.get("max_affordable", d.raw.get("min", 0)))
        return d.amount(d.raw.get("min", 0))

    def _choose_mode(self, d: Decision) -> Action:
        real = [o for o in d.options if o.label.lower() not in ("done", "cancel")]
        return d.choose(real[0] if real else d.options[0])

    def _choose_pile(self, d: Decision) -> Action:
        p1, p2 = d.raw.get("pile1", []), d.raw.get("pile2", [])
        return d.choose("pile1" if sum(value(c) for c in p1) >= sum(value(c) for c in p2) else "pile2")

    def _pay_mana(self, d: Decision) -> Action:
        return d.choose("cancel")  # auto-payment already failed: we can't afford it

    def _choose_ability(self, d: Decision) -> Action:
        real = [o for o in d.options if o.id != "cancel"]
        return d.choose(real[0] if real else d.options[0])

    # --- targets -----------------------------------------------------------------------------------

    def _target(self, d: Decision) -> Action:
        s = d.state
        you = s.get("you")
        selectable = [o for o in d.options if o.id != "done" and not o.get("selected")]
        if not selectable:
            return d.choose("done") if d.option("done") else d.default()
        prompt = d.prompt.lower()
        source = s.find(d.raw.get("source_id") or "")
        text = (text_of(source) + " " + prompt).lower()

        cards = {c["id"]: c for c in d.raw.get("cards", []) or []}

        def obj(o: Option) -> Dict:
            return cards.get(o.id) or s.find(o.id) or dict(o)

        # choosing cards from our own hand/library (mulligan bottom, discard, search)
        if "bottom of your library" in prompt or "discard" in prompt:
            me = s.me
            lands_in_hand = sum(1 for c in (me.get("hand") or []) if "Land" in (c.get("types") or [])) if me else 0
            def badness(o: Option) -> float:
                c = obj(o)
                is_land = "Land" in (c.get("types") or [])
                return (3.0 if is_land and lands_in_hand > 3 else 0.0) + (c.get("mana_value") or 0) * 0.3 \
                    - (0 if is_land else 0) - value(c) * 0.1
            return d.choose(max(selectable, key=badness))
        if "search" in prompt or "library" in prompt or "graveyard" in prompt and "your" in prompt:
            return d.choose(max(selectable, key=lambda o: value(obj(o))))

        harmful = bool(HARMFUL.search(text)) and not BENEFICIAL.search(text)
        mine = [o for o in selectable if o.get("controller") == you or o.id == you]
        theirs = [o for o in selectable if o not in mine]
        if harmful:
            pool = theirs or selectable
            creatures = [o for o in pool if o.get("kind") == "permanent"]
            if creatures:
                return d.choose(max(creatures, key=lambda o: value(obj(o))))
            players = [o for o in pool if o.get("kind") == "player" and o.id != you]
            if players:
                return d.choose(players[0])
            return d.choose(pool[0])
        pool = mine or selectable
        creatures = [o for o in pool if o.get("kind") == "permanent"]
        if creatures:
            def own_score(o: Option) -> float:
                c = obj(o)
                return value(c) + (5 if c.get("attacking") or c.get("blocking") else 0)
            return d.choose(max(creatures, key=own_score))
        return d.choose(pool[0])

    # --- combat ------------------------------------------------------------------------------------

    def _declare_attackers(self, d: Decision) -> Action:
        s = d.state
        opp = s.opponent
        me = s.me
        blockers = [c for c in (opp.creatures() if opp else []) if not c.get("tapped")]
        attackers = d.raw.get("attackers", [])
        by_id = {c["id"]: c for c in (me.get("battlefield") or [])} if me else {}
        total_power = sum((a.get("power") or 0) for a in attackers)
        lethal = opp is not None and total_power >= (opp.life or 0) and not blockers

        chosen = []
        for a in attackers:
            c = by_id.get(a["id"], a)
            power, tough = c.get("power") or 0, c.get("toughness") or 0
            text = text_of(c).lower()
            if a.get("must_attack") or lethal or not blockers:
                chosen.append(a["id"])
                continue
            flying = "flying" in text
            can_block = [b for b in blockers if not flying or any(k in text_of(b).lower() for k in ("flying", "reach"))]
            if not can_block:
                chosen.append(a["id"])
                continue
            # attack if no single blocker can kill it while surviving
            safe = all(((b.get("power") or 0) < tough) or ((b.get("toughness") or 0) <= power and value(b) >= value(c))
                       for b in can_block)
            if safe and power > 0:
                chosen.append(a["id"])
        return d.attack(chosen)

    def _declare_blockers(self, d: Decision) -> Action:
        s = d.state
        me = s.me
        attackers = {a["id"]: (s.find(a["id"]) or a) for a in d.raw.get("attackers", [])}
        # one blocker per attacker: attackers that need more (menace) are left unblocked
        single_ok = {a["id"] for a in d.raw.get("attackers", []) if (a.get("min_blockers") or 1) <= 1}
        incoming = sum((a.get("power") or 0) for a in attackers.values())
        life = (me.life or 20) if me else 20
        in_danger = incoming >= life
        used = set()
        blocks = []
        blockers = {b["id"]: (s.find(b["id"]) or b) for b in d.raw.get("blockers", [])}
        options = {b["id"]: b.get("attackers", []) for b in d.raw.get("blockers", [])}
        for att_id, att in sorted(attackers.items(), key=lambda kv: -((kv[1].get("power") or 0))):
            ap, at = att.get("power") or 0, att.get("toughness") or 0
            if att_id not in single_ok:
                continue
            candidates = [bid for bid, legal in options.items() if att_id in legal and bid not in used]
            best = None
            best_score = 0.0
            for bid in candidates:
                b = blockers[bid]
                bp, bt = b.get("power") or 0, b.get("toughness") or 0
                deathtouch = has_keyword(b, "deathtouch")
                kills = bp >= at or deathtouch
                survives = bt > ap and not has_keyword(att, "deathtouch")
                score = 0.0
                if kills and survives:
                    score = 3 + value(att)
                elif survives:
                    score = 1
                elif kills and value(b) <= value(att):
                    score = 2 + value(att) - value(b)
                elif in_danger:
                    score = 0.5 - value(b) * 0.01
                if score > best_score:
                    best, best_score = bid, score
            if best:
                used.add(best)
                blocks.append((best, att_id))
                incoming -= ap
                in_danger = incoming >= life
        return d.block(blocks)
