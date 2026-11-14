package dev.hearthgate.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitPolicyTest {
	/** The directive parses and lands on HotSpot's stack; removed again so other tests keep C2. */
	@Test
	void directiveIsAcceptedByTheJvm() throws Exception {
		String before = JitPolicy.diagnostic("compilerDirectivesPrint");
		JitPolicy.addDirectives();
		try {
			String printed = JitPolicy.diagnostic("compilerDirectivesPrint");
			assertTrue(count(printed) == count(before) + 1, printed);
		} finally {
			JitPolicy.diagnostic("compilerDirectivesRemove");
		}
		assertEquals(before, JitPolicy.diagnostic("compilerDirectivesPrint"));
	}

	private static int count(String directives) {
		return directives.split("dev/hearthgate/core/wasm/", -1).length - 1;
	}
}
