package dev.hearthgate.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge entry point. Forge's {@code @Mod} has no {@code dist} parameter, so
 * this class picks the side and delegates to a class per side (kept separate
 * so the dedicated server never loads client classes).
 */
@Mod("hearthgate")
public final class HearthgateForge {
	public HearthgateForge(FMLJavaModLoadingContext context) {
		// Hearthgate adds no network channels or registries, so it must not stop
		// an unmodded client joining a modded server, or the reverse: ignore
		// the mod's version on the other side (as Forge itself does).
		context.registerDisplayTest(IExtensionPoint.DisplayTest.IGNORE_ALL_VERSION);
		if (FMLEnvironment.dist == Dist.CLIENT) {
			HearthgateForgeClient.init(context);
		} else {
			// Only a dedicated server runs this branch (an integrated server
			// shares the client's dist), as Fabric's server entrypoint does.
			HearthgateForgeServer.init();
		}
	}
}
