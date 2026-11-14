package dev.hearthgate.core.server;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Dedicated server settings, stored as {@code config/hearthgate-server.json}.
 * Missing fields take the defaults below; a missing file is created with them.
 */
public final class ServerConfig {
	/** Whether the server shares itself through Tailcat at all. */
	public boolean enabled = true;
	/** Hours between key rotations; each rotation creates a new invitation. 0 disables rotation. */
	public double rotateAfterHours = 24 * 7;
	/**
	 * After a rotation, how long the previous tunnel keeps serving players who
	 * were connected through it. It closes earlier once they have all left.
	 */
	public double drainMinutes = 6 * 60;
	/** Name shown in Discord; empty uses the server's MOTD. */
	public String serverName = "";
	/** Allow relay (DERP) servers on private addresses, for self-hosted relays. */
	public boolean allowPrivateRelayAddresses = false;
	/** Log Tailcat's networking diagnostics. */
	public boolean verbose = false;
	/**
	 * "auto" uses the native helper when its add-on for this platform is
	 * installed, for much more throughput with many players; "off" always
	 * uses the WebAssembly helper.
	 */
	public String nativeHelper = "auto";
	public Discord discord = new Discord();

	public static final class Discord {
		/**
		 * Webhook to post invitations to (Channel settings → Integrations →
		 * Webhooks). Overridden by the HEARTHGATE_DISCORD_WEBHOOK environment
		 * variable. Anyone who can read the channel can join the server.
		 */
		public String webhookUrl = "";
		/** Edit one message in place instead of posting a new one each rotation. */
		public boolean editPreviousMessage = true;
		/** Optional text before the embed, such as {@code <@&ROLE_ID>} to ping a role. */
		public String mention = "";
	}

	private static final Gson GSON = new GsonBuilder()
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	public static ServerConfig load(Path file) throws IOException {
		ServerConfig c;
		if (Files.exists(file)) {
			try {
				c = GSON.fromJson(Files.readString(file), ServerConfig.class);
			} catch (JsonParseException e) {
				throw new IOException("invalid " + file.getFileName() + ": " + e.getMessage(), e);
			}
			if (c == null) {
				c = new ServerConfig();
			}
			if (c.discord == null) {
				c.discord = new Discord();
			}
		} else {
			c = new ServerConfig();
		}
		// Rewrite so new options appear with their defaults.
		Files.createDirectories(file.getParent());
		Files.writeString(file, GSON.toJson(c) + "\n");
		return c;
	}

	public String webhookUrl() {
		String env = System.getenv("HEARTHGATE_DISCORD_WEBHOOK");
		return env != null && !env.isBlank() ? env.trim() : discord.webhookUrl.trim();
	}

	/** Whether {@link #nativeHelper} allows the native helper. */
	public boolean nativeHelperAllowed() {
		return !"off".equalsIgnoreCase(nativeHelper == null ? "" : nativeHelper.trim());
	}

	public Duration rotateAfter() {
		return rotateAfterHours <= 0 ? null : Duration.ofSeconds((long) (rotateAfterHours * 3600));
	}

	public Duration drain() {
		return Duration.ofSeconds((long) Math.max(0, drainMinutes * 60));
	}
}
