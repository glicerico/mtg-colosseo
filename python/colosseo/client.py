"""HTTP + WebSocket client for a Colosseo server."""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any, Dict, Iterator, List, Optional

from websockets.sync.client import connect

DEFAULT_SERVER = "http://localhost:7070"

__all__ = ["ColosseoClient", "SeatConnection", "ColosseoError", "DEFAULT_SERVER"]


class ColosseoError(RuntimeError):
    pass


class ColosseoClient:
    """Thin REST client: decks, games and cards.

    >>> client = ColosseoClient("http://localhost:7070")
    >>> game = client.create_game([
    ...     {"name": "me", "type": "agent", "deck": "fdn:azorius-skies"},
    ...     {"name": "MAD", "type": "xmage", "deck": "fdn:gruul-stompers", "skill": 3},
    ... ])
    """

    def __init__(self, server: str = DEFAULT_SERVER, timeout: float = 60.0):
        self.server = server.rstrip("/")
        self.timeout = timeout

    # --- plumbing -----------------------------------------------------------------------------

    def _request(self, method: str, path: str, body: Any = None) -> Any:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.server + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode() or "null")
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            try:
                detail = json.loads(detail).get("error", detail)
            except ValueError:
                pass
            raise ColosseoError(f"{method} {path} failed ({e.code}): {detail}") from None
        except urllib.error.URLError as e:
            raise ColosseoError(f"can't reach Colosseo server at {self.server}: {e.reason}") from None

    def ws_url(self, path: str) -> str:
        if self.server.startswith("https://"):
            return "wss://" + self.server[len("https://"):] + path
        return "ws://" + self.server[len("http://"):] + path if self.server.startswith("http://") else self.server + path

    # --- API ----------------------------------------------------------------------------------

    def health(self) -> Dict[str, Any]:
        return self._request("GET", "/api/health")

    def decks(self, full: bool = False) -> List[Dict[str, Any]]:
        return self._request("GET", "/api/decks" + ("?full=1" if full else ""))

    def deck(self, deck_id: str) -> Dict[str, Any]:
        return self._request("GET", "/api/decks/" + urllib.request.quote(deck_id))

    def sets(self) -> List[Dict[str, Any]]:
        return self._request("GET", "/api/sets")

    def set_cards(self, code: str) -> List[Dict[str, Any]]:
        return self._request("GET", "/api/sets/" + code + "/cards")

    def cards(self, name: str) -> List[Dict[str, Any]]:
        return self._request("GET", "/api/cards?name=" + urllib.request.quote(name))

    def games(self) -> List[Dict[str, Any]]:
        return self._request("GET", "/api/games")

    def game(self, game_id: str) -> Dict[str, Any]:
        return self._request("GET", "/api/games/" + game_id)

    def create_game(self, seats: List[Dict[str, Any]], **options: Any) -> Dict[str, Any]:
        """Creates and starts a game.

        ``seats``: two dicts with ``type`` (``agent`` | ``human`` | ``xmage``), ``deck`` and optional
        ``name``, ``skill`` (xmage 1-10), ``stop_policy`` (``all`` | ``arena``), ``auto_pass``,
        ``yield_after_cast``, ``auto_pay``, ``timeout_s``.

        ``options``: ``starting_seat`` (-1 random), ``max_turns``, ``pace_ms``, ``seed``, ``record``, ``title``.
        """
        return self._request("POST", "/api/games", {"seats": seats, **options})

    def terminate(self, game_id: str) -> Dict[str, Any]:
        return self._request("POST", f"/api/games/{game_id}/terminate")

    def connect_seat(self, game_id: str, seat: int, token: Optional[str] = None) -> "SeatConnection":
        path = f"/ws/game/{game_id}?seat={seat}" + (f"&token={token}" if token else "")
        return SeatConnection(self.ws_url(path))

    def spectate(self, game_id: str, reveal: bool = False) -> "SeatConnection":
        return SeatConnection(self.ws_url(f"/ws/game/{game_id}?spectate=1" + ("&reveal=1" if reveal else "")))


class SeatConnection:
    """A WebSocket attached to a game (as a seat controller or spectator)."""

    def __init__(self, url: str, open_timeout: float = 30.0):
        self.url = url
        self.ws = connect(url, max_size=None, open_timeout=open_timeout, close_timeout=2)

    def send(self, message: Dict[str, Any]) -> None:
        self.ws.send(json.dumps(message))

    def recv(self, timeout: Optional[float] = None) -> Dict[str, Any]:
        return json.loads(self.ws.recv(timeout=timeout))

    def messages(self) -> Iterator[Dict[str, Any]]:
        while True:
            try:
                yield self.recv()
            except Exception:  # connection closed
                return

    def concede(self) -> None:
        self.send({"type": "concede"})

    def settings(self, **values: Any) -> None:
        self.send({"type": "settings", **values})

    def close(self) -> None:
        try:
            self.ws.close()
        except Exception:
            pass

    def __enter__(self) -> "SeatConnection":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()
