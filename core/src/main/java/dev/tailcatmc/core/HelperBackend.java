package dev.tailcatmc.core;

import dev.tailcatmc.core.host.NetworkPolicy;

import java.io.IOException;
import java.util.List;

/**
 * Runs helper sessions. {@link #WASM} works everywhere; a dedicated server
 * can use {@link dev.tailcatmc.core.nativehelper.NativeHelpers} instead.
 */
public interface HelperBackend {
	/**
	 * Starts a session with the given arguments (after argv[0]). The policy
	 * restricts the sockets the helper may open where the backend can enforce
	 * it: the WebAssembly helper has no sockets except through the JVM, while
	 * native code uses the operating system's directly.
	 */
	Helper start(List<String> args, NetworkPolicy policy, HelperSession.Listener listener) throws IOException;

	/** For logs and status, e.g. "WebAssembly" or "native (linux-x86_64)". */
	String description();

	/** The WebAssembly helper, run by Chicory inside the JVM. */
	HelperBackend WASM = new HelperBackend() {
		@Override
		public Helper start(List<String> args, NetworkPolicy policy, HelperSession.Listener listener)
				throws IOException {
			return HelperSession.start(args, policy, listener);
		}

		@Override
		public String description() {
			return "WebAssembly";
		}
	};
}
