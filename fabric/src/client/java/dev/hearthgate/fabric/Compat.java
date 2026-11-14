package dev.tailcatmc.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The few Minecraft APIs this mod uses that differ between 26.1 and 26.3.
 *
 * <p>The mod is compiled against 26.1 and runs on 26.1 through 26.3. Since
 * these versions are unobfuscated, the alternatives can be bound by name once
 * at startup; if none matches, initialization fails loudly instead of the mod
 * misbehaving later.
 *
 * <ul>
 *   <li>Screens: {@code Minecraft.setScreen}/{@code screen} in 26.1.x;
 *       {@code Minecraft.gui.setScreen}/{@code screen()} from 26.2.</li>
 *   <li>Toasts: {@code Minecraft.getToastManager()} in 26.1.x;
 *       {@code Minecraft.gui.toastManager()} from 26.2.</li>
 *   <li>Open to LAN: {@code publishServer(GameType, boolean, int)} in 26.1.x;
 *       {@code publishServer(MultiplayerScope, int)} from 26.2.</li>
 * </ul>
 */
public final class Compat {
	private static final MethodHandle SET_SCREEN;
	private static final MethodHandle GET_SCREEN;
	private static final MethodHandle TOASTS;
	private static final MethodHandle PUBLISH;
	private static final Object LAN_SCOPE; // null on 26.1.x

	static {
		MethodHandles.Lookup l = MethodHandles.publicLookup();
		try {
			MethodHandle gui = null;
			Class<?> guiClass = null;
			try {
				guiClass = Class.forName("net.minecraft.client.gui.Gui");
				gui = l.findGetter(Minecraft.class, "gui", guiClass);
			} catch (ReflectiveOperationException ignored) {
			}

			SET_SCREEN = first(
					() -> l.findVirtual(Minecraft.class, "setScreen", MethodType.methodType(void.class, Screen.class)),
					viaGui(gui, guiClass, l, "setScreen", MethodType.methodType(void.class, Screen.class)));
			GET_SCREEN = first(
					() -> l.findGetter(Minecraft.class, "screen", Screen.class),
					viaGui(gui, guiClass, l, "screen", MethodType.methodType(Screen.class)));
			TOASTS = first(
					() -> l.findVirtual(Minecraft.class, "getToastManager", MethodType.methodType(ToastManager.class)),
					viaGui(gui, guiClass, l, "toastManager", MethodType.methodType(ToastManager.class)));

			Object scope = null;
			MethodHandle publish;
			try {
				@SuppressWarnings({"unchecked", "rawtypes"})
				Class<? extends Enum> scopeClass = (Class<? extends Enum>) Class.forName(
						"net.minecraft.server.MinecraftServer$MultiplayerScope");
				scope = Enum.valueOf(scopeClass, "LAN");
				publish = l.findVirtual(IntegratedServer.class, "publishServer",
						MethodType.methodType(boolean.class, scopeClass, int.class));
			} catch (ClassNotFoundException e) {
				publish = l.findVirtual(IntegratedServer.class, "publishServer",
						MethodType.methodType(boolean.class, GameType.class, boolean.class, int.class));
			}
			PUBLISH = publish;
			LAN_SCOPE = scope;
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(
					"Tailcat for Minecraft does not support this Minecraft version: " + e);
		}
	}

	private Compat() {}

	public static void setScreen(Minecraft mc, Screen screen) {
		try {
			SET_SCREEN.invoke(mc, screen);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static Screen screen(Minecraft mc) {
		try {
			return (Screen) GET_SCREEN.invoke(mc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static ToastManager toasts(Minecraft mc) {
		try {
			return (ToastManager) TOASTS.invoke(mc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Opens the integrated server to LAN on {@code port} with its current game settings. */
	public static boolean publishToLan(IntegratedServer server, int port) {
		try {
			if (LAN_SCOPE != null) {
				return (boolean) PUBLISH.invoke(server, LAN_SCOPE, port);
			}
			return (boolean) PUBLISH.invoke(server, server.getDefaultGameType(), false, port);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Forces binding of every handle; called at startup so failures surface immediately. */
	static void verify() {}

	private interface Finder {
		MethodHandle find() throws ReflectiveOperationException;
	}

	private static Finder viaGui(MethodHandle gui, Class<?> guiClass, MethodHandles.Lookup l, String name,
			MethodType type) {
		return () -> {
			if (gui == null) {
				throw new NoSuchMethodException("Minecraft.gui");
			}
			return MethodHandles.filterArguments(l.findVirtual(guiClass, name, type), 0, gui);
		};
	}

	private static MethodHandle first(Finder... finders) throws ReflectiveOperationException {
		ReflectiveOperationException last = null;
		for (Finder f : finders) {
			try {
				return f.find();
			} catch (ReflectiveOperationException e) {
				last = e;
			}
		}
		throw last;
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) {
			return r;
		}
		if (t instanceof Error e) {
			throw e;
		}
		return new IllegalStateException(t);
	}
}
