# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Hearthgate is a Fabric mod (Minecraft 26.1–26.3, Java 25) that shares a singleplayer world or a dedicated server through a WireGuard tunnel using Tailscale's Tailcat library. The Go networking code is compiled to WebAssembly and then AOT-compiled to JVM bytecode, so it runs inside the game's JVM with no native code. README.md has the full design notes, performance numbers, and user-facing docs.

## Commands

Requires Go and git on `PATH`. Gradle provisions JDK 25 itself. Override Go with `-Pgo=/path/to/go`.

```sh
./gradlew build                                   # mod jar -> fabric/build/libs/hearthgate-fabric-<version>.jar
./gradlew :fabric:nativeAddonJar                  # optional native add-on for this platform (needs cgo / a C compiler)

(cd helper && go vet ./... && go test ./...)      # Go unit tests (native GOOS)
./gradlew :core:test                              # host + Wasm smoke tests, offline
./gradlew :core:test -PnetworkTests               # + real tunnels through Tailcat's public relays
./gradlew :core:test -PnetworkTests -PhelperJit=c1   # how CI runs them
./gradlew :core:test -PtestNative -PnetworkTests  # + native helper tests
./gradlew :core:test --tests '*EndToEndTest.nativeHostNativeJoin' -PtestNative -PnetworkTests   # single test
./gradlew :fabric:runClientGameTest -Pmc=26.3     # in-game test (opens a window; headless: xvfb-run -a -s "-screen 0 1920x1080x24")
./gradlew :fabric:runClientGameTest -PgametestNative  # game test with native add-on on the dedicated server
tools/check-linkage.sh                            # after :fabric:build: verifies the 26.1-built jar links on every supported version
tools/check-linkage-neoforge.sh                   # same for the NeoForge jar (Minecraft + net.neoforged references)
tools/server-smoke.sh :neoforge:runServer neoforge/run/server [-Pmc=26.3] [-PprebuiltJar=<jar>]  # headless dedicated-server smoke test (RCON; PORT env, default 25566)
./gradlew :neoforge:runClient -Pmc=<version>      # NeoForge dev client
tools/check-linkage-forge.sh                      # same for the Forge jar (after :forge:build)
tools/server-smoke.sh :forge:runServer forge/run/server [-Pmc=26.3] [-PprebuiltJar=<jar>]  # Forge smoke test (the jar is forge/build/libs/hearthgate-forge-<v>.jar, not -slim)
tools/quilt-smoke.sh 26.1                         # Quilt server with the Fabric jar + Fabric API (needs :fabric:build; PORT default 25568)
./gradlew :forge:runClient -Pmc=<version>         # Forge dev client
./gradlew :fabric:runClient -Pmc=<version>        # dev client
```

Useful test flags (`core/build.gradle` `test` block): `-PtestVerbose` (Tailcat logs; stalled transfers dump goroutines), `-PhelperEnv=K=V`, `-PtestHuge` (`-XX:-DontCompileHugeMethods`), `-PtestJfr`. Without `-PnetworkTests`, network tests are skipped, so an offline `:core:test` pass doesn't cover tunnels.

Dev-only env vars: `HEARTHGATE_DEV_OFFLINE_AUTH=1` (two-player testing with one account; dev environment only), `HEARTHGATE_VERBOSE=1`.

## Architecture

Three parts, one pipeline:

- **`helper/` (Go)**: a small program on `tailcat`. All logic is in `internal/app` (`host`, `join`, `keygen` subcommands), shared by `cmd/hearthgate-helper` (built for wasip1 by Gradle, or natively) and `cmd/libhearthgate` (C ABI for the native add-on, loaded via Java FFM). It talks to Java as **JSON lines on stdout** and stops when stdin closes. `internal/hostnet` has `_wasip1` vs `_native` implementations. `build-tags.txt` holds the `ts_omit_*` tags that trim Tailscale.
- **`core/` (Java, loader-independent)**: the WASI preview 1 host plus a custom `hearthgate` import module (`core/host/HelperHost.java`). WASI p1 can't create sockets, so Java NIO provides them as non-blocking fds, and `poll_oneoff` is backed by an NIO `Selector`. The host also does ChaCha20-Poly1305 (`aead_seal`/`aead_open`) and relay TLS (`tls_start`) via the JDK. `NetworkPolicy` enforces what the guest may reach: public addresses on 80/443 only, loopback on the target port only, listen on loopback only. `HelperBackend` picks Wasm or native (`nativehelper/`). `JitPolicy` keeps the generated classes out of C2 on ≤2-core machines. `core/server/` is the dedicated-server logic: identity persistence, supervision/backoff, make-before-break key rotation, Discord webhook.
- **`common/` (Java, loader-independent Minecraft code)**: compiled against vanilla Minecraft 26.1 only (Loom with just the `minecraft` dependency, no loader). Split source sets: `src/client` (`dev.hearthgate.common.client`: screens, share/join sessions, `Compat`, `HearthgateClientCommon`) and `src/main` (`dev.hearthgate.common.server`: `HearthgateServerCommon`, `/hearthgate` command; plus `dev.hearthgate.common.Platform`, and assets/lang/icon in `src/main/resources`). Loom fails the build if `main` references client classes. Loaders don't depend on common's jar: each loader module adds common's source dirs to its own source sets (see `fabric/build.gradle`), so common's classes and resources are compiled into the mod jar. Loaders feed `common` through `Platform`/`ClientPlatform` (set once from the entrypoint) and by calling the plain `HearthgateClientCommon.on*` / `HearthgateServerCommon.on*` methods from their events.
- **`neoforge/`**: the thin NeoForge adapter (ModDevGradle). `HearthgateNeoForgeClient` / `HearthgateNeoForgeServer` are `@Mod` classes with `dist` set (the server one only exists on dedicated servers), plus `NeoForgePlatform`. No split source sets: it adds both of common's source dirs to `main` (the client/server split is enforced when `:common` compiles). core + Chicory are JarJar'd. `-Pmc=<version>` picks `neoforge_<version>` from gradle.properties; `-PprebuiltJar=<jar>` makes a run load that jar from `run/*/mods` instead of the project's classes, to test the oldest-built jar on a newer Minecraft. Loom and ModDevGradle are both declared (`apply false`) in the root build.gradle.
- **`forge/`**: the thin Minecraft Forge adapter (ForgeGradle 7 + `net.minecraftforge.jarjar`, both declared `apply false` in the root build.gradle). Like `neoforge/` it adds common's source dirs to `main` and JarJars core + Chicory (`jarJar` task makes the real jar, `jar` becomes `-slim`; use the non-slim one). Forge's `@Mod` has no dist parameter: `HearthgateForge` branches on `FMLEnvironment.dist` into `HearthgateForgeClient` / `HearthgateForgeServer` (EventBus 7: `XEvent.BUS.addListener`). It registers `DisplayTest.IGNORE_ALL_VERSION` so it never blocks mismatched clients/servers. The main source set's classes and resources are redirected into one directory (`build/sourceSets/main`) because Forge dev runs treat one directory as the mod. `-Pmc=` picks `forge_<version>`; `-PprebuiltJar=<jar>` works as for NeoForge. The native add-on jar has a generated `META-INF/mods.toml` with `modLoader = "lowcodefml"`.
- **Quilt** has no module: Quilt Loader provides `fabricloader` 0.19.5, so the Fabric jar (which therefore requires only `>=0.19.5`) and the regular Fabric API run on Quilt. `tools/quilt-smoke.sh` tests this.
- **`fabric/`**: the thin Fabric adapter: entrypoints + event glue (`HearthgateFabricClient`, `HearthgateFabricServer`) and `FabricPlatform`. Same split source sets (`src/client`, `src/main`), plus `src/gametest` with the client game tests.

**Build pipeline in `core/build.gradle`** (incremental): `vendorHelper` (`go mod vendor`) → `patchHelper` (`git apply helper/patches/*.patch` onto `helper/vendor`) → `buildHelperWasm` (`GOOS=wasip1`) → `aotHelper` (Chicory build-time compiler → generated class `dev.hearthgate.core.wasm.HearthgateHelper`) → `helperBuildId` (SHA-256 of the helper sources, checked at runtime against the native library so a mismatched add-on falls back to Wasm).

## Things that aren't obvious

- **Never edit `helper/vendor/`.** It's regenerated on every build. To change Tailscale/wireguard-go code, edit or add a patch in `helper/patches/`. Patches must apply byte-for-byte to LF sources (`.gitattributes` marks them `-text`). README's "Patches to dependencies" section explains each one.
- **Multi-version support from one jar**: `common` and `fabric` compile against 26.1 (`minecraft_version`). APIs that changed by 26.3 are bound reflectively by name in `common/.../client/Compat.java`, which works because 26.x is unobfuscated. Avoid using newer-only APIs directly. Run `tools/check-linkage.sh` to catch them. `-Pmc=<version>` selects which Minecraft/Fabric API to compile/run against.
- **Adding a Minecraft version**: add `fabric_api_<version>=…`, `neoforge_<version>=…` and `forge_<version>=…` to `gradle.properties` (the linkage script and `-Pmc` read versions from there), widen `minecraft` in `fabric/src/main/resources/fabric.mod.json`, add it to the CI gametest matrix, run the game test with `-Pmc=<version>` and `tools/check-linkage.sh`, and add any changed API to `Compat`.
- After changing dependencies, regenerate `THIRD_PARTY_NOTICES.md` with `tools/third_party_notices.py`.
- CI (`.github/workflows/build.yml`) builds the native add-on on a runner per platform (Linux on ubuntu-22.04 for glibc 2.34 compatibility) and runs game tests on every supported version, plus 26.3 with the native helper.
