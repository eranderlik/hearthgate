package dev.hearthgate.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Reads the public parts of an invitation (see helper/internal/invite) so the
 * join screen can validate input and warn about version mismatches before
 * starting a tunnel. The helper performs the authoritative parse.
 */
final class Invites {
	static final String PREFIX = "hg1_";

	record Info(String minecraftVersion) {}

	private Invites() {}

	static Optional<Info> peek(String text) {
		String s = text.trim();
		if (!s.startsWith(PREFIX) || s.length() > 4096) {
			return Optional.empty();
		}
		try {
			byte[] json = Base64.getUrlDecoder().decode(s.substring(PREFIX.length()));
			JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
			if (o.get("version").getAsInt() != 1 || !o.get("tailcat").getAsString().startsWith("tc")) {
				return Optional.empty();
			}
			return Optional.of(new Info(o.has("mc") ? o.get("mc").getAsString() : null));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
