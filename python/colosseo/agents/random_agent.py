"""A uniformly random legal-move agent: the simplest baseline and a good fuzzer."""
from __future__ import annotations

import random
from typing import Optional

from ..agent import Agent
from ..protocol import Action, Decision


class RandomAgent(Agent):
    """Picks a random legal answer for every decision.

    ``act_probability`` controls how often it takes a non-pass action at priority (always passing would
    make games trivially short; always acting makes it cast every spell it can).
    """

    name = "random"

    def __init__(self, seed: Optional[int] = None, act_probability: float = 0.8, attack_probability: float = 0.5):
        self.rng = random.Random(seed)
        self.act_probability = act_probability
        self.attack_probability = attack_probability

    def decide(self, d: Decision) -> Action:
        rng = self.rng
        k = d.kind
        if k == "priority":
            actions = [o for o in d.options if o.id not in ("pass", "pass_turn")]
            if actions and rng.random() < self.act_probability:
                return d.choose(rng.choice(actions))
            return d.pass_priority()
        if k == "mulligan":
            return d.choose("keep" if rng.random() < 0.85 else "mulligan")
        if k == "declare_attackers":
            chosen = []
            for a in d.raw.get("attackers", []):
                if a.get("must_attack") or rng.random() < self.attack_probability:
                    pair = {"attacker": a["id"]}
                    if a.get("defenders"):
                        pair["defender"] = rng.choice(a["defenders"])
                    chosen.append(pair)
            return d.attack(chosen)
        if k == "declare_blockers":
            blocks = []
            for b in d.raw.get("blockers", []):
                if b.get("attackers") and rng.random() < 0.5:
                    blocks.append((b["id"], rng.choice(b["attackers"])))
            return d.block(blocks)
        if k == "amount":
            hi = d.raw.get("max_affordable", d.raw.get("max", 0))
            lo = d.raw.get("min", 0)
            return d.amount(rng.randint(lo, max(lo, min(hi, lo + 20))))
        if k == "multi_amount":
            # keep the engine's default distribution (always valid)
            return d.default()
        if k == "pay_mana":
            sources = [o for o in d.options if o.get("kind") in ("source", "pool")]
            return d.choose(rng.choice(sources)) if sources else d.choose("cancel")
        if k == "target":
            selectable = [o for o in d.options if o.id != "done" and not o.get("selected")]
            if not selectable:
                return d.choose("done") if d.option("done") else d.default()
            if d.option("done") and rng.random() < 0.2:
                return d.choose("done")
            return d.choose(rng.choice(selectable))
        if k == "choose_ability":
            real = [o for o in d.options if o.id != "cancel"]
            return d.choose(rng.choice(real or d.options))
        if k == "choose_choice":
            real = [o for o in d.options if o.id != "__cancel__"]
            return d.choose(rng.choice(real or d.options))
        if d.options:
            return d.choose(rng.choice(d.options))
        return d.default()
