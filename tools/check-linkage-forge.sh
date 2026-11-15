#!/usr/bin/env bash
# Like check-linkage.sh, for the Forge jar: verifies that every Minecraft and
# Forge (net.minecraftforge) class, method, and field referenced by the jar
# (compiled against the oldest supported version) exists in each supported
# Minecraft version's Forge. Run after ./gradlew :forge:build, with JDK 25 or
# newer as `java`.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSIONS=$(sed -n 's/^forge_\([0-9.]*\)=.*/\1/p' gradle.properties)
JAR=$(ls forge/build/libs/hearthgate-forge-*.jar | grep -v -e sources -e slim | head -1)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
(cd "$WORK" && unzip -q "$OLDPWD/$JAR" 'dev/*')
export LINK_PREFIXES=net/minecraftforge/
status=0
for v in $VERSIONS; do
	LIST=$(./gradlew -q :forge:printLinkJars -Pmc="$v" | grep "^/.*\.jar$") # the task also prints build noise
	mapfile -t JARS <<< "$LIST"
	(cd "$WORK" && java "$OLDPWD/tools/LinkCheck.java" "$v" "${JARS[@]}") | tee "$WORK/out"
	grep -q ' 0 missing' "$WORK/out" || status=1
done
exit $status
