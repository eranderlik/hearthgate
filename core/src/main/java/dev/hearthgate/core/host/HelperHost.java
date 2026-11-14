package dev.hearthgate.core.host;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.WasmFunctionHandle;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Host environment for one helper instance: WASI preview 1 plus the "hearthgate"
 * socket and crypto module.
 *
 * <p>All imports run on the single guest thread. The only cross-thread entry
 * points are {@link #feedStdin}, {@link #closeStdin}, {@link #kill} and the
 * connection workers, which communicate through {@link HostFd} state and
 * {@link Selector#wakeup()}.
 *
 * <p>The guest blocks only in {@code poll_oneoff}: Go's wasip1 scheduler calls
 * it when no goroutine is runnable, with a clock subscription for the next
 * timer and read/write subscriptions for descriptors goroutines wait on.
 */
public final class HelperHost implements AutoCloseable {
	private static final String WASI = "wasi_snapshot_preview1";
	private static final String TCMC = "hearthgate";
	private static final int IO_CHUNK = 256 * 1024;
	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int UDP_BUFFER = 4 << 20;

	private static final AtomicInteger WORKER_IDS = new AtomicInteger();
	private static final ExecutorService CONNECT_WORKERS = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "hearthgate-connect-" + WORKER_IDS.incrementAndGet());
		t.setDaemon(true);
		return t;
	});

	private final List<String> args;
	private final List<String> env;
	private final NetworkPolicy policy;
	private final Selector selector;
	private final Map<Integer, HostFd> fds = new HashMap<>();
	private final HostFd.Stdin stdin;
	private final HostFd.LineOutput stdout;
	private final HostFd.LineOutput stderr;
	private final SecureRandom random = new SecureRandom();
	private final long monotonicBase = System.nanoTime();
	private final ByteBuffer io = ByteBuffer.allocate(IO_CHUNK);
	private int nextFd = 3;
	private volatile boolean killed;
	private Cipher sealer;
	private Cipher opener;

	/** Thrown by {@code proc_exit} to unwind the guest. */
	public static final class Exit extends RuntimeException {
		public final int code;

		Exit(int code) {
			super("exit " + code, null, false, false);
			this.code = code;
		}
	}

	/** Thrown into the guest from its next host call after {@link #kill()}. */
	public static final class Killed extends RuntimeException {
		Killed() {
			super("helper killed", null, false, false);
		}
	}

	public HelperHost(List<String> args, NetworkPolicy policy, Consumer<String> stdoutLines,
			Consumer<String> stderrLines) throws IOException {
		this(args, List.of(), policy, stdoutLines, stderrLines);
	}

	/** {@code env} holds NAME=value entries for the guest's environment (e.g. GOGC). */
	public HelperHost(List<String> args, List<String> env, NetworkPolicy policy, Consumer<String> stdoutLines,
			Consumer<String> stderrLines) throws IOException {
		this.args = List.copyOf(args);
		this.env = List.copyOf(env);
		this.policy = policy;
		this.selector = Selector.open();
		this.stdin = new HostFd.Stdin(selector::wakeup);
		this.stdout = new HostFd.LineOutput(stdoutLines);
		this.stderr = new HostFd.LineOutput(stderrLines);
		fds.put(0, stdin);
		fds.put(1, stdout);
		fds.put(2, stderr);
	}

	/** Sends bytes to the guest's stdin. Safe from any thread. */
	public void feedStdin(byte[] data) {
		stdin.feed(data);
	}

	/** Closes the guest's stdin, which asks the helper to shut down. Safe from any thread. */
	public void closeStdin() {
		stdin.closeInput();
	}

	/** Forces the guest to stop at its next host call. Safe from any thread. */
	public void kill() {
		killed = true;
		selector.wakeup();
	}

	@Override
	public void close() {
		stdout.flush();
		stderr.flush();
		for (HostFd fd : fds.values()) {
			try {
				fd.close();
			} catch (IOException ignored) {
			}
		}
		fds.clear();
		try {
			selector.close();
		} catch (IOException ignored) {
		}
	}

	private void checkKilled() {
		if (killed) {
			throw new Killed();
		}
	}

	private int allocate(HostFd fd) {
		int n = nextFd++;
		fds.put(n, fd);
		return n;
	}

	// ---------------------------------------------------------------------
	// Import table

	private interface Fn {
		int call(Memory m, long[] a) throws Exception;
	}

	private static List<ValType> types(String sig) {
		List<ValType> out = new ArrayList<>(sig.length());
		for (char c : sig.toCharArray()) {
			out.add(c == 'I' ? ValType.I64 : ValType.I32);
		}
		return out;
	}

	/** An import returning an errno; {@code sig} uses i for i32 and I for i64. */
	private HostFunction errnoFn(String module, String name, String sig, Fn fn) {
		WasmFunctionHandle handle = (Instance inst, long... a) -> {
			int r;
			try {
				r = fn.call(inst.memory(), a);
			} catch (Exit | Killed e) {
				throw e;
			} catch (Exception e) {
				r = Errno.of(e);
			}
			return new long[] {r};
		};
		return new HostFunction(module, name, FunctionType.of(types(sig), List.of(ValType.I32)), handle);
	}

	public ImportValues imports() {
		List<HostFunction> f = new ArrayList<>();
		// WASI: process
		f.add(new HostFunction(WASI, "proc_exit", FunctionType.of(types("i"), List.of()),
				(inst, a) -> {
					throw new Exit((int) a[0]);
				}));
		f.add(errnoFn(WASI, "sched_yield", "", (m, a) -> {
			checkKilled();
			return 0;
		}));
		f.add(errnoFn(WASI, "args_sizes_get", "ii", (m, a) -> sizes(m, a, args)));
		f.add(errnoFn(WASI, "args_get", "ii", (m, a) -> strings(m, a, args)));
		f.add(errnoFn(WASI, "environ_sizes_get", "ii", (m, a) -> sizes(m, a, env)));
		f.add(errnoFn(WASI, "environ_get", "ii", (m, a) -> strings(m, a, env)));
		f.add(errnoFn(WASI, "clock_time_get", "iIi", this::clockTimeGet));
		f.add(errnoFn(WASI, "random_get", "ii", (m, a) -> {
			byte[] b = new byte[(int) a[1]];
			random.nextBytes(b);
			m.write((int) a[0], b);
			return 0;
		}));
		f.add(errnoFn(WASI, "poll_oneoff", "iiii", this::pollOneoff));
		// WASI: descriptors
		f.add(errnoFn(WASI, "fd_write", "iiii", this::fdWrite));
		f.add(errnoFn(WASI, "fd_read", "iiii", this::fdRead));
		f.add(errnoFn(WASI, "fd_close", "i", this::fdClose));
		f.add(errnoFn(WASI, "fd_fdstat_get", "ii", this::fdFdstatGet));
		f.add(errnoFn(WASI, "fd_fdstat_set_flags", "ii", (m, a) -> fds.containsKey((int) a[0]) ? 0 : Errno.EBADF));
		f.add(errnoFn(WASI, "fd_filestat_get", "ii", this::fdFilestatGet));
		f.add(errnoFn(WASI, "fd_prestat_get", "ii", (m, a) -> Errno.EBADF));
		f.add(errnoFn(WASI, "fd_prestat_dir_name", "iii", (m, a) -> Errno.EBADF));
		f.add(errnoFn(WASI, "fd_sync", "i", (m, a) -> fds.containsKey((int) a[0]) ? 0 : Errno.EBADF));
		f.add(errnoFn(WASI, "fd_readdir", "iiiIi", (m, a) -> Errno.EBADF));
		f.add(errnoFn(WASI, "sock_shutdown", "ii", (m, a) -> Errno.ENOTSUP));
		// WASI: filesystem. There are no preopened directories, so the guest
		// has no filesystem; these exist only to satisfy imports.
		f.add(errnoFn(WASI, "path_open", "iiiiiIIii", (m, a) -> Errno.ENOTCAPABLE));
		f.add(errnoFn(WASI, "path_filestat_get", "iiiii", (m, a) -> Errno.ENOTCAPABLE));
		f.add(errnoFn(WASI, "path_readlink", "iiiiii", (m, a) -> Errno.ENOTCAPABLE));
		f.add(errnoFn(WASI, "path_remove_directory", "iii", (m, a) -> Errno.ENOTCAPABLE));
		f.add(errnoFn(WASI, "path_rename", "iiiiii", (m, a) -> Errno.ENOTCAPABLE));
		f.add(errnoFn(WASI, "path_unlink_file", "iii", (m, a) -> Errno.ENOTCAPABLE));
		// hearthgate: sockets
		f.add(errnoFn(TCMC, "tcp_connect", "iiiii", this::tcpConnect));
		f.add(errnoFn(TCMC, "tcp_connect_status", "i", this::tcpConnectStatus));
		f.add(errnoFn(TCMC, "tcp_listen", "iii", this::tcpListen));
		f.add(errnoFn(TCMC, "tcp_accept", "ii", this::tcpAccept));
		f.add(errnoFn(TCMC, "sock_recv", "iiii", this::sockRecv));
		f.add(errnoFn(TCMC, "sock_send", "iiii", this::sockSend));
		f.add(errnoFn(TCMC, "sock_shutdown_write", "i", this::sockShutdownWrite));
		f.add(errnoFn(TCMC, "udp_bind", "iiiii", this::udpBind));
		f.add(errnoFn(TCMC, "udp_recvfrom", "iiiii", this::udpRecvFrom));
		f.add(errnoFn(TCMC, "udp_sendto", "iiii", this::udpSendTo));
		f.add(errnoFn(TCMC, "sock_addr", "iii", this::sockAddr));
		f.add(errnoFn(TCMC, "interfaces", "iii", this::interfaces));
		// hearthgate: crypto
		f.add(errnoFn(TCMC, "aead_seal", "iiiiiii", (m, a) -> aead(m, a, true)));
		f.add(errnoFn(TCMC, "aead_open", "iiiiiii", (m, a) -> aead(m, a, false)));
		return ImportValues.builder().withFunctions(Collections.unmodifiableList(f)).build();
	}

	private static int u32(long v) {
		return (int) v;
	}

	// ---------------------------------------------------------------------
	// WASI implementation

	private static int sizes(Memory m, long[] a, List<String> list) {
		int bytes = 0;
		for (String s : list) {
			bytes += s.getBytes(StandardCharsets.UTF_8).length + 1;
		}
		m.writeI32(u32(a[0]), list.size());
		m.writeI32(u32(a[1]), bytes);
		return 0;
	}

	private static int strings(Memory m, long[] a, List<String> list) {
		int ptrs = u32(a[0]), buf = u32(a[1]);
		for (String s : list) {
			m.writeI32(ptrs, buf);
			ptrs += 4;
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			m.write(buf, b);
			m.writeByte(buf + b.length, (byte) 0);
			buf += b.length + 1;
		}
		return 0;
	}

	private long monotonicNanos() {
		return System.nanoTime() - monotonicBase;
	}

	private static long realtimeNanos() {
		java.time.Instant now = java.time.Instant.now();
		return now.getEpochSecond() * 1_000_000_000L + now.getNano();
	}

	private int clockTimeGet(Memory m, long[] a) {
		checkKilled();
		int id = u32(a[0]);
		long t = switch (id) {
			case 0 -> realtimeNanos();
			case 1, 2, 3 -> monotonicNanos();
			default -> -1;
		};
		if (t < 0) {
			return Errno.EINVAL;
		}
		m.writeLong(u32(a[2]), t);
		return 0;
	}

	private int fdWrite(Memory m, long[] a) {
		int fd = u32(a[0]), iovs = u32(a[1]), n = u32(a[2]);
		if (!(fds.get(fd) instanceof HostFd.LineOutput out)) {
			return Errno.EBADF;
		}
		int total = 0;
		for (int i = 0; i < n; i++) {
			int ptr = m.readInt(iovs + i * 8), len = m.readInt(iovs + i * 8 + 4);
			if (len > 0) {
				out.write(m.readBytes(ptr, len));
				total += len;
			}
		}
		m.writeI32(u32(a[3]), total);
		return 0;
	}

	private int fdRead(Memory m, long[] a) {
		int fd = u32(a[0]), iovs = u32(a[1]), n = u32(a[2]);
		if (!(fds.get(fd) instanceof HostFd.Stdin in)) {
			return Errno.EBADF;
		}
		int want = 0;
		for (int i = 0; i < n; i++) {
			want += m.readInt(iovs + i * 8 + 4);
		}
		byte[] buf = new byte[Math.min(want, IO_CHUNK)];
		int got = in.read(buf, buf.length);
		if (got < 0) {
			return Errno.EAGAIN;
		}
		int off = 0;
		for (int i = 0; i < n && off < got; i++) {
			int ptr = m.readInt(iovs + i * 8), len = m.readInt(iovs + i * 8 + 4);
			int take = Math.min(len, got - off);
			m.write(ptr, buf, off, take);
			off += take;
		}
		m.writeI32(u32(a[3]), got);
		return 0;
	}

	private int fdClose(Memory m, long[] a) throws IOException {
		int fd = u32(a[0]);
		if (fd <= 2) {
			return 0;
		}
		HostFd f = fds.remove(fd);
		if (f == null) {
			return Errno.EBADF;
		}
		f.close();
		return 0;
	}

	private int fdFdstatGet(Memory m, long[] a) {
		HostFd f = fds.get(u32(a[0]));
		if (f == null) {
			return Errno.EBADF;
		}
		int p = u32(a[1]);
		m.writeByte(p, (byte) f.filetype);
		m.writeShort(p + 2, (short) (f.nonblocking ? 4 : 0)); // FDFLAG_NONBLOCK
		m.writeLong(p + 8, -1L);
		m.writeLong(p + 16, -1L);
		return 0;
	}

	private int fdFilestatGet(Memory m, long[] a) {
		HostFd f = fds.get(u32(a[0]));
		if (f == null) {
			return Errno.EBADF;
		}
		int p = u32(a[1]);
		m.fill((byte) 0, p, p + 64);
		m.writeByte(p + 16, (byte) f.filetype);
		m.writeLong(p + 24, 1);
		return 0;
	}

	// poll_oneoff ----------------------------------------------------------

	private static final int SUB_SIZE = 48, EVENT_SIZE = 32;
	private static final int EVENTTYPE_CLOCK = 0, EVENTTYPE_FD_READ = 1, EVENTTYPE_FD_WRITE = 2;

	private record Sub(long userdata, int type, int fd) {}

	private int pollOneoff(Memory m, long[] a) throws IOException {
		int in = u32(a[0]), out = u32(a[1]), nsubs = u32(a[2]), neventsPtr = u32(a[3]);
		if (nsubs <= 0) {
			return Errno.EINVAL;
		}
		Sub[] subs = new Sub[nsubs];
		long deadline = Long.MAX_VALUE;
		long now = System.nanoTime();
		for (int i = 0; i < nsubs; i++) {
			int s = in + i * SUB_SIZE;
			long userdata = m.readLong(s);
			int type = m.read(s + 8) & 0xFF;
			int fd = -1;
			if (type == EVENTTYPE_CLOCK) {
				int clock = m.readInt(s + 16);
				long timeout = m.readLong(s + 24);
				boolean abs = (m.readShort(s + 40) & 1) != 0;
				long rel = timeout;
				if (abs) {
					rel = timeout - (clock == 0 ? realtimeNanos() : monotonicNanos());
				}
				long d = rel <= 0 ? now : (rel > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + rel);
				deadline = Math.min(deadline, d);
			} else {
				fd = m.readInt(s + 16);
			}
			subs[i] = new Sub(userdata, type, fd);
		}

		List<int[]> events = new ArrayList<>(); // {sub index, error}
		while (true) {
			checkKilled();
			events.clear();
			Map<SelectableChannel, Integer> interest = new IdentityHashMap<>();
			for (int i = 0; i < nsubs; i++) {
				Sub s = subs[i];
				if (s.type == EVENTTYPE_CLOCK) {
					continue;
				}
				HostFd f = fds.get(s.fd);
				if (f == null) {
					events.add(new int[] {i, Errno.EBADF});
					continue;
				}
				SelectableChannel ch = f.channel();
				if (ch == null || !ch.isOpen()) {
					boolean ready = s.type == EVENTTYPE_FD_READ ? f.readReady() : f.writeReady();
					if (ready || ch != null) {
						events.add(new int[] {i, 0});
					}
				} else {
					int ops = s.type == EVENTTYPE_FD_READ ? f.readOps() : f.writeOps();
					interest.merge(ch, ops, (x, y) -> x | y);
				}
			}
			for (SelectionKey k : selector.keys()) {
				if (k.isValid() && !interest.containsKey(k.channel())) {
					k.interestOps(0);
				}
			}
			for (Map.Entry<SelectableChannel, Integer> e : interest.entrySet()) {
				SelectionKey k = e.getKey().keyFor(selector);
				if (k == null || !k.isValid()) {
					if (k != null) {
						selector.selectNow(); // flush the cancelled key
					}
					e.getKey().register(selector, e.getValue());
				} else {
					k.interestOps(e.getValue());
				}
			}

			if (!events.isEmpty()) {
				selector.selectNow();
			} else {
				long wait = deadline == Long.MAX_VALUE ? 0 : deadline - System.nanoTime();
				if (deadline != Long.MAX_VALUE && wait <= 0) {
					selector.selectNow();
				} else {
					selector.select(wait == 0 ? 0 : Math.max(1, (wait + 999_999) / 1_000_000));
				}
			}
			for (SelectionKey k : selector.selectedKeys()) {
				int ready = k.isValid() ? k.readyOps() : (SelectionKey.OP_READ | SelectionKey.OP_WRITE);
				for (int i = 0; i < nsubs; i++) {
					Sub s = subs[i];
					if (s.type == EVENTTYPE_CLOCK) {
						continue;
					}
					HostFd f = fds.get(s.fd);
					if (f == null || f.channel() != k.channel()) {
						continue;
					}
					int ops = s.type == EVENTTYPE_FD_READ ? f.readOps() : f.writeOps();
					if ((ready & ops) != 0) {
						events.add(new int[] {i, 0});
					}
				}
			}
			selector.selectedKeys().clear();
			if (events.isEmpty() && System.nanoTime() - deadline >= 0 && deadline != Long.MAX_VALUE) {
				for (int i = 0; i < nsubs; i++) {
					if (subs[i].type == EVENTTYPE_CLOCK) {
						events.add(new int[] {i, 0});
					}
				}
			}
			if (!events.isEmpty()) {
				break;
			}
		}

		int count = 0;
		boolean[] seen = new boolean[nsubs];
		for (int[] ev : events) {
			if (seen[ev[0]]) {
				continue;
			}
			seen[ev[0]] = true;
			Sub s = subs[ev[0]];
			int e = out + count * EVENT_SIZE;
			m.fill((byte) 0, e, e + EVENT_SIZE);
			m.writeLong(e, s.userdata);
			m.writeShort(e + 8, (short) ev[1]);
			m.writeByte(e + 10, (byte) s.type);
			count++;
		}
		m.writeI32(neventsPtr, count);
		return 0;
	}

	// ---------------------------------------------------------------------
	// hearthgate: sockets

	private static String str(Memory m, long ptr, long len) {
		return len == 0 ? "" : m.readString(u32(ptr), u32(len));
	}

	/** Splits "host:port", accepting "[v6]:port" and an empty host. */
	private static String[] splitHostPort(String s) {
		int colon = s.lastIndexOf(':');
		if (colon < 0) {
			throw new IllegalArgumentException("missing port in " + s);
		}
		String host = s.substring(0, colon);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		return new String[] {host, s.substring(colon + 1)};
	}

	private static int port(String p) {
		int port = Integer.parseInt(p);
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("bad port " + p);
		}
		return port;
	}

	private static boolean familyMatches(String network, InetAddress a) {
		return switch (network) {
			case "tcp4", "udp4" -> a instanceof Inet4Address;
			case "tcp6", "udp6" -> a instanceof Inet6Address;
			default -> true;
		};
	}

	private int tcpConnect(Memory m, long[] a) {
		String network = str(m, a[0], a[1]);
		String[] hp;
		int port;
		try {
			hp = splitHostPort(str(m, a[2], a[3]));
			port = port(hp[1]);
		} catch (IllegalArgumentException e) {
			return Errno.EINVAL;
		}
		if (!network.startsWith("tcp")) {
			return Errno.EAFNOSUPPORT;
		}
		HostFd.Tcp fd = new HostFd.Tcp(selector::wakeup);
		int n = allocate(fd);
		String host = hp[0];
		CONNECT_WORKERS.execute(() -> connectWorker(fd, network, host, port));
		m.writeI32(u32(a[4]), n);
		return 0;
	}

	private void connectWorker(HostFd.Tcp fd, String network, String host, int port) {
		int errno = Errno.EHOSTUNREACH;
		try {
			InetAddress[] all = InetAddress.getAllByName(host.isEmpty() ? "localhost" : host);
			boolean anyAllowed = false;
			for (InetAddress addr : all) {
				if (!familyMatches(network, addr)) {
					continue;
				}
				if (!policy.allowConnect(addr, port)) {
					errno = anyAllowed ? errno : Errno.EACCES;
					continue;
				}
				anyAllowed = true;
				if (fd.state() != HostFd.Tcp.CONNECTING) {
					return;
				}
				SocketChannel ch = SocketChannel.open();
				try {
					ch.socket().connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
					ch.configureBlocking(false);
					ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
					if (!fd.connected(ch)) {
						ch.close();
					}
					return;
				} catch (IOException e) {
					ch.close();
					errno = Errno.of(e);
				}
			}
		} catch (IOException | RuntimeException e) {
			errno = Errno.of(e);
		}
		fd.failed(errno);
	}

	private int tcpConnectStatus(Memory m, long[] a) {
		if (!(fds.get(u32(a[0])) instanceof HostFd.Tcp t)) {
			return Errno.EBADF;
		}
		return switch (t.state()) {
			case HostFd.Tcp.CONNECTING -> Errno.EAGAIN;
			case HostFd.Tcp.CONNECTED -> 0;
			case HostFd.Tcp.FAILED -> t.error();
			default -> Errno.EBADF;
		};
	}

	private int tcpListen(Memory m, long[] a) throws IOException {
		String[] hp;
		InetAddress addr;
		int port;
		try {
			hp = splitHostPort(str(m, a[0], a[1]));
			port = port(hp[1]);
			addr = InetAddress.getByName(hp[0].isEmpty() ? "127.0.0.1" : hp[0]);
		} catch (IllegalArgumentException | java.net.UnknownHostException e) {
			return Errno.EINVAL;
		}
		if (!policy.allowListen(addr)) {
			return Errno.EACCES;
		}
		ServerSocketChannel ch = ServerSocketChannel.open();
		try {
			ch.bind(new InetSocketAddress(addr, port), 64);
			ch.configureBlocking(false);
		} catch (IOException e) {
			ch.close();
			throw e;
		}
		m.writeI32(u32(a[2]), allocate(new HostFd.Listener(ch)));
		return 0;
	}

	private int tcpAccept(Memory m, long[] a) throws IOException {
		if (!(fds.get(u32(a[0])) instanceof HostFd.Listener l)) {
			return Errno.EBADF;
		}
		SocketChannel ch = l.channel.accept();
		if (ch == null) {
			return Errno.EAGAIN;
		}
		ch.configureBlocking(false);
		ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
		m.writeI32(u32(a[1]), allocate(new HostFd.Tcp(ch, selector::wakeup)));
		return 0;
	}

	private SocketChannel connected(int fd) throws IOException {
		if (!(fds.get(fd) instanceof HostFd.Tcp t)) {
			throw new java.nio.channels.ClosedChannelException();
		}
		return switch (t.state()) {
			case HostFd.Tcp.CONNECTED -> t.socket();
			case HostFd.Tcp.CONNECTING -> null;
			case HostFd.Tcp.FAILED -> throw new java.net.SocketException("not connected");
			default -> throw new java.nio.channels.ClosedChannelException();
		};
	}

	private int sockRecv(Memory m, long[] a) throws IOException {
		SocketChannel ch = connected(u32(a[0]));
		if (ch == null) {
			return Errno.EAGAIN;
		}
		io.clear().limit(Math.min(u32(a[2]), IO_CHUNK));
		int n = ch.read(io);
		if (n == 0) {
			return Errno.EAGAIN;
		}
		if (n < 0) {
			n = 0; // EOF
		} else {
			m.write(u32(a[1]), io.array(), 0, n);
		}
		m.writeI32(u32(a[3]), n);
		return 0;
	}

	private int sockSend(Memory m, long[] a) throws IOException {
		SocketChannel ch = connected(u32(a[0]));
		if (ch == null) {
			return Errno.EAGAIN;
		}
		int len = Math.min(u32(a[2]), IO_CHUNK);
		int n = ch.write(ByteBuffer.wrap(m.readBytes(u32(a[1]), len)));
		if (n == 0 && len > 0) {
			return Errno.EAGAIN;
		}
		m.writeI32(u32(a[3]), n);
		return 0;
	}

	private int sockShutdownWrite(Memory m, long[] a) throws IOException {
		SocketChannel ch = connected(u32(a[0]));
		if (ch == null) {
			return Errno.ENOTCONN;
		}
		ch.shutdownOutput();
		return 0;
	}

	private int udpBind(Memory m, long[] a) throws IOException {
		String network = str(m, a[0], a[1]);
		String[] hp;
		int port;
		try {
			hp = splitHostPort(str(m, a[2], a[3]));
			port = port(hp[1]);
		} catch (IllegalArgumentException e) {
			return Errno.EINVAL;
		}
		ProtocolFamily family = switch (network) {
			case "udp4" -> StandardProtocolFamily.INET;
			case "udp6" -> StandardProtocolFamily.INET6;
			case "udp" -> null;
			default -> throw new IllegalArgumentException(network);
		};
		InetAddress bindAddr;
		if (hp[0].isEmpty()) {
			bindAddr = family == StandardProtocolFamily.INET ? InetAddress.getByName("0.0.0.0")
					: family == StandardProtocolFamily.INET6 ? InetAddress.getByName("::") : null;
		} else {
			bindAddr = InetAddress.getByName(hp[0]);
		}
		DatagramChannel ch = family == null ? DatagramChannel.open() : DatagramChannel.open(family);
		try {
			try {
				ch.setOption(StandardSocketOptions.SO_RCVBUF, UDP_BUFFER);
				ch.setOption(StandardSocketOptions.SO_SNDBUF, UDP_BUFFER);
			} catch (IOException | UnsupportedOperationException ignored) {
			}
			ch.configureBlocking(false);
			ch.bind(bindAddr == null ? new InetSocketAddress(port) : new InetSocketAddress(bindAddr, port));
		} catch (IOException e) {
			ch.close();
			throw e;
		}
		m.writeI32(u32(a[4]), allocate(new HostFd.Udp(ch)));
		return 0;
	}

	private int udpRecvFrom(Memory m, long[] a) throws IOException {
		if (!(fds.get(u32(a[0])) instanceof HostFd.Udp u)) {
			return Errno.EBADF;
		}
		io.clear().limit(Math.min(u32(a[2]), IO_CHUNK));
		SocketAddress from = u.channel.receive(io);
		if (from == null) {
			return Errno.EAGAIN;
		}
		int n = io.position();
		m.write(u32(a[1]), io.array(), 0, n);
		m.writeI32(u32(a[3]), n);
		writeAddr(m, u32(a[4]), (InetSocketAddress) from);
		return 0;
	}

	private int udpSendTo(Memory m, long[] a) throws IOException {
		if (!(fds.get(u32(a[0])) instanceof HostFd.Udp u)) {
			return Errno.EBADF;
		}
		InetSocketAddress to = readAddr(m, u32(a[3]));
		if (to == null) {
			return Errno.EINVAL;
		}
		int len = u32(a[2]);
		ByteBuffer data = ByteBuffer.wrap(m.readBytes(u32(a[1]), len));
		int n = u.channel.send(data, to);
		if (n == 0 && len > 0) {
			return Errno.EAGAIN;
		}
		return 0;
	}

	private int sockAddr(Memory m, long[] a) throws IOException {
		HostFd f = fds.get(u32(a[0]));
		boolean remote = a[1] != 0;
		SocketAddress sa = switch (f) {
			case HostFd.Tcp t when t.socket() != null ->
					remote ? t.socket().getRemoteAddress() : t.socket().getLocalAddress();
			case HostFd.Listener l -> remote ? null : l.channel.getLocalAddress();
			case HostFd.Udp u -> remote ? null : u.channel.getLocalAddress();
			case null, default -> null;
		};
		if (!(sa instanceof InetSocketAddress isa)) {
			return f == null ? Errno.EBADF : Errno.ENOTCONN;
		}
		writeAddr(m, u32(a[2]), isa);
		return 0;
	}

	/** Writes the 20-byte address layout shared with hostnet_wasip1.go. */
	private static void writeAddr(Memory m, int p, InetSocketAddress sa) {
		m.fill((byte) 0, p, p + 20);
		byte[] ip = sa.getAddress().getAddress();
		m.writeByte(p, (byte) (ip.length == 4 ? 4 : 6));
		m.writeByte(p + 2, (byte) (sa.getPort() >> 8));
		m.writeByte(p + 3, (byte) sa.getPort());
		m.write(p + 4, ip);
	}

	private static InetSocketAddress readAddr(Memory m, int p) throws java.net.UnknownHostException {
		int family = m.read(p) & 0xFF;
		int port = ((m.read(p + 2) & 0xFF) << 8) | (m.read(p + 3) & 0xFF);
		byte[] ip = switch (family) {
			case 4 -> m.readBytes(p + 4, 4);
			case 6 -> m.readBytes(p + 4, 16);
			default -> null;
		};
		return ip == null ? null : new InetSocketAddress(InetAddress.getByAddress(ip), port);
	}

	private int interfaces(Memory m, long[] a) throws IOException {
		StringBuilder json = new StringBuilder("[");
		for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (json.length() > 1) {
				json.append(',');
			}
			json.append("{\"name\":").append(quote(ni.getName()))
					.append(",\"index\":").append(ni.getIndex())
					.append(",\"mtu\":").append(Math.max(ni.getMTU(), 0))
					.append(",\"up\":").append(ni.isUp())
					.append(",\"loopback\":").append(ni.isLoopback())
					.append(",\"p2p\":").append(ni.isPointToPoint())
					.append(",\"multicast\":").append(ni.supportsMulticast())
					.append(",\"addrs\":[");
			boolean first = true;
			for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
				if (ia.getAddress() == null) {
					continue;
				}
				String host = ia.getAddress().getHostAddress();
				int pct = host.indexOf('%');
				if (pct >= 0) {
					host = host.substring(0, pct);
				}
				if (!first) {
					json.append(',');
				}
				first = false;
				json.append(quote(host + "/" + ia.getNetworkPrefixLength()));
			}
			json.append("]}");
		}
		json.append(']');
		byte[] b = json.toString().getBytes(StandardCharsets.UTF_8);
		if (b.length > u32(a[1])) {
			return Errno.EMSGSIZE;
		}
		m.write(u32(a[0]), b);
		m.writeI32(u32(a[2]), b.length);
		return 0;
	}

	private static String quote(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') {
				b.append('\\').append(c);
			} else if (c < 0x20) {
				b.append(String.format("\\u%04x", (int) c));
			} else {
				b.append(c);
			}
		}
		return b.append('"').toString();
	}

	// ---------------------------------------------------------------------
	// hearthgate: crypto

	private int aead(Memory m, long[] a, boolean seal) throws GeneralSecurityException {
		byte[] key = m.readBytes(u32(a[0]), 32);
		byte[] nonce = m.readBytes(u32(a[1]), 12);
		int srcLen = u32(a[3]);
		if (!seal && srcLen < 16) {
			return Errno.EBADMSG;
		}
		byte[] src = m.readBytes(u32(a[2]), srcLen);
		int adLen = u32(a[5]);
		byte[] ad = adLen > 0 ? m.readBytes(u32(a[4]), adLen) : null;
		SecretKeySpec k = new SecretKeySpec(key, "ChaCha20");
		IvParameterSpec iv = new IvParameterSpec(nonce);
		Cipher c = seal ? sealer : opener;
		try {
			if (c == null) {
				throw new java.security.InvalidKeyException();
			}
			c.init(seal ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, k, iv);
		} catch (java.security.InvalidKeyException | java.security.InvalidAlgorithmParameterException e) {
			// Java refuses to re-encrypt with the key and nonce of the
			// previous init on the same object; a fresh object accepts it.
			c = Cipher.getInstance("ChaCha20-Poly1305");
			c.init(seal ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, k, iv);
			if (seal) {
				sealer = c;
			} else {
				opener = c;
			}
		}
		if (ad != null) {
			c.updateAAD(ad);
		}
		byte[] out;
		try {
			out = c.doFinal(src);
		} catch (AEADBadTagException e) {
			return Errno.EBADMSG;
		}
		m.write(u32(a[6]), out);
		return 0;
	}
}
