package dev.hearthgate.forge;

import dev.hearthgate.common.Platform;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** {@link Platform} backed by Minecraft Forge / FML. */
final class ForgePlatform implements Platform {
	static final ForgePlatform INSTANCE = new ForgePlatform();

	private ForgePlatform() {}

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
		return !FMLEnvironment.production;
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.isLoaded(modId);
	}

	@Override
	public Optional<Path> findModPath(String modId, String pathInMod) throws IOException {
		IModFileInfo info = ModList.getModFileById(modId);
		if (info == null) {
			return Optional.empty();
		}
		Path p = info.getFile().findResource(pathInMod.split("/"));
		return p != null && Files.exists(p) ? Optional.of(p) : Optional.empty();
	}
}
