package dev.hearthgate.fabric;

import net.minecraft.network.chat.Component;

/** Turns the helper's error codes into messages for players. */
final class ErrorMessages {
	private ErrorMessages() {}

	static String describe(String code, String detail) {
		String key = switch (code == null ? "" : code) {
			case "relay_unreachable" -> "hearthgate.error.relay_unreachable";
			case "host_unreachable" -> "hearthgate.error.host_unreachable";
			case "invalid_invite" -> "hearthgate.error.invalid_invite";
			default -> null;
		};
		if (key == null) {
			return detail == null ? Component.translatable("hearthgate.error.unknown").getString() : detail;
		}
		Sessions.LOG.warn("Hearthgate {}: {}", code, detail);
		return Component.translatable(key).getString();
	}

	static String rootMessage(Throwable error) {
		while (error.getCause() != null) {
			error = error.getCause();
		}
		if (error instanceof java.util.concurrent.TimeoutException) {
			return Component.translatable("hearthgate.error.timeout").getString();
		}
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}
}
