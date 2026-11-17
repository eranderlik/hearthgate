# How Hearthgate compares to other world-sharing mods

This is a technical comparison of Hearthgate with the other Minecraft mods
that make a singleplayer world or a server reachable without port forwarding.
It is meant for reviewers who want to know what each project does on the wire,
and who it trusts to do it.

Every claim about another project comes from reading its source at the commit
listed below, not from its marketing page. Where we inferred behaviour rather
than reading it directly, the text says so. If we've got something wrong,
please open an issue and we'll correct it.

## Projects reviewed

Snapshot taken 2026-11-16.

| Project | Repository | Commit | License |
|---|---|---|---|
| e4mc (mod) | [vgskye/e4mc-minecraft-architectury](https://github.com/vgskye/e4mc-minecraft-architectury) | `93ce8a8` (2026-10-01) | MIT |
| e4mc relay | [vgskye/e4mc-quiclime](https://github.com/vgskye/e4mc-quiclime) | `6e97f68` (2026-01-13) | MIT / Apache-2.0 |
| e4mc Dialtone (iroh binding) | [vgskye/iroh-java](https://github.com/vgskye/iroh-java) | `5bc00b4` (2026-10-01) | see repo |
| World Host | [Gaming32/world-host](https://github.com/Gaming32/world-host) | `dc55708` (2025-04-05) | MIT |
| Essential | [EssentialGG/Essential](https://github.com/EssentialGG/Essential) | `54ba13b` (2026-11-02) | source-available, all rights reserved |
| playit.gg plugin (official) | [playit-cloud/playit-minecraft-plugin](https://github.com/playit-cloud/playit-minecraft-plugin) | `4888f44` (2026-02-19) | see repo |
| playit.gg mod (unofficial, archived) | [maxomatic458/playit-minecraft-mod](https://github.com/maxomatic458/playit-minecraft-mod) | `5b22a9a` (2025-08-19) | MIT |
| Ngrok-LAN | [Alujjdnd/Ngrok-LAN](https://github.com/Alujjdnd/Ngrok-LAN) | `1f8f702` (2024-12-28) | MIT |
| ZenithX | [adamcollinsytt-ops/ZenithX-mod](https://github.com/adamcollinsytt-ops/ZenithX-mod) | `1fc5e50` (2026-11-10) | all rights reserved |
| tailcat-for-minecraft | [tailscale/tailcat-for-minecraft](https://github.com/tailscale/tailcat-for-minecraft) | `c561a89` (2026-08-26) | see repo |

**Not reviewed:** Seamless – Serverless Multiplayer and TunnelCraft, because
their Modrinth pages link no source. Playit Companion, the official playit mod,
links no source either, so the playit rows below describe the official Bukkit
plugin, which uses the same playit control protocol. Peerless and PeerCraft are
CurseForge-only, and we didn't find their source.

## Summary

| | Hearthgate | e4mc | World Host | Essential | playit.gg | ZenithX |
|---|---|---|---|---|---|---|
| Joiner needs the mod | **yes** | no (yes for P2P) | no via proxy, yes for friends | yes | no | yes |
| Account or sign-up | none | none | Mojang session (automatic) | Essential account (Mojang-backed) | playit.gg account (guest or claimed) | ZenithX account |
| Direct (P2P) game traffic | WireGuard via magicsock NAT traversal | iroh QUIC hole punching ("Dialtone", modded joiners only) | UPnP port mapping only | ICE (STUN/TURN) with QUIC or PseudoTCP | no | custom ICE over UDP |
| Fallback | DERP relay (Tailcat's public map) | e4mc QUIC relay | World Host proxy server | Essential TURN servers | always relayed | ZenithX relay |
| Who can connect | holders of the invitation (WireGuard PSK + key) | anyone with the `word-word.<domain>` address | friends (by UUID) via UPnP; anyone with the proxy address | invited UUIDs | anyone with the tunnel address | friends or invites (via ZenithX backend) |
| Encryption added on the path | WireGuard, end to end between peers | QUIC TLS to relay; iroh QUIC end to end on Dialtone | none beyond Minecraft's | QUIC with certificate checks off ("MC deals with encryption") | none beyond Minecraft's | AES-GCM, key passed through signalling |
| LAN exposure while sharing | loopback only; LAN pinger stopped; fails closed | vanilla Open to LAN listener left in place | vanilla Open to LAN listener left in place (plus a UPnP port on the router) | Open to LAN required first | n/a (server) or vanilla LAN | not checked |
| Native code | none by default (Go → Wasm → JVM bytecode); optional server add-on | iroh native library **downloaded at runtime**, SHA-256 pinned; netty QUIC natives | none | netty QUIC natives | none (plugin) / agent binary | none found |
| Dedicated servers | first-class: persistent identity, key rotation, Discord | works, warns it's "not recommended" | no | no | the main use case | no |
| Minecraft versions | 26.1–26.3 only | 1.17–26.3 | 1.16.1–1.21.5 | 1.12.2–26.3 | plugin: Spigot/Paper; Companion 1.18–1.21.8 | 1.21.9–26.3 |

## Hearthgate in one paragraph

Each share or join starts a Tailcat node (userspace WireGuard plus Tailscale's
magicsock and DERP). The Go code is compiled to `wasip1` and then
ahead-of-time to JVM bytecode, so it runs inside the game's JVM. The
invitation `hg1_<base64url JSON>` carries a Tailcat token holding the host's
WireGuard public key, a fresh 256-bit pre-shared key and the relay region
(`helper/internal/invite/invite.go`, `tailcat.PresharedKey`). Peers connect
directly when NAT traversal succeeds and through DERP when it doesn't. Either
way, the only thing a relay ever carries is WireGuard ciphertext. The host
helper serves one virtual TCP port and forwards it to the integrated server on
loopback. Minecraft's own login and encryption run unchanged inside the tunnel.
Java creates every socket, so `NetworkPolicy` limits what the guest code can
reach, whatever it asks for: public addresses on ports 80/443 only, and
loopback only on the target port.

## Project by project

### e4mc

e4mc is the closest comparison and the most widely used. It has two transports.

**Relay path (any client).** When the integrated server starts its TCP
listener, a mixin (`ServerConnectionListenerMixin`) captures Minecraft's own
channel initializer and event loop. `QuiclimeSession` asks
`broker.e4mc.link/getBestRelay` for a relay, opens a QUIC connection to it
(ALPN `quiclime`) and requests a domain. The relay (`e4mc-quiclime`,
`routing.rs`) assigns `<word>-<word>.<base domain>` from a 7,776-word list,
which gives about 6×10⁷ names (≈26 bits). Players connect to that name on TCP
25565. The relay reads the Minecraft handshake, routes on the hostname
(rate-limited per source IP), and pipes the TCP stream over a QUIC stream to
the host. Vanilla clients work. The address is the only access control before
Minecraft's own login, so the relay operator can see the handshake and Login
Start (username) in clear text, though not the session after online-mode
encryption kicks in.

**Dialtone (P2P, both sides modded).** If the relay advertises
`dialtone_sidecar`, the host also starts an iroh endpoint and registers its
iroh ticket with the relay. With `dialtoneSanitizeTicket` on (the default),
direct IP addresses are removed from the ticket first, leaving only relay
addresses. A modded joiner looks up a `TXT e4mc-dialtone-resolver=` record for
the domain, fetches the ticket from
`https://<resolver>/.well-known/dialtone_ticket/<domain>`
(`ServerNameResolverMixin`), and connects over iroh, which hole-punches when it
can. On Dialtone connections e4mc **turns off Minecraft's own encryption**
(`ServerLoginPacketListenerImplMixin.killDoubleEncryption`) and relies on
iroh's QUIC TLS instead. It binds Mojang session authentication to that TLS
session by feeding TLS-exported keying material into `digestData` in place of
the usual server hash. That's a neat design: our reading is that a relay
handing out a substitute ticket couldn't complete online-mode authentication
against the real host, because the two TLS sessions would export different
keys.

**Natives.** The iroh JNI library isn't in the jar. `iroh-java`'s
`Native.loadNativeLibrary` downloads it from `natives.e4mc.link` into a cache
directory and checks it against a SHA-256 hash compiled into the jar before
`System.load`. The QUIC codec comes from netty-incubator.

**LAN.** The mixin hooks in *after* vanilla `startTcpServerListener`, and we
found no code that rebinds the listener or stops the LAN pinger. Open to LAN
keeps its vanilla behaviour: it listens on all interfaces and announces the
world on the LAN.

**How Hearthgate differs:**
- e4mc's big advantage is that vanilla clients can join. Hearthgate can't do
  that.
- Both projects offer P2P. In e4mc it's only for modded joiners; in Hearthgate
  it's the only mode.
- Access: an e4mc address is a public name on a shared relay, and anyone who
  learns or guesses it reaches Minecraft's login. A Hearthgate invitation
  contains a WireGuard PSK, so without it the WireGuard handshake fails before
  any Minecraft traffic is exchanged.
- In e4mc, the relay operator runs the domain broker, the relays, the Dialtone
  ticket lookup and the native-library CDN. Hearthgate depends on Tailcat's
  DERP map (`tailcat.dev/derpmap.json`) and its relays, which see ciphertext
  only, plus whoever you send the invitation to.
- e4mc runs on almost every Minecraft version. Hearthgate only runs on 26.x.

### World Host

World Host keeps a long-lived TCP connection to `world-host.jemnetworks.com`.
It authenticates through Mojang's `joinServer` and encrypts that connection the
way Minecraft does, with RSA key exchange and AES-CFB8 (`ProtocolClient`).

- **Friends.** When a friend asks to join, the host (`RequestJoin` in
  `WorldHostS2CMessage`) checks the friend list. If UPnP is available, it asks
  the router for a 60-second port mapping to the LAN port and sends the
  friend its public IP and port. Otherwise it falls back to the proxy.
- **Proxy.** The address is `<connection-id>.<base ip>`. The connection ID is a
  42-bit `SecureRandom` value chosen once per game launch and spelled as three
  words (`WorldHost.CONNECTION_ID`, `connectionIdToString`). Vanilla clients
  can join through it, and anyone with the address reaches Minecraft's login.
- **Hole punching** (`protocol/punch`) exists, but in this snapshot it's only
  used for Simple Voice Chat's UDP (`PunchReason.SIMPLE_VOICE_CHAT`). Game
  traffic is either a direct UPnP connection or proxied.
- The last release supports 1.21.5. The repository's last commit is
  2025-04-05.

**How Hearthgate differs:** Hearthgate doesn't touch the router. A UPnP
mapping opens the LAN port to the whole internet for as long as it lasts, not
just to the friend. Hearthgate also gets direct connections on networks
without UPnP, such as CGNAT, many mobile hotspots and university networks,
where World Host has to proxy. World Host's friend list is the better UX for
a fixed group of people, and its proxy lets vanilla clients join.

### Essential

Essential is a large, source-available mod. World hosting ("SPS") is one
feature among many.

- **Signalling** goes through Essential's account connection. A host only
  creates an agent for users it has invited (`IceManagerImpl.handlePacket`:
  `isInvited(user)`).
- **Transport** is Essential's own ICE implementation (`subprojects/ice`),
  using STUN and TURN servers at `{us,eu,as}.{stun,turn}.essential.gg`. On top
  of it runs QUIC (netty-incubator native QUIC) when both sides support it,
  otherwise ice4j's PseudoTCP.
- **Encryption.** The QUIC layer uses a self-signed certificate and
  `InsecureTrustManagerFactory`, with the comment "MC deals with encryption by
  itself" (`QuicBackendImpl`). Confidentiality and server authentication come
  from Minecraft's online-mode handshake, which
  `Mixin_IceChannelIsNotLocal` keeps on by making ICE channels count as
  non-local.
- **Server side.** The ICE endpoint is an in-process Netty `LocalChannel`, so
  ICE itself opens no TCP port. A comment in `Mixin_NettyIceServer` says the
  world must be opened to LAN *first*, to capture the channel initializer and
  to make the integrated server behave like a LAN server. We found no code
  that restricts that LAN listener to loopback while hosting.

**How Hearthgate differs:** the two are architecturally the most similar: a
direct path via NAT traversal, a relay fallback, and access control before
Minecraft's login. The differences are around the edges. Essential needs an
Essential account and its backend for signalling and TURN. Hearthgate needs
no account, and the invitation is the credential. Essential's QUIC is
deliberately unauthenticated and leaves security to Minecraft's encryption,
whereas Hearthgate authenticates the tunnel itself with WireGuard keys and the
PSK. Essential runs on far more Minecraft versions and ships a polished
invite UI. It doesn't do dedicated servers, and its license forbids reuse or
modification.

### playit.gg

The official plugin (`playit-cloud/playit-minecraft-plugin`) is a Java port of
the playit agent. On first start it generates a claim code and asks the user to
visit `playit.gg/claim/<code>`, which links it to a playit account.
`PlayitKeysSetup` then exchanges the claim for an agent secret. A UDP control
channel (`PlayitControlChannel`) receives notices of new connections. For each
one, `PlayitTcpTunnel` opens a TCP connection to the playit tunnel server,
sends a claim token and pipes the stream to the Minecraft server. Players use
a fixed public address, and `PlayitHeaderDecoder` passes their real IP through.
No encryption is added beyond Minecraft's own. The archived community mod
(`maxomatic458/playit-minecraft-mod`) works the same way.

**How Hearthgate differs:** playit is built for servers that strangers should
be able to find, with a stable public address, vanilla clients and real-IP
forwarding. It needs an account and always relays, so latency depends on the
nearest playit PoP. A Hearthgate server is reachable only with its invitation,
needs no account, and usually connects players directly.

### Ngrok-LAN

Ngrok-LAN is a Fabric mod (last release for 1.20.2). Through `java-ngrok`, it
downloads and runs the `ngrok` binary as a subprocess with the user's ngrok
authtoken, and exposes the LAN port as an ngrok TCP tunnel. It's always
relayed, requires an ngrok account, and the address is public.

### ZenithX

ZenithX is a Fabric mod for 1.21.9–26.3 with its own friend system. It builds
ICE by hand over a single UDP socket (`IceSession`, `StunClient`, with
`stun.l.google.com` and `stun.cloudflare.com`), uses its own reliable-stream
layer (`ReliableStream`), and encrypts with AES-GCM. The host generates the
16-byte AES key and puts it in its signalling offer (`IceSession.sendSignal`),
so the signalling server (`wss://…wisp.uno`, discovered from a GitHub gist at
startup) carries the key alongside the connection. When ICE fails it falls
back to a ZenithX relay. It also has an in-game updater, achievements and
cosmetics.

**How Hearthgate differs:** in Hearthgate, the key material that
authenticates the tunnel goes only into the invitation, and the person sharing
decides how it travels. Hearthgate also uses WireGuard and magicsock, which
are widely deployed and reviewed, instead of a hand-written ICE and
reliability layer.

### tailcat-for-minecraft

This is Tailscale's prototype that Hearthgate started from. It's a Fabric
1.21.1 client mod that bundles six native `mclink-helper` executables (built
from Go) and runs one as a subprocess. Its README describes it as a "mostly
AI-generated prototype" that Tailscale doesn't plan to distribute. Hearthgate
keeps the idea (a Tailcat token as the invitation, loopback-only listeners)
and changes the rest:
- the helper runs in-process as WebAssembly instead of native executables;
- the JVM enforces a network policy on the helper;
- crypto and TLS are delegated to the JDK;
- sharing no longer relies on vanilla LAN publishing (see `LoopbackOnly`);
- it supports NeoForge, Forge and Quilt;
- it adds dedicated-server hosting.

## Where Hearthgate is different

1. **The invitation is the access control, enforced below Minecraft.** Without
   the WireGuard PSK and host key, a peer can't complete the WireGuard
   handshake, so it never reaches Minecraft's handshake, server list ping or
   login. Everything else reviewed here except Essential and ZenithX exposes
   Minecraft's login to anyone with an address. Essential and ZenithX gate
   access through their own account backends. Hearthgate gates it with a
   secret that only the people sharing it ever handle.
2. **Relays see only ciphertext.** DERP forwards WireGuard packets. e4mc's
   relay path and playit pass the Minecraft stream through as is (safe after
   online-mode encryption, but the handshake and username are visible).
   ZenithX's key travels through its signalling server.
3. **No LAN exposure, and it fails closed.** Sharing doesn't leave vanilla's
   all-interfaces listener or LAN broadcast running. `LoopbackOnly` swaps the
   listener for a loopback one and stops the LAN pinger, and if it can't bind
   the private fields it needs, it refuses to share. e4mc and World Host leave
   the vanilla listener in place, and Essential's comment says it needs Open
   to LAN first.
4. **No native code by default, and the guest code is sandboxed.** The jar
   contains no native libraries or executables, nothing is downloaded at
   runtime, and Java opens every socket the helper uses under a fixed policy.
   e4mc downloads a native library (hash-pinned), Essential and e4mc ship
   netty QUIC natives, and Ngrok-LAN runs a downloaded executable. The
   optional native add-on for servers gives up this isolation, as the README
   says.
5. **No account and no project-run backend.** Hearthgate's only
   infrastructure dependency is Tailcat's public DERP map and relays. There's
   no Hearthgate server to sign up with, go down or be rate-limited by.
6. **Dedicated-server hosting.** Identity persists across restarts. Keys
   rotate make-before-break on a schedule, with connected players kept on the
   old tunnel while it drains. `/hearthgate rotate` revokes a leaked
   invitation, and there's an optional Discord announcement. Of the projects
   reviewed, only playit targets servers, and it does so with public
   addresses.

## Where the others are better

- **Vanilla joiners.** e4mc, World Host's proxy and playit all let unmodded
  clients join. Hearthgate requires the mod on every client, so it doesn't
  suit public servers or players who can't change their mod list.
- **Version coverage.** e4mc (1.17–26.3) and Essential (1.12.2–26.3) cover
  nearly every version people still play. Hearthgate supports only 26.1–26.3
  on Java 25.
- **Throughput.** The WebAssembly helper peaks at about 1.5–2 MB/s each way,
  and on a server that budget is shared by every player. It's plenty for
  play, but chunk loading on join is slower than with a native QUIC or TCP
  path. The native add-on removes the limit for servers.
- **Discovery and UX.** World Host and Essential have friend lists and
  online status. Hearthgate invitations are copied and pasted by hand.
- **Third-party dependency.** Hearthgate depends on Tailcat's public relays
  and DERP map. It isn't affiliated with Tailscale, and if that
  infrastructure changes, Hearthgate is affected. For now Hearthgate always
  uses Tailcat's default DERP map, whereas e4mc lets you configure its broker
  and relay. Support for custom relays may be added in the future if people
  ask for it.
- **IP exposure on direct paths.** When a direct path is established, peers
  learn each other's public IP, as with any P2P transport (e4mc Dialtone,
  Essential and ZenithX included). Relay-only designs (e4mc's relay path,
  playit, ngrok) hide the host's IP from players.
- **Bearer invitations.** Anyone who gets an invitation can connect until
  sharing stops or the keys rotate. Minecraft's login, the whitelist and bans
  still apply after that, but the network-level gate is only as private as
  the channel the invitation was sent over.

## Reproducing this review

```sh
git clone https://github.com/vgskye/e4mc-minecraft-architectury && git -C e4mc-minecraft-architectury checkout 93ce8a8
# … and the same for each repository and commit in the table above
```

Files referenced above, by project:

- **e4mc:** `common/src/main/java/link/e4mc/{QuiclimeSession,Config}.java`,
  `mixin/{ServerConnectionListener,ServerNameResolver,ServerLoginPacketListenerImpl}Mixin.java`
- **e4mc-quiclime:** `src/{main,routing}.rs`
- **iroh-java:** `src/main/java/link/e4mc/iroh/Native.java`,
  `rust/src/lib.rs` (`sanitize_ticket`)
- **World Host:** `src/main/java/io/github/gaming32/worldhost/{WorldHost.java,protocol/ProtocolClient.java,protocol/WorldHostS2CMessage.java,protocol/punch/PunchReason.java}`
- **Essential:**
  `gui/essential/src/main/kotlin/gg/essential/network/connectionmanager/ice/{IceManager,IceManagerImpl}.kt`,
  `subprojects/quic-connector/src/main/java/gg/essential/quic/backend/QuicBackendImpl.java`,
  `src/main/java/gg/essential/mixins/transformers/feature/ice/{server/Mixin_NettyIceServer,common/Mixin_IceChannelIsNotLocal}.java`
- **playit plugin:**
  `src/main/java/gg/playit/{control/PlayitControlChannel,minecraft/PlayitTcpTunnel,minecraft/PlayitKeysSetup}.java`
- **ZenithX:**
  `src/client/java/org/adam/zenithx/network/{IceSession,StunClient,ReliableStream}.java`,
  `src/main/java/org/adam/zenithx/network/Servers.java`
