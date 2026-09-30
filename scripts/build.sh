#!/usr/bin/env bash
# Builds everything needed to run the Colosseo server: XMage (once) and the engine.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ ! -f "$HOME/.m2/repository/org/mage/mage-sets/1.4.61/mage-sets-1.4.61.jar" ] || [ "${REBUILD_XMAGE:-0}" = "1" ]; then
    "$ROOT/scripts/build_xmage.sh"
fi
echo ">> building the engine"
(cd "$ROOT/engine" && mvn -B -q package)
echo ">> done: engine/target/colosseo-engine.jar"
