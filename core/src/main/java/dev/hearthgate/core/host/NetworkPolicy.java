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
 */
public record NetworkPolicy(Set<Integer> remoteTcpPorts, Set<InetSocketAddress> exactTargets,
		boolean allowLoopbackListen, boolean allowPrivateRemote) {

	public static final Set<Integer> DEFAULT_REMOTE_PORTS = Set.of(80, 443);

	public NetworkPolicy {
		remoteTcpPorts = Set.copyOf(remoteTcpPorts);
		exactTargets = Set.copyOf(exactTargets);
	}

	/** Policy for sharing a server listening on loopback port {@code lanPort}. */
	public static NetworkPolicy forHost(int lanPort) {
		return new NetworkPolicy(DEFAULT_REMOTE_PORTS, Set.of(
				new InetSocketAddress(InetAddress.getLoopbackAddress(), lanPort),
				new InetSocketAddress("::1", lanPort)), false, false);
	}

	/** Policy for sharing the server at {@code target} (a resolved address). */
	public static NetworkPolicy forHost(InetSocketAddress target) {
		return new NetworkPolicy(DEFAULT_REMOTE_PORTS, Set.of(target), false, false);
	}

	/** Policy for joining a shared world. */
	public static NetworkPolicy forJoin() {
		return new NetworkPolicy(DEFAULT_REMOTE_PORTS, Set.of(), true, false);
	}

	/** Policy for helper commands that need no network at all. */
	public static NetworkPolicy none() {
		return new NetworkPolicy(Set.of(), Set.of(), false, false);
	}

	public NetworkPolicy withPrivateRemote(boolean allow) {
		return new NetworkPolicy(remoteTcpPorts, exactTargets, allowLoopbackListen, allow);
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
