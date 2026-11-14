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
		static final int CONNECTING = 0, CONNECTED = 1, FAILED = 2, CLOSED = 3;

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
			SocketChannel ch = channel;
			if (ch != null) {
				ch.close();
			}
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
