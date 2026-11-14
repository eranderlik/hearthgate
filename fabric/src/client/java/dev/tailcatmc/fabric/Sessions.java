package dev.tailcatmc.fabric;

import dev.tailcatmc.core.HelperEvent;
import dev.tailcatmc.core.HelperSession;
import dev.tailcatmc.core.host.NetworkPolicy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the (at most one) active Tailcat session: sharing the integrated
 * server, or a tunnel to someone else's. All methods run on the client thread;
 * helper events are hopped onto it.
 */
public final class Sessions {
	static final Logger LOG = LoggerFactory.getLogger("tailcat-mc");
	private static final long STARTUP_TIMEOUT_S = 45;
	/** How long a join tunnel survives without Minecraft connecting through it. */
	private static final long JOIN_GRACE_NANOS = TimeUnit.SECONDS.toNanos(60);

	public enum Mode { HOST, JOIN }

	/** Snapshot of the active session for screens to display. */
	public record Status(Mode mode, boolean ready, String invite, List<HelperEvent.Peer> peers) {}

	private final Minecraft mc;
	private Session session;

	private final class Session implements HelperSession.Listener {
		final Mode mode;
		final CompletableFuture<HelperEvent> ready = new CompletableFuture<>();
		HelperSession helper;
		IntegratedServer offlineAuthServer;
		boolean previousAuth;
		HelperEvent readyEvent;
		List<HelperEvent.Peer> peers = List.of();
		long readyAt;
		boolean playing;

		Session(Mode mode) {
			this.mode = mode;
		}

		@Override
		public void onEvent(String line) {
			HelperEvent event;
			try {
				event = HelperEvent.parse(line);
			} catch (IOException e) {
				LOG.error("Bad event from Tailcat helper: {}", e.getMessage());
				return;
			}
			mc.execute(() -> handle(this, event));
		}

		@Override
		public void onLog(String line) {
			LOG.info("[helper] {}", line);
		}

		@Override
		public void onExit(int code, Throwable error) {
			if (error != null) {
				LOG.error("Tailcat helper crashed", error);
			}
			mc.execute(() -> {
				ready.completeExceptionally(new IllegalStateException(
						error != null ? "The Tailcat helper crashed: " + error : "The Tailcat helper stopped"));
				if (session == this) {
					endSession(this, error != null ? "The Tailcat helper crashed; see the log for details." : null);
				}
			});
		}
	}

	public Sessions(Minecraft mc) {
		this.mc = mc;
	}

	public Status status() {
		Session s = session;
		if (s == null) {
			return null;
		}
		return new Status(s.mode, s.readyEvent != null, s.readyEvent == null ? null : s.readyEvent.invite(), s.peers);
	}

	public boolean isSharing() {
		return session != null && session.mode == Mode.HOST;
	}

	/** Shares the integrated server, opening it to LAN if needed. Completes with the invitation. */
	public CompletableFuture<String> share() {
		if (session != null && session.mode == Mode.HOST) {
			return session.ready.thenApply(HelperEvent::invite);
		}
		stop();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("No world is open."));
		}
		if (!server.isPublished()) {
			if (!Compat.publishToLan(server, HttpUtil.getAvailablePort())) {
				return CompletableFuture.failedFuture(new IllegalStateException("Minecraft could not open this world to other players."));
			}
		}
		int port = server.getPort();
		Session s = new Session(Mode.HOST);
		maybeDevelopmentOfflineAuth(s, server);
		List<String> args = new ArrayList<>(List.of("host", "--target", "127.0.0.1:" + port, "--mc", gameVersion()));
		if (TailcatClient.verbose()) {
			args.add("--verbose");
		}
		return begin(s, args, NetworkPolicy.forHost(port)).thenApply(HelperEvent::invite);
	}

	/** Opens a tunnel for an invitation, then connects Minecraft through it. */
	public CompletableFuture<Void> join(Screen parent, String invite) {
		stop();
		List<String> args = new ArrayList<>(List.of("join", "--invite", invite.trim()));
		if (TailcatClient.verbose()) {
			args.add("--verbose");
		}
		Session s = new Session(Mode.JOIN);
		return begin(s, args, NetworkPolicy.forJoin()).thenAccept(event -> {
			String address = event.address();
			ServerData data = new ServerData(Component.translatable("tailcatmc.server_name").getString(), address,
					ServerData.Type.OTHER);
			ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false, null);
		});
	}

	private CompletableFuture<HelperEvent> begin(Session s, List<String> args, NetworkPolicy policy) {
		try {
			s.helper = HelperSession.start(args, policy, s);
		} catch (IOException e) {
			restoreAuth(s);
			return CompletableFuture.failedFuture(e);
		}
		session = s;
		return s.ready.orTimeout(STARTUP_TIMEOUT_S, TimeUnit.SECONDS).whenComplete((e, err) -> {
			if (err != null) {
				mc.execute(() -> {
					if (session == s) {
						endSession(s, null);
					}
				});
			}
		});
	}

	private void handle(Session s, HelperEvent e) {
		switch (e.type()) {
			case "ready" -> {
				s.readyEvent = e;
				s.readyAt = System.nanoTime();
				s.ready.complete(e);
			}
			case "peers" -> {
				if (s.mode == Mode.HOST && e.peers().size() > s.peers.size()) {
					HelperEvent.Peer p = e.peers().getLast();
					toast(Component.translatable("tailcatmc.toast.player_connected"),
							Component.translatable(p.direct() ? "tailcatmc.path.direct" : "tailcatmc.path.relayed",
									p.relay() == null ? "?" : p.relay()));
				}
				s.peers = e.peers();
			}
			case "error" -> {
				String message = ErrorMessages.describe(e.code(), e.message());
				s.ready.completeExceptionally(new IllegalStateException(message));
				if (session == s) {
					endSession(s, s.readyEvent != null ? message : null);
				}
			}
			default -> {
			}
		}
	}

	/** Called every client tick to end sessions whose purpose is gone. */
	public void tick() {
		Session s = session;
		if (s == null) {
			return;
		}
		if (s.mode == Mode.HOST) {
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null || !server.isRunning()) {
				stop();
			}
		} else if (!s.playing && s.readyEvent != null && System.nanoTime() - s.readyAt > JOIN_GRACE_NANOS
				&& !(Compat.screen(mc) instanceof ConnectScreen)) {
			stop();
		}
	}

	/** Minecraft finished joining a server. */
	public void onPlayJoin() {
		if (session != null && session.mode == Mode.JOIN && session.readyEvent != null) {
			session.playing = true;
		}
	}

	/** Minecraft left a server. */
	public void onPlayDisconnect() {
		if (session != null && session.mode == Mode.JOIN && session.playing) {
			stop();
		}
	}

	public void stop() {
		if (session != null) {
			endSession(session, null);
		}
	}

	private void endSession(Session s, String toastMessage) {
		if (session == s) {
			session = null;
		}
		if (s.helper != null) {
			s.helper.close();
		}
		restoreAuth(s);
		if (toastMessage != null) {
			toast(Component.translatable("tailcatmc.toast.stopped"), Component.literal(toastMessage));
		}
	}

	private void toast(Component title, Component body) {
		SystemToast.add(Compat.toasts(mc), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, body);
	}

	/**
	 * Development only: lets a second offline-mode dev client join, so the mod
	 * can be tested with one Minecraft account. Never active in production.
	 */
	private static void maybeDevelopmentOfflineAuth(Session s, IntegratedServer server) {
		if (FabricLoader.getInstance().isDevelopmentEnvironment()
				&& "1".equals(System.getenv("TCMC_DEV_OFFLINE_AUTH"))) {
			s.offlineAuthServer = server;
			s.previousAuth = server.usesAuthentication();
			server.setUsesAuthentication(false);
			LOG.warn("TCMC_DEV_OFFLINE_AUTH: authentication disabled while sharing (development only)");
		}
	}

	private static void restoreAuth(Session s) {
		if (s.offlineAuthServer != null) {
			s.offlineAuthServer.setUsesAuthentication(s.previousAuth);
			s.offlineAuthServer = null;
		}
	}

	public static String gameVersion() {
		return SharedConstants.getCurrentVersion().name();
	}
}
