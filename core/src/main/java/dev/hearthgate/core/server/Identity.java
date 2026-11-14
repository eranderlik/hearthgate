package dev.hearthgate.core.server;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/**
 * The server's persistent Tailcat identity and publishing state, stored in
 * {@code config/hearthgate/identity.json}. The keys are secrets: the file is
 * created readable only by its owner where the filesystem supports it.
 */
public final class Identity {
	public String privateKey;
	public String presharedKey;
	/** DERP region pinned after the first start, so the invitation stays the same. 0 = not yet chosen. */
	public int region;
	/** When these keys were created; rotation is due {@code rotateAfter} later. */
	public long createdAtEpochSeconds;
	/** The invitation most recently published to Discord, to avoid reposting it. */
	public String publishedInvite;
	/** The Discord message to edit in place. */
	public String discordMessageId;

	private static final Gson GSON = new GsonBuilder()
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.setPrettyPrinting()
			.create();

	public static Identity load(Path file) throws IOException {
		if (!Files.exists(file)) {
			return null;
		}
		try {
			Identity id = GSON.fromJson(Files.readString(file), Identity.class);
			return id == null || id.privateKey == null || id.presharedKey == null ? null : id;
		} catch (JsonParseException e) {
			throw new IOException("invalid " + file.getFileName() + ": " + e.getMessage(), e);
		}
	}

	public static Identity fresh(String privateKey, String presharedKey, Identity previous) {
		Identity id = new Identity();
		id.privateKey = privateKey;
		id.presharedKey = presharedKey;
		id.createdAtEpochSeconds = Instant.now().getEpochSecond();
		if (previous != null) {
			id.discordMessageId = previous.discordMessageId;
			id.publishedInvite = previous.publishedInvite;
		}
		return id;
	}

	public Instant createdAt() {
		return Instant.ofEpochSecond(createdAtEpochSeconds);
	}

	/** Writes atomically, owner-only. */
	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.deleteIfExists(tmp);
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		}
		Files.writeString(tmp, GSON.toJson(this) + "\n");
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
