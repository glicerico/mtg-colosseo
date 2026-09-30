"""A tiny agent: play lands, cast the most expensive spell it can, attack with everything that is safe.

    python examples/my_first_agent.py            # 10 games vs XMage's AI (skill 1)
"""
from colosseo import Agent, run_match


class GreedyAgent(Agent):
    name = "greedy"

    def decide(self, d):
        s = d.state
        if d.kind == "priority" and not s.get("stack"):
            lands = d.options_where(action="play_land")
            if lands:
                return d.choose(lands[0])
            spells = d.options_where(action="cast")
            if spells:
                best = max(spells, key=lambda o: (s.find(o.source_id) or {}).get("mana_value", 0))
                return d.choose(best).with_comment(f"casting the biggest thing I can: {best.label}")
            return d.pass_priority()
        if d.kind == "declare_attackers":
            opp_blockers = [c for c in s.opponent.creatures() if not c.tapped]
            biggest = max([c.power or 0 for c in opp_blockers], default=0)
            return d.attack([a["id"] for a in d["attackers"] if (a.get("toughness") or 0) > biggest])
        return d.default()


if __name__ == "__main__":
    print(run_match(GreedyAgent, "xmage:1", games=10))
