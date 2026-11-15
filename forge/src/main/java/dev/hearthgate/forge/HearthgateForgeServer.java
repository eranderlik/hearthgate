package dev.hearthgate.forge;

import dev.hearthgate.common.Platform;
import dev.hearthgate.common.server.HearthgateServerCommon;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/** Forge glue for dedicated servers; the logic is in {@link HearthgateServerCommon}. */
final class HearthgateForgeServer {
	private HearthgateForgeServer() {}

	static void init() {
		Platform.set(ForgePlatform.INSTANCE);
		RegisterCommandsEvent.BUS.addListener(e -> HearthgateServerCommon.registerCommands(e.getDispatcher()));
		ServerStartedEvent.BUS.addListener(e -> HearthgateServerCommon.onServerStarted(e.getServer()));
		ServerStoppingEvent.BUS.addListener(e -> HearthgateServerCommon.onServerStopping(e.getServer()));
		HearthgateServerCommon.init();
	}
}
