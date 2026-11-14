package dev.hearthgate.core.nativehelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Names the platforms the native helper is built for. */
public final class NativePlatform {
	private NativePlatform() {}

	/** Platforms with a native helper build: os-arch, as used in add-on names. */
	public static final java.util.List<String> SUPPORTED =
			java.util.List.of("linux-x86_64", "linux-aarch64", "windows-x86_64", "macos-aarch64");

	/**
	 * This JVM's platform in the form of {@link #SUPPORTED}, or a descriptive
	 * name that is not in that list (such as "linux-musl-x86_64") when no
	 * native helper can run here.
	 */
	public static String current() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> System.getProperty("os.arch", "unknown");
		};
		if (os.startsWith("linux")) {
			// The library links against glibc; musl (Alpine) can't load it.
			return (isMusl() ? "linux-musl-" : "linux-") + arch;
		}
		if (os.startsWith("windows")) {
			return "windows-" + arch;
		}
		if (os.startsWith("mac")) {
			return "macos-" + arch;
		}
		return os.replace(' ', '_') + "-" + arch;
	}

	/** The library's file name on {@code platform}. */
	public static String libraryName(String platform) {
		if (platform.startsWith("windows-")) {
			return "hearthgate.dll";
		}
		return platform.startsWith("macos-") ? "libhearthgate.dylib" : "libhearthgate.so";
	}

	/** The Fabric mod id of the add-on carrying {@code platform}'s library. */
	public static String addonModId(String platform) {
		return "hearthgate_native_" + platform.replace('-', '_');
	}

	private static boolean isMusl() {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
				if (line.contains("ld-musl-") || line.contains("libc.musl-")) {
					return true;
				}
			}
		} catch (IOException | RuntimeException e) {
			// Not knowable; assume glibc, the common case. Loading will fail
			// cleanly on a mismatch.
		}
		return false;
	}
}
