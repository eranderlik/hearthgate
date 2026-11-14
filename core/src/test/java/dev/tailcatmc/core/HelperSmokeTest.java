package dev.tailcatmc.core;

import dev.tailcatmc.core.host.NetworkPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Offline tests: the WebAssembly helper starts, speaks its protocol, and exits. */
class HelperSmokeTest {
	@Test
	void usageErrorWithoutArguments() throws Exception {
		Recorder r = new Recorder("usage");
		long t0 = System.nanoTime();
		HelperSession.start(List.of(), NetworkPolicy.forJoin(), r);
		HelperEvent e = r.next(120);
		System.out.printf("first event after %.2fs%n", (System.nanoTime() - t0) / 1e9);
		assertEquals("error", e.type());
		assertEquals("usage", e.code());
		assertEquals("stopped", r.next(10).type());
		assertEquals(0, r.exited.get(10, TimeUnit.SECONDS));
		assertNull(r.error);
	}

	@Test
	void rejectsMalformedInvite() throws Exception {
		Recorder r = new Recorder("badinvite");
		HelperSession.start(List.of("join", "--invite", "tcmc1_notbase64!"), NetworkPolicy.forJoin(), r);
		HelperEvent e = r.next(120);
		assertEquals("error", e.type());
		assertEquals("invalid_invite", e.code());
		assertEquals(0, r.exited.get(10, TimeUnit.SECONDS));
	}
}
