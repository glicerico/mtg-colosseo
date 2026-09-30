"""Round robin between the reference agents and XMage's AI, with Elo ratings."""
from colosseo import round_robin
from colosseo.agents import HeuristicAgent, RandomAgent

matches, elo = round_robin({"random": RandomAgent, "heuristic": HeuristicAgent, "mad-1": "xmage:1"},
                           games=6, parallel=3, max_turns=40)
for m in matches:
    print(m)
for name, rating in elo.items():
    print(f"{name:12s} {rating:7.1f}")
