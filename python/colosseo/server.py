"""Starting a local Colosseo server from Python (useful for scripts and notebooks)."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import List, Optional

from .runner import wait_for_server

__all__ = ["find_root", "launch"]


def find_root() -> Path:
    """The repository root (the directory containing engine/, web/ and decks/)."""
    env = os.environ.get("COLOSSEO_ROOT")
    if env:
        return Path(env)
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "engine").is_dir() and (parent / "decks").is_dir():
            return parent
    raise FileNotFoundError("can't locate the Colosseo repository; set COLOSSEO_ROOT")


def launch(port: int = 7070, root: Optional[Path] = None, java_opts: str = "-Xmx4g",
           extra_args: Optional[List[str]] = None, wait: bool = True, log_file: Optional[str] = None,
           timeout: float = 300.0) -> subprocess.Popen:
    """Starts ``scripts/run_server.sh`` in the background and (by default) waits until it answers.

    The first start builds XMage's card database (about a minute). Stop it with ``proc.terminate()``.
    """
    root = Path(root) if root else find_root()
    env = dict(os.environ, PORT=str(port), JAVA_OPTS=java_opts)
    out = open(log_file, "ab") if log_file else subprocess.DEVNULL
    proc = subprocess.Popen([str(root / "scripts" / "run_server.sh"), *(extra_args or [])],
                            cwd=root, env=env, stdout=out, stderr=subprocess.STDOUT)
    if wait:
        try:
            wait_for_server(f"http://localhost:{port}", timeout=timeout)
        except TimeoutError:
            proc.terminate()
            raise
    return proc
