#!/usr/bin/env bash
# Verifies that every Minecraft class, method, and field referenced by the
# built mod jar (compiled against the oldest supported version) exists in
# each supported Minecraft version. Run after ./gradlew :fabric:build, with
# JDK 25 or newer as `java` (LinkCheck uses the class-file API).
#
# Gradle reports each version's Minecraft jars (printMinecraftJars), so this
# doesn't depend on where Loom caches them.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSIONS=$(sed -n 's/^fabric_api_\([0-9.]*\)=.*/\1/p' gradle.properties)
JAR=$(ls fabric/build/libs/hearthgate-fabric-*.jar | grep -v sources | head -1)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
(cd "$WORK" && unzip -q "$OLDPWD/$JAR" 'dev/*')
status=0
for v in $VERSIONS; do
	LIST=$(./gradlew -q :fabric:printMinecraftJars -Pmc="$v" | grep "^/") # other plugins may print build noise (fails the script if Gradle does)
	mapfile -t JARS <<< "$LIST"
	(cd "$WORK" && java "$OLDPWD/tools/LinkCheck.java" "$v" "${JARS[@]}") | tee "$WORK/out"
	grep -q ' 0 missing' "$WORK/out" || status=1
done
exit $status
