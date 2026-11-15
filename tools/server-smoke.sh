#!/usr/bin/env bash
# Headless dedicated-server smoke test for any loader module.
#
#   tools/server-smoke.sh <gradle-run-task> <run-dir> [extra gradle args...]
#   tools/server-smoke.sh :neoforge:runServer neoforge/run/server -Pmc=26.3
#
# Writes eula.txt and server.properties into <run-dir> (the server's working
# directory, as set in the loader's run configuration), starts the server
# through Gradle, waits for Hearthgate's "server is reachable" log line, runs
# `hearthgate invite` over RCON (so no console stdin is needed) and checks
# that an invitation (hg1_...) comes back, then runs `stop`. Exits non-zero on any failure or timeout.
#
# Environment:
#   PORT           server port (default 25566); RCON uses PORT+100
#   SMOKE_TIMEOUT  seconds to wait for the server to become reachable (default 900;
#                  the first run downloads Minecraft's assets)
#   SMOKE_EXPECT   if set, an extended regex that must also appear in the log
#                  (e.g. "using the native helper")
#   SMOKE_LOG      log file (default <run-dir>/smoke.log)
#
# Needs network access (Tailcat's public relays) and Go on PATH, like the build.
set -uo pipefail
cd "$(dirname "$0")/.."
if [ $# -lt 2 ]; then
	echo "usage: $0 <gradle-run-task> <run-dir> [gradle args...]" >&2
	exit 2
fi
TASK=$1
RUNDIR=$2
shift 2
PORT=${PORT:-25566}
TIMEOUT=${SMOKE_TIMEOUT:-900}
mkdir -p "$RUNDIR"
LOG=${SMOKE_LOG:-$RUNDIR/smoke.log}
: > "$LOG"

echo "eula=true" > "$RUNDIR/eula.txt"
cat > "$RUNDIR/server.properties" <<EOF
server-port=$PORT
online-mode=false
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
enable-status=false
enable-rcon=true
rcon.port=$((PORT + 100))
rcon.password=smoke
EOF

WORK=$(mktemp -d)
./gradlew "$TASK" "$@" < /dev/null > "$LOG" 2>&1 &
GRADLE=$!

cleanup() {
	if kill -0 "$GRADLE" 2>/dev/null; then
		kill "$GRADLE" 2>/dev/null
		sleep 2
		pkill -P "$GRADLE" 2>/dev/null
	fi
	rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
	echo "SMOKE FAILED: $1" >&2
	echo "--- last 40 lines of $LOG ---" >&2
	tail -40 "$LOG" >&2
	exit 1
}

wait_for() { # pattern timeout
	local end=$((SECONDS + $2))
	while [ $SECONDS -lt $end ]; do
		grep -Eq "$1" "$LOG" && return 0
		kill -0 "$GRADLE" 2>/dev/null || { grep -Eq "$1" "$LOG"; return $?; }
		sleep 2
	done
	return 1
}

wait_for "Hearthgate: server is reachable" "$TIMEOUT" || fail "Hearthgate never reported the server reachable"
echo "server is reachable"

# Minimal RCON client: prints the response to one command.
rcon() {
	python3 -I - "$((PORT + 100))" "$1" <<'PY'
import socket, struct, sys, time
port, cmd = int(sys.argv[1]), sys.argv[2]
def send(s, i, t, body):
    p = struct.pack('<ii', i, t) + body.encode() + b'\0\0'
    s.sendall(struct.pack('<i', len(p)) + p)
def recv(s):
    n = struct.unpack('<i', s.recv(4))[0]
    d = b''
    while len(d) < n:
        d += s.recv(n - len(d))
    return struct.unpack('<ii', d[:8]), d[8:-2].decode(errors='replace')
for _ in range(30):
    try:
        s = socket.create_connection(('127.0.0.1', port), timeout=10)
        break
    except OSError:
        time.sleep(1)
else:
    sys.exit(1)
send(s, 1, 3, 'smoke'); recv(s)
send(s, 2, 2, cmd)
print(recv(s)[1])
PY
}
OUT=$(rcon "hearthgate invite") || fail "RCON connection failed"
echo "$OUT" | grep -Eq "hg1_[A-Za-z0-9_-]{20,}" || fail "no invitation from /hearthgate invite (got: $OUT)"
echo "invitation printed"

if [ -n "${SMOKE_EXPECT:-}" ]; then
	grep -Eq "$SMOKE_EXPECT" "$LOG" || fail "log lacks expected text: $SMOKE_EXPECT"
	echo "found: $SMOKE_EXPECT"
fi

rcon stop > /dev/null 2>&1 || true
end=$((SECONDS + 120))
while kill -0 "$GRADLE" 2>/dev/null && [ $SECONDS -lt $end ]; do sleep 2; done
kill -0 "$GRADLE" 2>/dev/null && fail "server did not stop within 120s"
wait "$GRADLE"
RC=$?
[ $RC -eq 0 ] || fail "gradle exited with status $RC"
if grep -E "\[(hearthgate)[^]]*/(ERROR)\]|\(hearthgate\) .*ERROR|ERROR.*[Hh]earthgate" "$LOG" | grep -v "Hearthgate disabled" | head -3 | grep -q .; then
	fail "Hearthgate logged errors"
fi
echo "SMOKE OK"
