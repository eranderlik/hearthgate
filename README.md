# Hearthgate

![Hearthgate: a lit pixel-art fireplace](docs/banner.png)

Play your singleplayer world with friends on other networks, or keep a
dedicated server reachable without port forwarding. No accounts, no VPN app,
nothing to install but the mod.

Click **Share with Hearthgate** in the pause menu, send the invitation to a
friend, and they paste it into **Multiplayer → Join with Hearthgate**. The
connection is a WireGuard® tunnel that goes peer-to-peer whenever NAT traversal
succeeds (usually) and falls back to an encrypted relay when it doesn't, using
Tailscale's open-source [Tailcat](https://github.com/tailscale/tailcat).

Hearthgate is an independent project, not affiliated with or endorsed by
Tailscale. It started from Tailscale's
[tailcat-for-minecraft](https://github.com/tailscale/tailcat-for-minecraft)
demo, which bundled six native Go executables and launched one as a
subprocess. This mod compiles the Go networking code to WebAssembly and runs it
**inside the game's JVM**, so it ships one platform-independent jar with no
native code, no executables to sign, and nothing for antivirus software to
quarantine. Dedicated servers that need more throughput can add an optional
per-platform [native helper add-on](#native-helper-add-on-optional).

| | |
|---|---|
| Minecraft | 26.1, 26.1.1, 26.1.2, 26.2, 26.3 (one jar for all) |
| Loader | Fabric Loader ≥ 0.20.1 with Fabric API |
| Java | 25 (what Minecraft 26.x requires) |
| Side | Client: share a singleplayer world, or join. Dedicated server: always-on hosting with optional Discord announcements. Players always need the mod to join. |

## Using it

**Host:** open a world, press Esc, click **Share with Hearthgate**, then
**Copy Invitation**, and send it to your friends privately. The share screen
shows who is connected and whether each connection is direct or relayed.
Sharing stops when you click **Stop Sharing** or leave the world. A stopped
invitation can't be used again.

**Join:** **Multiplayer → Join with Hearthgate** (top right), paste the
invitation, and click **Connect**. If the host is on a different Minecraft
version the screen warns you before you try.

The invitation is a secret. It contains the tunnel's pre-shared key, and
anyone holding it can connect while you share. Minecraft's own login still
applies: the world stays in online mode, so joiners need genuine accounts,
and `/kick` and `/ban` work as usual.

## Dedicated servers

Install the same jar (plus Fabric API) on a Fabric dedicated server. On
startup it creates `config/hearthgate-server.json` and starts hosting: the
server is reachable through Tailcat for as long as it runs, as an alternative
to opening a port on your router. Players join through **Multiplayer → Join
with Hearthgate**, exactly like a shared singleplayer world.

* **Stable invitation.** The server's identity (WireGuard key, pre-shared key,
  relay region) is kept in `config/hearthgate/identity.json`, which is created
  readable only by its owner. Restarting the server doesn't change the
  invitation.
* **Key rotation.** By default the keys rotate every 7 days, which retires the
  old invitation. Rotation is make-before-break: the new tunnel comes up
  first, and the old one keeps serving the players already connected through
  it until they leave, or for at most `drain_minutes`.
* **Supervision.** If the helper exits or the relays are unreachable, it
  restarts with backoff (5 s up to 5 min).
* **Discord.** Set `discord.webhook_url` (Channel settings → Integrations →
  Webhooks), or the `HEARTHGATE_DISCORD_WEBHOOK` environment variable to keep the
  secret out of the file. The server posts the invitation as an embed, edits
  **that same message** whenever the keys rotate, and marks it offline while
  the server is down. Anyone who can read the channel can connect, so use a
  private channel, and preferably the whitelist.

```jsonc
{
  "enabled": true,
  "rotate_after_hours": 168.0,     // 0 = never rotate
  "drain_minutes": 360.0,          // how long the previous invitation keeps serving connected players
  "server_name": "",               // for Discord; empty = MOTD
  "allow_private_relay_addresses": false,
  "verbose": false,
  "native_helper": "auto",         // "off" = always use WebAssembly, even with the add-on installed
  "discord": {
    "webhook_url": "",
    "edit_previous_message": true, // false = post a new message per rotation
    "mention": ""                  // e.g. "<@&ROLE_ID>" to ping a role on new invitations
  }
}
```

Operators (permission level 3) and the console have:

| Command | |
|---|---|
| `/hearthgate` or `/hearthgate status` | State, players connected via Hearthgate (direct/relayed), next rotation, last error |
| `/hearthgate invite` | Shows the invitation (click to copy) |
| `/hearthgate rotate` | Rotates keys now, e.g. if the invitation leaked |
| `/hearthgate announce` | Re-posts the announcement to Discord |

The server forwards tunnel connections to its own `server-port` on loopback,
or to `server-ip` if that is set. Its normal security settings all apply:
keep `online-mode=true`, and use the whitelist if the invitation is shared
widely.

### Native helper add-on (optional)

The WebAssembly helper's throughput, about 2 MB/s each way, is plenty for a
shared world, but on a server it is one budget shared by every player. For
busier servers there is an add-on jar per platform,
`hearthgate-native-<platform>-<version>.jar`. Put the one for your server's
platform in `mods/` next to the mod, and the server runs Tailcat's networking
as native Go instead:

| Platform | Add-on |
|---|---|
| Linux x86-64 (glibc 2.34+: Debian 12, Ubuntu 22.04, RHEL 9 or newer) | `linux-x86_64` |
| Linux ARM64 (same glibc requirement) | `linux-aarch64` |
| Windows x86-64 | `windows-x86_64` |
| macOS Apple silicon | `macos-aarch64` |

* It is the same Go helper, built as a shared library and called in-process
  through Java's Foreign Function & Memory API. There is no subprocess or
  executable, and nothing is downloaded. The library is extracted to
  `.hearthgate/natives/<hash>/` in the server directory and loaded from there.
* The add-on must match the mod exactly. Fabric enforces the version, and the
  library carries a hash of the helper sources, which the mod checks. If the
  add-on is missing, for another platform, mismatched, or can't load (musl
  libc/Alpine, older glibc), the server logs why and uses WebAssembly.
* `/hearthgate status` shows which helper is running.
* Java prints a one-time *restricted method* warning when the library loads.
  Add `--enable-native-access=ALL-UNNAMED` to the server's `java` command to
  allow it explicitly.
* The difference from WebAssembly is isolation. Native code opens sockets
  directly, so the JVM-enforced network policy described below doesn't apply.
  The helper still connects only to the configured server address and
  Tailcat's relays. It also shares the server's process: a crash in native
  code would stop the server, where a WebAssembly crash only restarts the
  tunnel. The helper recovers panics in its own goroutines, but not inside
  its dependencies.

## How it works

```
┌──────────────── Host's Minecraft (JVM) ─────────────────┐        ┌──────────────── Friend's Minecraft (JVM) ───────────────┐
│ Integrated server ◄─ 127.0.0.1 ─ Tailcat helper (Wasm)  │◄══════►│ Tailcat helper (Wasm) ─ 127.0.0.1 ─► Minecraft client   │
│                                  Go → WebAssembly → JVM │  UDP   │                                                         │
│                                  bytecode, on Chicory   │ (or DERP relay)                                                  │
└─────────────────────────────────────────────────────────┘        └─────────────────────────────────────────────────────────┘
```

* **`helper/`** is a small Go program built on the `tailcat` library
  (commands in `internal/app`, shared by `cmd/hearthgate-helper`, the WebAssembly
  build, and `cmd/libhearthgate`, the native add-on's C ABI). It serves
  one virtual port (25565) in the tunnel and proxies it to the integrated
  server (host), or listens on a loopback port that Minecraft connects to
  (join). It talks to the game as JSON lines on stdout and stops when stdin
  closes.
* It is compiled with `GOOS=wasip1`, then compiled **ahead of time to JVM
  bytecode** with the [Chicory](https://chicory.dev) build-time compiler, so
  nothing is interpreted or compiled when the game starts. Each share or join
  session gets its own WebAssembly instance, and so its own Go runtime, on a
  dedicated thread.
* **`core/`** is the host environment that instance runs in: a WASI preview 1
  implementation plus a small `hearthgate` import module for sockets. WASI preview 1
  can't create sockets, so the JVM provides them through Java NIO. Each socket
  appears to Go as a non-blocking file descriptor, so goroutines park in Go's
  normal wasip1 netpoller, and the runtime blocks in `poll_oneoff`, which the
  host implements with an NIO `Selector`. An open tunnel with no traffic costs
  about 0.2% of a CPU core.
* Every socket is created by Java, so the host enforces a **network policy**
  regardless of what the guest asks for. It may reach public addresses only on
  ports 80 and 443 (the Tailcat relays and their DERP map), loopback only on
  the shared world's port, and it may listen only on loopback.
* WireGuard's per-packet ChaCha20-Poly1305 is delegated to the JDK
  (`aead_seal`/`aead_open`), which uses hardware intrinsics. It is over 100×
  faster than the same code running as WebAssembly.
* TLS to the relays (DERP connections, netcheck's HTTPS probe, and the DERP
  map download) is also run by the JDK. `tls_start` hands a connected socket
  to a JDK TLS client, and the guest reads and writes plaintext. The JDK
  verifies the certificate chain against its trusted roots and the name
  against the relay's hostname. Connecting to a relay takes about 0.3 s
  instead of 2.7 s with `crypto/tls` running as WebAssembly. Pinned or
  self-signed relay certificates still use Go's TLS, and
  `-Dhearthgate.helperEnv=HEARTHGATE_GO_TLS=1` switches back to it entirely.
* On machines with 2 or fewer CPU cores, the helper's generated classes are
  kept out of HotSpot's C2 compiler (`JitPolicy`, which adds a compiler
  directive at runtime, so no launch flag is needed). See
  [Performance](#performance) for why.
* **`core/…/server/`** holds the dedicated-server logic, independent of the
  mod loader: identity persistence, supervision, rotation, and the Discord
  webhook client.
* **`common/`** holds all the Minecraft-facing code that doesn't depend on a
  mod loader, compiled against vanilla Minecraft. `src/client` has two
  screens, two buttons, and session management; `src/main` has the
  dedicated-server logic, the `/hearthgate` command, the language file and
  icon (Loom's split source sets keep the server side from touching client
  classes). It reaches the loader through a small `Platform` interface
  (config/game directories, whether a mod is loaded) and is driven by plain
  method calls from the loader's events.
  It is compiled against 26.1. The few APIs that changed by 26.3 (screen
  management, toasts, *Open to LAN*) are bound by name at startup in
  [`Compat`](common/src/client/java/dev/hearthgate/common/client/Compat.java). That
  works because Minecraft 26.x ships unobfuscated.
* **`fabric/`** is the Fabric adapter: the entrypoints, the event wiring and
  the `Platform` implementation. It compiles `common`'s sources into its own
  jar, so the mod is a single file.

### Performance

Measured on a desktop with both peers in one JVM (so each figure is shared by
two helpers):

| | |
|---|---|
| Time to share (invitation ready) | ~2 s |
| Time to join (tunnel up) | ~3 s |
| Throughput, after JIT warm-up | ~1.5–2 MB/s each way |
| Memory per session | ~25 MiB |
| CPU while idle | ~0.2% of one core |

That comfortably covers normal play (a few hundred KB/s at most), with chunk
loading on join taking a few seconds. Throughput is limited by Go's goroutine
scheduling running as WebAssembly. HotSpot declines to JIT-compile methods
above 8,000 bytecodes, and several hot Go runtime functions exceed that.
Players who can set JVM arguments gain about 25% with
`-XX:-DontCompileHugeMethods`. Go runtime settings can be passed to the helper
with `-Dhearthgate.helperEnv=GOGC=400`, but GC tuning measured under 10%.

**Machines with few cores.** For the first minute or two, C2 spends tens of
CPU-seconds optimizing the helper's very large generated methods. On a
2-core machine that starves the helper itself. It misses network deadlines,
its connection keeps switching between direct and relayed, and the
tunnelled TCP stream backs off until it barely moves. So on such machines
the helper's code runs with C1 only, which roughly halves peak throughput
but removes the stall. Measured with each side in its own JVM and its own
cores, echoing 8 MiB:

| Cores per side | First 8 MiB | After warm-up |
|---|---|---|
| 2, C2 (before) | 221 s (0.04 MB/s) | ~1.2 MB/s |
| 2, C1 only (now the default) | 25 s (0.34 MB/s) | 0.4–0.6 MB/s |
| 4, C2 (unchanged) | 28–31 s (~0.3 MB/s) | ~1.3–1.5 MB/s |

`-Dhearthgate.helperJit=c2` or `=c1` overrides the automatic choice.

On a server, the host helper's throughput is shared by all players. With the
native add-on it stops being the bottleneck (same machine, through Tailcat's
public relays, echoing 8 MiB per player):

| Server's helper | Players (WebAssembly clients) | Total throughput, each way |
|---|---|---|
| WebAssembly | 1 | 2.2 MB/s |
| WebAssembly | 4 | 1.9 MB/s, shared |
| Native | 1 | 2.3 MB/s (limited by the client) |
| Native | 4 | 8.8 MB/s (each client at its own maximum) |
| Native, with a native client | 1 | ~115 MB/s |

The native host is ready in 0.2 s and uses about 11 MiB.

## Building

Requirements: **Go** (helper/go.mod's version, or any Go ≥ 1.21 that can
download it) and **git** on `PATH`. Gradle provisions JDK 25 itself, both for
the build and for its own daemon, so no JDK needs to be installed.

```sh
./gradlew build           # mod jar: fabric/build/libs/hearthgate-fabric-<version>.jar
./gradlew :fabric:nativeAddonJar  # native add-on for this machine's platform (needs a C compiler for cgo)
```

The native add-on is built per platform on that platform: CI runs one job each
on Linux x86-64 and ARM64, Windows, and macOS. To cross-compile instead, pass
`-PnativePlatform=linux-aarch64 -PnativeCc='zig cc -target aarch64-linux-gnu'`
or similar.

The core module's build is a pipeline of incremental tasks: `vendorHelper`
(`go mod vendor`) → `patchHelper` (applies `helper/patches/`) →
`buildHelperWasm` → `aotHelper` (Chicory AOT) → `compileJava`. Use `-Pgo=…` to
pick a Go binary.

### Patches to dependencies

`helper/patches/` holds five small patches that make Tailscale's code usable
from WebAssembly with host-provided sockets. The first four should be upstreamed:

1. **`0001-netns-host-network`** (tailscale.com): `netns.SetHostNetwork` lets
   an embedder supply every socket Tailscale creates. `netns.Listener` now
   returns a narrow `ListenPacketer` interface instead of `*net.ListenConfig`;
   all its callers only call `ListenPacket`.
2. **`0002-neterror-wasip1`** (tailscale.com): an upstream bug.
   `neterror_js.go` lists `wasip1` in its build constraint, but its `_js.go`
   filename limits it to `GOOS=js`, so magicsock doesn't compile for wasip1.
3. **`0003-wireguard-host-aead`** (wireguard-go): a wasip1 implementation of the
   existing per-platform `chacha20poly1305New` hook that calls the host.
4. **`0004-derphttp-host-tls`** (tailscale.com): `derphttp.HostTLS` lets an
   embedder perform DERP's TLS. It applies only where standard verification
   would (no custom `TLSConfig`, pinned `CertName`, or `InsecureForTests`).
   Without TLS state there is no meta cert, so the server's key arrives through
   the normal HTTP upgrade. `DialRegionTLS` returns a `net.Conn` for netcheck.
5. **`0005-netstack-wasip1-queue`** (tailscale.com): wasip1-only sizes for the
   in-tunnel TCP stack. WebAssembly runs every goroutine on one thread, so a
   TCP sender queues its whole burst for WireGuard before the goroutine that
   drains the queue can run. Tailscale's 512-packet queue overflowed, the
   excess (retransmissions included) was dropped, and the stream then
   recovered one multi-second retransmission timeout at a time, which showed
   up as transfers stalling for minutes. The patch caps TCP buffers at 1 MiB
   (under 900 packets in flight per connection) and gives the queue 4096
   slots. Native builds are unchanged.

### Testing

```sh
(cd helper && go test ./...)                   # Go unit tests (native)
./gradlew :core:test                           # host + Wasm smoke tests (offline)
./gradlew :core:test -PnetworkTests            # + host/join and dedicated hosting (restart, rotation,
                                               #   Discord via a fake webhook) through Tailcat's real relays
./gradlew :core:test -PnetworkTests -PhelperJit=c1  # as CI runs them: both tunnel ends share a small
                                               #   runner, like two 2-core machines (-PtestVerbose for
                                               #   Tailcat's logs; a stalled transfer dumps goroutines)
./gradlew :core:test -PtestNative -PnetworkTests  # + the same on the native helper, and the
                                               #   multi-player throughput comparison
./gradlew :fabric:runClientGameTest -Pmc=26.3  # in-game test on a real client (opens a window)
./gradlew :fabric:runClientGameTest -PgametestNative  # ...with the native add-on on the dedicated server
tools/check-linkage.sh                         # 26.1-built jar links on every version
```

The client game tests: (1) create a world, share it through the mod's UI, join
it from a second in-process helper the way a remote player would, and
server-list-ping the integrated server through the tunnel; (2) start a
dedicated server with the mod, then join it through **Join with Hearthgate** with
its invitation and load into the world. Screenshots go to
`fabric/build/run/clientGameTest/screenshots/`. CI
(`.github/workflows/build.yml`) runs it on every supported version.

For two-player testing with one Minecraft account, launch the host with
`HEARTHGATE_DEV_OFFLINE_AUTH=1 ./gradlew :fabric:runClient`. It disables
authentication while sharing, works only in a development environment, and
is restored when sharing stops. Set `HEARTHGATE_VERBOSE=1` for Tailcat's networking
logs.

### Supporting a new Minecraft version

Add `fabric_api_<version>=…` to `gradle.properties`, widen `minecraft` in
`fabric.mod.json`, then run the game test with `-Pmc=<version>` and
`tools/check-linkage.sh`. If an API the mod uses has changed, add the
alternative to `Compat`.

## Licensing and naming

BSD-3-Clause (see [LICENSE](LICENSE)); bundled components are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (regenerate with
`tools/third_party_notices.py` after changing dependencies). The public Tailcat
relays are operated by Tailscale, are rate limited, and come with no uptime
promise.

Hearthgate is an independent, community-made mod. It isn't affiliated with,
sponsored by, or endorsed by Tailscale Inc. "Tailscale" is a registered
trademark of Tailscale Inc., and "Tailcat" is the name of Tailscale's
open-source library this mod uses. Those names appear here only to describe
that library and to credit the demo this project started from. Please don't
report Hearthgate issues to Tailscale.

The icon and banner are adapted from [LPC Fireplace](https://opengameart.org/content/lpc-fireplace)
by Lanea Zimmerman (Sharm), licensed
[CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/); see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md#artwork).

Minecraft is a trademark of Mojang Synergies AB. Hearthgate is not an official
Minecraft product and is not approved by or associated with Mojang or
Microsoft.

WireGuard is a registered trademark of Jason A. Donenfeld.
