package dev.tailcatmc.core;

import dev.tailcatmc.core.host.NetworkPolicy;
import dev.tailcatmc.core.nativehelper.NativeHelpers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hosts and joins through Tailcat's public relays inside this JVM, with an
 * echo server standing in for Minecraft. Opt in with {@code -PnetworkTests};
 * add {@code -PtestNative} to also run the native helper (on the host side,
 * as a dedicated server would, and on both sides for its raw speed).
 */
class EndToEndTest {
	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	void wasmHostWasmJoin() throws Exception {
		network();
		transfer(HelperBackend.WASM, HelperBackend.WASM, 1, true);
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	void nativeHostWasmJoin() throws Exception {
		transfer(nativeBackend(), HelperBackend.WASM, 1, true);
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	void nativeHostNativeJoin() throws Exception {
		transfer(nativeBackend(), nativeBackend(), 1, false);
	}

	/** Several players at once, as on a server, each on its own WebAssembly client. */
	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	void wasmHostFourPlayers() throws Exception {
		network();
		Assumptions.assumeTrue(System.getProperty("tcmc.nativeLibrary") != null, "compared with -PtestNative only");
		transfer(HelperBackend.WASM, HelperBackend.WASM, 4, false);
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	void nativeHostFourPlayers() throws Exception {
		transfer(nativeBackend(), HelperBackend.WASM, 4, false);
	}

	private static void network() {
		Assumptions.assumeTrue(Boolean.getBoolean("tcmc.networkTests"), "network tests disabled");
	}

	private static HelperBackend nativeBackend() throws Exception {
		network();
		String lib = System.getProperty("tcmc.nativeLibrary");
		Assumptions.assumeTrue(lib != null, "native tests need -PtestNative");
		return NativeHelpers.load(Path.of(lib), System.getProperty("tcmc.nativePlatform"));
	}

	/**
	 * Starts a host on {@code hostB} and {@code players} joiners on
	 * {@code joinB}, then has every joiner echo 8 MiB through the tunnel at
	 * the same time, three rounds, reporting the combined rate.
	 */
	private static void transfer(HelperBackend hostB, HelperBackend joinB, int players, boolean measureIdle)
			throws Exception {
		String label = hostB.description() + " host, " + players + "x " + joinB.description() + " join";
		try (ServerSocket echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			Thread echoThread = new Thread(() -> serveEcho(echo), "echo");
			echoThread.setDaemon(true);
			echoThread.start();

			Recorder host = new Recorder("host");
			long t0 = System.nanoTime();
			Helper hs = hostB.start(
					List.of("host", "--target", "127.0.0.1:" + echo.getLocalPort(), "--mc", "test"),
					NetworkPolicy.forHost(echo.getLocalPort()), host);
			HelperEvent ready = host.nextOfType("ready", 90);
			System.out.printf("[%s] host ready after %.1fs%n", label, (System.nanoTime() - t0) / 1e9);

			List<Recorder> joins = new ArrayList<>();
			List<Helper> joinHelpers = new ArrayList<>();
			List<InetSocketAddress> addrs = new ArrayList<>();
			for (int i = 0; i < players; i++) {
				Recorder join = new Recorder("join" + i);
				long t1 = System.nanoTime();
				joinHelpers.add(joinB.start(List.of("join", "--invite", ready.invite()), NetworkPolicy.forJoin(), join));
				HelperEvent joined = join.nextOfType("ready", 90);
				System.out.printf("[%s] join %d ready after %.1fs%n", label, i, (System.nanoTime() - t1) / 1e9);
				String[] hp = joined.address().split(":");
				addrs.add(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
				joins.add(join);
			}

			for (int round = 0; round < 3; round++) {
				byte[][] data = new byte[players][8 << 20];
				byte[][] got = new byte[players][8 << 20];
				List<Thread> threads = new ArrayList<>();
				long t = System.nanoTime();
				for (int i = 0; i < players; i++) {
					new Random(round * 31L + i).nextBytes(data[i]);
					int p = i;
					Thread th = new Thread(() -> echoThrough(addrs.get(p), data[p], got[p]));
					th.start();
					threads.add(th);
				}
				for (Thread th : threads) {
					th.join(240_000);
				}
				double secs = (System.nanoTime() - t) / 1e9;
				long total = (long) players * (8 << 20);
				System.out.printf("[%s] round %d: echoed %d MB in %.2fs: %.2f MB/s each way in total%n",
						label, round, total >> 20, secs, total / secs / 1e6);
				for (int i = 0; i < players; i++) {
					assertArrayEquals(data[i], got[i], "player " + i + " round " + round);
				}
			}
			HelperEvent peers = host.nextOfType("peers", 30);
			System.out.println("[" + label + "] host sees peers: " + peers.peers());

			if (measureIdle) {
				// Idle cost: an open tunnel with no traffic must not burn CPU.
				// Native helper threads aren't Java threads, so this measures
				// the whole process.
				var os = (com.sun.management.OperatingSystemMXBean)
						java.lang.management.ManagementFactory.getOperatingSystemMXBean();
				Thread.sleep(5_000);
				long before = os.getProcessCpuTime();
				long idleWindow = 20_000;
				Thread.sleep(idleWindow);
				double idleCores = (os.getProcessCpuTime() - before) / 1e6 / idleWindow;
				System.out.printf("[%s] idle CPU of the test JVM: %.2f%% of one core%n", label, idleCores * 100);
				System.out.printf("[%s] helper memory: host %d MiB, join %d MiB%n", label, hs.memoryBytes() >> 20,
						joinHelpers.get(0).memoryBytes() >> 20);
				assertTrue(idleCores < 0.05, "helpers busy while idle");
			}

			joinHelpers.forEach(Helper::stop);
			hs.stop();
			for (Recorder j : joins) {
				assertEquals(0, j.exited.get(20, TimeUnit.SECONDS));
			}
			assertEquals(0, host.exited.get(20, TimeUnit.SECONDS));
		}
	}

	private static void echoThrough(InetSocketAddress addr, byte[] data, byte[] got) {
		try (Socket s = new Socket()) {
			s.connect(addr, 10_000);
			Thread reader = new Thread(() -> readFully(s, got));
			reader.start();
			OutputStream out = s.getOutputStream();
			for (int off = 0; off < data.length; off += 64 << 10) {
				out.write(data, off, Math.min(64 << 10, data.length - off));
			}
			reader.join(240_000);
		} catch (Exception e) {
			Arrays.fill(got, (byte) 0);
		}
	}

	private static void serveEcho(ServerSocket ss) {
		while (!ss.isClosed()) {
			try {
				Socket c = ss.accept();
				Thread t = new Thread(() -> {
					try (c; InputStream in = c.getInputStream(); OutputStream out = c.getOutputStream()) {
						in.transferTo(out);
					} catch (Exception ignored) {
					}
				});
				t.setDaemon(true);
				t.start();
			} catch (Exception e) {
				return;
			}
		}
	}

	private static void readFully(Socket s, byte[] into) {
		try {
			InputStream in = s.getInputStream();
			int off = 0;
			while (off < into.length) {
				int n = in.read(into, off, into.length - off);
				if (n < 0) {
					break;
				}
				off += n;
			}
		} catch (Exception e) {
			Arrays.fill(into, (byte) 0);
		}
	}
}
