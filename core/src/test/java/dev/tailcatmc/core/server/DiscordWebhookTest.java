package dev.tailcatmc.core.server;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookTest {
	private static DiscordWebhook.Announcement online(String invite) {
		return new DiscordWebhook.Announcement("Test Server", "26.3", invite, true,
				Instant.ofEpochSecond(1_900_000_000L), "<@&123>");
	}

	@Test
	void postsThenEditsInPlaceAndRecoversFromDeletedMessage() throws Exception {
		try (FakeDiscord d = new FakeDiscord(); DiscordWebhook w = new DiscordWebhook(d.url(), false)) {
			String id = w.publish(null, online("tcmc1_A")).get(10, TimeUnit.SECONDS);
			FakeDiscord.Request post = d.next();
			assertEquals("POST", post.method());
			assertTrue(FakeDiscord.description(post).contains("tcmc1_A"));
			assertTrue(post.body().get("content").getAsString().contains("<@&123>"));
			assertEquals("roles", post.body().getAsJsonObject("allowed_mentions").getAsJsonArray("parse")
					.get(0).getAsString());

			assertEquals(id, w.publish(id, online("tcmc1_B")).get(10, TimeUnit.SECONDS));
			FakeDiscord.Request patch = d.next();
			assertEquals("PATCH", patch.method());
			assertTrue(patch.path().endsWith("/messages/" + id));
			assertTrue(FakeDiscord.description(patch).contains("tcmc1_B"));

			d.deleted.add(id);
			String id2 = w.publish(id, online("tcmc1_C")).get(10, TimeUnit.SECONDS);
			assertNotEquals(id, id2);
			assertEquals("POST", d.requests.stream().reduce((a, b) -> b).orElseThrow().method());
		}
	}

	@Test
	void retriesAfterRateLimit() throws Exception {
		try (FakeDiscord d = new FakeDiscord(); DiscordWebhook w = new DiscordWebhook(d.url(), false)) {
			d.rateLimitNext.set(2);
			w.publish(null, online("tcmc1_A")).get(20, TimeUnit.SECONDS);
			assertEquals("POST", d.next().method());
		}
	}

	@Test
	void offlineAnnouncementOmitsInvitation() {
		var body = DiscordWebhook.body(new DiscordWebhook.Announcement("S", "26.1", null, false, null, ""));
		String json = body.toString();
		assertTrue(json.contains("is offline"));
		assertTrue(!json.contains("tcmc1_"));
		assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
	}

	@Test
	void acceptsOnlyDiscordWebhookUrls() {
		DiscordWebhook.validate(URI.create("https://discord.com/api/webhooks/1/abc"));
		DiscordWebhook.validate(URI.create("https://canary.discord.com/api/webhooks/1/abc"));
		for (String bad : new String[] {"http://discord.com/api/webhooks/1/abc", "https://evil.example/api/webhooks/1/a",
				"https://discord.com/channels/1/2", "https://discord.com/api/webhooks/1/abc?wait=true"}) {
			assertThrows(IllegalArgumentException.class, () -> DiscordWebhook.validate(URI.create(bad)), bad);
		}
	}
}
