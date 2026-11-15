package dev.hearthgate.core.server;

import dev.hearthgate.core.Helper;
import dev.hearthgate.core.HelperBackend;
import dev.hearthgate.core.HelperEvent;
import dev.hearthgate.core.HelperSession;
import dev.hearthgate.core.host.NetworkPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Keeps a dedicated Minecraft server reachable through Tailcat.
 *
 * <ul>
 *   <li>The server's identity (node key, pre-shared key, relay region) is
 *       persisted, so its invitation survives restarts.</li>
 *   <li>The helper is supervised: if it exits or can't reach the relays, it
 *       is restarted with exponential backoff.</li>
 *   <li>Keys rotate on a schedule, make-before-break: the next identity's
 *       tunnel must be up before it replaces the current one. The old
 *       tunnel then refuses new connections, so the old invitation admits no
 *       one, but keeps serving the players connected through it until they
 *       leave or the drain period ends.</li>
 *   <li>Each new invitation, and online/offline state, is announced to
 *       Discord if a webhook is configured.</li>
 * </ul>
 *
 * <p>All state lives on one scheduler thread; helper events are hopped onto it.
 */
public final class PersistentHost implements AutoCloseable {
	static final Logger LOG = LoggerFactory.getLogger("hearthgate");
	private static final Duration MIN_BACKOFF = Duration.ofSeconds(5);
	private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
	private static final long KEYGEN_TIMEOUT_S = 60;

	/**
	 * @param target           the Minecraft server's address (resolved)
	 * @param minecraftVersion recorded in invitations and announcements
	 * @param serverName       shown in announcements
	 * @param identityFile     where the identity is persisted
	 * @param backend          runs the helper: WebAssembly, or the native add-on
	 */
	public record Options(InetSocketAddress target, String minecraftVersion, String serverName, Path identityFile,
			ServerConfig config, HelperBackend backend) {
		public Options(InetSocketAddress target, String minecraftVersion, String serverName, Path identityFile,
				ServerConfig config) {
			this(target, minecraftVersion, serverName, identityFile, config, HelperBackend.WASM);
		}
	}

	/** A snapshot for the {@code /hearthgate status} command. */
	public record Status(String state, String invite, Instant validUntil, int players, int directPlayers,
			int drainingTunnels, String lastError, String engine) {}

	private final Options opts;
	private final DiscordWebhook discord;
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "hearthgate-server");
		t.setDaemon(true);
		return t;
	});

	private Identity identity;
	private Tunnel active;
	private Tunnel candidate;
	private final List<Tunnel> draining = new ArrayList<>();
	private int failures;
	private ScheduledFuture<?> rotationTask;
	private ScheduledFuture<?> restartTask;
	private boolean closed;
	private boolean announcedOnline;
	private volatile String lastError;
	private volatile Status status = new Status("starting", null, null, 0, 0, 0, null, null);

	/** One helper instance serving one identity. */
	private final class Tunnel implements HelperSession.Listener {
		final Identity id;
		Helper helper;
		String invite;
		List<HelperEvent.Peer> peers = List.of();
		ScheduledFuture<?> drainTimeout;

		Tunnel(Identity id) {
			this.id = id;
		}

		@Override
		public void onEvent(String line) {
			HelperEvent e;
			try {
				e = HelperEvent.parse(line);
			} catch (IOException ex) {
				LOG.error("Bad event from Hearthgate helper: {}", ex.getMessage());
				return;
			}
			post(() -> handle(this, e));
		}

		@Override
		public void onLog(String line) {
			LOG.info("[helper] {}", line);
		}

		@Override
		public void onExit(int code, Throwable error) {
			if (error != null && !(error instanceof dev.hearthgate.core.host.HelperHost.Killed)) {
				LOG.error("Hearthgate helper crashed", error);
			}
			post(() -> exited(this));
		}

		void stop() {
			if (drainTimeout != null) {
				drainTimeout.cancel(false);
			}
			if (helper != null) {
				helper.close();
			}
		}
	}

	public PersistentHost(Options opts) {
		this.opts = opts;
		String url = opts.config().webhookUrl();
		DiscordWebhook d = null;
		if (!url.isEmpty()) {
			try {
				d = new DiscordWebhook(url);
			} catch (IllegalArgumentException e) {
				LOG.error("Discord announcements disabled: {}", e.getMessage());
			}
		}
		this.discord = d;
	}

	/** Test hook: use a webhook that isn't on discord.com. */
	PersistentHost(Options opts, DiscordWebhook discord) {
		this.opts = opts;
		this.discord = discord;
	}

	/** Runs {@code r} on the scheduler, dropping it once the host has shut down. */
	private void post(Runnable r) {
		try {
			exec.execute(r);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// Closed: late events from stopping helpers don't matter.
		}
	}

	public void start() {
		exec.execute(this::bringUp);
	}

	public Status status() {
		return status;
	}

	/** Rotates the keys now. The current invitation keeps working until the new one is up. */
	public CompletableFuture<Void> rotateNow() {
		return CompletableFuture.runAsync(this::rotate, exec);
	}

	/** Re-sends the current announcement to Discord. */
	public CompletableFuture<Void> republish() {
		return CompletableFuture.runAsync(() -> {
			announcedOnline = false;
			if (identity != null) {
				identity.publishedInvite = null;
			}
			publish();
		}, exec);
	}

	public boolean discordEnabled() {
		return discord != null;
	}

	// ---------------------------------------------------------------------

	private void bringUp() {
		try {
			identity = Identity.load(opts.identityFile());
			if (identity == null) {
				LOG.info("Creating this server's Tailcat identity");
				identity = generate(null);
				identity.save(opts.identityFile());
			}
		} catch (Exception e) {
			lastError = "could not load or create the Tailcat identity: " + e.getMessage();
			LOG.error("Hearthgate: {}", lastError, e);
			failures++;
			restartTask = exec.schedule(this::bringUp, backoff().toMillis(), TimeUnit.MILLISECONDS);
			updateStatus();
			return;
		}
		active = startTunnel(identity);
		updateStatus();
	}

	/** Runs the helper's keygen command; called on the scheduler thread. */
	private Identity generate(Identity previous) throws IOException, InterruptedException, ExecutionException,
			TimeoutException {
		CompletableFuture<HelperEvent> keys = new CompletableFuture<>();
		opts.backend().start(List.of("keygen"), NetworkPolicy.none(), new HelperSession.Listener() {
			@Override
			public void onEvent(String line) {
				try {
					HelperEvent e = HelperEvent.parse(line);
					if (e.type().equals("keys")) {
						keys.complete(e);
					}
				} catch (IOException e) {
					keys.completeExceptionally(e);
				}
			}

			@Override
			public void onExit(int code, Throwable error) {
				keys.completeExceptionally(new IOException("keygen exited without keys"));
			}
		});
		HelperEvent e = keys.get(KEYGEN_TIMEOUT_S, TimeUnit.SECONDS);
		return Identity.fresh(e.privateKey(), e.presharedKey(), previous);
	}

	private Tunnel startTunnel(Identity id) {
		Tunnel t = new Tunnel(id);
		InetSocketAddress target = opts.target();
		List<String> args = new ArrayList<>(List.of("host",
				"--target", hostPort(target),
				"--mc", opts.minecraftVersion(),
				"--key", id.privateKey,
				"--psk", id.presharedKey));
		if (id.region > 0) {
			args.add("--region");
			args.add(Integer.toString(id.region));
		}
		if (opts.config().verbose) {
			args.add("--verbose");
		}
		NetworkPolicy policy = NetworkPolicy.forHost(target).withPrivateRemote(opts.config().allowPrivateRelayAddresses);
		try {
			t.helper = opts.backend().start(args, policy, t);
		} catch (IOException e) {
			lastError = "could not start the Hearthgate helper: " + e.getMessage();
			post(() -> exited(t));
		}
		return t;
	}

	private static String hostPort(InetSocketAddress a) {
		String host = a.getAddress().getHostAddress();
		return (host.contains(":") ? "[" + host + "]" : host) + ":" + a.getPort();
	}

	private void handle(Tunnel t, HelperEvent e) {
		if (closed) {
			return;
		}
		switch (e.type()) {
			case "ready" -> ready(t, e);
			case "peers" -> {
				t.peers = e.peers();
				if (draining.contains(t) && t.peers.isEmpty()) {
					LOG.info("Hearthgate: previous tunnel has no players left; closing it");
					closeDraining(t);
				}
			}
			case "error" -> {
				lastError = e.code() + ": " + e.message();
				LOG.warn("Hearthgate helper error: {}", lastError);
			}
			default -> {
			}
		}
		updateStatus();
	}

	private void ready(Tunnel t, HelperEvent e) {
		t.invite = e.invite();
		if (e.region() > 0 && t.id.region != e.region()) {
			t.id.region = e.region(); // pin the relay so the invitation stays stable
		}
		if (t == candidate) {
			Tunnel old = active;
			active = t;
			candidate = null;
			identity = t.id;
			if (old != null) {
				retire(old);
			}
			LOG.info("Hearthgate: keys rotated; a new invitation is in effect");
		}
		if (t == active) {
			failures = 0;
			lastError = null;
			saveIdentity();
			LOG.info("Hearthgate: server is reachable. Run /hearthgate invite to see the invitation{}",
					discord != null ? " (also posted to Discord)" : "");
			publish();
			scheduleRotation();
		}
	}

	private void exited(Tunnel t) {
		if (closed) {
			return;
		}
		if (t == active) {
			active = null;
			failures++;
			Duration delay = backoff();
			if (failures % 3 == 0 && identity.region != 0) {
				// The pinned relay region may have been retired; let the
				// helper choose again (this changes the invitation).
				LOG.warn("Hearthgate: repeated failures with relay region {}; trying another", identity.region);
				identity.region = 0;
			}
			LOG.warn("Hearthgate helper stopped; restarting in {}s", delay.toSeconds());
			restartTask = exec.schedule(() -> {
				if (!closed && active == null) {
					active = startTunnel(identity);
					updateStatus();
				}
			}, delay.toMillis(), TimeUnit.MILLISECONDS);
		} else if (t == candidate) {
			candidate = null;
			failures++;
			Duration delay = backoff();
			LOG.warn("Hearthgate: new keys' tunnel failed to start; retrying rotation in {}s", delay.toSeconds());
			rotationTask = exec.schedule(this::rotate, delay.toMillis(), TimeUnit.MILLISECONDS);
		} else {
			draining.remove(t);
		}
		updateStatus();
	}

	private Duration backoff() {
		long ms = MIN_BACKOFF.toMillis() << Math.min(10, Math.max(0, failures - 1));
		return Duration.ofMillis(Math.min(ms, MAX_BACKOFF.toMillis()));
	}

	private Instant validUntil() {
		Duration every = opts.config().rotateAfter();
		return every == null || identity == null ? null : identity.createdAt().plus(every);
	}

	private void scheduleRotation() {
		if (rotationTask != null) {
			rotationTask.cancel(false);
		}
		Instant due = validUntil();
		if (due == null) {
			return;
		}
		long delay = Math.max(0, Duration.between(Instant.now(), due).toMillis());
		rotationTask = exec.schedule(this::rotate, delay, TimeUnit.MILLISECONDS);
	}

	private void rotate() {
		if (closed || candidate != null || identity == null) {
			return;
		}
		try {
			LOG.info("Hearthgate: rotating keys");
			candidate = startTunnel(generate(identity));
		} catch (Exception e) {
			lastError = "key rotation failed: " + e.getMessage();
			LOG.error("Hearthgate: {}", lastError);
			failures++;
			rotationTask = exec.schedule(this::rotate, backoff().toMillis(), TimeUnit.MILLISECONDS);
		}
		updateStatus();
	}

	private void retire(Tunnel old) {
		if (old.peers.isEmpty()) {
			old.stop();
			return;
		}
		// The old invitation stops admitting anyone now; only the players
		// already connected through it stay, for at most the drain period.
		old.helper.drain();
		draining.add(old);
		Duration drain = opts.config().drain();
		LOG.info("Hearthgate: previous invitation retired; its tunnel keeps serving the {} player(s) already connected for up to {} minutes",
				old.peers.size(), drain.toMinutes());
		old.drainTimeout = exec.schedule(() -> closeDraining(old), drain.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void closeDraining(Tunnel t) {
		draining.remove(t);
		t.stop();
		updateStatus();
	}

	private void saveIdentity() {
		try {
			identity.save(opts.identityFile());
		} catch (IOException e) {
			LOG.error("Hearthgate: could not save identity: {}", e.getMessage());
		}
	}

	private void publish() {
		if (discord == null || active == null || active.invite == null) {
			return;
		}
		String invite = active.invite;
		boolean changed = !invite.equals(identity.publishedInvite);
		boolean edit = opts.config().discord.editPreviousMessage;
		// When posting fresh messages, only post new invitations; when editing,
		// also flip the existing message back to online after a restart.
		if (!changed && (announcedOnline || !edit)) {
			return;
		}
		Identity id = identity;
		DiscordWebhook.Announcement a = new DiscordWebhook.Announcement(opts.serverName(), opts.minecraftVersion(),
				invite, true, validUntil(), opts.config().discord.mention);
		discord.publish(edit ? id.discordMessageId : null, a).whenCompleteAsync((messageId, err) -> {
			if (err != null) {
				lastError = "Discord: " + rootMessage(err);
				LOG.error("Hearthgate: could not post to Discord: {}", rootMessage(err));
				return;
			}
			announcedOnline = true;
			id.discordMessageId = messageId;
			id.publishedInvite = invite;
			if (id == identity) {
				saveIdentity();
			}
			LOG.info("Hearthgate: invitation posted to Discord");
			updateStatus();
		}, exec);
	}

	private static String rootMessage(Throwable t) {
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return t.getMessage() == null ? t.toString() : t.getMessage();
	}

	private void updateStatus() {
		String state;
		if (closed) {
			state = "stopped";
		} else if (active != null && active.invite != null) {
			state = candidate != null ? "online (rotating keys)" : "online";
		} else if (failures > 0) {
			state = "reconnecting";
		} else {
			state = "starting";
		}
		List<HelperEvent.Peer> peers = new ArrayList<>(active == null ? List.of() : active.peers);
		draining.forEach(t -> peers.addAll(t.peers));
		int direct = (int) peers.stream().filter(HelperEvent.Peer::direct).count();
		status = new Status(state, active == null ? null : active.invite, validUntil(), peers.size(), direct,
				draining.size(), lastError, opts.backend().description());
	}

	/** Stops all tunnels and, when editing a Discord message, marks it offline. Blocks briefly. */
	@Override
	public void close() {
		CompletableFuture<Void> offline = CompletableFuture.supplyAsync(() -> {
			closed = true;
			if (rotationTask != null) {
				rotationTask.cancel(false);
			}
			if (restartTask != null) {
				restartTask.cancel(false);
			}
			for (Tunnel t : draining) {
				t.stop();
			}
			draining.clear();
			if (candidate != null) {
				candidate.stop();
			}
			if (active != null) {
				active.stop();
			}
			updateStatus();
			if (discord != null && identity != null && identity.discordMessageId != null
					&& opts.config().discord.editPreviousMessage && announcedOnline) {
				return discord.publish(identity.discordMessageId, new DiscordWebhook.Announcement(opts.serverName(),
						opts.minecraftVersion(), null, false, null, ""));
			}
			return CompletableFuture.<String>completedFuture(null);
		}, exec).thenCompose(f -> f).thenAccept(id -> {});
		try {
			offline.get(8, TimeUnit.SECONDS);
		} catch (Exception e) {
			LOG.warn("Hearthgate: could not mark the Discord message offline: {}", rootMessage(e));
		}
		exec.shutdown();
		if (discord != null) {
			discord.close();
		}
	}
}
