package dev.tailcatmc.core.nativehelper;

import dev.tailcatmc.core.Helper;
import dev.tailcatmc.core.HelperEvent;
import dev.tailcatmc.core.Recorder;
import dev.tailcatmc.core.host.NetworkPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeHelpersTest {
	@Test
	void extractIsContentAddressedAndReplacesOldVersions(@TempDir Path tmp) throws Exception {
		Path v1 = Files.writeString(tmp.resolve("libtcmc.so"), "one");
		Path cache = tmp.resolve("cache");
		Path a = NativeHelpers.extract(v1, cache);
		assertEquals("one", Files.readString(a));
		assertEquals(a, NativeHelpers.extract(v1, cache), "same content reuses the file");

		Path v2 = Files.writeString(Files.createDirectory(tmp.resolve("other")).resolve("libtcmc.so"), "two");
		Path b = NativeHelpers.extract(v2, cache);
		assertEquals("two", Files.readString(b));
		assertFalse(Files.exists(a), "previous version removed");
		try (var s = Files.list(cache)) {
			assertEquals(1, s.count(), "only the current version and no temporary files remain");
		}
	}

	@Test
	void platformNames() {
		String p = NativePlatform.current();
		assertNotNull(p);
		assertEquals("tailcatmc_native_linux_x86_64", NativePlatform.addonModId("linux-x86_64"));
		assertEquals("tcmc.dll", NativePlatform.libraryName("windows-x86_64"));
	}

	private static NativeHelpers lib() throws Exception {
		String lib = System.getProperty("tcmc.nativeLibrary");
		Assumptions.assumeTrue(lib != null, "native tests need -PtestNative");
		assertEquals(System.getProperty("tcmc.nativePlatform"), NativePlatform.current());
		assertTrue(NativeHelpers.nativeAccessEnabled());
		return NativeHelpers.load(Path.of(lib), NativePlatform.current());
	}

	@Test
	void keygen() throws Exception {
		Recorder r = new Recorder("keygen");
		lib().start(List.of("keygen"), NetworkPolicy.none(), r);
		HelperEvent keys = r.nextOfType("keys", 10);
		assertTrue(keys.privateKey().startsWith("privkey:"));
		assertEquals("stopped", r.next(10).type());
		assertEquals(0, r.exited.get(10, TimeUnit.SECONDS));
	}

	@Test
	void reportsErrorsLikeTheWasmHelper() throws Exception {
		Recorder r = new Recorder("bad");
		lib().start(List.of("join", "--invite", "tcmc1_notbase64!"), NetworkPolicy.forJoin(), r);
		HelperEvent e = r.next(10);
		assertEquals("error", e.type());
		assertEquals("invalid_invite", e.code());
		assertEquals("stopped", r.next(10).type());

		Recorder u = new Recorder("usage");
		lib().start(List.of(), NetworkPolicy.none(), u);
		assertEquals("usage", u.next(10).code());
	}

	@Test
	void stopAndKill() throws Exception {
		// A host that can't reach anything still waits for its relay; stop
		// must end it promptly, and kill must report an exit immediately.
		Recorder r = new Recorder("stop");
		Helper h = lib().start(List.of("host", "--target", "127.0.0.1:1"), NetworkPolicy.none(), r);
		Thread.sleep(200);
		h.stop();
		assertEquals(0, r.exited.get(15, TimeUnit.SECONDS));
		assertFalse(h.isAlive());

		Recorder k = new Recorder("kill");
		Helper h2 = lib().start(List.of("host", "--target", "127.0.0.1:1"), NetworkPolicy.none(), k);
		Thread.sleep(200);
		h2.kill();
		assertEquals(-1, k.exited.get(1, TimeUnit.SECONDS));
	}
}
