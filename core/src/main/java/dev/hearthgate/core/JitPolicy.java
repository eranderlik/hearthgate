package dev.hearthgate.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps the WebAssembly helper's code out of HotSpot's C2 compiler on
 * machines with few CPU cores.
 *
 * <p>The helper is compiled ahead of time into very large methods. For the
 * first minute or two, C2 spends tens of CPU-seconds optimizing them. On a
 * machine with two cores that starves the helper itself: it misses network
 * deadlines, its connection keeps switching between direct and relayed
 * paths, and the tunnelled TCP stream backs off until it barely moves
 * (measured: 0.04 MB/s for the first 8 MB, against 0.55 MB/s with C1 only).
 * With C1 alone, peak throughput is roughly halved but there is no stall.
 *
 * <p>The directive applies only to the helper's classes, and only in this
 * JVM. It is added through the DiagnosticCommand MBean (what {@code jcmd
 * Compiler.directives_add} uses), so no launch flag is needed. The system
 * property {@code hearthgate.helperJit} overrides the choice: {@code c1}
 * always limits, {@code c2} never does, {@code auto} (the default) limits
 * when the JVM has at most {@link #FEW_CORES} processors.
 */
public final class JitPolicy {
	static final Logger LOG = LoggerFactory.getLogger("hearthgate");
	static final int FEW_CORES = 2;
	static final String DIRECTIVES = """
			[{
			  match: "dev/hearthgate/core/wasm/*.*",
			  c2: { Exclude: true }
			}]
			""";
	private static boolean applied;

	private JitPolicy() {}

	/** Applies the policy once per JVM; later calls do nothing. Never throws. */
	public static synchronized void apply() {
		if (applied) {
			return;
		}
		applied = true;
		String mode = System.getProperty("hearthgate.helperJit", "auto").trim().toLowerCase(java.util.Locale.ROOT);
		int cores = Runtime.getRuntime().availableProcessors();
		boolean limit = switch (mode) {
			case "c1" -> true;
			case "c2" -> false;
			default -> cores <= FEW_CORES;
		};
		if (!limit) {
			return;
		}
		try {
			addDirectives();
			LOG.info("Hearthgate: {} CPU core(s) available; using lighter JIT optimization for its network helper "
					+ "so connections stay responsive (set -Dhearthgate.helperJit=c2 to disable)", cores);
		} catch (Exception | LinkageError e) {
			LOG.warn("Hearthgate: could not limit JIT optimization of its network helper: {}", e.toString());
		}
	}

	/** Adds {@link #DIRECTIVES} to the JVM's compiler directive stack. */
	static void addDirectives() throws Exception {
		Path file = Files.createTempFile("hearthgate-jit", ".json");
		try {
			Files.writeString(file, DIRECTIVES);
			String r = diagnostic("compilerDirectivesAdd", file.toString()).trim();
			// Success prints "1 compiler directives added"; errors explain the parse failure.
			if (!r.matches("(?s)1 compiler directives? added.*")) {
				throw new IllegalStateException("directive rejected: " + r);
			}
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/** Runs a DiagnosticCommand (jcmd) operation in this JVM. */
	static String diagnostic(String op, String... args) throws Exception {
		return String.valueOf(ManagementFactory.getPlatformMBeanServer().invoke(
				new ObjectName("com.sun.management:type=DiagnosticCommand"), op,
				new Object[] {args}, new String[] {String[].class.getName()}));
	}
}
