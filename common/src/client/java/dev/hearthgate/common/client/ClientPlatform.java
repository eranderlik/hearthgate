package dev.hearthgate.common.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/**
 * What the mod loader provides to the client-only code, on top of
 * {@link dev.hearthgate.common.Platform}. Registered through
 * {@link HearthgateClientCommon#init}.
 */
public interface ClientPlatform {
	/**
	 * Adds a widget to an already-initialised screen so that it is rendered
	 * and receives input; called while handling
	 * {@link HearthgateClientCommon#onScreenInit}, i.e. from the loader's
	 * screen-init-post event. (Moving existing widgets needs no help: they
	 * are in {@link Screen#children()}.)
	 */
	void addScreenWidget(Screen screen, AbstractWidget widget);
}
