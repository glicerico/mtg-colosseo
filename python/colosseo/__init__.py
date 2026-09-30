"""MTG Colosseo: a platform where agents and humans play Magic: The Gathering against each other.

Quick start::

    from colosseo import Agent, run_game
    from colosseo.agents import HeuristicAgent

    result = run_game(HeuristicAgent(), "xmage", "fdn:azorius-skies", "fdn:gruul-stompers")
"""
from .agent import Agent, GameResult, play
from .client import ColosseoClient, ColosseoError, DEFAULT_SERVER, SeatConnection
from .env import ColosseoEnv
from .protocol import Action, Decision, Obj, Option, PlayerState, State
from .render import render_decision, render_state
from .runner import MatchResult, elo_ratings, round_robin, run_game, run_match, wait_for_server

__version__ = "0.1.0"

__all__ = [
    "Agent", "GameResult", "play",
    "ColosseoClient", "ColosseoError", "DEFAULT_SERVER", "SeatConnection",
    "ColosseoEnv",
    "Action", "Decision", "Obj", "Option", "PlayerState", "State",
    "render_decision", "render_state",
    "MatchResult", "elo_ratings", "round_robin", "run_game", "run_match", "wait_for_server",
]
