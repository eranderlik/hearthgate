package dev.hearthgate.forge;

import dev.hearthgate.common.Platform;
import dev.hearthgate.common.client.HearthgateClientCommon;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Forge glue for the client side; the logic is in {@link HearthgateClientCommon}. */
final class HearthgateForgeClient {
	private HearthgateForgeClient() {}

	static void init(FMLJavaModLoadingContext context) {
		Platform.set(ForgePlatform.INSTANCE);
		HearthgateClientCommon.init(ScreenWidgets::add);
		// The Minecraft instance is fully constructed by client setup.
		FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(e ->
				e.enqueueWork(() -> HearthgateClientCommon.onClientStarted(Minecraft.getInstance())));
		GameShuttingDownEvent.BUS.addListener(e ->
				HearthgateClientCommon.onClientStopping(Minecraft.getInstance()));
		TickEvent.ClientTickEvent.Post.BUS.addListener(e ->
				HearthgateClientCommon.onClientTick(Minecraft.getInstance()));
		ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(e ->
				HearthgateClientCommon.onPlayJoin(Minecraft.getInstance(), e.getPlayer().connection));
		ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(e ->
				HearthgateClientCommon.onPlayDisconnect(Minecraft.getInstance()));
		ScreenEvent.Init.Post.BUS.addListener((ScreenEvent.Init.Post e) -> {
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
