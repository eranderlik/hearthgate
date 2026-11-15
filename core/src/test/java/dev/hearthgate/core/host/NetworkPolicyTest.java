package dev.hearthgate.core.host;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPolicyTest {
	private static InetAddress ip(String s) throws Exception {
		return InetAddress.getByName(s);
	}

	@Test
	void hostMayReachOnlyItsLanPortOnLoopback() throws Exception {
		NetworkPolicy p = NetworkPolicy.forHost(41234);
		assertTrue(p.allowConnect(ip("127.0.0.1"), 41234));
		assertFalse(p.allowConnect(ip("127.0.0.1"), 22));
		assertFalse(p.allowConnect(ip("::1"), 8080));
		assertFalse(p.allowListen(ip("127.0.0.1")));
	}

	@Test
	void hostMayReachExactlyItsConfiguredTarget() throws Exception {
		NetworkPolicy p = NetworkPolicy.forHost(new java.net.InetSocketAddress(ip("192.168.1.20"), 25565));
		assertTrue(p.allowConnect(ip("192.168.1.20"), 25565));
		assertFalse(p.allowConnect(ip("192.168.1.20"), 22));
		assertFalse(p.allowConnect(ip("192.168.1.21"), 25565));
		assertFalse(p.allowConnect(ip("127.0.0.1"), 25565));
	}

	@Test
	void remoteOnlyPublicAddressesOnWebPorts() throws Exception {
		NetworkPolicy p = NetworkPolicy.forJoin();
		assertTrue(p.allowConnect(ip("203.0.113.5"), 443));
		assertTrue(p.allowConnect(ip("2001:db8::1"), 80));
		assertFalse(p.allowConnect(ip("203.0.113.5"), 25565));
		for (String priv : new String[] {"10.0.0.1", "192.168.1.1", "172.16.0.1", "169.254.1.1", "100.64.0.1",
				"fd7a:115c:a1e0::1", "fe80::1", "0.0.0.0", "224.0.0.1"}) {
			assertFalse(p.allowConnect(ip(priv), 443), priv);
		}
		assertTrue(p.withPrivateRemote(true).allowConnect(ip("192.168.1.1"), 443));
	}

	@Test
	void udpGoesToPeersButNotThisMachineOrTheLocalSegment() throws Exception {
		for (NetworkPolicy p : new NetworkPolicy[] {NetworkPolicy.forJoin(), NetworkPolicy.forHost(41234)}) {
			// Peer endpoints: public, and private for players on the same network.
			for (String ok : new String[] {"203.0.113.5", "2001:db8::1", "192.168.1.20", "10.1.2.3", "100.64.0.1",
					"fd7a:115c:a1e0::1"}) {
				assertTrue(p.allowUdpSend(ip(ok)), ok);
			}
			for (String no : new String[] {"127.0.0.1", "127.1.2.3", "::ffff:127.0.0.1", "::1", "0.0.0.0", "0.1.2.3", "::",
					"255.255.255.255", "224.0.0.251", "239.255.255.250", "ff02::1", "169.254.169.254", "fe80::1"}) {
				assertFalse(p.allowUdpSend(ip(no)), no);
			}
			assertTrue(p.allowUdpBind(ip("0.0.0.0")));
			assertTrue(p.allowUdpBind(ip("::")));
			assertTrue(p.allowUdpBind(ip("127.0.0.1")));
			assertFalse(p.allowUdpBind(ip("192.168.1.10")));
		}
		NetworkPolicy none = NetworkPolicy.none();
		assertFalse(none.allowUdpBind(ip("0.0.0.0")));
		assertFalse(none.allowUdpSend(ip("203.0.113.5")));
	}

	@Test
	void joinMayListenOnLoopbackOnly() throws Exception {
		NetworkPolicy p = NetworkPolicy.forJoin();
		assertTrue(p.allowListen(ip("127.0.0.1")));
		assertFalse(p.allowListen(ip("0.0.0.0")));
		assertFalse(p.allowListen(ip("192.168.1.10")));
	}
}
