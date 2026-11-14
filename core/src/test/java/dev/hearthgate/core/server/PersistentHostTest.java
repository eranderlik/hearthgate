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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

			// 4. Rotation: a new invitation replaces the message's content.
			again.rotateNow().get(30, TimeUnit.SECONDS);
			FakeDiscord.Request rotated = discord.next();
			assertEquals("PATCH", rotated.method());
			assertEquals(post.path().length() > 0, true);
			String inviteB = invite(rotated);
			assertNotEquals(inviteA, inviteB);
			assertTrue(echoThrough(inviteB), "invitation B works");
			// With no one connected through A, its tunnel closes right away.
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (again.status().drainingTunnels() > 0 && System.nanoTime() < deadline) {
				Thread.sleep(200);
			}
			assertEquals(0, again.status().drainingTunnels());
			assertEquals(1, discord.messages.size(), "one message, edited in place");
			again.close();
		}
	}

	private static String invite(FakeDiscord.Request r) {
		Matcher m = INVITE.matcher(FakeDiscord.description(r));
		assertTrue(m.find(), "announcement contains an invitation");
		return m.group();
	}

	/** Joins with a separate helper and echoes a message through the tunnel. */
	private static boolean echoThrough(String invite) throws Exception {
		CompletableFuture<HelperEvent> ready = new CompletableFuture<>();
		HelperSession join = HelperSession.start(List.of("join", "--invite", invite), NetworkPolicy.forJoin(),
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
		try {
			String[] hp = ready.get(60, TimeUnit.SECONDS).address().split(":");
			try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
				s.setSoTimeout(20_000);
				byte[] msg = "meow".getBytes(StandardCharsets.UTF_8);
				s.getOutputStream().write(msg);
				return new String(s.getInputStream().readNBytes(msg.length), StandardCharsets.UTF_8).equals("meow");
			}
		} finally {
			join.close();
		}
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
