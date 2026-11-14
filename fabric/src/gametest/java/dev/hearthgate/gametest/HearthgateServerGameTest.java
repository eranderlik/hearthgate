package dev.tailcatmc.gametest;

import dev.tailcatmc.core.server.PersistentHost;
import dev.tailcatmc.fabric.JoinScreen;
import dev.tailcatmc.fabric.server.TailcatServer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

import java.util.Properties;

/**
 * Starts a dedicated server with the mod's server side, waits for its Tailcat
 * invitation, then joins it through the client UI (Multiplayer → Join with
 * Tailcat) and plays: a full login and world download through the tunnel.
 */
public final class TailcatServerGameTest implements FabricClientGameTest {
	private static final int TPS = 20;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// The game test JVM is a client, so the loader doesn't run "server"
		// entrypoints; initialize ours by hand. It acts only on dedicated servers.
		new TailcatServer().onInitializeServer();

		Properties props = new Properties();
		props.setProperty("online-mode", "false"); // the dev client has no real account
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props)) {
			ctx.waitFor(mc -> {
				PersistentHost h = TailcatServer.host();
				return h != null && h.status().invite() != null;
			}, 90 * TPS);
			String invite = TailcatServer.host().status().invite();
			String engine = TailcatServer.host().status().engine();
			System.out.println("[tailcat-gametest] dedicated server invitation ready; helper: " + engine);
			// With -PgametestNative the native add-on is installed and must be used.
			String expected = Boolean.getBoolean("tailcatmc.test.expectNative") ? "native" : "WebAssembly";
			if (!engine.startsWith(expected)) {
				throw new AssertionError("expected the " + expected + " helper, got " + engine);
			}
			server.runCommand("tailcat status");

			ctx.setScreen(() -> new JoinMultiplayerScreen(new TitleScreen()));
			ctx.clickScreenButton("tailcatmc.button.join");
			ctx.waitForScreen(JoinScreen.class);
			ctx.runOnClient(mc -> {
				for (var child : dev.tailcatmc.fabric.Compat.screen(mc).children()) {
					if (child instanceof EditBox box) {
						box.setValue(invite);
					}
				}
			});
			ctx.takeScreenshot("tailcat-server-1-join-screen");
			ctx.clickScreenButton("tailcatmc.join.connect");

			// Logged in and the world is arriving through the tunnel.
			ctx.waitFor(mc -> mc.player != null && mc.level != null, 120 * TPS);
			ctx.waitTicks(5 * TPS);
			ctx.takeScreenshot("tailcat-server-2-in-world");
			int players = server.computeOnServer(s -> s.getPlayerCount());
			if (players != 1) {
				throw new AssertionError("expected 1 player on the dedicated server, got " + players);
			}
			System.out.println("[tailcat-gametest] joined the dedicated server through Tailcat");
			server.runCommand("tailcat status");

			// Leaving the server ends the client's join tunnel.
			ctx.runOnClient(mc -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("test done")));
			ctx.waitFor(mc -> mc.level == null, 30 * TPS);
			ctx.waitFor(mc -> dev.tailcatmc.fabric.TailcatClient.sessions().status() == null, 10 * TPS);
		}
		ctx.setScreen(TitleScreen::new);
	}
}
