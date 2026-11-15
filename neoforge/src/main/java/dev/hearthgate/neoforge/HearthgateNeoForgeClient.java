package dev.hearthgate.neoforge;

import dev.hearthgate.common.Platform;
import dev.hearthgate.common.client.HearthgateClientCommon;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** NeoForge glue for the client side; the logic is in {@link HearthgateClientCommon}. */
@Mod(value = "hearthgate", dist = Dist.CLIENT)
public final class HearthgateNeoForgeClient {
	public HearthgateNeoForgeClient(IEventBus modBus) {
		Platform.set(NeoForgePlatform.INSTANCE);
		HearthgateClientCommon.init((screen, widget) -> {
			// Adding to the screen's children isn't enough to render: use the
			// screen's own method, which NeoForge exposes to ScreenEvent.Init.Post.
			ScreenWidgets.add(screen, widget);
		});
		// The Minecraft instance is fully constructed by client setup.
		modBus.addListener((FMLClientSetupEvent e) ->
				e.enqueueWork(() -> HearthgateClientCommon.onClientStarted(Minecraft.getInstance())));
		NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent e) ->
				HearthgateClientCommon.onClientStopping(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) ->
				HearthgateClientCommon.onClientTick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) ->
				HearthgateClientCommon.onPlayJoin(Minecraft.getInstance(), e.getPlayer().connection));
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) ->
				HearthgateClientCommon.onPlayDisconnect(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) -> {
			ScreenWidgets.current = e;
			try {
				HearthgateClientCommon.onScreenInit(Minecraft.getInstance(), e.getScreen(),
						e.getScreen().width, e.getScreen().height);
			} finally {
				ScreenWidgets.current = null;
			}
		});
	}
}
