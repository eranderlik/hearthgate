package dev.tailcatmc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One event from the helper's stdout protocol (see cmd/tcmc-helper/main.go).
 * Parsing is strict: the helper and this class ship together, so anything
 * unexpected indicates a bug and is rejected.
 */
public record HelperEvent(String type, String mode, String invite, String address, String code, String message,
		List<Peer> peers, int region, String privateKey, String presharedKey) {

	public record Peer(String id, boolean direct, String relay) {}

	private static final Set<String> TYPES = Set.of("ready", "peers", "error", "stopped", "keys");
	private static final Set<String> FIELDS = Set.of("type", "mode", "invite", "address", "code", "message", "peers",
			"region", "private_key", "preshared_key");

	public static HelperEvent parse(String line) throws IOException {
		try {
			JsonObject o = JsonParser.parseString(line).getAsJsonObject();
			for (String key : o.keySet()) {
				if (!FIELDS.contains(key)) {
					throw new IOException("unknown helper event field: " + key);
				}
			}
			String type = string(o, "type");
			if (type == null || !TYPES.contains(type)) {
				throw new IOException("unknown helper event type: " + type);
			}
			List<Peer> peers = new ArrayList<>();
			if (o.has("peers")) {
				JsonArray arr = o.getAsJsonArray("peers");
				for (JsonElement e : arr) {
					JsonObject p = e.getAsJsonObject();
					peers.add(new Peer(string(p, "id"), p.has("direct") && p.get("direct").getAsBoolean(),
							string(p, "relay")));
				}
			}
			HelperEvent e = new HelperEvent(type, string(o, "mode"), string(o, "invite"), string(o, "address"),
					string(o, "code"), string(o, "message"), List.copyOf(peers),
					o.has("region") ? o.get("region").getAsInt() : 0,
					string(o, "private_key"), string(o, "preshared_key"));
			if (type.equals("keys") && (e.privateKey == null || e.presharedKey == null)) {
				throw new IOException("keys event lacks keys");
			}
			if (type.equals("ready")) {
				if ("host".equals(e.mode)) {
					if (e.invite == null) {
						throw new IOException("host ready event lacks invite");
					}
				} else if ("join".equals(e.mode)) {
					if (e.address == null || !e.address.startsWith("127.0.0.1:")) {
						throw new IOException("join ready address is not loopback");
					}
				} else {
					throw new IOException("invalid ready mode");
				}
			}
			return e;
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("malformed helper event", e);
		}
	}

	/** Never print key material, e.g. when this event is logged. */
	@Override
	public String toString() {
		return "HelperEvent[type=" + type + ", mode=" + mode + ", code=" + code + ", message=" + message
				+ ", peers=" + peers + ", region=" + region + (invite != null ? ", invite=<secret>" : "")
				+ (privateKey != null ? ", keys=<secret>" : "") + "]";
	}

	private static String string(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
	}
}
