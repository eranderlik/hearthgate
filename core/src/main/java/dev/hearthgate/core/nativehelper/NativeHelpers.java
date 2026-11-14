package dev.hearthgate.core.nativehelper;

import com.google.gson.Gson;
import dev.hearthgate.core.Helper;
import dev.hearthgate.core.HelperBackend;
import dev.hearthgate.core.HelperSession;
import dev.hearthgate.core.host.NetworkPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the helper as native Go, loaded into the JVM from a shared library
 * (helper/cmd/libhearthgate) through the Foreign Function &amp; Memory API.
 *
 * <p>This is an optional accelerator for dedicated servers: tunnelled traffic
 * is many times faster than on the WebAssembly helper, at the cost of native
 * code in the server process. Unlike WebAssembly, native code opens sockets
 * itself, so {@link NetworkPolicy} is not enforced; the helper's own
 * validation (a numeric loopback/LAN target, Tailcat's relays) still applies.
 *
 * <p>A process can hold only one Go runtime, so the library is loaded once
 * and all sessions share it, each as its own set of goroutines.
 */
public final class NativeHelpers implements HelperBackend {
	static final int ABI_VERSION = 1;
	private static final int RECORD_CAPACITY = 16 << 10;
	private static final int POLL_MS = 1000;
	private static final AtomicInteger IDS = new AtomicInteger();
	private static NativeHelpers loaded;

	private final String platform;
	private final MethodHandle start;
	private final MethodHandle next;
	private final MethodHandle stop;
	private final MethodHandle heapBytes;

	private NativeHelpers(String platform, SymbolLookup lib) {
		this.platform = platform;
		Linker linker = Linker.nativeLinker();
		start = linker.downcallHandle(find(lib, "hg_start"),
				FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
		next = linker.downcallHandle(find(lib, "hg_next"), FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		stop = linker.downcallHandle(find(lib, "hg_stop"), FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));
		heapBytes = linker.downcallHandle(find(lib, "hg_heap_bytes"), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
	}

	/**
	 * Loads the library at {@code file}, built for {@code platform}, and checks
	 * that it was built from the same helper sources as this mod.
	 *
	 * @throws IOException if it can't be loaded or doesn't match
	 */
	public static synchronized NativeHelpers load(Path file, String platform) throws IOException {
		if (loaded != null) {
			return loaded;
		}
		SymbolLookup lib;
		try {
			lib = SymbolLookup.libraryLookup(file, Arena.global());
		} catch (IllegalArgumentException e) {
			throw new IOException("cannot load " + file.getFileName() + ": " + e.getMessage(), e);
		}
		Linker linker = Linker.nativeLinker();
		try {
			int abi = (int) linker.downcallHandle(find(lib, "hg_abi_version"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact();
			if (abi != ABI_VERSION) {
				throw new IOException("library ABI " + abi + ", expected " + ABI_VERSION);
			}
			MemorySegment id = (MemorySegment) linker.downcallHandle(find(lib, "hg_build_id"),
					FunctionDescriptor.of(ValueLayout.ADDRESS)).invokeExact();
			String buildId = id.reinterpret(256).getString(0);
			String expected = expectedBuildId();
			if (!buildId.equals(expected)) {
				throw new IOException("the native add-on is for a different version of Hearthgate "
						+ "(helper build " + abbrev(buildId) + ", this mod needs " + abbrev(expected) + ")");
			}
		} catch (IOException | RuntimeException | Error e) {
			throw e instanceof IOException io ? io : new IOException(e.toString(), e);
		} catch (Throwable t) {
			throw new IOException(t.toString(), t);
		}
		loaded = new NativeHelpers(platform, lib);
		return loaded;
	}

	/** The build ID of the helper sources compiled into this mod. */
	public static String expectedBuildId() throws IOException {
		try (InputStream in = HelperSession.class.getResourceAsStream("helper-build-id")) {
			if (in == null) {
				throw new IOException("helper-build-id resource missing");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
	}

	/**
	 * Whether the JVM lets this code call native functions without printing a
	 * warning (it does when started with {@code --enable-native-access}).
	 */
	public static boolean nativeAccessEnabled() {
		return NativeHelpers.class.getModule().isNativeAccessEnabled();
	}

	/**
	 * Copies the library in {@code source} (typically inside an add-on jar) to
	 * a file under {@code cacheDir}, named after its SHA-256 so each version is
	 * written once and never modified while loaded. Returns that file.
	 */
	public static Path extract(Path source, Path cacheDir) throws IOException {
		Files.createDirectories(cacheDir);
		restrict(cacheDir);
		Path tmp = Files.createTempFile(cacheDir, "extract-", ".tmp");
		try {
			String sha;
			try (InputStream in = new DigestInputStream(Files.newInputStream(source), sha256());
					OutputStream out = Files.newOutputStream(tmp)) {
				in.transferTo(out);
				sha = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
			}
			Path dir = cacheDir.resolve(sha.substring(0, 32));
			Path target = dir.resolve(source.getFileName().toString());
			if (Files.isRegularFile(target) && sha.equals(sha256(target))) {
				return target;
			}
			Files.createDirectories(dir);
			try {
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!sha.equals(sha256(target))) {
				throw new IOException("extracted library does not match its source");
			}
			deleteOtherVersions(cacheDir, dir);
			return target;
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** Removes libraries left by earlier versions; best effort (Windows locks loaded ones). */
	private static void deleteOtherVersions(Path cacheDir, Path keep) {
		try (var dirs = Files.list(cacheDir)) {
			for (Path d : (Iterable<Path>) dirs::iterator) {
				if (Files.isDirectory(d) && !d.equals(keep) && d.getFileName().toString().matches("[0-9a-f]{32}")) {
					try (var files = Files.list(d)) {
						for (Path f : (Iterable<Path>) files::iterator) {
							Files.deleteIfExists(f);
						}
					}
					Files.deleteIfExists(d);
				}
			}
		} catch (IOException ignored) {
			// Still in use, or not ours to delete.
		}
	}

	private static void restrict(Path dir) {
		try {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Not POSIX (Windows): the game directory's own ACLs apply.
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String sha256(Path f) throws IOException {
		try (InputStream in = Files.newInputStream(f)) {
			MessageDigest d = sha256();
			byte[] buf = new byte[1 << 16];
			for (int n; (n = in.read(buf)) > 0; ) {
				d.update(buf, 0, n);
			}
			return HexFormat.of().formatHex(d.digest());
		}
	}

	private static String abbrev(String id) {
		return id.length() > 12 ? id.substring(0, 12) : id;
	}

	private static MemorySegment find(SymbolLookup lib, String name) {
		return lib.find(name).orElseThrow(() -> new IllegalStateException("library lacks " + name));
	}

	@Override
	public String description() {
		return "native (" + platform + ")";
	}

	@Override
	public Helper start(List<String> args, NetworkPolicy policy, HelperSession.Listener listener) throws IOException {
		long handle;
		try (Arena a = Arena.ofConfined()) {
			handle = (long) start.invokeExact(a.allocateFrom(new Gson().toJson(args)));
		} catch (Throwable t) {
			throw new IOException("native helper failed to start: " + t, t);
		}
		if (handle <= 0) {
			throw new IOException("native helper rejected its arguments");
		}
		Session s = new Session(handle, listener);
		s.reader.start();
		return s;
	}

	private final class Session implements Helper {
		final long handle;
		final HelperSession.Listener listener;
		final Thread reader;
		final CompletableFuture<Integer> exit = new CompletableFuture<>();
		/** Set by kill(): stop delivering output; the reader still drains the session. */
		final AtomicBoolean abandoned = new AtomicBoolean();

		Session(long handle, HelperSession.Listener listener) {
			this.handle = handle;
			this.listener = listener;
			reader = new Thread(this::read, "hearthgate-native-" + IDS.incrementAndGet());
			reader.setDaemon(true);
		}

		private void read() {
			Throwable error = null;
			try (Arena a = Arena.ofConfined()) {
				MemorySegment buf = a.allocate(RECORD_CAPACITY);
				while (true) {
					int n = (int) next.invokeExact(handle, buf, RECORD_CAPACITY, POLL_MS);
					if (n == 0) {
						continue;
					}
					if (n < 0) {
						break; // unknown handle; cannot happen while this thread reads it
					}
					byte tag = buf.get(ValueLayout.JAVA_BYTE, 0);
					if (tag == 'X') {
						break;
					}
					if (abandoned.get()) {
						continue;
					}
					String line = new String(buf.asSlice(1, n - 1).toArray(ValueLayout.JAVA_BYTE),
							StandardCharsets.UTF_8);
					safely(() -> {
						if (tag == 'E') {
							listener.onEvent(line);
						} else {
							listener.onLog(line);
						}
					});
				}
			} catch (Throwable t) {
				error = t;
			}
			finish(0, error);
		}

		private void finish(int code, Throwable error) {
			if (exit.complete(code)) {
				safely(() -> listener.onExit(code, error));
			}
		}

		@Override
		public void stop() {
			try {
				stop.invokeExact(handle);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		/**
		 * Goroutines can't be killed from outside, so this stops the session,
		 * reports it as exited, and discards whatever it still emits.
		 */
		@Override
		public void kill() {
			stop();
			abandoned.set(true);
			finish(-1, null);
		}

		@Override
		public boolean isAlive() {
			return !exit.isDone();
		}

		@Override
		public CompletableFuture<Integer> exitFuture() {
			return exit;
		}

		@Override
		public long memoryBytes() {
			try {
				return (long) heapBytes.invokeExact(); // shared by all sessions
			} catch (Throwable t) {
				return 0;
			}
		}

		@Override
		public void close() {
			stop();
			exit.copy().orTimeout(5, TimeUnit.SECONDS).exceptionally(t -> {
				kill();
				return -1;
			});
		}
	}

	private static void safely(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("hearthgate").error("Hearthgate helper listener failed", e);
		}
	}
}
