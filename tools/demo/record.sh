#!/usr/bin/env bash
# Records the scripted share-and-join demo: two Fabric dev clients ("Alex"
# hosts, "Steve" joins) side by side on a virtual display, driven by
# fabric/src/gametest/.../DemoDriver.java. Needs Xvfb, ffmpeg, network access
# (real Tailcat relays) and Go on PATH for the build.
#
#   tools/demo/record.sh [-Pmc=26.3] [-PdemoSeed=...]
#
# Output: build/demo/raw.mkv (2560x720) and build/demo/events.log; then run
# tools/demo/render.sh to cut, caption and export.
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$PWD
out=$root/build/demo
disp=${DEMO_DISPLAY:-:77}

rm -rf "$out"
mkdir -p "$out"

for role in host join; do
	run=fabric/run/demo-$role
	mkdir -p "$run"
	rm -rf "$run/saves"
	# Skip first-launch screens, keep rendering while unfocused, and pin the
	# GUI scale so both windows look the same.
	cat > "$run/options.txt" <<-EOF
		onboardAccessibility:false
		skipMultiplayerWarning:true
		joinedFirstServer:true
		tutorialStep:none
		pauseOnLostFocus:false
		inactivityFpsLimit:"minimized"
		maxFps:60
		renderDistance:8
		simulationDistance:8
		guiScale:3
		narrator:0
		soundCategory_master:0.0
		realmsNotifications:false
		hideSplashTexts:true
		lang:en_us
	EOF
done

pids=()
cleanup() {
	for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
	wait 2>/dev/null || true
}
trap cleanup EXIT

Xvfb "$disp" -screen 0 2560x720x24 -nolisten tcp >"$out/xvfb.log" 2>&1 &
pids+=($!)
sleep 2
export DISPLAY=$disp

./gradlew :fabric:compileGametestJava -Pdemo "$@" -q
./gradlew :fabric:runDemoHost -Pdemo "$@" >"$out/host.log" 2>&1 &
pids+=($!)
sleep 20 # let the first build release its locks
./gradlew :fabric:runDemoJoin -Pdemo "$@" >"$out/join.log" 2>&1 &
pids+=($!)

wait_for() { # file, seconds
	local i=0
	until [[ -e $out/$1 ]]; do
		for r in host join; do
			if [[ -e $out/failed-$r ]]; then echo "demo $r failed; see $out/$r.log" >&2; exit 1; fi
		done
		((i++ < $2 * 2)) || { echo "timed out waiting for $1" >&2; exit 1; }
		sleep 0.5
	done
}
wait_for host-ready 600
wait_for join-ready 600
echo "both clients ready; recording"

ffmpeg -hide_banner -loglevel error -f x11grab -framerate 30 -video_size 2560x720 -draw_mouse 0 -i "$disp" \
	-c:v libx264 -preset ultrafast -crf 14 -pix_fmt yuv444p "$out/raw.mkv" </dev/null &
rec=$!
pids+=($rec)
date +%s%3N >"$out/record-start"
sleep 1
touch "$out/go"

wait_for host-done 600
sleep 1
kill -INT "$rec"
wait "$rec" 2>/dev/null || true
echo "recorded $out/raw.mkv"
