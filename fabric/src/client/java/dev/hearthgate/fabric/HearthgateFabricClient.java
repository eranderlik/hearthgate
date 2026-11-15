package dev.hearthgate.fabric;

import dev.hearthgate.common.Platform;
import dev.hearthgate.common.client.HearthgateClientCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

/** Fabric glue for the client side; the logic is in {@link HearthgateClientCommon}. */
public final class HearthgateFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Platform.set(FabricPlatform.INSTANCE);
		HearthgateClientCommon.init((screen, widget) -> Screens.getWidgets(screen).add(widget));
		ClientLifecycleEvents.CLIENT_STARTED.register(HearthgateClientCommon::onClientStarted);
		ClientLifecycleEvents.CLIENT_STOPPING.register(HearthgateClientCommon::onClientStopping);
		ClientTickEvents.END_CLIENT_TICK.register(HearthgateClientCommon::onClientTick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				HearthgateClientCommon.onPlayJoin(client, handler));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				HearthgateClientCommon.onPlayDisconnect(client));
		ScreenEvents.AFTER_INIT.register(HearthgateClientCommon::onScreenInit);
	}
}
