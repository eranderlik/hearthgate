package dev.hearthgate.gametest;

import dev.hearthgate.core.HelperEvent;
import dev.hearthgate.core.HelperSession;
import dev.hearthgate.core.host.NetworkPolicy;
import dev.hearthgate.common.client.JoinScreen;
import dev.hearthgate.common.client.Sessions;
import dev.hearthgate.common.client.ShareScreen;
import dev.hearthgate.common.client.HearthgateClientCommon;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shares a real singleplayer world through the mod's UI, joins it from a
 * second in-process helper (as a remote player's game would), and pings the
 * integrated server through the tunnel. Needs internet access to Tailcat's
 * public relays. Run with {@code ./gradlew :fabric:runClientGameTest}.
 */
public final class HearthgateClientGameTest implements FabricClientGameTest {
	private static final int TICKS_PER_SECOND = 20;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			// (Portable across gametest API 5 and 6, which renamed the
			// client-level accessors.)
			ctx.waitFor(mc -> mc.level != null, 60 * TICKS_PER_SECOND);
			ctx.waitTicks(2 * TICKS_PER_SECOND);

			ctx.setScreen(() -> new PauseScreen(true));
			ctx.waitForScreen(PauseScreen.class);
			ctx.takeScreenshot("hearthgate-1-pause-menu");
			ctx.clickScreenButton("hearthgate.button.share");
			ctx.waitForScreen(ShareScreen.class);

			ctx.waitFor(mc -> {
				Sessions.Status s = HearthgateClientCommon.sessions().status();
				return s != null && s.ready();
			}, 60 * TICKS_PER_SECOND);
			String invite = ctx.computeOnClient(mc -> HearthgateClientCommon.sessions().status().invite());
			ctx.takeScreenshot("hearthgate-2-sharing");

			String status = pingThroughTunnel(invite);
			String version = SharedConstants.getCurrentVersion().name();
			if (!status.contains("\"" + version + "\"")) {
				throw new AssertionError("status through tunnel lacks version " + version + ": " + status);
			}
			log("server list ping through the tunnel OK: " + status);

			ctx.waitFor(mc -> !HearthgateClientCommon.sessions().status().peers().isEmpty(), 30 * TICKS_PER_SECOND);
			ctx.waitTicks(TICKS_PER_SECOND);
			ctx.takeScreenshot("hearthgate-3-peer-connected");

			ctx.clickScreenButton("hearthgate.share.stop");
			ctx.waitFor(mc -> HearthgateClientCommon.sessions().status() == null, 10 * TICKS_PER_SECOND);
		}

		ctx.setScreen(() -> new JoinMultiplayerScreen(new TitleScreen()));
		ctx.waitForScreen(JoinMultiplayerScreen.class);
		ctx.takeScreenshot("hearthgate-4-multiplayer");
		ctx.clickScreenButton("hearthgate.button.join");
		ctx.waitForScreen(JoinScreen.class);
		ctx.takeScreenshot("hearthgate-5-join-screen");
		ctx.setScreen(TitleScreen::new);
	}

	/** Joins with a separate helper instance and performs a server list ping through it. */
	private static String pingThroughTunnel(String invite) {
		CompletableFuture<HelperEvent> ready = new CompletableFuture<>();
		HelperSession join;
		try {
			join = HelperSession.start(List.of("join", "--invite", invite), NetworkPolicy.forJoin(),
					new HelperSession.Listener() {
						@Override
						public void onEvent(String line) {
							try {
								HelperEvent e = HelperEvent.parse(line);
								if (e.type().equals("ready")) {
									ready.complete(e);
								} else if (e.type().equals("error")) {
									ready.completeExceptionally(new AssertionError(e.message()));
								}
							} catch (IOException e) {
								ready.completeExceptionally(e);
							}
						}

						@Override
						public void onLog(String line) {
							log("[join helper] " + line);
						}
					});
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		try {
			String[] hp = ready.get(60, TimeUnit.SECONDS).address().split(":");
			return serverListPing(hp[0], Integer.parseInt(hp[1]));
		} catch (Exception e) {
			throw new AssertionError("joining through the tunnel failed", e);
		} finally {
			join.close();
		}
	}

	private static String serverListPing(String host, int port) throws IOException {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), 15_000);
			s.setSoTimeout(30_000);
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			ByteArrayOutputStream hs = new ByteArrayOutputStream();
			DataOutputStream h = new DataOutputStream(hs);
			writeVarInt(h, 0x00); // handshake
			writeVarInt(h, SharedConstants.getProtocolVersion());
			byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
			writeVarInt(h, hostBytes.length);
			h.write(hostBytes);
			h.writeShort(port);
			writeVarInt(h, 1); // next state: status
			writeVarInt(out, hs.size());
			out.write(hs.toByteArray());
			writeVarInt(out, 1); // status request: length 1, id 0
			writeVarInt(out, 0x00);
			out.flush();

			DataInputStream in = new DataInputStream(s.getInputStream());
			readVarInt(in); // packet length
			if (readVarInt(in) != 0x00) {
				throw new IOException("unexpected status packet id");
			}
			byte[] json = new byte[readVarInt(in)];
			in.readFully(json);
			return new String(json, StandardCharsets.UTF_8);
		}
	}

	private static void writeVarInt(DataOutputStream out, int v) throws IOException {
		while ((v & ~0x7F) != 0) {
			out.writeByte((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.writeByte(v);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int v = 0, shift = 0, b;
		do {
			b = in.readUnsignedByte();
			v |= (b & 0x7F) << shift;
			shift += 7;
		} while ((b & 0x80) != 0 && shift < 35);
		return v;
	}

	private static void log(String s) {
		System.out.println("[hearthgate-gametest] " + s);
	}
}
