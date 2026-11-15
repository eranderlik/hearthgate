package dev.hearthgate.neoforge;

import dev.hearthgate.common.Platform;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** {@link Platform} backed by NeoForge / FML. */
final class NeoForgePlatform implements Platform {
	static final NeoForgePlatform INSTANCE = new NeoForgePlatform();

	private NeoForgePlatform() {}

	@Override
	public Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public Path gameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.getCurrent().isProduction();
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	@Override
	public Optional<Path> findModPath(String modId, String pathInMod) throws IOException {
		IModFileInfo info = ModList.get().getModFileById(modId);
		if (info == null) {
			return Optional.empty();
		}
		Optional<URI> uri = info.getFile().getContents().findFile(pathInMod);
		if (uri.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(Path.of(uri.get()));
		} catch (FileSystemNotFoundException e) {
			// A jar: URI whose file system isn't open yet. Open it and keep it
			// open for the process's lifetime, as the returned path needs it.
			try {
				FileSystems.newFileSystem(uri.get(), Map.of());
			} catch (FileSystemAlreadyExistsException ignored) {
			}
			return Optional.of(Path.of(uri.get()));
		}
	}
}
