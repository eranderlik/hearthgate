package dev.hearthgate.neoforge;

import dev.hearthgate.common.Platform;
import dev.hearthgate.common.server.HearthgateServerCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * NeoForge glue for dedicated servers; the logic is in {@link HearthgateServerCommon}.
 * Loaded only on the dedicated server, as Fabric's server entrypoint is, so an
 * integrated server never gets the command or a persistent host.
 */
@Mod(value = "hearthgate", dist = Dist.DEDICATED_SERVER)
public final class HearthgateNeoForgeServer {
	public HearthgateNeoForgeServer() {
		Platform.set(NeoForgePlatform.INSTANCE);
		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) ->
				HearthgateServerCommon.registerCommands(e.getDispatcher()));
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> HearthgateServerCommon.onServerStarted(e.getServer()));
		NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> HearthgateServerCommon.onServerStopping(e.getServer()));
		HearthgateServerCommon.init();
	}
}
