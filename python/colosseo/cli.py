"""Command line interface: ``colosseo --help`` (or ``python -m colosseo``)."""
from __future__ import annotations

import argparse
import importlib
import json
import logging
import sys
from typing import Any, List

from .agent import Agent, outcome, play
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


def _client(args: argparse.Namespace) -> ColosseoClient:
    return ColosseoClient(args.server, api_key=args.api_key)


def cmd_decks(args: argparse.Namespace) -> None:
    client = _client(args)
    for d in client.decks(full=True):
        print(f"{d['id']:32s} {d.get('colors', ''):6s} {d['name']} - {d.get('description', '')}")
    for s in client.sets():
        print(f"{s['deck']:32s} {'':6s} random sealed deck from {s['name']}")


def cmd_games(args: argparse.Namespace) -> None:
    for g in _client(args).games():
        seats = " vs ".join(f"{s['name']} ({s['type']})" for s in g["seats"])
        result = g.get("result") or {}
        text = ""
        if result:
            kind = outcome(result)
            text = {"win": f"winner: {result.get('winner')}", "draw": "draw"}.get(kind, f"unfinished: {result.get('reason') or result.get('error')}")
        print(f"{g['id']}  {g['status']:10s} turn {g['turn']:3d}  {seats}  {text}")


def cmd_play(args: argparse.Namespace) -> None:
    a = resolve_agent(args.agent)
    b = resolve_agent(args.opponent)
    if args.games == 1:
        def created(info: dict) -> None:
            base = args.server.rstrip("/")
            print(f"game {info['game_id']} created - watch it at {base}{info.get('watch_url') or '/#/watch/' + info['game_id']}")
            if info.get("owner_url"):
                print(f"  watch with both hands (owner link, keep it private): {base}{info['owner_url']}")
            for s in info["seats"]:
                if s["type"] == "human":
                    url = s.get("url") or f"/#/game/{info['game_id']}/seat/{s['seat']}"
                    print(f"  play seat {s['seat']} at {base}{url}")
        result = run_game(a, b, args.deck, args.opp_deck or args.deck, server=args.server,
                          max_turns=args.max_turns, pace_ms=args.pace_ms, starting_seat=args.starting_seat,
                          seed=args.seed, on_created=created, client=_client(args))
        _print(result)
        return
    match = run_match(a, b, games=args.games, decks=[(args.deck, args.opp_deck or args.deck)], server=args.server,
                      max_turns=args.max_turns, pace_ms=args.pace_ms, parallel=args.parallel, seed=args.seed,
                      client=_client(args))
    print(match)


def cmd_match(args: argparse.Namespace) -> None:
    decks: Any = args.decks or None
    match = run_match(resolve_agent(args.a), resolve_agent(args.b), games=args.games, decks=decks,
                      server=args.server, max_turns=args.max_turns, parallel=args.parallel, seed=args.seed,
                      swap_decks=args.swap_decks, client=_client(args), rated=args.rated)
    print(match)
    print(f"match seed: {match.seed} (pass --seed {match.seed} to replay this schedule)")
    if args.json:
        _print({"a": match.a, "b": match.b, "wins_a": match.wins_a, "wins_b": match.wins_b,
                "draws": match.draws, "errors": match.errors, "seed": match.seed,
                "fallbacks_a": match.fallbacks_a, "fallbacks_b": match.fallbacks_b,
                "forfeits_a": match.forfeits_a, "forfeits_b": match.forfeits_b,
                "schedule": [vars(g) for g in match.schedule], "games": match.games})


def cmd_tournament(args: argparse.Namespace) -> None:
    players = {spec: resolve_agent(spec) for spec in args.players}
    matches, ratings = round_robin(players, games=args.games, decks=args.decks or None, server=args.server,
                                   max_turns=args.max_turns, parallel=args.parallel, seed=args.seed,
                                   client=_client(args), rated=args.rated)
    for m in matches:
        print(m)
    if matches:
        print(f"seed: {matches[0].seed}")
    print("\nElo:")
    for name, rating in ratings.items():
        print(f"  {name:30s} {rating:7.1f}")


def cmd_leaderboard(args: argparse.Namespace) -> None:
    board = _client(args).leaderboard()
    rows = board.get("ratings", [])
    print(f"{board.get('rated_games', 0)} rated games")
    if rows:
        print(f"{'agent':40s} {'Elo':>7s} {'games':>6s} {'W-L-D':>11s} {'unfin.':>6s} {'fallb.':>6s} {'forf.':>5s}")
    for r in rows:
        wld = f"{r['wins']}-{r['losses']}-{r['draws']}"
        print(f"{r['agent_id'][:40]:40s} {r['rating']:7.1f} {r['games']:6d} {wld:>11s} {r['unfinished']:6d}"
              f" {r['fallbacks']:6d} {r['forfeits']:5d}")


def cmd_record(args: argparse.Namespace) -> None:
    """Writes a game record (JSONL) to stdout: one seat's view with --seat, else the full record."""
    for line in _client(args).record(args.game_id, seat=args.seat, token=args.token):
        print(json.dumps(line))


def cmd_agent(args: argparse.Namespace) -> None:
    """Plays one seat of an existing game from this process (run it in its own container/sandbox)."""
    spec = resolve_agent(args.agent)
    if isinstance(spec, str):
        raise SystemExit("--agent must be a Python agent (built-in name or module:Class)")
    agent = spec() if not isinstance(spec, Agent) else spec
    result = play(agent, args.game_id, args.seat, args.server, token=args.token, client=_client(args),
                  on_error=args.on_error)
    _print(result)


def cmd_watch(args: argparse.Namespace) -> None:
    """Text spectator: prints the game log as it happens."""
    client = _client(args)
    with client.spectate(args.game_id, reveal=args.reveal, token=args.token) as conn:
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
            elif t == "error":
                print(f"! {msg.get('message')}", file=sys.stderr)
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
    parser.add_argument("--api-key", default=None, help="API key for servers started with --api-key (default: $COLOSSEO_API_KEY)")
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
        p.add_argument("--seed", type=int, help="seed (games: engine seed; matches: schedule seed)")

    def rated_opt(p: argparse.ArgumentParser) -> None:
        p.add_argument("--rated", action="store_true", help="count the games on the server's leaderboard")

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
    p.add_argument("--swap-decks", action="store_true", help="players also swap decks within each block of games")
    p.add_argument("--json", action="store_true", help="print the schedule and per-game results as JSON")
    game_opts(p)
    rated_opt(p)
    p.set_defaults(fn=cmd_match)

    p = sub.add_parser("tournament", help="round robin with Elo ratings")
    p.add_argument("players", nargs="+")
    p.add_argument("--games", type=int, default=10, help="games per pairing")
    p.add_argument("--decks", nargs="*")
    game_opts(p)
    rated_opt(p)
    p.set_defaults(fn=cmd_tournament)

    sub.add_parser("leaderboard", help="Elo ratings over the server's rated games").set_defaults(fn=cmd_leaderboard)

    p = sub.add_parser("record", help="download a game record (JSONL)")
    p.add_argument("game_id")
    p.add_argument("--seat", type=int, help="only what this seat could see (needs its token)")
    p.add_argument("--token", help="seat token or owner token")
    p.set_defaults(fn=cmd_record)

    p = sub.add_parser("agent", help="play one seat of an existing game from this process")
    p.add_argument("game_id")
    p.add_argument("--seat", type=int, required=True)
    p.add_argument("--token", help="the seat's token")
    p.add_argument("--agent", default="heuristic", help="random|heuristic|claude|module:Class")
    p.add_argument("--on-error", choices=["default", "forfeit"], default="default",
                   help="what an exception in the agent does (forfeit = concede the game)")
    p.set_defaults(fn=cmd_agent)

    p = sub.add_parser("watch", help="print a game's log live")
    p.add_argument("game_id")
    p.add_argument("--reveal", action="store_true", help="show both hands (needs --token on protected games)")
    p.add_argument("--token", help="the game's owner token")
    p.set_defaults(fn=cmd_watch)

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING, format="%(levelname)s %(message)s")
    try:
        args.fn(args)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
