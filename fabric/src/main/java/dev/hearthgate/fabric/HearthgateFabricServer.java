package dev.hearthgate.fabric;

import dev.hearthgate.common.Platform;
import dev.hearthgate.common.server.HearthgateServerCommon;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Fabric glue for the dedicated-server side; the logic is in {@link HearthgateServerCommon}. */
public final class HearthgateFabricServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		Platform.set(FabricPlatform.INSTANCE);
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) ->
				HearthgateServerCommon.registerCommands(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(HearthgateServerCommon::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(HearthgateServerCommon::onServerStopping);
		HearthgateServerCommon.init();
	}
}
