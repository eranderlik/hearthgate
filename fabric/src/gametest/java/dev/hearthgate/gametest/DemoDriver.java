package dev.hearthgate.gametest;

import dev.hearthgate.common.client.HearthgateClientCommon;
import dev.hearthgate.common.client.JoinScreen;
import dev.hearthgate.common.client.Sessions;
import dev.hearthgate.common.client.ShareScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Development only: plays a scripted share-and-join for recording the demo
 * videos (tools/demo/record.sh). Inert unless {@code -Dhearthgate.demo=host}
 * or {@code =join} is set, which only the {@code -Pdemo} Loom runs do.
 *
 * <p>The two roles coordinate through marker files in
 * {@code -Dhearthgate.demo.dir}: the recorder writes {@code go}, the host
 * writes {@code copied} after clicking Copy Invitation, and so on. Clicks go
 * through Minecraft's own mouse handler, so buttons highlight and press as
 * they would for a person; a drawn pointer stands in for the OS cursor, which
 * a virtual display doesn't render nicely.
 */
public final class DemoDriver implements ClientModInitializer {
	static final String WORLD = "Hearthgate Demo";

	private static final String[] POINTER = {
			"X..........",
			"XX.........",
			"XWX........",
			"XWWX.......",
			"XWWWX......",
			"XWWWWX.....",
			"XWWWWWX....",
			"XWWWWWWX...",
			"XWWWWWWWX..",
			"XWWWWWWWWX.",
			"XWWWWWXXXXX",
			"XWWXWWX....",
			"XWX.XWWX...",
			"XX..XWWX...",
			"X....XWWX..",
			".....XWWX..",
			"......XX...",
	};

	private static volatile long clickedAt;
	private static volatile boolean pointerVisible;

	private String role;
	private Path dir;
	private Minecraft mc;
	private Method onMove;
	private Method onButton;

	@Override
	public void onInitializeClient() {
		role = System.getProperty("hearthgate.demo");
		if (role == null) {
			return;
		}
		dir = Path.of(System.getProperty("hearthgate.demo.dir"));
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
				ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> drawPointer(g)));
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			mc = client;
			long handle = client.getWindow().handle();
			GLFW.glfwSetWindowPos(handle, role.equals("join") ? 1280 : 0, 0);
			Thread t = new Thread(this::run, "hearthgate-demo");
			t.setDaemon(true);
			t.start();
		});
	}

	private void run() {
		try {
			onMove = MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
			onMove.setAccessible(true);
			onButton = MouseHandler.class.getDeclaredMethod("onButton", long.class, MouseButtonInfo.class, int.class);
			onButton.setAccessible(true);
			waitFor(m -> m.screen instanceof TitleScreen, 300);
			sleep(3000);
			if (role.equals("host")) {
				host();
			} else {
				join();
			}
		} catch (Throwable e) {
			log("FAILED: " + e);
			e.printStackTrace();
			mark("failed-" + role);
		}
	}

	// ---- host ----

	private void host() throws Exception {
		// Setup, before recording starts: a fresh world from a fixed seed.
		on(() -> {
			CreateWorldScreen.openFresh(mc, () -> mc.setScreen(new TitleScreen()));
			return null;
		});
		waitFor(m -> m.screen instanceof CreateWorldScreen, 30);
		on(() -> {
			var ui = ((CreateWorldScreen) mc.screen).getUiState();
			ui.setName(WORLD);
			ui.setSeed(System.getProperty("hearthgate.demo.seed", "hearthgate"));
			ui.setAllowCommands(true);
			return null;
		});
		sleep(500);
		pressNow("selectWorld.create");
		waitFor(m -> m.level != null && m.player != null && m.screen == null, 300);
		sleep(8000);
		stage();
		mark("host-ready");

		waitForMark("go", 600);
		pointerVisible = true;
		sleep(2500);
		on(() -> {
			mc.pauseGame(false);
			return null;
		});
		waitFor(m -> m.screen instanceof PauseScreen, 10);
		sleep(900);
		click("hearthgate.button.share");
		waitFor(m -> m.screen instanceof ShareScreen, 10);
		event("share-clicked");
		waitFor(m -> {
			Sessions.Status s = HearthgateClientCommon.sessions().status();
			return s != null && s.ready();
		}, 120);
		event("share-ready");
		sleep(1800);
		click("hearthgate.share.copy");
		event("copied");
		sleep(400);
		moveAside();
		String invite = on(() -> HearthgateClientCommon.sessions().status().invite());
		Files.writeString(dir.resolve("invite"), invite);
		mark("copied");

		// New players spawn near world spawn; put the friend right in front of the host instead.
		waitFor(m -> m.getSingleplayerServer().getPlayerList().getPlayerCount() > 1, 180);
		on(() -> {
			IntegratedServer srv = mc.getSingleplayerServer();
			srv.execute(() -> srv.getCommands().performPrefixedCommand(
					srv.createCommandSourceStack().withSuppressedOutput(), friendTp));
			return null;
		});
		event("friend-placed");

		waitFor(m -> {
			Sessions.Status s = HearthgateClientCommon.sessions().status();
			return s != null && !s.peers().isEmpty() && m.getConnection() != null
					&& m.getConnection().getOnlinePlayers().size() > 1;
		}, 180);
		event("peer-connected");
		sleep(3500);
		click("gui.done");
		sleep(300);
		click("menu.returnToGame");
		event("host-back-in-game");
		pointerVisible = false;
		waitForMark("join-done", 120);
		sleep(1000);
		mark("host-done");
	}

	/** Daytime, clear, and the friend's spawn point a few blocks in front of the host, facing them. */
	private void stage() throws Exception {
		on(() -> {
			int[] spot = findSpot();
			int hx = spot[0], hy = spot[1], hz = spot[2];
			float yaw = spot[3];
			double rad = Math.toRadians(yaw);
			int fx = Mth.floor(hx + 0.5 - Math.sin(rad) * FRIEND_DISTANCE);
			int fz = Mth.floor(hz + 0.5 + Math.cos(rad) * FRIEND_DISTANCE);
			int fy = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, fx, fz);
			float faceHost = Mth.wrapDegrees(yaw + 180);
			friendTp = "tp @a[name=!" + mc.getUser().getName() + "] " + (fx + 0.5) + " " + fy + " " + (fz + 0.5) + " "
					+ faceHost + " 4";
			log("staging host at " + hx + " " + hy + " " + hz + " yaw " + yaw + ", friend at " + fx + " " + fy + " " + fz);
			IntegratedServer srv = mc.getSingleplayerServer();
			String[] cmds = {
					"time set 4000",
					"weather clear",
					"gamerule doDaylightCycle false",
					"gamerule advance_time false",
					"gamerule doWeatherCycle false",
					"gamerule advance_weather false",
					"gamerule spawnRadius 0",
					"gamerule respawn_radius 0",
					"gamerule doMobSpawning false",
					"gamerule spawn_mobs false",
					"setworldspawn " + fx + " " + fy + " " + fz + " " + faceHost,
					"tp @a " + (hx + 0.5) + " " + hy + " " + (hz + 0.5) + " " + yaw + " 6",
			};
			srv.execute(() -> {
				for (String c : cmds) {
					try {
						srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), c);
					} catch (RuntimeException e) {
						log("command failed: " + c + ": " + e);
					}
				}
			});
			return null;
		});
		sleep(7000);
		on(() -> {
			mc.gui.getChat().clearMessages(false);
			return null;
		});
	}

	private static final int FRIEND_DISTANCE = 5;
	private String friendTp;

	/**
	 * Searches the loaded area around spawn for a camera position: open grass
	 * (no tree overhead), flat clear ground where the friend will appear, and
	 * a long unobstructed view behind them. Returns {x, y, z, yaw}.
	 */
	private int[] findSpot() {
		var level = mc.level;
		BlockPos base = mc.player.blockPosition();
		double bestScore = Double.NEGATIVE_INFINITY;
		int[] best = {base.getX(), base.getY(), base.getZ(), (int) mc.player.getYRot()};
		for (int dx = -60; dx <= 60; dx += 3) {
			for (int dz = -60; dz <= 60; dz += 3) {
				int x = base.getX() + dx, z = base.getZ() + dz;
				int h = openGround(level, x, z);
				if (h == Integer.MIN_VALUE) {
					continue;
				}
				for (int yaw = 0; yaw < 360; yaw += 30) {
					double sin = -Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
					boolean ok = true;
					for (int d = 1; d <= FRIEND_DISTANCE + 1 && ok; d++) {
						int g = openGround(level, Mth.floor(x + 0.5 + sin * d), Mth.floor(z + 0.5 + cos * d));
						ok = g != Integer.MIN_VALUE && Math.abs(g - h) <= 1;
					}
					if (!ok) {
						continue;
					}
					double score = 0;
					for (int d = 8; d <= 60; d += 2) {
						int t = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x + sin * d), Mth.floor(z + cos * d));
						double allowed = h + 2 + d * 0.12;
						if (t > allowed) {
							score -= 3;
						} else {
							score += 1 + Math.min(4, (h - t) * 0.15);
						}
					}
					score -= Math.hypot(dx, dz) * 0.05;
					if (score > bestScore) {
						bestScore = score;
						best = new int[] {x, h, z, yaw};
					}
				}
			}
		}
		log("best spot score " + bestScore);
		return best;
	}

	/** The surface height if (x, z) is grass with nothing overhead, else MIN_VALUE. */
	private static int openGround(net.minecraft.client.multiplayer.ClientLevel level, int x, int z) {
		int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (h != level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
				|| h != level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)) {
			return Integer.MIN_VALUE;
		}
		return level.getBlockState(new BlockPos(x, h - 1, z)).is(Blocks.GRASS_BLOCK) ? h : Integer.MIN_VALUE;
	}

	// ---- join ----

	private void join() throws Exception {
		mark("join-ready");
		waitForMark("go", 600);
		pointerVisible = true;
		waitForMark("copied", 300);
		sleep(1200);
		click("menu.multiplayer");
		waitFor(m -> m.screen instanceof JoinMultiplayerScreen, 10);
		sleep(1000);
		click("hearthgate.button.join");
		waitFor(m -> m.screen instanceof JoinScreen, 10);
		sleep(900);
		EditBox box = on(() -> find(mc.screen, w -> w instanceof EditBox));
		moveTo(center(box));
		clickHere();
		sleep(500);
		String invite = on(() -> mc.keyboardHandler.getClipboard());
		if (invite == null || !invite.startsWith("hg1_")) {
			log("clipboard didn't carry the invitation; using the file");
			invite = Files.readString(dir.resolve("invite")).trim();
		}
		String pasted = invite;
		on(() -> {
			box.insertText(pasted);
			return null;
		});
		event("pasted");
		sleep(1300);
		click("hearthgate.join.connect");
		event("connect-clicked");
		waitFor(m -> m.level != null && m.player != null && m.screen == null, 180);
		event("joined");
		pointerVisible = false;
		// Offline dev accounts trigger an "unverified chat" notice that real players never see.
		for (int i = 0; i < 25; i++) {
			on(() -> {
				mc.getToastManager().clear();
				return null;
			});
			sleep(100);
		}
		for (int i = 0; i < 2; i++) {
			on(() -> {
				mc.options.keyJump.setDown(true);
				return null;
			});
			sleep(250);
			on(() -> {
				mc.options.keyJump.setDown(false);
				return null;
			});
			sleep(700);
		}
		sleep(4000);
		event("join-done");
		mark("join-done");
	}

	// ---- input ----

	private void click(String key) throws Exception {
		AbstractWidget w = on(() -> find(mc.screen, x -> x instanceof AbstractWidget a && matches(a.getMessage(), key)));
		if (w == null) {
			throw new IllegalStateException("no button " + key + " on " + on(() -> mc.screen));
		}
		moveTo(center(w));
		sleep(350);
		clickHere();
	}

	/** Clicks without the pointer animation (setup only). */
	private void pressNow(String key) throws Exception {
		AbstractWidget w = on(() -> find(mc.screen, x -> x instanceof AbstractWidget a && matches(a.getMessage(), key)));
		double[] c = center(w);
		on(() -> {
			onMove.invoke(mc.mouseHandler, mc.getWindow().handle(), c[0], c[1]);
			return null;
		});
		clickHere();
	}

	private void clickHere() throws Exception {
		clickedAt = System.currentTimeMillis();
		long h = on(() -> mc.getWindow().handle());
		on(() -> onButton.invoke(mc.mouseHandler, h, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0), GLFW.GLFW_PRESS));
		sleep(90);
		on(() -> onButton.invoke(mc.mouseHandler, h, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0), GLFW.GLFW_RELEASE));
	}

	/** Eases the pointer to a window position (in window pixels). */
	private void moveTo(double[] to) throws Exception {
		double[] from = on(() -> new double[] {mc.mouseHandler.xpos(), mc.mouseHandler.ypos()});
		double dist = Math.hypot(to[0] - from[0], to[1] - from[1]);
		long duration = (long) Mth.clamp(dist * 0.9, 300, 750);
		long h = on(() -> mc.getWindow().handle());
		long start = System.currentTimeMillis();
		while (true) {
			double t = Math.min(1, (System.currentTimeMillis() - start) / (double) duration);
			double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
			double x = from[0] + (to[0] - from[0]) * e;
			double y = from[1] + (to[1] - from[1]) * e;
			mc.execute(() -> {
				try {
					onMove.invoke(mc.mouseHandler, h, x, y);
				} catch (ReflectiveOperationException ex) {
					throw new RuntimeException(ex);
				}
			});
			if (t >= 1) {
				break;
			}
			sleep(10);
		}
		on(() -> null); // let the last move land
	}

	/** Moves the pointer off the buttons, so no tooltip lingers. */
	private void moveAside() throws Exception {
		double[] to = on(() -> new double[] {mc.getWindow().getScreenWidth() * 0.82, mc.getWindow().getScreenHeight() * 0.9});
		moveTo(to);
	}

	private double[] center(AbstractWidget w) throws Exception {
		return on(() -> {
			double s = mc.getWindow().getGuiScale();
			return new double[] {(w.getX() + w.getWidth() / 2.0) * s, (w.getY() + w.getHeight() / 2.0) * s};
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T find(Screen screen, Predicate<GuiEventListener> p) {
		if (screen == null) {
			return null;
		}
		for (GuiEventListener l : screen.children()) {
			if (p.test(l)) {
				return (T) l;
			}
		}
		return null;
	}

	private static boolean matches(Component c, String key) {
		if (c.getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
			return true;
		}
		return c.getString().equals(Component.translatable(key).getString());
	}

	// ---- pointer ----

	private static void drawPointer(GuiGraphicsExtractor g) {
		if (!pointerVisible) {
			return;
		}
		Minecraft m = Minecraft.getInstance();
		double s = m.getWindow().getGuiScale();
		float x = (float) (m.mouseHandler.xpos() / s);
		float y = (float) (m.mouseHandler.ypos() / s);
		g.nextStratum();
		var pose = g.pose();

		long since = System.currentTimeMillis() - clickedAt;
		if (since < 350) {
			float t = since / 350f;
			int alpha = (int) (200 * (1 - t));
			float r = 3 + 9 * t;
			pose.pushMatrix();
			pose.translate(x, y);
			int a = alpha << 24 | 0xFFC266;
			int ri = Math.round(r);
			g.fill(-ri, -ri, ri, -ri + 1, a);
			g.fill(-ri, ri - 1, ri, ri, a);
			g.fill(-ri, -ri + 1, -ri + 1, ri - 1, a);
			g.fill(ri - 1, -ri + 1, ri, ri - 1, a);
			pose.popMatrix();
		}

		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale((float) (2.0 / s));
		for (int row = 0; row < POINTER.length; row++) {
			String line = POINTER[row];
			for (int col = 0; col < line.length(); col++) {
				char ch = line.charAt(col);
				if (ch == 'X') {
					g.fill(col, row, col + 1, row + 1, 0xFF000000);
				} else if (ch == 'W') {
					g.fill(col, row, col + 1, row + 1, 0xFFFFFFFF);
				}
			}
		}
		pose.popMatrix();
	}

	// ---- plumbing ----

	private <T> T on(Callable<T> c) throws Exception {
		return mc.submit(() -> {
			try {
				return c.call();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).get();
	}

	private void waitFor(Predicate<Minecraft> p, int seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000L;
		while (true) {
			Minecraft m = Minecraft.getInstance();
			if (m != null && mc != null && Boolean.TRUE.equals(on(() -> p.test(m)))) {
				return;
			}
			if (System.currentTimeMillis() > end) {
				throw new IllegalStateException("timed out (" + role + ")");
			}
			sleep(100);
		}
	}

	private void waitForMark(String name, int seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000L;
		while (!Files.exists(dir.resolve(name))) {
			if (System.currentTimeMillis() > end) {
				throw new IllegalStateException("timed out waiting for " + name);
			}
			sleep(100);
		}
	}

	private void mark(String name) {
		try {
			Files.writeString(dir.resolve(name), "");
			event(name);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** Appends to events.log, which the recorder uses to cut and caption the video. */
	private void event(String name) {
		try {
			Files.writeString(dir.resolve("events.log"), System.currentTimeMillis() + " " + role + " " + name + "\n",
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		log(name);
	}

	private static void sleep(long ms) throws InterruptedException {
		Thread.sleep(ms);
	}

	private static void log(String s) {
		System.out.println("[hearthgate-demo] " + s);
	}
}
