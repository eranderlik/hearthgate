package dev.hearthgate.core.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsFdTest {
	/** A server whose certificate isn't from a trusted root must be refused, not silently accepted. */
	@Test
	void untrustedCertificateFailsTheHandshake(@TempDir Path dir) throws Exception {
		KeyStore store = selfSigned(dir);
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(store, "changeit".toCharArray());
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), null, null);
		untrusted(ctx);
	}

	private static KeyStore selfSigned(Path dir) throws Exception {
		Path ks = dir.resolve("server.p12");
		Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
				"-genkeypair", "-alias", "s", "-keyalg", "EC", "-dname", "CN=localhost", "-validity", "2",
				"-ext", "SAN=dns:localhost", "-keystore", ks.toString(), "-storetype", "PKCS12",
				"-storepass", "changeit").inheritIO().start();
		assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool failed");
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (FileInputStream in = new FileInputStream(ks.toFile())) {
			store.load(in, "changeit".toCharArray());
		}
		return store;
	}

	private static void untrusted(SSLContext ctx) throws Exception {
		try (SSLServerSocket server = (SSLServerSocket) ctx.getServerSocketFactory()
				.createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread t = new Thread(() -> {
				try (var s = server.accept()) {
					s.getInputStream().read(); // drive the server side of the handshake
				} catch (Exception ignored) {
				}
			});
			t.setDaemon(true);
			t.start();

			SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
					server.getLocalPort()));
			Object woke = new Object();
			HostFd.Tls tls = new HostFd.Tls(ch, "localhost", () -> {
				synchronized (woke) {
					woke.notifyAll();
				}
			});
			tls.start();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
			synchronized (woke) {
				while (tls.state() == HostFd.Tls.HANDSHAKING && System.nanoTime() < deadline) {
					woke.wait(200);
				}
			}
			assertEquals(HostFd.Tls.FAILED, tls.state());
			assertEquals(Errno.ECONNABORTED, tls.error());
			assertEquals(-2, tls.recv(new byte[16], 16), "reads report the failure");
			assertEquals(-2, tls.send(new byte[] {1}), "writes report the failure");
			assertTrue(!ch.isOpen(), "the socket is closed");
		}
	}

	/**
	 * Echoes random data through the descriptor while the guest side reads
	 * and writes concurrently, as DERP traffic does, and checks every byte.
	 */
	@Test
	void concurrentTrafficArrivesIntact(@TempDir Path dir) throws Exception {
		KeyStore store = selfSigned(dir);
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(store, "changeit".toCharArray());
		javax.net.ssl.TrustManagerFactory tmf =
				javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(store);
		SSLContext serverCtx = SSLContext.getInstance("TLS");
		serverCtx.init(kmf.getKeyManagers(), null, null);
		SSLContext clientCtx = SSLContext.getInstance("TLS");
		clientCtx.init(null, tmf.getTrustManagers(), null);

		try (SSLServerSocket server = (SSLServerSocket) serverCtx.getServerSocketFactory()
				.createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread echo = new Thread(() -> {
				try (var s = server.accept()) {
					s.getInputStream().transferTo(s.getOutputStream());
				} catch (Exception ignored) {
				}
			});
			echo.setDaemon(true);
			echo.start();

			SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
					server.getLocalPort()));
			Object woke = new Object();
			HostFd.Tls tls = new HostFd.Tls(ch, "localhost", () -> {
				synchronized (woke) {
					woke.notifyAll();
				}
			}, clientCtx);
			tls.start();

			byte[] data = new byte[8 << 20];
			new java.util.Random(7).nextBytes(data);
			byte[] got = new byte[data.length];
			java.util.concurrent.atomic.AtomicReference<Throwable> fail = new java.util.concurrent.atomic.AtomicReference<>();
			// The guest's two goroutines: one writes in odd-sized pieces, one reads.
			Thread writer = new Thread(() -> {
				try {
					java.util.Random r = new java.util.Random(1);
					int off = 0;
					while (off < data.length) {
						int len = Math.min(data.length - off, 1 + r.nextInt(70_000));
						int n = tls.send(java.util.Arrays.copyOfRange(data, off, off + len));
						if (n == -2) {
							throw new AssertionError("send failed: " + tls.error());
						}
						if (n == -1) {
							synchronized (woke) {
								woke.wait(5);
							}
							continue;
						}
						off += n;
					}
				} catch (Throwable t) {
					fail.set(t);
				}
			});
			Thread reader = new Thread(() -> {
				try {
					int off = 0;
					byte[] buf = new byte[33_333];
					while (off < got.length) {
						int n = tls.recv(buf, Math.min(buf.length, got.length - off));
						if (n == -2 || n == 0) {
							throw new AssertionError("recv ended at " + off + ": " + n + " error " + tls.error());
						}
						if (n == -1) {
							synchronized (woke) {
								woke.wait(5);
							}
							continue;
						}
						System.arraycopy(buf, 0, got, off, n);
						off += n;
					}
				} catch (Throwable t) {
					fail.set(t);
				}
			});
			writer.start();
			reader.start();
			writer.join(60_000);
			reader.join(60_000);
			if (fail.get() != null) {
				throw new AssertionError(fail.get());
			}
			org.junit.jupiter.api.Assertions.assertArrayEquals(data, got);
			tls.close();
		}
	}
}
