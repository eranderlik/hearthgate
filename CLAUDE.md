# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Hearthgate is a Minecraft mod (26.1–26.3, Java 25) for **Fabric, Quilt, NeoForge and Minecraft Forge** that shares a singleplayer world or a dedicated server through a WireGuard tunnel using Tailscale's Tailcat library. The Go networking code is compiled to WebAssembly and then AOT-compiled to JVM bytecode, so it runs inside the game's JVM with no native code. Each loader gets one jar that runs on every supported Minecraft version and platform. README.md has the full design notes, performance numbers, and user-facing docs.

## Commands

Requires Go and git on `PATH`. Gradle provisions JDK 25 itself. Override Go with `-Pgo=/path/to/go`. `-Pmc=<version>` picks the Minecraft version (and matching loader build from `gradle.properties`) for any loader's compile or run; the default is 26.1.

**Build** (`./gradlew build` builds every loader):

```sh
./gradlew build                     # all jars, below
#   fabric/build/libs/hearthgate-fabric-<v>.jar      (Fabric and Quilt)
#   neoforge/build/libs/hearthgate-neoforge-<v>.jar
#   forge/build/libs/hearthgate-forge-<v>.jar        (ship this one, not -slim)
./gradlew :fabric:nativeAddonJar    # optional native add-on for this platform (needs cgo / a C compiler); one jar for all three loaders
```

**Helper and core tests:**

```sh
(cd helper && go vet ./... && go test ./...)      # Go unit tests (native GOOS)
./gradlew :core:test                              # host + Wasm smoke tests, offline
./gradlew :core:test -PnetworkTests               # + real tunnels through Tailcat's public relays
./gradlew :core:test -PnetworkTests -PhelperJit=c1   # how CI runs them
./gradlew :core:test -PtestNative -PnetworkTests  # + native helper tests
./gradlew :core:test --tests '*EndToEndTest.nativeHostNativeJoin' -PtestNative -PnetworkTests   # single test
```

Useful test flags (`core/build.gradle` `test` block): `-PtestVerbose` (Tailcat logs; stalled transfers dump goroutines), `-PhelperEnv=K=V`, `-PtestHuge` (`-XX:-DontCompileHugeMethods`), `-PtestJfr`. Without `-PnetworkTests`, network tests are skipped, so an offline `:core:test` pass doesn't cover tunnels.

**Per-loader testing.** Only Fabric has in-game (client) tests. NeoForge, Forge and Quilt are covered by headless dedicated-server smoke tests plus linkage checks, so client-side changes on those loaders need a manual `runClient`.

```sh
# Fabric
./gradlew :fabric:runClientGameTest -Pmc=26.3     # in-game test (opens a window; headless: xvfb-run -a -s "-screen 0 1920x1080x24")
./gradlew :fabric:runClientGameTest -PgametestNative  # game test with the native add-on on the dedicated server
tools/check-linkage.sh                            # after :fabric:build: the 26.1-built jar links on every supported version

# NeoForge
tools/server-smoke.sh :neoforge:runServer neoforge/run/server [-Pmc=26.3] [-PprebuiltJar=<jar>]
tools/check-linkage-neoforge.sh                   # Minecraft + net.neoforged references

# Forge
tools/server-smoke.sh :forge:runServer forge/run/server [-Pmc=26.3] [-PprebuiltJar=<jar>]
tools/check-linkage-forge.sh                      # after :forge:build

# Quilt (Fabric jar + regular Fabric API on a Quilt server; needs :fabric:build)
tools/quilt-smoke.sh 26.1                         # PORT default 25568

# Dev clients
./gradlew :fabric:runClient -Pmc=<version>
./gradlew :neoforge:runClient -Pmc=<version>
./gradlew :forge:runClient -Pmc=<version>
```

`tools/server-smoke.sh` starts the server through Gradle, waits for Hearthgate's "server is reachable" line, runs `hearthgate invite` over RCON and checks that an `hg1_…` invitation comes back. It needs network access. Env: `PORT` (default 25566, RCON on PORT+100), `SMOKE_TIMEOUT`, `SMOKE_EXPECT` (an extra log regex, e.g. `using the native helper`), `SMOKE_LOG`. `-PprebuiltJar=<jar>` (NeoForge/Forge) makes the run load that jar from `run/*/mods` instead of the project's classes. CI uses it to run the 26.1-built jar on every newer version.

Dev-only env vars: `HEARTHGATE_DEV_OFFLINE_AUTH=1` (two-player testing with one account; dev environment only), `HEARTHGATE_VERBOSE=1`.

## Architecture

```
helper/ (Go) ──wasip1──► core/ (Java, no Minecraft) ◄── common/ (vanilla Minecraft 26.1) ◄── fabric/ | neoforge/ | forge/
```

- **`helper/` (Go)**: a small program on `tailcat`. All logic is in `internal/app` (`host`, `join`, `keygen` subcommands), shared by `cmd/hearthgate-helper` (built for wasip1 by Gradle, or natively) and `cmd/libhearthgate` (C ABI for the native add-on, loaded via Java FFM). It talks to Java as **JSON lines on stdout**. Lines on stdin are commands (`drain` retires a host's invitation, `stacks` dumps goroutines), and it stops when stdin closes. The native add-on gets the same commands as C exports (`hg_drain`). `internal/hostnet` has `_wasip1` vs `_native` implementations. `build-tags.txt` holds the `ts_omit_*` tags that trim Tailscale.
- **`core/` (Java, no Minecraft or loader classes)**: the WASI preview 1 host plus a custom `hearthgate` import module (`core/host/HelperHost.java`). WASI p1 can't create sockets, so Java NIO provides them as non-blocking fds, and `poll_oneoff` is backed by an NIO `Selector`. The host also does ChaCha20-Poly1305 (`aead_seal`/`aead_open`) and relay TLS (`tls_start`) via the JDK. `NetworkPolicy` enforces what the guest may reach. TCP: public addresses on 80/443 only, loopback on the target port only, listen on loopback only. UDP: bind to wildcard or loopback only, and never send to loopback, multicast, broadcast, link-local or 0.0.0.0/8. Bind addresses must be IP literals and are never resolved as names. `HelperBackend` picks Wasm or native (`nativehelper/`). `JitPolicy` keeps the generated classes out of C2 on ≤2-core machines. `core/server/` is the dedicated-server logic: identity persistence, supervision/backoff, make-before-break key rotation (`PersistentHost` drains the old helper as soon as the new one is up), Discord webhook.
- **`common/` (Java, all loader-independent Minecraft code)**: compiled against vanilla Minecraft 26.1 only (Loom with just the `minecraft` dependency, no loader). It has split source sets, and Loom fails the build if `main` references client classes:
  - `src/client` (`dev.hearthgate.common.client`): screens, share/join `Sessions`, `Compat`, `LoopbackOnly`, `HearthgateClientCommon`, `ClientPlatform`.
  - `src/main`: `dev.hearthgate.common.server` (`HearthgateServerCommon`, the `/hearthgate` command), `dev.hearthgate.common.Platform`, and the shared assets/lang/icon.

  Loader modules don't depend on common's jar. Each adds common's source dirs to its own source sets, so common's classes and resources are compiled into every mod jar.
- **Loader adapters** are deliberately thin. Each one only:
  1. Implements `Platform` (`configDir`, `gameDir`, `isDevelopmentEnvironment`, `isModLoaded`) and registers it once with `Platform.set(...)` from each entrypoint. On the client it also passes a `ClientPlatform` (`addScreenWidget`) to `HearthgateClientCommon.init(...)`.
  2. Forwards loader events to the plain static `HearthgateClientCommon.on*` (`onClientStarted`/`Stopping`/`Tick`, `onPlayJoin`/`Disconnect`, `onScreenInit`) and `HearthgateServerCommon.on*` (`onServerStarted`/`Stopping`) methods.

  New behavior goes in `common` (or `core`), not in an adapter. A new event hook means a new `on*` method in common, wired up in all three adapters.

| | `fabric/` | `neoforge/` | `forge/` |
|---|---|---|---|
| Build plugin | Loom | ModDevGradle | ForgeGradle 7 + `net.minecraftforge.jarjar` |
| Entrypoints | `HearthgateFabricClient`, `HearthgateFabricServer` | `@Mod(dist=…)` classes `HearthgateNeoForgeClient`, `HearthgateNeoForgeServer` (server one only on dedicated servers) | one `@Mod` class `HearthgateForge` branches on `FMLEnvironment.dist` into `HearthgateForgeClient` / `HearthgateForgeServer` (EventBus 7: `XEvent.BUS.addListener`) |
| Source sets | split `src/client` + `src/main`, plus `src/gametest` | single `main` with both of common's dirs (the split is enforced when `:common` compiles) | same as NeoForge; classes and resources are redirected into one dir (`build/sourceSets/main`) because Forge dev runs treat one directory as the mod |
| core + Chicory | `include` (jar-in-jar) | JarJar | JarJar (`jarJar` task makes the real jar; `jar` becomes `-slim`) |
| Metadata | `fabric.mod.json` (`fabricloader >=0.19.5`) | `META-INF/neoforge.mods.toml` (NeoForge ≥ 26.1.0) | `META-INF/mods.toml` (Forge ≥ 62) + `pack.mcmeta` (without it Forge shows a warning screen and skips the lang files) |
| Screen widgets | Fabric API `Screens.getWidgets(screen).add(...)` | `ScreenWidgets` holds the current `ScreenEvent.Init.Post` | same `ScreenWidgets` pattern |

  Forge registers `DisplayTest.IGNORE_ALL_VERSION` so it never blocks clients or servers without the mod. Loom, ModDevGradle and ForgeGradle are all declared `apply false` in the root `build.gradle`, and the Forge maven is in `settings.gradle`.
- **Quilt** has no module. Quilt Loader provides `fabricloader` 0.19.5, so the Fabric jar (which therefore requires only `>=0.19.5`; don't raise it) and the regular Fabric API run on Quilt.
- **Native add-on** (`:fabric:nativeAddonJar`, built in `fabric/build.gradle`) is code-free: one jar per platform with the native library plus generated metadata for all three loaders (`fabric.mod.json`, `neoforge.mods.toml`, and a Forge `mods.toml` with `modLoader = "lowcodefml"`).

**Build pipeline in `core/build.gradle`** (incremental): `vendorHelper` (`go mod vendor`) → `patchHelper` (`git apply helper/patches/*.patch` onto `helper/vendor`) → `buildHelperWasm` (`GOOS=wasip1`) → `aotHelper` (Chicory build-time compiler → generated class `dev.hearthgate.core.wasm.HearthgateHelper`) → `helperBuildId` (SHA-256 of the helper sources, checked at runtime against the native library so a mismatched add-on falls back to Wasm).

## Things that aren't obvious

- **Never edit `helper/vendor/`.** It's regenerated on every build. To change Tailscale/wireguard-go code, edit or add a patch in `helper/patches/`. Patches must apply byte-for-byte to LF sources (`.gitattributes` marks them `-text`). README's "Patches to dependencies" section explains each one.
- **One jar per loader for every Minecraft version.** `common` and all three adapters compile against 26.1 (`minecraft_version`). APIs that changed by 26.3 are bound reflectively by name in `common/.../client/Compat.java`, which works because 26.x is unobfuscated. Don't use newer-only APIs directly. The linkage scripts catch them: `tools/check-linkage.sh` for Fabric, plus `-neoforge.sh` / `-forge.sh`, which also check loader API references across loader versions.
- **`LoopbackOnly` is a security boundary.** Sharing a singleplayer world calls vanilla *Open to LAN*, which listens on all interfaces and announces the world. `LoopbackOnly` swaps the listener for a loopback one and stops the LAN pinger by reflecting on two private fields (`ServerConnectionListener.channels`, `IntegratedServer.lanPinger`). If those fields can't be bound, sharing refuses to start. Keep that fail-closed behavior, and recheck it on every new Minecraft version (the Fabric client game test covers it).
- **Native ABI.** Changing the C exports in `cmd/libhearthgate` means bumping `abiVersion` there and `ABI_VERSION` in `core/.../nativehelper/NativeHelpers.java` together. An add-on with a mismatched ABI or build ID is ignored, and Hearthgate falls back to Wasm.
- **Adding a Minecraft version**:
  1. Add `fabric_api_<version>=…`, `neoforge_<version>=…` and `forge_<version>=…` to `gradle.properties`. `-Pmc` and the linkage scripts read versions from there.
  2. Widen the Minecraft range in `fabric/src/main/resources/fabric.mod.json`, `neoforge/src/main/resources/META-INF/neoforge.mods.toml` and `forge/src/main/resources/META-INF/mods.toml` (currently `[26.1,26.4)`).
  3. Add the version to the CI matrices for `gametest`, `neoforge` and `forge` (and `quilt` if relevant).
  4. Run the Fabric game test with `-Pmc=<version>`, the server smoke tests, and all three linkage scripts.
  5. Add any changed API to `Compat`.
- After changing dependencies, regenerate `THIRD_PARTY_NOTICES.md` with `tools/third_party_notices.py`.
- **CI and releases** (`.github/workflows/build.yml`, named `release`). The workflow runs only on `v*` tags and manual dispatch, not on push or PR. A tag must equal `v` + `mod_version` in `gradle.properties`, so bump that first.
  - Jobs: build + Go tests + Fabric linkage, then the native add-on per platform (Linux on ubuntu-22.04 for glibc 2.34; it waits on `build` so a bad tag fails before the costly macOS/Windows runners), the Fabric game tests on every version (plus 26.3 with the native helper), NeoForge and Forge server smoke tests of the 26.1-built jar on every version, and Quilt smoke tests on 26.1 and 26.3.
  - The `release` job publishes the GitHub release only if every job passes.
