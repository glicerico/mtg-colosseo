"""Claude plays one game against XMage's AI. Watch it live from the lobby (http://localhost:7070).

    pip install -e "python/[llm]"      # and set ANTHROPIC_API_KEY (or run `ant auth login`)
    python examples/claude_vs_xmage.py
"""
import logging

from colosseo import run_game
from colosseo.agents import ClaudeAgent

logging.basicConfig(level=logging.INFO)
agent = ClaudeAgent(effort="medium")   # every decision is one API call; comments show up in the game log
result = run_game(agent, "xmage:2", "fdn:azorius-skies", "fdn:gruul-stompers",
                  on_created=lambda g: print(f"watch: http://localhost:7070/#/watch/{g['game_id']}"))
print(result, f"({agent.calls} API calls)")
