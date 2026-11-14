package dev.hearthgate.core;

import java.util.concurrent.CompletableFuture;

/**
 * A running helper session, on either backend (see {@link HelperBackend}).
 * Its output is delivered to the {@link HelperSession.Listener} it was
 * started with.
 */
public interface Helper extends AutoCloseable {
	/** Asks the helper to shut down cleanly; it then emits {@code stopped} and exits. */
	void stop();

	/** Stops the helper without waiting for it to clean up. */
	void kill();

	boolean isAlive();

	/** Completes with the exit code when the helper stops. */
	CompletableFuture<Integer> exitFuture();

	/** Asks the helper to log every goroutine's stack, for diagnosing stalls. */
	default void dumpStacks() {}

	/** Memory the helper uses, for diagnostics; 0 if unknown. */
	long memoryBytes();

	/** Stops the helper, killing it if it hasn't exited within a few seconds. */
	@Override
	void close();
}
