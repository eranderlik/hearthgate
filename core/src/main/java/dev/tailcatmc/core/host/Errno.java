package dev.tailcatmc.core.host;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Locale;

/** WASI preview 1 errno values, as Go's syscall package on wasip1 defines them. */
final class Errno {
	static final int SUCCESS = 0;
	static final int EACCES = 2;
	static final int EADDRINUSE = 3;
	static final int EADDRNOTAVAIL = 4;
	static final int EAFNOSUPPORT = 5;
	static final int EAGAIN = 6;
	static final int EBADF = 8;
	static final int EBADMSG = 9;
	static final int ECANCELED = 11;
	static final int ECONNABORTED = 13;
	static final int ECONNREFUSED = 14;
	static final int ECONNRESET = 15;
	static final int EHOSTUNREACH = 23;
	static final int EINVAL = 28;
	static final int EIO = 29;
	static final int EMSGSIZE = 35;
	static final int ENETUNREACH = 40;
	static final int ENOENT = 44;
	static final int ENOSYS = 52;
	static final int ENOTCONN = 53;
	static final int ENOTSUP = 58;
	static final int EPERM = 63;
	static final int EPIPE = 64;
	static final int ETIMEDOUT = 73;
	static final int ENOTCAPABLE = 76;

	private Errno() {}

	/** Maps a Java networking failure to the closest errno. */
	static int of(Throwable e) {
		if (e instanceof SocketTimeoutException) return ETIMEDOUT;
		if (e instanceof NoRouteToHostException) return EHOSTUNREACH;
		if (e instanceof PortUnreachableException) return ECONNREFUSED;
		if (e instanceof UnknownHostException || e instanceof UnresolvedAddressException) return EHOSTUNREACH;
		if (e instanceof UnsupportedAddressTypeException) return EAFNOSUPPORT;
		if (e instanceof BindException) {
			return message(e).contains("assign") ? EADDRNOTAVAIL : EADDRINUSE;
		}
		if (e instanceof ConnectException) {
			return message(e).contains("timed out") ? ETIMEDOUT : ECONNREFUSED;
		}
		if (e instanceof AsynchronousCloseException) return ECANCELED;
		if (e instanceof ClosedChannelException) return EBADF;
		if (e instanceof SecurityException) return EACCES;
		if (e instanceof IOException) {
			String m = message(e);
			if (m.contains("reset")) return ECONNRESET;
			if (m.contains("broken pipe")) return EPIPE;
			if (m.contains("network is unreachable")) return ENETUNREACH;
			if (m.contains("no route")) return EHOSTUNREACH;
			if (m.contains("not connected")) return ENOTCONN;
			if (m.contains("protocol family") || m.contains("address family")) return EAFNOSUPPORT;
			if (m.contains("message too long")) return EMSGSIZE;
			if (m.contains("permission") || m.contains("not permitted")) return EPERM;
		}
		return EIO;
	}

	private static String message(Throwable e) {
		return e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
	}
}
