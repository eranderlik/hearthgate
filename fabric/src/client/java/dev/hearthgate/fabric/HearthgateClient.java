package dev.hearthgate.fabric;

import dev.hearthgate.core.HelperSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;

public final class HearthgateClient implements ClientModInitializer {
	private static Sessions sessions;

	public static Sessions sessions() {
		return sessions;
	}

	static boolean verbose() {
		return Boolean.getBoolean("hearthgate.verbose") || "1".equals(System.getenv("HEARTHGATE_VERBOSE"));
	}

	@Override
	public void onInitializeClient() {
		Compat.verify();
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
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
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (sessions != null) {
				sessions.stop();
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (sessions != null) {
				sessions.tick();
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (sessions != null) {
				sessions.onPlayJoin();
			}
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			if (sessions != null) {
				sessions.onPlayDisconnect();
			}
		}));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen && client.hasSingleplayerServer()) {
				addShareButton(client, screen);
			} else if (screen instanceof JoinMultiplayerScreen) {
				addJoinButton(client, screen, width);
			}
		});
	}

	/** Adds "Share with Hearthgate" in a row beneath "Open to LAN", moving later rows down. */
	private static void addShareButton(Minecraft client, Screen screen) {
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
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
			widgets.add(b.bounds(4, 4, 150, 20).build());
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
		widgets.add(b.bounds(left, below + 4, right - left, lan.getHeight()).build());
	}

	/** Adds "Join with Hearthgate" to the multiplayer screen's header. */
	private static void addJoinButton(Minecraft client, Screen screen, int width) {
		Screens.getWidgets(screen).add(Button.builder(Component.translatable("hearthgate.button.join"),
						btn -> Compat.setScreen(client, new JoinScreen(screen)))
				.tooltip(Tooltip.create(Component.translatable("hearthgate.button.join_tooltip")))
				.bounds(width - 134, 6, 128, 20).build());
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
