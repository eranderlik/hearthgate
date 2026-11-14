package dev.hearthgate.core.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts the server's invitation to a Discord channel through a webhook, and
 * edits that message when the invitation changes or the server goes offline.
 *
 * <p>Requests run one at a time on a private thread, so announcements arrive
 * in order. The webhook URL contains its token and is never logged.
 */
public final class DiscordWebhook implements AutoCloseable {
	private static final Set<String> DISCORD_HOSTS =
			Set.of("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com");
	private static final int MAX_ATTEMPTS = 5;
	static final int COLOR_ONLINE = 0x57F287;
	static final int COLOR_OFFLINE = 0x99AAB5;

	/** What to announce. {@code validUntil} is null when keys don't rotate. */
	public record Announcement(String serverName, String minecraftVersion, String invite, boolean online,
			Instant validUntil, String mention) {}

	private final URI url;
	private final HttpClient http;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "hearthgate-discord");
		t.setDaemon(true);
		return t;
	});

	public DiscordWebhook(String url) {
		this(URI.create(url), true);
	}

	/** {@code strict} requires a discord.com webhook URL; tests use local servers. */
	DiscordWebhook(URI url, boolean strict) {
		if (strict) {
			validate(url);
		}
		String s = url.toString();
		this.url = URI.create(s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	static void validate(URI url) {
		if (!"https".equals(url.getScheme()) || url.getHost() == null || !DISCORD_HOSTS.contains(url.getHost())
				|| url.getPath() == null || !url.getPath().startsWith("/api/webhooks/") || url.getQuery() != null) {
			throw new IllegalArgumentException(
					"not a Discord webhook URL (expected https://discord.com/api/webhooks/…)");
		}
	}

	/**
	 * Publishes {@code a}, editing {@code messageId} if given and still present.
	 * Completes with the id of the message now showing it.
	 */
	public CompletableFuture<String> publish(String messageId, Announcement a) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String body = body(a).toString();
				if (messageId != null && !messageId.isBlank()) {
					HttpResponse<String> r = send("PATCH", URI.create(url + "/messages/" + messageId), body);
					if (r.statusCode() / 100 == 2) {
						return id(r);
					}
					if (r.statusCode() != 404) {
						throw failure(r);
					}
					// Deleted by someone: post a new one.
				}
				HttpResponse<String> r = send("POST", URI.create(url + "?wait=true"), body);
				if (r.statusCode() / 100 != 2) {
					throw failure(r);
				}
				return id(r);
			} catch (IOException e) {
				throw new java.util.concurrent.CompletionException(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new java.util.concurrent.CompletionException(e);
			}
		}, worker);
	}

	static JsonObject body(Announcement a) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", a.serverName() + (a.online() ? " is online" : " is offline"));
		embed.addProperty("color", a.online() ? COLOR_ONLINE : COLOR_OFFLINE);
		StringBuilder d = new StringBuilder();
		if (a.online()) {
			d.append("Minecraft ").append(a.minecraftVersion())
					.append(". With the **Hearthgate** mod, open **Multiplayer → Join with Hearthgate** "
							+ "and paste this invitation:\n```\n")
					.append(a.invite()).append("\n```");
		} else {
			d.append("The server is offline. The invitation will work again when it is back, "
					+ "unless its keys have rotated by then.");
		}
		embed.addProperty("description", d.toString());
		JsonArray fields = new JsonArray();
		if (a.online()) {
			JsonObject valid = new JsonObject();
			valid.addProperty("name", "Valid until");
			long t = a.validUntil() == null ? 0 : a.validUntil().getEpochSecond();
			valid.addProperty("value", a.validUntil() == null
					? "Until the server's keys are rotated"
					: "<t:" + t + ":f> (<t:" + t + ":R>). A new invitation will be posted here.");
			valid.addProperty("inline", false);
			fields.add(valid);
		}
		embed.add("fields", fields);
		embed.addProperty("timestamp", Instant.now().toString());
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Keep this invitation within this channel: anyone with it can connect.");
		embed.add("footer", footer);

		JsonObject o = new JsonObject();
		String mention = a.mention() == null ? "" : a.mention().trim();
		o.addProperty("content", mention);
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		o.add("embeds", embeds);
		// Only ping what the operator configured, never @everyone by accident.
		JsonObject allowed = new JsonObject();
		JsonArray parse = new JsonArray();
		if (mention.contains("<@&")) {
			parse.add("roles");
		}
		if (mention.matches(".*<@!?\\d+>.*")) {
			parse.add("users");
		}
		allowed.add("parse", parse);
		o.add("allowed_mentions", allowed);
		return o;
	}

	private HttpResponse<String> send(String method, URI uri, String body) throws IOException, InterruptedException {
		HttpResponse<String> r = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			HttpRequest req = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(20))
					.header("Content-Type", "application/json")
					.header("User-Agent", "Hearthgate (https://github.com/eranderlik/tailcat-mc, 1)")
					.method(method, HttpRequest.BodyPublishers.ofString(body))
					.build();
			try {
				r = http.send(req, HttpResponse.BodyHandlers.ofString());
			} catch (IOException e) {
				if (attempt == MAX_ATTEMPTS) {
					throw new IOException("Discord unreachable: " + e.getMessage(), null);
				}
				Thread.sleep(backoff(attempt));
				continue;
			}
			if (r.statusCode() == 429) {
				Thread.sleep(retryAfterMillis(r));
			} else if (r.statusCode() >= 500) {
				Thread.sleep(backoff(attempt));
			} else {
				return r;
			}
		}
		return r;
	}

	private static long backoff(int attempt) {
		return Math.min(30_000L, 1_000L << attempt);
	}

	private static long retryAfterMillis(HttpResponse<String> r) {
		try {
			double s = JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble();
			return (long) Math.min(60_000, Math.max(100, s * 1000));
		} catch (RuntimeException e) {
			return r.headers().firstValue("Retry-After").map(v -> (long) (Double.parseDouble(v) * 1000)).orElse(5_000L);
		}
	}

	private static String id(HttpResponse<String> r) throws IOException {
		try {
			return JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
		} catch (RuntimeException e) {
			throw new IOException("unexpected Discord response");
		}
	}

	private static IOException failure(HttpResponse<String> r) {
		String hint = switch (r.statusCode()) {
			case 401, 403, 404 -> " (is the webhook URL correct, and does the webhook still exist?)";
			case 400 -> " (Discord rejected the message)";
			default -> "";
		};
		return new IOException("Discord returned HTTP " + r.statusCode() + hint);
	}

	@Override
	public void close() {
		worker.shutdown();
	}
}
