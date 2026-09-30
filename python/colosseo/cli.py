"""Command line interface: ``colosseo --help`` (or ``python -m colosseo``)."""
from __future__ import annotations

import argparse
import importlib
import json
import logging
import sys
from typing import Any, List

from .agent import Agent
from .client import ColosseoClient, DEFAULT_SERVER
from .runner import AgentSpec, round_robin, run_game, run_match

BUILTIN_AGENTS = {
    "random": "colosseo.agents.random_agent:RandomAgent",
    "heuristic": "colosseo.agents.heuristic:HeuristicAgent",
    "claude": "colosseo.agents.llm:ClaudeAgent",
}


def resolve_agent(spec: str) -> AgentSpec:
    """``random`` | ``heuristic`` | ``claude`` | ``xmage`` | ``xmage:N`` | ``human`` | ``package.module:ClassName``."""
    if spec.startswith("xmage") or spec == "human":
        return spec
    target = BUILTIN_AGENTS.get(spec, spec)
    if ":" not in target:
        raise SystemExit(f"unknown agent {spec!r}: use {', '.join(BUILTIN_AGENTS)}, xmage[:N], human or module:Class")
    module_name, cls_name = target.split(":", 1)
    cls = getattr(importlib.import_module(module_name), cls_name)
    if not (isinstance(cls, type) and issubclass(cls, Agent)) and not callable(cls):
        raise SystemExit(f"{target} is not an Agent class or factory")
    return cls


def _print(obj: Any) -> None:
    print(json.dumps(obj, indent=2))


def cmd_decks(args: argparse.Namespace) -> None:
    client = ColosseoClient(args.server)
    for d in client.decks(full=True):
        print(f"{d['id']:32s} {d.get('colors', ''):6s} {d['name']} - {d.get('description', '')}")
    for s in client.sets():
        print(f"{s['deck']:32s} {'':6s} random sealed deck from {s['name']}")


def cmd_games(args: argparse.Namespace) -> None:
    for g in ColosseoClient(args.server).games():
        seats = " vs ".join(f"{s['name']} ({s['type']})" for s in g["seats"])
        result = g.get("result") or {}
        outcome = ""
        if result:
            outcome = "draw" if result.get("winner_seat") is None else f"winner: {result.get('winner')}"
        print(f"{g['id']}  {g['status']:9s} turn {g['turn']:3d}  {seats}  {outcome}")


def cmd_play(args: argparse.Namespace) -> None:
    a = resolve_agent(args.agent)
    b = resolve_agent(args.opponent)
    if args.games == 1:
        def created(info: dict) -> None:
            print(f"game {info['game_id']} created - watch it at {args.server}/#/watch/{info['game_id']}")
            for s in info["seats"]:
                if s["type"] == "human":
                    print(f"  play seat {s['seat']} at {args.server}/#/game/{info['game_id']}/seat/{s['seat']}")
        result = run_game(a, b, args.deck, args.opp_deck or args.deck, server=args.server,
                          max_turns=args.max_turns, pace_ms=args.pace_ms, starting_seat=args.starting_seat,
                          on_created=created)
        _print(result)
        return
    match = run_match(a, b, games=args.games, decks=[(args.deck, args.opp_deck or args.deck)], server=args.server,
                      max_turns=args.max_turns, pace_ms=args.pace_ms, parallel=args.parallel)
    print(match)


def cmd_match(args: argparse.Namespace) -> None:
    decks: Any = args.decks or None
    match = run_match(resolve_agent(args.a), resolve_agent(args.b), games=args.games, decks=decks,
                      server=args.server, max_turns=args.max_turns, parallel=args.parallel, seed=args.seed)
    print(match)
    if args.json:
        _print({"a": match.a, "b": match.b, "wins_a": match.wins_a, "wins_b": match.wins_b,
                "draws": match.draws, "errors": match.errors, "games": match.games})


def cmd_tournament(args: argparse.Namespace) -> None:
    players = {spec: resolve_agent(spec) for spec in args.players}
    matches, ratings = round_robin(players, games=args.games, decks=args.decks or None, server=args.server,
                                   max_turns=args.max_turns, parallel=args.parallel, seed=args.seed)
    for m in matches:
        print(m)
    print("\nElo:")
    for name, rating in ratings.items():
        print(f"  {name:30s} {rating:7.1f}")


def cmd_watch(args: argparse.Namespace) -> None:
    """Text spectator: prints the game log as it happens."""
    client = ColosseoClient(args.server)
    with client.spectate(args.game_id, reveal=args.reveal) as conn:
        for msg in conn.messages():
            t = msg.get("type")
            if t == "hello":
                for e in msg.get("log", []):
                    print(f"[T{e['turn']}] {e['text']}")
            elif t == "log":
                e = msg["entry"]
                print(f"[T{e['turn']}] {e['text']}")
            elif t == "action" and msg.get("comment"):
                print(f"      seat {msg['seat']}: {msg['summary']} -- {msg['comment']}")
            elif t == "game_over":
                _print(msg["result"])
                return


def cmd_serve(args: argparse.Namespace) -> None:
    from .server import launch
    proc = launch(port=args.port, wait=False)
    print(f"starting Colosseo server on http://localhost:{args.port} (Ctrl-C to stop)")
    try:
        proc.wait()
    except KeyboardInterrupt:
        proc.terminate()


def main(argv: List[str] = None) -> None:
    parser = argparse.ArgumentParser(prog="colosseo", description="MTG Colosseo - agents and humans playing Magic")
    parser.add_argument("--server", default=DEFAULT_SERVER, help="server URL (default %(default)s)")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("serve", help="start a local server (scripts/run_server.sh)")
    p.add_argument("--port", type=int, default=7070)
    p.set_defaults(fn=cmd_serve)

    sub.add_parser("decks", help="list decks").set_defaults(fn=cmd_decks)
    sub.add_parser("games", help="list games on the server").set_defaults(fn=cmd_games)

    def game_opts(p: argparse.ArgumentParser) -> None:
        p.add_argument("--max-turns", type=int, default=60)
        p.add_argument("--parallel", type=int, default=1, help="games played concurrently")

    p = sub.add_parser("play", help="play one agent against an opponent")
    p.add_argument("--agent", default="heuristic", help="random|heuristic|claude|human|xmage[:N]|module:Class")
    p.add_argument("--opponent", default="xmage", help="same forms as --agent")
    p.add_argument("--deck", default="fdn:azorius-skies")
    p.add_argument("--opp-deck", default="fdn:gruul-stompers")
    p.add_argument("--games", type=int, default=1)
    p.add_argument("--pace-ms", type=int, default=0, help="slow the game down for spectators")
    p.add_argument("--starting-seat", type=int, default=-1)
    game_opts(p)
    p.set_defaults(fn=cmd_play)

    p = sub.add_parser("match", help="series between two agents over random deck pairings")
    p.add_argument("a")
    p.add_argument("b")
    p.add_argument("--games", type=int, default=10)
    p.add_argument("--decks", nargs="*", help="deck ids to sample from (default: all)")
    p.add_argument("--seed", type=int)
    p.add_argument("--json", action="store_true", help="print per-game results as JSON")
    game_opts(p)
    p.set_defaults(fn=cmd_match)

    p = sub.add_parser("tournament", help="round robin with Elo ratings")
    p.add_argument("players", nargs="+")
    p.add_argument("--games", type=int, default=10, help="games per pairing")
    p.add_argument("--decks", nargs="*")
    p.add_argument("--seed", type=int)
    game_opts(p)
    p.set_defaults(fn=cmd_tournament)

    p = sub.add_parser("watch", help="print a game's log live")
    p.add_argument("game_id")
    p.add_argument("--reveal", action="store_true", help="show both hands")
    p.set_defaults(fn=cmd_watch)

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING, format="%(levelname)s %(message)s")
    try:
        args.fn(args)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
