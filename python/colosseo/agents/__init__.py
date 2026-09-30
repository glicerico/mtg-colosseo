"""Reference agents. ``ClaudeAgent`` needs the optional ``anthropic`` dependency (``pip install "colosseo[llm]"``)."""
from .heuristic import HeuristicAgent
from .random_agent import RandomAgent

__all__ = ["RandomAgent", "HeuristicAgent", "ClaudeAgent"]


def __getattr__(name):
    if name == "ClaudeAgent":
        from .llm import ClaudeAgent
        return ClaudeAgent
    raise AttributeError(name)
