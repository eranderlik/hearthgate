package dev.hearthgate.common.client;

import dev.hearthgate.core.HelperSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;

/**
 * The client side of Hearthgate, independent of the mod loader. Each loader
 * module calls these from its own events, on the client thread:
 *
 * <ul>
 *   <li>{@link #init} from the client mod initializer (after {@code Platform.set}),</li>
 *   <li>{@link #onClientStarted} once the client has finished starting,</li>
 *   <li>{@link #onClientStopping} when it is shutting down,</li>
 *   <li>{@link #onClientTick} at the end of every client tick,</li>
 *   <li>{@link #onPlayJoin} / {@link #onPlayDisconnect} when a play connection
 *       is established / lost,</li>
 *   <li>{@link #onScreenInit} after any screen is initialised.</li>
 * </ul>
 */
public final class HearthgateClientCommon {
	private static Sessions sessions;
	private static ClientPlatform clientPlatform;

	private HearthgateClientCommon() {}

	public static Sessions sessions() {
		return sessions;
	}

	static boolean verbose() {
		return Boolean.getBoolean("hearthgate.verbose") || "1".equals(System.getenv("HEARTHGATE_VERBOSE"));
	}

	/**
	 * Registers the loader's client platform and checks that the
	 * version-specific Minecraft APIs bind, failing loudly if not.
	 */
	public static void init(ClientPlatform platform) {
		clientPlatform = platform;
		Compat.verify();
	}

	public static void onClientStarted(Minecraft client) {
		sessions = new Sessions(client);
		// Parse the compiled helper module in the background so the first
		// share or join starts promptly.
		Thread t = new Thread(() -> {
			long t0 = System.nanoTime();
			HelperSession.preload();
			Sessions.LOG.info("Hearthgate helper loaded in {} ms", (System.nanoTime() - t0) / 1_000_000);
		}, "hearthgate-preload");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	public static void onClientStopping(Minecraft client) {
		if (sessions != null) {
			sessions.stop();
		}
	}

	public static void onClientTick(Minecraft client) {
		if (sessions != null) {
			sessions.tick();
		}
	}

	/** A play connection to a server (integrated or remote) was established. */
	public static void onPlayJoin(Minecraft client, ClientPacketListener handler) {
		client.execute(() -> {
			if (sessions != null) {
				sessions.onPlayJoin(handler.getConnection());
			}
		});
	}

	/** The play connection was lost or closed. */
	public static void onPlayDisconnect(Minecraft client) {
		client.execute(() -> {
			if (sessions != null) {
				sessions.onPlayDisconnect();
			}
		});
	}

	/** After a screen has been initialised (and again on resize): adds Hearthgate's buttons. */
	public static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
		if (screen instanceof PauseScreen && client.hasSingleplayerServer()) {
			addShareButton(client, screen);
		} else if (screen instanceof JoinMultiplayerScreen) {
			addJoinButton(client, screen, width);
		}
	}

	/** Adds "Share with Hearthgate" in a row beneath "Open to LAN", moving later rows down. */
	private static void addShareButton(Minecraft client, Screen screen) {
		List<AbstractWidget> widgets = widgets(screen);
		Component label = Component.translatable(sessions != null && sessions.isSharing()
				? "hearthgate.button.sharing" : "hearthgate.button.share");
		Button.Builder b = Button.builder(label, btn -> Compat.setScreen(client, new ShareScreen(screen)))
				.tooltip(Tooltip.create(Component.translatable("hearthgate.button.share_tooltip")));
		// The button that opens the world to others: "Open to LAN" in 26.1.x,
		// "Open to LAN" under a new key in 26.2, "World Options..." in 26.3.
		// Fall back to the Options row, then to a corner.
		AbstractWidget lan = find(widgets, "menu.shareToLan", "menu.multiplayerOptions.button",
				"options.worldOptions.button", "menu.options");
		if (lan == null) {
			clientPlatform.addScreenWidget(screen, b.bounds(4, 4, 150, 20).build());
			return;
		}
		// Span the whole row the LAN button sits in (it shares it with Options).
		int left = lan.getX(), right = lan.getX() + lan.getWidth();
		for (AbstractWidget w : widgets) {
			if (w.getY() == lan.getY()) {
				left = Math.min(left, w.getX());
				right = Math.max(right, w.getX() + w.getWidth());
			}
		}
		int below = lan.getY() + lan.getHeight();
		for (AbstractWidget w : widgets) {
			if (w.getY() >= below) {
				w.setY(w.getY() + 24);
			}
		}
		clientPlatform.addScreenWidget(screen, b.bounds(left, below + 4, right - left, lan.getHeight()).build());
	}

	/** Adds "Join with Hearthgate" to the multiplayer screen's header. */
	private static void addJoinButton(Minecraft client, Screen screen, int width) {
		clientPlatform.addScreenWidget(screen, Button.builder(Component.translatable("hearthgate.button.join"),
						btn -> Compat.setScreen(client, new JoinScreen(screen)))
				.tooltip(Tooltip.create(Component.translatable("hearthgate.button.join_tooltip")))
				.bounds(width - 134, 6, 128, 20).build());
	}

	/** A snapshot of the screen's widgets; add new ones with {@link ClientPlatform#addScreenWidget}. */
	private static List<AbstractWidget> widgets(Screen screen) {
		List<AbstractWidget> out = new ArrayList<>();
		for (var child : screen.children()) {
			if (child instanceof AbstractWidget w) {
				out.add(w);
			}
		}
		return out;
	}

	/** Returns the first widget labelled with any of {@code keys}, trying keys in order. */
	private static AbstractWidget find(List<AbstractWidget> widgets, String... keys) {
		for (String key : keys) {
			for (AbstractWidget w : widgets) {
				if (w.getMessage().getContents() instanceof TranslatableContents tc && tc.getKey().equals(key)) {
					return w;
				}
			}
		}
		return null;
	}
}
