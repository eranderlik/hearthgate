package dev.tailcatmc.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.network.chat.Component;

/** Small layout helpers for widget-only screens. */
final class Ui {
	private static final int MARGIN = 20;

	private Ui() {}

	static MultiLineTextWidget centered(Component text, Font font, int screenWidth, int y) {
		MultiLineTextWidget w = new MultiLineTextWidget(0, y, text, font)
				.setMaxWidth(screenWidth - 2 * MARGIN)
				.setCentered(true);
		w.setX((screenWidth - w.getWidth()) / 2);
		return w;
	}

	static void set(MultiLineTextWidget w, Component text, int screenWidth) {
		if (!w.getMessage().equals(text)) {
			w.setMessage(text);
			w.setX((screenWidth - w.getWidth()) / 2);
		}
	}

	static String shorten(String value, int max) {
		if (value.length() <= max) {
			return value;
		}
		int half = (max - 1) / 2;
		return value.substring(0, half) + "…" + value.substring(value.length() - half);
	}
}
