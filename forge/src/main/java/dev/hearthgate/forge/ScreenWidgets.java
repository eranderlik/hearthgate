package dev.hearthgate.forge;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;

/** Adds widgets to the screen whose {@link ScreenEvent.Init.Post} is being handled (client thread only). */
final class ScreenWidgets {
	static ScreenEvent.Init.Post current;

	private ScreenWidgets() {}

	static void add(Screen screen, AbstractWidget widget) {
		ScreenEvent.Init.Post e = current;
		if (e == null || e.getScreen() != screen) {
			throw new IllegalStateException("addScreenWidget called outside ScreenEvent.Init.Post");
		}
		e.addListener(widget);
	}
}
