package dev.hearthgate.core.host;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * A file descriptor in the guest's WASI descriptor table.
 *
 * <p>Readiness for {@code poll_oneoff} comes from one of two places. Descriptors
 * backed by an open NIO channel register it with the host's selector. Others
 * (stdin, and sockets whose connection is still being set up on a worker
 * thread) report readiness themselves, and whichever thread changes it wakes
 * the selector.
 */
abstract class HostFd {
	// WASI filetype values.
	static final int FILETYPE_CHARACTER_DEVICE = 2;
	static final int FILETYPE_SOCKET_DGRAM = 5;
	static final int FILETYPE_SOCKET_STREAM = 6;

	final int filetype;
	/** Whether fd_fdstat_get reports FDFLAG_NONBLOCK, making Go poll it. */
	final boolean nonblocking;

	HostFd(int filetype, boolean nonblocking) {
		this.filetype = filetype;
		this.nonblocking = nonblocking;
	}

	/** The channel to select on, or null when {@link #readReady}/{@link #writeReady} apply. */
	SelectableChannel channel() {
		return null;
	}

	int readOps() {
		return SelectionKey.OP_READ;
	}

	int writeOps() {
		return SelectionKey.OP_WRITE;
	}

	boolean readReady() {
		return true;
	}

	boolean writeReady() {
		return true;
	}

	void close() throws IOException {}

	/** The guest's stdin: bytes the JVM feeds it, then EOF to request shutdown. */
	static final class Stdin extends HostFd {
		private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
		private int offset;
		private boolean eof;
		private final Runnable wakeup;

		Stdin(Runnable wakeup) {
			super(FILETYPE_CHARACTER_DEVICE, true);
			this.wakeup = wakeup;
		}

		synchronized void feed(byte[] data) {
			if (!eof && data.length > 0) {
				chunks.add(data.clone());
			}
			wakeup.run();
		}

		synchronized void closeInput() {
			eof = true;
			wakeup.run();
		}

		/** Copies available bytes; returns -1 for EAGAIN and 0 for EOF. */
		synchronized int read(byte[] dst, int len) {
			if (chunks.isEmpty()) {
				return eof ? 0 : -1;
			}
			int n = 0;
			while (n < len && !chunks.isEmpty()) {
				byte[] head = chunks.peek();
				int take = Math.min(len - n, head.length - offset);
				System.arraycopy(head, offset, dst, n, take);
				n += take;
				offset += take;
				if (offset == head.length) {
					chunks.poll();
					offset = 0;
				}
			}
			return n;
		}

		@Override
		synchronized boolean readReady() {
			return eof || !chunks.isEmpty();
		}
	}

	/** stdout or stderr: UTF-8 lines delivered to a consumer on the guest thread. */
	static final class LineOutput extends HostFd {
		private static final int MAX_LINE = 1 << 20;
		private final Consumer<String> lines;
		private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();

		LineOutput(Consumer<String> lines) {
			super(FILETYPE_CHARACTER_DEVICE, false);
			this.lines = lines;
		}

		/**
		 * Splits on newline bytes. A 0x0A byte never occurs inside a multi-byte
		 * UTF-8 sequence, so each complete line decodes on its own.
		 */
		void write(byte[] data) {
			int start = 0;
			for (int i = 0; i < data.length; i++) {
				if (data[i] == '\n') {
					pending.write(data, start, i - start);
					emit();
					start = i + 1;
				}
			}
			pending.write(data, start, data.length - start);
			if (pending.size() > MAX_LINE) {
				emit();
			}
		}

		void flush() {
			if (pending.size() > 0) {
				emit();
			}
		}

		private void emit() {
			lines.accept(pending.toString(StandardCharsets.UTF_8));
			pending.reset();
		}
	}

	/** A TCP stream. Outbound connections are set up on a worker thread. */
	static final class Tcp extends HostFd {
		static final int CONNECTING = 0, CONNECTED = 1, FAILED = 2, CLOSED = 3, UPGRADED = 4;

		private volatile int state;
		private volatile int error;
		private volatile SocketChannel channel;
		private final Runnable wakeup;

		/** An accepted, already connected stream. */
		Tcp(SocketChannel channel, Runnable wakeup) {
			super(FILETYPE_SOCKET_STREAM, true);
			this.channel = channel;
			this.state = CONNECTED;
			this.wakeup = wakeup;
		}

		/** An outbound stream whose connection is pending. */
		Tcp(Runnable wakeup) {
			super(FILETYPE_SOCKET_STREAM, true);
			this.state = CONNECTING;
			this.wakeup = wakeup;
		}

		/** Called by the connecting worker. Returns false if the guest closed us first. */
		synchronized boolean connected(SocketChannel ch) {
			if (state != CONNECTING) {
				return false;
			}
			channel = ch;
			state = CONNECTED;
			wakeup.run();
			return true;
		}

		synchronized void failed(int errno) {
			if (state == CONNECTING) {
				error = errno;
				state = FAILED;
				wakeup.run();
			}
		}

		int state() {
			return state;
		}

		int error() {
			return error;
		}

		SocketChannel socket() {
			return channel;
		}

		private Tls upgradedTo;

		/** The connection now carries TLS run by the host (see {@link Tls}); the guest no longer uses it. */
		synchronized void handOver(Tls tls) {
			state = UPGRADED;
			upgradedTo = tls;
		}

		@Override
		SelectableChannel channel() {
			return state == CONNECTED ? channel : null;
		}

		@Override
		boolean readReady() {
			return state != CONNECTING;
		}

		@Override
		boolean writeReady() {
			return state != CONNECTING;
		}

		@Override
		synchronized void close() throws IOException {
			state = CLOSED;
			if (upgradedTo != null) {
				// Closing the TCP connection under TLS (DERP does) ends the TLS
				// stream too; mark it closed first so that isn't reported as an error.
				upgradedTo.close();
			}
			SocketChannel ch = channel;
			if (ch != null) {
				ch.close();
			}
		}
	}

	/**
	 * A TLS client stream run by the JDK over a connected TCP socket taken
	 * from the guest (see {@code tls_start}). The guest reads and writes
	 * plaintext. A virtual thread does the handshake and then reads; another
	 * writes. Both exchange data with the guest through bounded buffers and
	 * report readiness changes by waking the selector.
	 */
	static final class Tls extends HostFd {
		static final int HANDSHAKING = 0, OPEN = 1, FAILED = 2, CLOSED = 3;
		private static final int MAX_BUFFERED = 256 * 1024;
		private static final int READ_CHUNK = 16 * 1024;
		private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("hearthgate");

		final SocketChannel channel;
		private final String serverName;
		private final Runnable wakeup;
		private final ArrayDeque<byte[]> in = new ArrayDeque<>();
		private final ArrayDeque<byte[]> out = new ArrayDeque<>();
		private int inBytes, inOffset, outBytes;
		private boolean inEof, shutdownWrite, outputShut;
		private volatile int state = HANDSHAKING;
		private volatile int error;
		private javax.net.ssl.SSLSocket ssl;

		private final javax.net.ssl.SSLContext context;

		/** {@code channel} must be connected, in blocking mode, and no longer selected. */
		Tls(SocketChannel channel, String serverName, Runnable wakeup) {
			this(channel, serverName, wakeup, null);
		}

		/** With a specific TLS context instead of the JVM's default trust; for tests. */
		Tls(SocketChannel channel, String serverName, Runnable wakeup, javax.net.ssl.SSLContext context) {
			super(FILETYPE_SOCKET_STREAM, true);
			this.channel = channel;
			this.serverName = serverName;
			this.wakeup = wakeup;
			this.context = context;
		}

		void start() {
			Thread.ofVirtual().name("hearthgate-tls-read").start(this::handshakeAndRead);
		}

		private void handshakeAndRead() {
			try {
				java.net.InetSocketAddress remote = (java.net.InetSocketAddress) channel.getRemoteAddress();
				javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) (context != null ? context
						: javax.net.ssl.SSLContext.getDefault()).getSocketFactory().createSocket(channel.socket(), serverName, remote.getPort(), true);
				javax.net.ssl.SSLParameters p = s.getSSLParameters();
				// Verify the certificate's name, as an HTTPS client does.
				p.setEndpointIdentificationAlgorithm("HTTPS");
				p.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(serverName)));
				s.setSSLParameters(p);
				s.startHandshake();
				synchronized (this) {
					if (state != HANDSHAKING) {
						return; // closed meanwhile
					}
					ssl = s;
					state = OPEN;
				}
				wakeup.run();
				Thread.ofVirtual().name("hearthgate-tls-write").start(this::write);
				java.io.InputStream is = s.getInputStream();
				while (true) {
					synchronized (this) {
						while (inBytes >= MAX_BUFFERED && state == OPEN) {
							wait();
						}
						if (state != OPEN) {
							return;
						}
					}
					byte[] buf = new byte[READ_CHUNK];
					int n = is.read(buf);
					synchronized (this) {
						if (n < 0) {
							inEof = true;
						} else {
							in.add(n == buf.length ? buf : java.util.Arrays.copyOf(buf, n));
							inBytes += n;
						}
					}
					wakeup.run();
					if (n < 0) {
						return;
					}
				}
			} catch (javax.net.ssl.SSLException e) {
				fail(Errno.ECONNABORTED, e, true);
			} catch (IOException e) {
				fail(Errno.ECONNRESET, e, false);
			} catch (InterruptedException e) {
				fail(Errno.ECONNRESET, e, false);
			} catch (RuntimeException | java.security.NoSuchAlgorithmException e) {
				fail(Errno.ECONNABORTED, e, true);
			}
		}

		private void write() {
			try {
				java.io.OutputStream os = ssl.getOutputStream();
				while (true) {
					byte[] chunk;
					synchronized (this) {
						while (out.isEmpty() && !shutdownWrite && state == OPEN) {
							wait();
						}
						if (state != OPEN) {
							return;
						}
						if (out.isEmpty()) { // shutdownWrite with everything sent
							if (!outputShut) {
								outputShut = true;
								ssl.shutdownOutput();
							}
							wait();
							continue;
						}
						chunk = out.poll();
						outBytes -= chunk.length;
					}
					wakeup.run(); // there is room to write again
					os.write(chunk);
					os.flush();
				}
			} catch (IOException | InterruptedException e) {
				fail(Errno.ECONNRESET, e, false);
			}
		}

		private void fail(int errno, Exception e, boolean log) {
			boolean first;
			int was;
			synchronized (this) {
				was = state;
				first = state == HANDSHAKING || state == OPEN;
				if (first) {
					error = errno;
					state = FAILED;
				}
				notifyAll();
			}
			if (first && log) {
				javax.net.ssl.SSLSocket s = ssl;
				LOG.warn("TLS connection to {} failed {}: {}", serverName,
						was == HANDSHAKING ? "during the handshake" : "after the handshake ("
								+ (s == null ? "?" : s.getSession().getProtocol() + ", "
								+ s.getSession().getCipherSuite()) + ")",
						e.getCause() == null ? e.toString() : e + " (caused by " + e.getCause() + ")");
			}
			try {
				channel.close();
			} catch (IOException ignored) {
			}
			wakeup.run();
		}

		int state() {
			return state;
		}

		int error() {
			return error;
		}

		/** Copies decrypted bytes; returns -1 for EAGAIN, 0 for EOF, or -2 when failed. */
		synchronized int recv(byte[] dst, int len) {
			if (in.isEmpty()) {
				if (state == FAILED || state == CLOSED) {
					return -2;
				}
				return inEof ? 0 : -1;
			}
			int n = 0;
			while (n < len && !in.isEmpty()) {
				byte[] head = in.peek();
				int take = Math.min(len - n, head.length - inOffset);
				System.arraycopy(head, inOffset, dst, n, take);
				n += take;
				inOffset += take;
				if (inOffset == head.length) {
					in.poll();
					inOffset = 0;
				}
			}
			inBytes -= n;
			notifyAll();
			return n;
		}

		/** Queues plaintext; returns bytes taken, -1 for EAGAIN, or -2 when failed or shut down. */
		synchronized int send(byte[] src) {
			if (state == HANDSHAKING) {
				return -1;
			}
			if (state != OPEN || shutdownWrite) {
				return -2;
			}
			int room = MAX_BUFFERED - outBytes;
			if (room <= 0) {
				return -1;
			}
			byte[] chunk = src.length <= room ? src : java.util.Arrays.copyOf(src, room);
			out.add(chunk);
			outBytes += chunk.length;
			notifyAll();
			return chunk.length;
		}

		synchronized void shutdownOutput() {
			shutdownWrite = true;
			notifyAll();
		}

		@Override
		synchronized boolean readReady() {
			return state != HANDSHAKING && (state != OPEN || inEof || !in.isEmpty());
		}

		@Override
		synchronized boolean writeReady() {
			return state != HANDSHAKING && (state != OPEN || outBytes < MAX_BUFFERED);
		}

		@Override
		void close() throws IOException {
			synchronized (this) {
				state = CLOSED;
				notifyAll();
			}
			// Closing the socket, not the SSLSocket, never blocks on a close_notify.
			channel.close();
		}
	}

	static final class Listener extends HostFd {
		final ServerSocketChannel channel;

		Listener(ServerSocketChannel channel) {
			super(FILETYPE_SOCKET_STREAM, true);
			this.channel = channel;
		}

		@Override
		SelectableChannel channel() {
			return channel;
		}

		@Override
		int readOps() {
			return SelectionKey.OP_ACCEPT;
		}

		@Override
		int writeOps() {
			return 0;
		}

		@Override
		void close() throws IOException {
			channel.close();
		}
	}

	static final class Udp extends HostFd {
		final DatagramChannel channel;

		Udp(DatagramChannel channel) {
			super(FILETYPE_SOCKET_DGRAM, true);
			this.channel = channel;
		}

		@Override
		SelectableChannel channel() {
			return channel;
		}

		@Override
		void close() throws IOException {
			channel.close();
		}
	}
}
