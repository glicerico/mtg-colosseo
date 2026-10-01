import json
import os
from pathlib import Path

import pytest

from colosseo import Decision

FIXTURES = Path(__file__).parent / "fixtures"
SERVER = os.environ.get("COLOSSEO_SERVER", "http://localhost:7070")
DIALOG_DECK = str(Path(__file__).parent / "decks" / "dialog-test.dck")


@pytest.fixture(scope="session")
def raw_decisions():
    """One real decision message per kind, recorded from engine games."""
    return json.loads((FIXTURES / "decisions.json").read_text())


@pytest.fixture
def decisions(raw_decisions):
    return {k: Decision(json.loads(json.dumps(v))) for k, v in raw_decisions.items()}


#: set COLOSSEO_REQUIRE_SERVER=1 (as CI does) to make integration tests fail instead of skipping
REQUIRE_SERVER = os.environ.get("COLOSSEO_REQUIRE_SERVER") == "1"


def server_up() -> bool:
    if REQUIRE_SERVER:
        return True
    from colosseo import ColosseoClient
    try:
        ColosseoClient(SERVER, timeout=3).health()
        return True
    except Exception:
        return False
