package dev.hearthgate.core.host;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineOutputTest {
	@Test
	void splitsLinesAcrossWritesIncludingMultibyteCharacters() {
		List<String> lines = new ArrayList<>();
		HostFd.LineOutput out = new HostFd.LineOutput(lines::add);
		byte[] text = "héllo 🐈\nsecond\nthi".getBytes(StandardCharsets.UTF_8);
		for (byte b : text) {
			out.write(new byte[] {b}); // worst case: one byte per write
		}
		out.write("rd\n".getBytes(StandardCharsets.UTF_8));
		assertEquals(List.of("héllo 🐈", "second", "third"), lines);
		out.write("tail".getBytes(StandardCharsets.UTF_8));
		out.flush();
		assertEquals("tail", lines.getLast());
	}
}
