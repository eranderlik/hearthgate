package dev.hearthgate.core.host;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

/**
 * What the sandboxed helper may reach. Because every socket the helper opens
 * is created by the JVM, this is enforced regardless of what the guest code
 * asks for, including addresses taken from a pasted invitation.
 *
 * @param remoteTcpPorts      ports the helper may connect to on public
 *                            addresses: DERP relays and the DERP map over
 *                            HTTPS, and the relays' HTTP captive-portal check
 * @param exactTargets        additional address:port pairs the helper may
 *                            connect to, i.e. the Minecraft server it shares
 * @param allowLoopbackListen whether the helper may listen on loopback, which
 *                            joining needs so Minecraft can connect to it
 * @param allowPrivateRemote  whether TCP to private, link-local, and other
 *                            non-public addresses is allowed (for self-hosted
 *                            relays on a LAN)
 * @param allowUdp            whether the helper may use UDP at all (WireGuard
 *                            and its NAT traversal); see {@link #allowUdpBind}
 *                            and {@link #allowUdpSend} for what it may do with it
 */
public record NetworkPolicy(Set<Integer> remoteTcpPorts, Set<InetSocketAddress> exactTargets,
		boolean allowLoopbackListen, boolean allowPrivateRemote, boolean allowUdp) {

	public static final Set<Integer> DEFAULT_REMOTE_PORTS = Set.of(80, 443);

	public NetworkPolicy {
		remoteTcpPorts = Set.copyOf(remoteTcpPorts);
		exactTargets = Set.copyOf(exactTargets);
	}

	/** Policy for sharing a server listening on loopback port {@code lanPort}. */
	public static NetworkPolicy forHost(int lanPort) {
		return new NetworkPolicy(DEFAULT_REMOTE_PORTS, Set.of(
				new InetSocketAddress(InetAddress.getLoopbackAddress(), lanPort),
				new InetSocketAddress("::1", lanPort)), false, false, true);
	}

	/** Policy for sharing the server at {@code target} (a resolved address). */
	public static NetworkPolicy forHost(InetSocketAddress target) {
		return new NetworkPolicy(DEFAULT_REMOTE_PORTS, Set.of(target), false, false, true);
	}

	/** Policy for joining a shared world. */
	public static NetworkPolicy forJoin() {
		return new NetworkPolicy(DEFAULT_REMOTE_PORTS, Set.of(), true, false, true);
	}

	/** Policy for helper commands that need no network at all. */
	public static NetworkPolicy none() {
		return new NetworkPolicy(Set.of(), Set.of(), false, false, false);
	}

	public NetworkPolicy withPrivateRemote(boolean allow) {
		return new NetworkPolicy(remoteTcpPorts, exactTargets, allowLoopbackListen, allow, allowUdp);
	}

	boolean allowConnect(InetAddress address, int port) {
		if (exactTargets.contains(new InetSocketAddress(address, port))) {
			return true;
		}
		if (address.isLoopbackAddress() || !remoteTcpPorts.contains(port)) {
			return false;
		}
		if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		return allowPrivateRemote || isPublic(address);
	}

	boolean allowListen(InetAddress address) {
		return allowLoopbackListen && address.isLoopbackAddress();
	}

	/**
	 * Where a UDP socket may be bound: the wildcard address (WireGuard must
	 * receive from peers on any interface) or loopback, never one specific
	 * interface.
	 */
	boolean allowUdpBind(InetAddress address) {
		return allowUdp && (address.isAnyLocalAddress() || address.isLoopbackAddress());
	}

	/**
	 * Where a UDP datagram may be sent. WireGuard and its NAT traversal send to
	 * whatever endpoints peers and relays advertise: public addresses, and
	 * private ones when both players are on the same network, on any port. A
	 * peer can't use that to reach this machine's own services or flood the
	 * local network segment, so loopback, unspecified, multicast, broadcast and
	 * link-local destinations are refused.
	 */
	boolean allowUdpSend(InetAddress address) {
		if (!allowUdp || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
				|| address.isLinkLocalAddress()) {
			return false;
		}
		byte[] b = address.getAddress();
		// 0.0.0.0/8 ("this network") and the limited broadcast address.
		return !(b.length == 4 && (b[0] == 0 || (b[0] & b[1] & b[2] & b[3]) == (byte) 0xFF));
	}

	static boolean isPublic(InetAddress a) {
		if (a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress()
				|| a.isAnyLocalAddress() || a.isMulticastAddress()) {
			return false;
		}
		byte[] b = a.getAddress();
		if (b.length == 4) {
			int x = b[0] & 0xFF, y = b[1] & 0xFF;
			// CGNAT 100.64/10 (includes Tailscale's own range) and 0/8.
			return !(x == 100 && y >= 64 && y < 128) && x != 0;
		}
		// IPv6 unique local fc00::/7.
		return (b[0] & 0xFE) != 0xFC;
	}
}
