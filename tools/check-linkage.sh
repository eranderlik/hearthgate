#!/usr/bin/env bash
# Verifies that every Minecraft class, method, and field referenced by the
# built mod jar (compiled against the oldest supported version) exists in
# each supported Minecraft version. Run after ./gradlew :fabric:build.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSIONS=$(sed -n 's/^fabric_api_\([0-9.]*\)=.*/\1/p' gradle.properties)
JAR=$(ls fabric/build/libs/tailcat-mc-fabric-*.jar | grep -v sources | head -1)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
(cd "$WORK" && unzip -q "$OLDPWD/$JAR" 'dev/*')
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf"
status=0
for v in $VERSIONS; do
	if [ ! -f "$CACHE/$v/minecraft-merged-deobf-$v.jar" ]; then
		./gradlew -q :fabric:dependencies --configuration compileClasspath -Pmc="$v" > /dev/null
	fi
	(cd "$WORK" && java "$OLDPWD/tools/LinkCheck.java" "$CACHE/$v/minecraft-merged-deobf-$v.jar") | tee "$WORK/out"
	grep -q ' 0 missing' "$WORK/out" || status=1
done
exit $status
