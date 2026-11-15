package dev.hearthgate.fabric;

import dev.hearthgate.common.Platform;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Optional;

/** {@link Platform} backed by Fabric Loader. */
final class FabricPlatform implements Platform {
	static final FabricPlatform INSTANCE = new FabricPlatform();

	private FabricPlatform() {}

	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	@Override
	public Optional<Path> findModPath(String modId, String pathInMod) {
		return FabricLoader.getInstance().getModContainer(modId).flatMap(c -> c.findPath(pathInMod));
	}
}
