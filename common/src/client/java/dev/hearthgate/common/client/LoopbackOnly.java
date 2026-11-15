package dev.hearthgate.common.client;

import io.netty.channel.ChannelFuture;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.server.network.ServerConnectionListener;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keeps a world shared with Hearthgate reachable only from this machine.
 *
 * <p>Sharing needs the integrated server to be published, and vanilla's
 * <em>Open to LAN</em> listens on every network interface and announces the
 * world on the LAN, which would let anyone on the same network join without an
 * invitation. The helper only connects over loopback, so after publishing, the
 * all-interfaces listener is replaced with a loopback one on the same port and
 * the LAN announcement is stopped.
 *
 * <p>Vanilla has no API for either, so this reads two private fields, which
 * are the same in every supported version. Every loader exposes Minecraft as
 * an open (automatic) module, so deep reflection is allowed. If the fields
 * can't be bound, {@link #available()} is false and sharing refuses to start
 * rather than publish the world on the network.
 */
final class LoopbackOnly {
	private static final VarHandle CHANNELS; // ServerConnectionListener.channels
	private static final VarHandle LAN_PINGER; // IntegratedServer.lanPinger
	private static final String UNAVAILABLE;

	static {
		VarHandle channels = null, pinger = null;
		String unavailable = null;
		try {
			channels = MethodHandles.privateLookupIn(ServerConnectionListener.class, MethodHandles.lookup())
					.findVarHandle(ServerConnectionListener.class, "channels", List.class);
			pinger = MethodHandles.privateLookupIn(IntegratedServer.class, MethodHandles.lookup())
					.findVarHandle(IntegratedServer.class, "lanPinger", LanServerPinger.class);
		} catch (ReflectiveOperationException | RuntimeException e) {
			unavailable = e.toString();
			channels = pinger = null;
		}
		CHANNELS = channels;
		LAN_PINGER = pinger;
		UNAVAILABLE = unavailable;
	}

	private LoopbackOnly() {}

	/** Whether {@link #restrict} can work on this Minecraft version; null if so, else why not. */
	static String unavailableReason() {
		return UNAVAILABLE;
	}

	static boolean available() {
		return UNAVAILABLE == null;
	}

	/**
	 * Replaces the all-interfaces game listener on {@code port}, which
	 * publishing just opened, with one on loopback, and stops the LAN
	 * announcement. Call on the client thread right after publishing.
	 *
	 * @throws IOException if the loopback listener can't be opened; the world
	 *                     then has no network listener at all (it fails closed)
	 */
	@SuppressWarnings("unchecked")
	static void restrict(IntegratedServer server, int port) throws IOException {
		if (!available()) {
			throw new IOException("can't restrict the world to loopback: " + UNAVAILABLE);
		}
		Thread pinger = (Thread) LAN_PINGER.get(server);
		if (pinger != null) {
			pinger.interrupt();
			LAN_PINGER.set(server, null);
		}

		ServerConnectionListener listener = server.getConnection();
		List<ChannelFuture> channels = (List<ChannelFuture>) CHANNELS.get(listener);
		List<ChannelFuture> exposed = new ArrayList<>();
		synchronized (channels) {
			for (Iterator<ChannelFuture> it = channels.iterator(); it.hasNext();) {
				ChannelFuture f = it.next();
				if (f.channel().localAddress() instanceof InetSocketAddress a && a.getPort() == port
						&& !a.getAddress().isLoopbackAddress()) {
					it.remove();
					exposed.add(f);
				}
			}
		}
		if (exposed.isEmpty()) {
			throw new IOException("no network listener found on port " + port);
		}
		for (ChannelFuture f : exposed) {
			f.channel().close().syncUninterruptibly();
		}
		try {
			// 127.0.0.1, not getLoopbackAddress(), which may be ::1: the helper dials 127.0.0.1.
			listener.startTcpServerListener(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port);
		} catch (Exception e) { // Netty rethrows bind failures undeclared
			throw new IOException("could not listen on loopback port " + port + ": " + e, e);
		}
	}
}
