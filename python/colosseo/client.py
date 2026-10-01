"""HTTP + WebSocket client for a Colosseo server."""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Iterator, List, Optional

from websockets.exceptions import ConnectionClosed
from websockets.sync.client import connect

DEFAULT_SERVER = "http://localhost:7070"

#: WebSocket close codes the server uses to refuse a connection (retrying won't help)
CLOSE_BAD_TOKEN = 4001
CLOSE_FORBIDDEN = 4003
CLOSE_UNKNOWN_GAME = 4004
FATAL_CLOSE_CODES = (CLOSE_BAD_TOKEN, CLOSE_FORBIDDEN, CLOSE_UNKNOWN_GAME)

__all__ = ["ColosseoClient", "SeatConnection", "ColosseoError", "DEFAULT_SERVER", "FATAL_CLOSE_CODES"]


class ColosseoError(RuntimeError):
    def __init__(self, message: str, status: Optional[int] = None):
        super().__init__(message)
        self.status = status


class ColosseoClient:
    """Thin REST client: decks, games and cards.

    >>> client = ColosseoClient("http://localhost:7070")
    >>> game = client.create_game([
    ...     {"name": "me", "type": "agent", "deck": "fdn:azorius-skies"},
    ...     {"name": "MAD", "type": "xmage", "deck": "fdn:gruul-stompers", "skill": 3},
    ... ])
    """

    def __init__(self, server: str = DEFAULT_SERVER, timeout: float = 60.0, api_key: Optional[str] = None):
        """``api_key``: required by servers started with ``--api-key`` to create games (defaults to the
        ``COLOSSEO_API_KEY`` environment variable)."""
        self.server = server.rstrip("/")
        self.timeout = timeout
        self.api_key = api_key if api_key is not None else os.environ.get("COLOSSEO_API_KEY")

    # --- plumbing -----------------------------------------------------------------------------

    def _request(self, method: str, path: str, body: Any = None, token: Optional[str] = None) -> Any:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
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
            raise ColosseoError(f"{method} {path} failed ({e.code}): {detail}", e.code) from None
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

        ``options``: ``starting_seat`` (-1 random), ``max_turns``, ``pace_ms``, ``seed``, ``record``, ``title``,
        ``require_tokens``, ``public_comments``, ``abandon_timeout_s``, ``deadline_s``.

        The response holds the secrets for this game: each bridge seat's ``token`` and the ``owner_token``
        (needed to watch with hands revealed or to stop the game on a protected server).
        """
        return self._request("POST", "/api/games", {"seats": seats, **options}, token=self.api_key)

    def terminate(self, game_id: str, token: Optional[str] = None) -> Dict[str, Any]:
        """Stops a running game. ``token``: the game's owner token (or the API key) on protected servers."""
        return self._request("POST", f"/api/games/{game_id}/terminate", token=token or self.api_key)

    def connect_seat(self, game_id: str, seat: int, token: Optional[str] = None) -> "SeatConnection":
        path = f"/ws/game/{game_id}?seat={seat}" + (f"&token={urllib.parse.quote(token)}" if token else "")
        return SeatConnection(self.ws_url(path))

    def spectate(self, game_id: str, reveal: bool = False, token: Optional[str] = None) -> "SeatConnection":
        """Watches a game. ``reveal`` shows both hands and needs the owner ``token`` on protected games."""
        path = f"/ws/game/{game_id}?spectate=1" + ("&reveal=1" if reveal else "")
        if token:
            path += "&token=" + urllib.parse.quote(token)
        return SeatConnection(self.ws_url(path))


class SeatConnection:
    """A WebSocket attached to a game (as a seat controller or spectator)."""

    def __init__(self, url: str, open_timeout: float = 30.0):
        self.url = url
        self._cm = connect(url, max_size=None, open_timeout=open_timeout, close_timeout=2)
        self.ws = self._cm.__enter__()

    def send(self, message: Dict[str, Any]) -> None:
        self.ws.send(json.dumps(message))

    def recv(self, timeout: Optional[float] = None) -> Dict[str, Any]:
        return json.loads(self.ws.recv(timeout=timeout))

    #: close code and reason sent by the server, once the connection is closed (e.g. 4001 = bad token)
    close_code: Optional[int] = None
    close_reason: str = ""

    def messages(self) -> Iterator[Dict[str, Any]]:
        """Yields messages until the connection closes (then see :attr:`close_code`)."""
        while True:
            try:
                yield self.recv()
            except ConnectionClosed as e:
                rcvd = getattr(e, "rcvd", None)
                if rcvd is not None:
                    self.close_code, self.close_reason = rcvd.code, rcvd.reason
                return
            except Exception:  # noqa: BLE001 - any other transport failure ends the stream
                return

    @property
    def refused(self) -> bool:
        """Whether the server refused this connection (bad token, unknown game, ...): retrying won't help."""
        return self.close_code in FATAL_CLOSE_CODES

    def concede(self) -> None:
        self.send({"type": "concede"})

    def settings(self, **values: Any) -> None:
        self.send({"type": "settings", **values})

    def close(self) -> None:
        try:
            self._cm.__exit__(None, None, None)
        except Exception:
            pass

    def __enter__(self) -> "SeatConnection":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()
