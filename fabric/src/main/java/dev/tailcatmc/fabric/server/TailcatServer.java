package dev.tailcatmc.fabric.server;

import dev.tailcatmc.core.HelperBackend;
import dev.tailcatmc.core.HelperSession;
import dev.tailcatmc.core.nativehelper.NativeHelpers;
import dev.tailcatmc.core.nativehelper.NativePlatform;
import dev.tailcatmc.core.server.PersistentHost;
import dev.tailcatmc.core.server.ServerConfig;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Dedicated server support: keeps the server reachable through Tailcat for as
 * long as it runs, with a persistent invitation, as an alternative to port
 * forwarding. Configured by {@code config/tailcatmc-server.json}.
 */
public final class TailcatServer implements DedicatedServerModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("tailcat-mc");
	private static volatile PersistentHost host;
	/** Completes once the helper to use has been chosen (see {@link #chooseBackend}). */
	private static volatile CompletableFuture<HelperBackend> backend =
			CompletableFuture.completedFuture(HelperBackend.WASM);

	/** The running host, or null; public for tests. */
	public static PersistentHost host() {
		return host;
	}

	@Override
	public void onInitializeServer() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> TailcatCommand.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> start(server, configDir));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			PersistentHost h = host;
			host = null;
			if (h != null) {
				h.close();
			}
		});
		// Pick the helper, and get it ready while the world loads.
		CompletableFuture<HelperBackend> chosen = new CompletableFuture<>();
		backend = chosen;
		Thread t = new Thread(() -> {
			HelperBackend b = HelperBackend.WASM;
			try {
				b = chooseBackend(configDir, FabricLoader.getInstance().getGameDir());
			} finally {
				chosen.complete(b);
			}
			if (b == HelperBackend.WASM) {
				HelperSession.preload();
			}
		}, "tcmc-preload");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * The native helper if its add-on for this platform is installed and
	 * allowed by the config, else the WebAssembly helper. The system property
	 * {@code tailcatmc.nativeLibrary} names a library file to use instead of
	 * an add-on's, for development and self-built libraries.
	 */
	static HelperBackend chooseBackend(Path configDir, Path gameDir) {
		try {
			if (!ServerConfig.load(configDir.resolve("tailcatmc-server.json")).nativeHelperAllowed()) {
				return HelperBackend.WASM;
			}
		} catch (IOException e) {
			return HelperBackend.WASM; // reported when the server starts
		}
		String platform = NativePlatform.current();
		Path library;
		try {
			String override = System.getProperty("tailcatmc.nativeLibrary");
			if (override != null && !override.isBlank()) {
				library = Path.of(override);
				LOG.info("Tailcat: using native helper library {}", library);
			} else {
				Optional<ModContainer> addon = FabricLoader.getInstance()
						.getModContainer(NativePlatform.addonModId(platform));
				if (addon.isEmpty()) {
					for (String other : NativePlatform.SUPPORTED) {
						if (!other.equals(platform)
								&& FabricLoader.getInstance().isModLoaded(NativePlatform.addonModId(other))) {
							LOG.warn("Tailcat: the native helper add-on installed is for {}, but this server runs on {}",
									other, platform);
						}
					}
					if (NativePlatform.SUPPORTED.contains(platform)) {
						LOG.info("Tailcat: using the WebAssembly helper. For servers with many players, install "
								+ "the native helper add-on for {} for much higher throughput.", platform);
					}
					return HelperBackend.WASM;
				}
				String inJar = "natives/tailcatmc/" + platform + "/" + NativePlatform.libraryName(platform);
				Path source = addon.get().findPath(inJar)
						.orElseThrow(() -> new IOException("the add-on lacks " + inJar));
				library = NativeHelpers.extract(source, gameDir.resolve(".tailcatmc").resolve("natives"));
			}
			if (!NativeHelpers.nativeAccessEnabled()) {
				LOG.info("Tailcat: loading the native helper. Java warns about this unless the server is started "
						+ "with --enable-native-access=ALL-UNNAMED");
			}
			HelperBackend b = NativeHelpers.load(library, platform);
			LOG.info("Tailcat: using the native helper for {}", platform);
			return b;
		} catch (IOException | RuntimeException e) {
			LOG.warn("Tailcat: the native helper can't be used ({}); using the WebAssembly helper instead",
					e.getMessage());
			return HelperBackend.WASM;
		}
	}

	private static void start(MinecraftServer server, Path configDir) {
		if (!server.isDedicatedServer()) {
			return; // integrated servers are shared from the pause menu instead
		}
		ServerConfig config;
		try {
			config = ServerConfig.load(configDir.resolve("tailcatmc-server.json"));
		} catch (IOException e) {
			LOG.error("Tailcat disabled: {}", e.getMessage());
			return;
		}
		if (!config.enabled) {
			LOG.info("Tailcat sharing is disabled in tailcatmc-server.json");
			return;
		}
		InetSocketAddress target;
		try {
			target = target(server);
		} catch (UnknownHostException e) {
			LOG.error("Tailcat disabled: cannot resolve server-ip: {}", e.getMessage());
			return;
		}
		if (!server.usesAuthentication()) {
			LOG.warn("Tailcat: online-mode is off, so anyone with the invitation can join under any name. "
					+ "Turn on online-mode or the whitelist.");
		} else if (!server.getPlayerList().isUsingWhitelist()) {
			LOG.info("Tailcat: anyone with the invitation and a Minecraft account can join. "
					+ "Consider the whitelist, especially when posting the invitation to Discord.");
		}
		String name = config.serverName.isBlank() ? server.getMotd() : config.serverName;
		PersistentHost h = new PersistentHost(new PersistentHost.Options(target,
				SharedConstants.getCurrentVersion().name(), name == null || name.isBlank() ? "Minecraft server" : name,
				configDir.resolve("tailcatmc").resolve("identity.json"), config,
				backend.join()));
		host = h;
		h.start();
	}

	/** The address Tailcat forwards to: server-ip if set, else loopback, on server-port. */
	static InetSocketAddress target(MinecraftServer server) throws UnknownHostException {
		String ip = server.getLocalIp();
		InetAddress addr = ip == null || ip.isBlank() ? InetAddress.getLoopbackAddress() : InetAddress.getByName(ip);
		if (addr.isAnyLocalAddress()) {
			addr = InetAddress.getLoopbackAddress();
		}
		return new InetSocketAddress(addr, server.getPort());
	}
}
