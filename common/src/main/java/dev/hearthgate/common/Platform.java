package dev.hearthgate.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What the mod loader provides to the loader-independent code. Each loader's
 * entrypoint calls {@link #set} once, before any other Hearthgate code runs.
 *
 * <p>The client-only part is {@code dev.hearthgate.common.client.ClientPlatform}.
 */
public interface Platform {
	/** The loader's config directory (holds {@code hearthgate-server.json}). */
	Path configDir();

	/** The game directory (holds {@code mods/}, the world, {@code .hearthgate/}). */
	Path gameDir();

	/** True when running from an IDE or Gradle dev run rather than a production install. */
	boolean isDevelopmentEnvironment();

	/** True if the mod with this id is loaded. */
	boolean isModLoaded(String modId);

	/**
	 * Finds {@code pathInMod} inside the jar or directory of the loaded mod
	 * {@code modId}, or empty if that mod isn't loaded or lacks the path.
	 * Used to read the native helper library out of its add-on mod.
	 */
	Optional<Path> findModPath(String modId, String pathInMod) throws IOException;

	// ---- registration ----

	static void set(Platform platform) {
		Holder.platform = platform;
	}

	/** The registered platform; fails if no loader entrypoint has called {@link #set}. */
	static Platform get() {
		Platform p = Holder.platform;
		if (p == null) {
			throw new IllegalStateException("Hearthgate: no Platform registered by the loader entrypoint");
		}
		return p;
	}

	final class Holder {
		private static volatile Platform platform;

		private Holder() {}
	}
}
