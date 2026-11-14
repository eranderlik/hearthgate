package dev.tailcatmc.core.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A minimal stand-in for Discord's webhook API. */
final class FakeDiscord implements AutoCloseable {
	record Request(String method, String path, JsonObject body) {}

	final HttpServer server;
	final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
	final Map<String, JsonObject> messages = new ConcurrentHashMap<>();
	final List<String> deleted = new CopyOnWriteArrayList<>();
	final AtomicInteger rateLimitNext = new AtomicInteger();
	private final AtomicInteger ids = new AtomicInteger(1000);

	FakeDiscord() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/webhooks/1/token", ex -> {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			int status;
			String reply;
			if (rateLimitNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
				status = 429;
				reply = "{\"retry_after\":0.2}";
			} else {
				JsonObject json = JsonParser.parseString(body).getAsJsonObject();
				requests.add(new Request(method, path, json));
				String id;
				if (method.equals("POST")) {
					id = Integer.toString(ids.incrementAndGet());
					messages.put(id, json);
					status = 200;
				} else {
					id = path.substring(path.lastIndexOf('/') + 1);
					status = messages.containsKey(id) && !deleted.contains(id) ? 200 : 404;
					if (status == 200) {
						messages.put(id, json);
					}
				}
				reply = status == 200 ? "{\"id\":\"" + id + "\"}" : "{\"message\":\"Unknown Message\"}";
			}
			byte[] b = reply.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status, b.length);
			ex.getResponseBody().write(b);
			ex.close();
		});
		server.start();
	}

	URI url() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/token");
	}

	Request next() throws InterruptedException {
		Request r = requests.poll(60, TimeUnit.SECONDS);
		if (r == null) {
			throw new AssertionError("no Discord request within 60s");
		}
		return r;
	}

	static String description(Request r) {
		return r.body().getAsJsonArray("embeds").get(0).getAsJsonObject().get("description").getAsString();
	}

	static String title(Request r) {
		return r.body().getAsJsonArray("embeds").get(0).getAsJsonObject().get("title").getAsString();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
