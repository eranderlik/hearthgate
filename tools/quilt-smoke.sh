#!/usr/bin/env bash
# Headless dedicated-server smoke test on Quilt Loader. Quilt has no jar of its
# own: it runs the Fabric jar with the regular Fabric API.
#
#   tools/quilt-smoke.sh <minecraft-version>      # e.g. 26.1 or 26.3
#
# Installs a Quilt server for that Minecraft version into a scratch directory,
# adds Fabric API (the version gradle.properties lists for it) and the built
# hearthgate-fabric jar (run ./gradlew :fabric:build first), then runs the same
# checks as tools/server-smoke.sh: Hearthgate reports the server reachable,
# `hearthgate invite` over RCON returns an invitation, and `stop` shuts down.
#
# Environment:
#   PORT           server port (default 25568); RCON uses PORT+100
#   JAVA           java 25+ to run the installer and the server (default: java)
#   QUILT_DIR      scratch directory (default: a fresh temp dir, deleted after)
#   QUILT_LOADER   Quilt Loader version (default: the installer's choice)
#   HEARTHGATE_JAR the Fabric jar to test (default: fabric/build/libs/hearthgate-fabric-*.jar)
#   SMOKE_TIMEOUT, SMOKE_EXPECT: see tools/server-smoke.sh
set -euo pipefail
cd "$(dirname "$0")/.."
if [ $# -ne 1 ]; then
	echo "usage: $0 <minecraft-version>" >&2
	exit 2
fi
MC=$1
JAVA=${JAVA:-java}
INSTALLER_VERSION=0.15.1
FABRIC_API=$(sed -n "s/^fabric_api_${MC//./\\.}=//p" gradle.properties)
[ -n "$FABRIC_API" ] || { echo "no fabric_api_$MC in gradle.properties" >&2; exit 2; }
JAR=${HEARTHGATE_JAR:-$(ls fabric/build/libs/hearthgate-fabric-*.jar 2>/dev/null | grep -v -e sources -e native | head -1 || true)}
[ -f "$JAR" ] || { echo "no Fabric jar; run ./gradlew :fabric:build first" >&2; exit 2; }
JAR=$(realpath "$JAR")

if [ -n "${QUILT_DIR:-}" ]; then
	DIR=$QUILT_DIR
	mkdir -p "$DIR"
else
	DIR=$(mktemp -d)
	trap 'rm -rf "$DIR"' EXIT
fi
DIR=$(realpath "$DIR")

CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/hearthgate-smoke
mkdir -p "$CACHE"
fetch() { # url file: download unless cached
	if [ ! -s "$2" ]; then
		curl -fsSL -o "$2.part" "$1"
		mv -f "$2.part" "$2"
	fi
}
INSTALLER=$CACHE/quilt-installer-$INSTALLER_VERSION.jar
fetch "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/$INSTALLER_VERSION/quilt-installer-$INSTALLER_VERSION.jar" "$INSTALLER"
API_JAR=$CACHE/fabric-api-$FABRIC_API.jar
fetch "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API/fabric-api-$FABRIC_API.jar" "$API_JAR"

echo "Installing Quilt server for Minecraft $MC into $DIR"
"$JAVA" -jar "$INSTALLER" install server "$MC" ${QUILT_LOADER:-} --download-server --install-dir="$DIR" > "$DIR/install.log" 2>&1 \
	|| { cat "$DIR/install.log" >&2; exit 1; }
mkdir -p "$DIR/mods"
rm -f "$DIR"/mods/*.jar
cp "$API_JAR" "$JAR" "$DIR/mods/"

export PORT=${PORT:-25568}
export SMOKE_COMMAND="exec '$JAVA' -jar quilt-server-launch.jar --nogui"
export SMOKE_LOG=$DIR/smoke.log
SMOKE_TIMEOUT=${SMOKE_TIMEOUT:-300} tools/server-smoke.sh quilt "$DIR"
