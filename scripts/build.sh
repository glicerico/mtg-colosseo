#!/usr/bin/env bash
# Builds everything needed to run the Colosseo server: XMage (once) and the engine.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# rebuild when the XMage patches changed since the last build
STAMP="$HOME/.m2/repository/org/mage/colosseo-patches.sha"
PATCHES="$(cat "$ROOT"/scripts/build_xmage.sh "$ROOT"/scripts/xmage-patches/*.patch 2>/dev/null | sha256sum | cut -d' ' -f1)"
if [ ! -f "$HOME/.m2/repository/org/mage/mage-sets/1.4.61/mage-sets-1.4.61.jar" ] || [ "${REBUILD_XMAGE:-0}" = "1" ] \
        || [ "$(cat "$STAMP" 2>/dev/null)" != "$PATCHES" ]; then
    "$ROOT/scripts/build_xmage.sh"
    echo "$PATCHES" > "$STAMP"
fi
echo ">> building the engine"
COMMIT="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then COMMIT="$COMMIT-dirty"; fi
(cd "$ROOT/engine" && mvn -B -q package -Dsource.commit="$COMMIT")
echo ">> done: engine/target/colosseo-engine.jar"
