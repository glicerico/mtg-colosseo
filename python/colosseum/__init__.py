"""MTG Colosseum: a platform where agents and humans play Magic: The Gathering against each other.

Quick start::

    from colosseum import Agent, run_game
    from colosseum.agents import HeuristicAgent

    result = run_game(HeuristicAgent(), "xmage", "fdn:azorius-skies", "fdn:gruul-stompers")
"""
from .agent import Agent, GameResult, is_terminal, outcome, play
from .client import ColosseumClient, ColosseumError, DEFAULT_SERVER, SeatConnection
from .env import ColosseumEnv
from .protocol import Action, Decision, Obj, Option, PlayerState, State
from .render import render_decision, render_state
from .runner import (MatchResult, ScheduledGame, elo_ratings, match_schedule, round_robin, run_game, run_match,
                     wait_for_server)

__version__ = "0.1.0"

__all__ = [
    "Agent", "GameResult", "is_terminal", "outcome", "play",
    "ColosseumClient", "ColosseumError", "DEFAULT_SERVER", "SeatConnection",
    "ColosseumEnv",
    "Action", "Decision", "Obj", "Option", "PlayerState", "State",
    "render_decision", "render_state",
    "MatchResult", "ScheduledGame", "elo_ratings", "match_schedule", "round_robin", "run_game", "run_match",
    "wait_for_server",
]
