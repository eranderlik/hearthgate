package dev.hearthgate.core;

import com.dylibso.chicory.runtime.Instance;
import dev.hearthgate.core.host.HelperHost;
import dev.hearthgate.core.host.NetworkPolicy;
import dev.hearthgate.core.wasm.HearthgateHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One running instance of the WebAssembly networking helper.
 *
 * <p>Each session gets its own WebAssembly instance (and so its own Go
 * runtime) on a dedicated thread. The helper reports progress as JSON lines,
 * delivered to the {@link Listener} on that thread; listeners must not block.
 * {@link #stop()} asks the helper to shut down cleanly; {@link #kill()} stops
 * it at its next host call.
 */
public final class HelperSession implements Helper {
	private static final AtomicInteger IDS = new AtomicInteger();
	/** Go functions are ordinary JVM calls, so deep call chains need a deep stack. */
	private static final long THREAD_STACK = 64L << 20;

	public interface Listener {
		/** A JSON event line from the helper's stdout. */
		void onEvent(String line);

		/** A diagnostic line from the helper's stderr. */
		default void onLog(String line) {}

		/** The helper exited. {@code error} is null for a normal exit. */
		default void onExit(int code, Throwable error) {}
	}

	private final HelperHost host;
	private final Thread thread;
	private final CompletableFuture<Integer> exit = new CompletableFuture<>();
	private volatile Instance instance;

	private HelperSession(List<String> args, NetworkPolicy policy, Listener listener) throws IOException {
		List<String> argv = new ArrayList<>();
		argv.add("hearthgate-helper");
		argv.addAll(args);
		// A listener failure must not surface in the guest as an I/O error.
		host = new HelperHost(argv, guestEnv(), policy, line -> safely(() -> listener.onEvent(line)),
				line -> safely(() -> listener.onLog(line)));
		thread = new Thread(null, () -> run(listener), "hearthgate-helper-" + IDS.incrementAndGet(), THREAD_STACK);
		thread.setDaemon(true);
	}

	/**
	 * The helper's environment: Go runtime settings from the
	 * {@code hearthgate.helperEnv} system property (comma-separated NAME=value),
	 * defaulting to {@link #DEFAULT_ENV}.
	 */
	private static List<String> guestEnv() {
		String p = System.getProperty("hearthgate.helperEnv");
		if (p == null) {
			return DEFAULT_ENV;
		}
		return java.util.Arrays.stream(p.split(",")).map(String::trim).filter(s -> s.contains("=")).toList();
	}

	static final List<String> DEFAULT_ENV = List.of();

	private static void safely(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("hearthgate").error("Hearthgate helper listener failed", e);
		}
	}

	/** Starts the helper with the given arguments (after argv[0]). */
	public static HelperSession start(List<String> args, NetworkPolicy policy, Listener listener) throws IOException {
		JitPolicy.apply();
		HelperSession s = new HelperSession(args, policy, listener);
		s.thread.start();
		return s;
	}

	/**
	 * Loads and links the compiled helper module without running it, so the
	 * first session starts quickly. Safe to call from any thread, repeatedly.
	 */
	public static void preload() {
		JitPolicy.apply();
		HearthgateHelper.load();
	}

	private void run(Listener listener) {
		int code = -1;
		Throwable error = null;
		try {
			Instance instance = Instance.builder(HearthgateHelper.load())
					.withImportValues(host.imports())
					.withMachineFactory(HearthgateHelper::create)
					.withStart(false)
					.build();
			this.instance = instance;
			instance.export("_start").apply();
			code = 0;
		} catch (HelperHost.Exit e) {
			code = e.code;
		} catch (HelperHost.Killed e) {
			error = e;
		} catch (Throwable t) {
			error = t;
		} finally {
			host.close();
		}
		exit.complete(code);
		listener.onExit(code, error);
	}

	@Override
	public void drain() {
		host.feedStdin("drain\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	@Override
	public void dumpStacks() {
		host.feedStdin("stacks\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	/** Asks the helper to shut down by closing its stdin. */
	@Override
	public void stop() {
		host.closeStdin();
	}

	/** Stops the helper at its next host call, without cleanup on its side. */
	@Override
	public void kill() {
		host.kill();
	}

	/** Current size of the helper's WebAssembly linear memory, for diagnostics. */
	@Override
	public long memoryBytes() {
		Instance i = instance;
		return i == null || !isAlive() ? 0 : (long) i.memory().pages() * 65536;
	}

	@Override
	public boolean isAlive() {
		return !exit.isDone();
	}

	/** Completes with the exit code when the helper stops. */
	@Override
	public CompletableFuture<Integer> exitFuture() {
		return exit;
	}

	/** Stops the helper, killing it if it hasn't exited within a few seconds. */
	@Override
	public void close() {
		stop();
		exit.copy().orTimeout(5, TimeUnit.SECONDS).exceptionally(t -> {
			kill();
			return -1;
		});
	}
}
