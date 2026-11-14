package dev.hearthgate.core;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Collects a session's events for assertions. */
public final class Recorder implements HelperSession.Listener {
	final String name;
	public final LinkedBlockingQueue<HelperEvent> events = new LinkedBlockingQueue<>();
	public final List<String> logs = new CopyOnWriteArrayList<>();
	public final CompletableFuture<Integer> exited = new CompletableFuture<>();
	volatile Throwable error;

	public Recorder(String name) {
		this.name = name;
	}

	@Override
	public void onEvent(String line) {
		System.out.println("[" + name + " event] " + line.replaceAll("hg1_[A-Za-z0-9_-]+", "hg1_<redacted>")
				.replaceAll("\"(private_key|preshared_key)\":\"[^\"]*\"", "\"$1\":\"<redacted>\""));
		try {
			events.add(HelperEvent.parse(line));
		} catch (IOException e) {
			error = e;
		}
	}

	@Override
	public void onLog(String line) {
		System.out.println("[" + name + " log] " + line);
		logs.add(line);
	}

	@Override
	public void onExit(int code, Throwable error) {
		if (error != null) {
			this.error = error;
			error.printStackTrace();
		}
		exited.complete(code);
	}

	public HelperEvent next(long seconds) throws InterruptedException {
		HelperEvent e = events.poll(seconds, TimeUnit.SECONDS);
		if (e == null) {
			throw new AssertionError(name + ": no event within " + seconds + "s");
		}
		return e;
	}

	public HelperEvent nextOfType(String type, long seconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (true) {
			long left = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
			HelperEvent e = next(Math.max(1, left));
			if (e.type().equals(type)) {
				return e;
			}
			if (e.type().equals("error") || e.type().equals("stopped")) {
				throw new AssertionError(name + ": wanted " + type + " but got " + e);
			}
		}
	}
}
