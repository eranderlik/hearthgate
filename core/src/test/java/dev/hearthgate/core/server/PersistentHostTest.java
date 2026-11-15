package dev.hearthgate.core.server;

import dev.hearthgate.core.HelperEvent;
import dev.hearthgate.core.HelperSession;
import dev.hearthgate.core.host.NetworkPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dedicated-server hosting through Tailcat's public relays, announcing to a
 * fake Discord. Opt in with {@code -PnetworkTests}.
 */
class PersistentHostTest {
	private static final Pattern INVITE = Pattern.compile("hg1_[A-Za-z0-9_-]+");

	@Test
	@Timeout(value = 6, unit = TimeUnit.MINUTES)
	void stableAcrossRestartsRotatesAndAnnounces(@TempDir Path dir) throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean("hearthgate.networkTests"), "network tests disabled");
		try (ServerSocket mc = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
				FakeDiscord discord = new FakeDiscord()) {
			Thread echo = new Thread(() -> serveEcho(mc));
			echo.setDaemon(true);
			echo.start();

			ServerConfig cfg = new ServerConfig();
			cfg.rotateAfterHours = 0; // rotate manually below
			Path idFile = dir.resolve("hearthgate/identity.json");
			PersistentHost.Options opts = new PersistentHost.Options(
					new InetSocketAddress(InetAddress.getLoopbackAddress(), mc.getLocalPort()), "test", "Test Server",
					idFile, cfg);

			// 1. First start: identity created, invitation posted.
			PersistentHost host = new PersistentHost(opts, new DiscordWebhook(discord.url(), false));
			host.start();
			FakeDiscord.Request post = discord.next();
			assertEquals("POST", post.method());
			String inviteA = invite(post);
			assertEquals(inviteA, host.status().invite());
			if (Files.getFileStore(idFile).supportsFileAttributeView("posix")) {
				assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(idFile)));
			}
			assertTrue(echoThrough(inviteA), "invitation A works");

			// 2. Shutdown marks the message offline.
			host.close();
			FakeDiscord.Request off = discord.next();
			assertEquals("PATCH", off.method());
			assertTrue(FakeDiscord.title(off).endsWith("is offline"));

			// 3. Restart: the same invitation, and the same message flips back online.
			PersistentHost again = new PersistentHost(opts, new DiscordWebhook(discord.url(), false));
			again.start();
			FakeDiscord.Request back = discord.next();
			assertEquals("PATCH", back.method());
			assertEquals(inviteA, invite(back), "invitation is stable across restarts");
			assertTrue(echoThrough(inviteA), "invitation A works after restart");

			// 4. Rotation, with a player still connected through A: a new
			// invitation replaces the message's content, and A is retired.
			Player stayed = Player.join(inviteA);
			assertTrue(stayed.echo(), "player connected through A");
			long peersDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (again.status().players() == 0 && System.nanoTime() < peersDeadline) {
				Thread.sleep(200);
			}
			assertTrue(again.status().players() > 0, "host sees the player on A");
			again.rotateNow().get(30, TimeUnit.SECONDS);
			FakeDiscord.Request rotated = discord.next();
			assertEquals("PATCH", rotated.method());
			assertEquals(post.path().length() > 0, true);
			String inviteB = invite(rotated);
			assertNotEquals(inviteA, inviteB);
			assertTrue(echoThrough(inviteB), "invitation B works");
			assertEquals(1, again.status().drainingTunnels(), "A's tunnel drains for the player still on it");
			assertTrue(stayed.echo(), "the player already on A keeps playing");
			assertFalse(echoThrough(inviteA), "a retired invitation admits no one new");
			assertTrue(stayed.echo(), "refusing a newcomer doesn't affect the player on A");

			// That player leaves, so A's tunnel closes as soon as the host
			// notices, well before the drain period (6 hours) ends. Noticing
			// takes seconds; longer on a loaded machine.
			stayed.close();
			long t0 = System.nanoTime();
			long deadline = t0 + TimeUnit.SECONDS.toNanos(90);
			while (again.status().drainingTunnels() > 0 && System.nanoTime() < deadline) {
				Thread.sleep(200);
			}
			assertEquals(0, again.status().drainingTunnels());
			System.out.printf("previous tunnel closed %.1fs after its last player left%n", (System.nanoTime() - t0) / 1e9);
			assertEquals(1, discord.messages.size(), "one message, edited in place");
			again.close();
		}
	}

	private static String invite(FakeDiscord.Request r) {
		Matcher m = INVITE.matcher(FakeDiscord.description(r));
		assertTrue(m.find(), "announcement contains an invitation");
		return m.group();
	}

	/**
	 * Joins with a separate helper and echoes a message through the tunnel.
	 * False if the host refuses the tunnel or the connection.
	 */
	private static boolean echoThrough(String invite) throws Exception {
		try (Player p = Player.join(invite)) {
			return p.echo();
		} catch (IOException | ExecutionException e) {
			return false; // the tunnel or the connection was refused
		}
	}

	/** A joiner with one connection open through its tunnel. */
	private record Player(HelperSession helper, Socket socket) implements AutoCloseable {
		static Player join(String invite) throws Exception {
			CompletableFuture<HelperEvent> ready = new CompletableFuture<>();
			HelperSession join = startJoin(invite, ready);
			try {
				String[] hp = ready.get(60, TimeUnit.SECONDS).address().split(":");
				Socket s = new Socket(hp[0], Integer.parseInt(hp[1]));
				s.setSoTimeout(20_000);
				return new Player(join, s);
			} catch (Exception e) {
				join.close();
				throw e;
			}
		}

		boolean echo() {
			try {
				byte[] msg = "meow".getBytes(StandardCharsets.UTF_8);
				socket.getOutputStream().write(msg);
				return new String(socket.getInputStream().readNBytes(msg.length), StandardCharsets.UTF_8).equals("meow");
			} catch (IOException e) {
				return false;
			}
		}

		@Override
		public void close() throws IOException {
			try {
				socket.close();
			} finally {
				helper.close();
			}
		}
	}

	/** Starts a join helper; {@code ready} completes with its ready event. */
	private static HelperSession startJoin(String invite, CompletableFuture<HelperEvent> ready) throws IOException {
		return HelperSession.start(List.of("join", "--invite", invite), NetworkPolicy.forJoin(),
				new HelperSession.Listener() {
					@Override
					public void onEvent(String line) {
						try {
							HelperEvent e = HelperEvent.parse(line);
							if (e.type().equals("ready")) {
								ready.complete(e);
							} else if (e.type().equals("error")) {
								ready.completeExceptionally(new IOException(e.code()));
							}
						} catch (IOException e) {
							ready.completeExceptionally(e);
						}
					}
				});
	}

	private static void serveEcho(ServerSocket ss) {
		while (!ss.isClosed()) {
			try {
				Socket c = ss.accept();
				Thread t = new Thread(() -> {
					try (c; InputStream in = c.getInputStream(); OutputStream out = c.getOutputStream()) {
						in.transferTo(out);
					} catch (IOException ignored) {
					}
				});
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				return;
			}
		}
	}
}
