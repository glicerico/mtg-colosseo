"""Claude plays one game against XMage's AI. Watch it live from the lobby (http://localhost:7070).

    pip install -e "python/[llm]"      # and set ANTHROPIC_API_KEY (or run `ant auth login`)
    python examples/claude_vs_xmage.py
"""
import logging

from colosseo import run_game
from colosseo.agents import ClaudeAgent

logging.basicConfig(level=logging.INFO)
agent = ClaudeAgent(effort="medium")   # every decision is one API call
# the owner link shows both hands and Claude's comments (other spectators and the opponent don't get them)
result = run_game(agent, "xmage:2", "fdn:azorius-skies", "fdn:gruul-stompers",
                  on_created=lambda g: print(f"watch: http://localhost:7070{g['owner_url']}"))
print(result, f"({agent.calls} API calls)")
