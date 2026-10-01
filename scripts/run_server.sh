#!/usr/bin/env bash
# Starts the Colosseo server (web UI + agent API) on http://localhost:${PORT:-7070}
#   PORT=7070 JAVA_OPTS=-Xmx4g scripts/run_server.sh [extra engine args]
# By default the server only listens on 127.0.0.1 and runs in open (trusted local) mode. To accept other
# machines, bind to all interfaces; tokens are then required (see --help and docs/architecture.md):
#   HOST=0.0.0.0 COLOSSEO_API_KEY=... scripts/run_server.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/engine/target/colosseo-engine.jar"
if [ ! -f "$JAR" ]; then
    "$ROOT/scripts/build.sh"
fi
DATA="${COLOSSEO_DATA:-$ROOT/data}"
mkdir -p "$DATA"
# XMage keeps its card database in ./db of the working directory (built on first start, ~1 minute)
cd "$DATA"
exec java ${JAVA_OPTS:--Xmx4g} -jar "$JAR" --host "${HOST:-127.0.0.1}" --port "${PORT:-7070}" --web "$ROOT/web" --decks "$ROOT/decks" --data "$DATA" "$@"
