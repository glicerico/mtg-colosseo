#!/usr/bin/env bash
# Fetches XMage (the rules engine) and installs the modules the Colosseo engine needs into ~/.m2.
#
#   XMAGE_REF   git commit/tag of magefree/mage to build (default: the tested revision below)
#   XMAGE_DIR   where to clone XMage (default: .xmage in the repository root)
#
# Needs git, a JDK (17+) and Maven. Takes ~5 minutes and ~2 GB of disk.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
XMAGE_REF="${XMAGE_REF:-12aa9cfcc596680314125c9d968c1b7e5fadecf7}"   # XMage 1.4.61 (2026-09-30)
XMAGE_DIR="${XMAGE_DIR:-$ROOT/.xmage}"
XMAGE_VERSION="1.4.61"

if [ ! -d "$XMAGE_DIR/.git" ]; then
    echo ">> cloning XMage into $XMAGE_DIR"
    git clone --filter=blob:none --no-checkout https://github.com/magefree/mage "$XMAGE_DIR"
fi
cd "$XMAGE_DIR"
git sparse-checkout init --no-cone
# only the modules the engine depends on (the client, server and tests are not needed)
git sparse-checkout set '/*' '!/Mage.Client/' '!/Mage.Server/' '!/Mage.Server.Console/' '!/Mage.Tests/' \
    '!/Mage.Verify/' '!/Mage.Reports/' '!/Mage.Plugins/' '!/Utils/'
if ! git cat-file -e "$XMAGE_REF^{commit}" 2>/dev/null; then
    git fetch --depth 1 origin "$XMAGE_REF"
fi
git checkout -q --force "$XMAGE_REF"

# keep only the modules we build in the aggregator pom
sed -i.bak -E '/<module>(Mage\.Client|Mage\.Server|Mage\.Server\.Console|Mage\.Tests|Mage\.Verify|Mage\.Reports|Mage\.Plugins)<\/module>/d' pom.xml

MODULES="Mage,Mage.Common,Mage.Sets,Mage.Server.Plugins,Mage.Server.Plugins/Mage.Player.AI,Mage.Server.Plugins/Mage.Player.AI.MAD,Mage.Server.Plugins/Mage.Player.Human,Mage.Server.Plugins/Mage.Game.TwoPlayerDuel"
echo ">> building XMage $XMAGE_VERSION modules (this takes a few minutes)"
MAVEN_OPTS="${MAVEN_OPTS:--Xmx6g}" mvn -B -q install -DskipTests -Djacoco.skip=true -T 4 -pl "$MODULES" -am
mv pom.xml.bak pom.xml
echo ">> XMage installed into ~/.m2 (org.mage:*:$XMAGE_VERSION)"
