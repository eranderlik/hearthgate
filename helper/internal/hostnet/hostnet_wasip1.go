//go:build wasip1

// Package hostnet supplies the helper's sockets from the embedding JVM.
//
// WASI preview 1 cannot create sockets, so the Java host exposes them through
// the "hearthgate" import module. Each host socket is also a WASI file descriptor
// whose fdstat reports FDFLAG_NONBLOCK. That makes os.NewFile register it with
// the Go runtime's wasip1 netpoller, so a goroutine waiting on a socket parks
// and the runtime blocks in poll_oneoff, which the host services with a Java
// NIO selector. Host socket calls never block; they return EAGAIN instead,
// and syscall.RawConn retries them when the netpoller reports readiness.
//
// Install routes Tailscale's socket creation (netns), interface enumeration
// (netmon), and net/http's default transport to these sockets.
package hostnet

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
)

// Host imports. All return a WASI errno (0 on success). Addresses passed out
// of the host use the 20-byte wireAddr layout.

//go:wasmimport hearthgate tcp_connect
func hostTCPConnect(network unsafe.Pointer, networkLen uint32, addr unsafe.Pointer, addrLen uint32, outFD unsafe.Pointer) uint32

//go:wasmimport hearthgate tcp_connect_status
func hostTCPConnectStatus(fd int32) uint32

//go:wasmimport hearthgate tcp_listen
func hostTCPListen(addr unsafe.Pointer, addrLen uint32, outFD unsafe.Pointer) uint32

//go:wasmimport hearthgate tcp_accept
func hostTCPAccept(fd int32, outFD unsafe.Pointer) uint32

//go:wasmimport hearthgate sock_recv
func hostRecv(fd int32, buf unsafe.Pointer, bufLen uint32, outN unsafe.Pointer) uint32

//go:wasmimport hearthgate sock_send
func hostSend(fd int32, buf unsafe.Pointer, bufLen uint32, outN unsafe.Pointer) uint32

//go:wasmimport hearthgate sock_shutdown_write
func hostShutdownWrite(fd int32) uint32

//go:wasmimport hearthgate udp_bind
func hostUDPBind(network unsafe.Pointer, networkLen uint32, addr unsafe.Pointer, addrLen uint32, outFD unsafe.Pointer) uint32

//go:wasmimport hearthgate udp_recvfrom
func hostUDPRecvFrom(fd int32, buf unsafe.Pointer, bufLen uint32, outN unsafe.Pointer, outAddr unsafe.Pointer) uint32

//go:wasmimport hearthgate udp_sendto
func hostUDPSendTo(fd int32, buf unsafe.Pointer, bufLen uint32, addr unsafe.Pointer) uint32

//go:wasmimport hearthgate sock_addr
func hostSockAddr(fd int32, remote uint32, outAddr unsafe.Pointer) uint32

//go:wasmimport hearthgate interfaces
func hostInterfaces(buf unsafe.Pointer, bufLen uint32, outN unsafe.Pointer) uint32

// wireAddr is the host's socket address encoding: family (4 or 6), one
// padding byte, big-endian port, then 16 address bytes (IPv4 uses the first 4).
type wireAddr [20]byte

func (w *wireAddr) addrPort() netip.AddrPort {
	port := binary.BigEndian.Uint16(w[2:4])
	switch w[0] {
	case 4:
		return netip.AddrPortFrom(netip.AddrFrom4([4]byte(w[4:8])), port)
	case 6:
		return netip.AddrPortFrom(netip.AddrFrom16([16]byte(w[4:20])), port)
	}
	return netip.AddrPort{}
}

func toWire(ap netip.AddrPort) (w wireAddr) {
	a := ap.Addr()
	if a.Is4() {
		w[0] = 4
		b := a.As4()
		copy(w[4:], b[:])
	} else {
		w[0] = 6
		b := a.As16()
		copy(w[4:], b[:])
	}
	binary.BigEndian.PutUint16(w[2:4], ap.Port())
	return w
}

func errnoOf(e uint32) error {
	if e == 0 {
		return nil
	}
	return syscall.Errno(e)
}

func strPtr(s string) (unsafe.Pointer, uint32) {
	if s == "" {
		return nil, 0
	}
	return unsafe.Pointer(unsafe.StringData(s)), uint32(len(s))
}

func bufPtr(b []byte) unsafe.Pointer {
	if len(b) == 0 {
		return nil
	}
	return unsafe.Pointer(&b[0])
}

// hostFile wraps a host socket descriptor in an *os.File so that the Go
// runtime polls it.
type hostFile struct {
	fd int32
	f  *os.File
	rc syscall.RawConn
}

func newHostFile(fd int32, name string) (*hostFile, error) {
	f := os.NewFile(uintptr(fd), name)
	if f == nil {
		return nil, fmt.Errorf("hostnet: invalid descriptor %d", fd)
	}
	rc, err := f.SyscallConn()
	if err != nil {
		f.Close()
		return nil, err
	}
	return &hostFile{fd: fd, f: f, rc: rc}, nil
}

func (h *hostFile) addr(remote bool) netip.AddrPort {
	var w wireAddr
	r := uint32(0)
	if remote {
		r = 1
	}
	if hostSockAddr(h.fd, r, unsafe.Pointer(&w)) != 0 {
		return netip.AddrPort{}
	}
	return w.addrPort()
}

// readOp runs op until it stops returning EAGAIN, parking on read readiness.
func (h *hostFile) readOp(op func() uint32) error {
	var errno uint32
	if err := h.rc.Read(func(uintptr) bool {
		errno = op()
		return errno != uint32(syscall.EAGAIN)
	}); err != nil {
		return err
	}
	return errnoOf(errno)
}

func (h *hostFile) writeOp(op func() uint32) error {
	var errno uint32
	if err := h.rc.Write(func(uintptr) bool {
		errno = op()
		return errno != uint32(syscall.EAGAIN)
	}); err != nil {
		return err
	}
	return errnoOf(errno)
}

// fixErr converts os.File errors into the net package's equivalents, which
// is what Tailscale and net/http test for.
func fixErr(op, network string, local, remote net.Addr, err error) error {
	if err == nil || err == io.EOF {
		return err
	}
	if errors.Is(err, fs.ErrClosed) {
		err = net.ErrClosed
	}
	if errors.Is(err, os.ErrDeadlineExceeded) {
		err = os.ErrDeadlineExceeded
	}
	return &net.OpError{Op: op, Net: network, Source: local, Addr: remote, Err: err}
}

// tcpConn is a host TCP stream.
type tcpConn struct {
	h             *hostFile
	local, remote *net.TCPAddr
}

func newTCPConn(h *hostFile) *tcpConn {
	return &tcpConn{
		h:      h,
		local:  net.TCPAddrFromAddrPort(h.addr(false)),
		remote: net.TCPAddrFromAddrPort(h.addr(true)),
	}
}

func (c *tcpConn) Read(b []byte) (int, error) {
	if len(b) == 0 {
		return 0, nil
	}
	var n uint32
	err := c.h.readOp(func() uint32 {
		return hostRecv(c.h.fd, bufPtr(b), uint32(len(b)), unsafe.Pointer(&n))
	})
	if err != nil {
		return 0, fixErr("read", "tcp", c.local, c.remote, err)
	}
	if n == 0 {
		return 0, io.EOF
	}
	return int(n), nil
}

func (c *tcpConn) Write(b []byte) (int, error) {
	written := 0
	for written < len(b) {
		var n uint32
		rest := b[written:]
		err := c.h.writeOp(func() uint32 {
			return hostSend(c.h.fd, bufPtr(rest), uint32(len(rest)), unsafe.Pointer(&n))
		})
		written += int(n)
		if err != nil {
			return written, fixErr("write", "tcp", c.local, c.remote, err)
		}
	}
	return written, nil
}

func (c *tcpConn) CloseWrite() error {
	return fixErr("shutdown", "tcp", c.local, c.remote, errnoOf(hostShutdownWrite(c.h.fd)))
}

func (c *tcpConn) CloseRead() error { return nil }

func (c *tcpConn) Close() error                       { return c.h.f.Close() }
func (c *tcpConn) LocalAddr() net.Addr                { return c.local }
func (c *tcpConn) RemoteAddr() net.Addr               { return c.remote }
func (c *tcpConn) SetDeadline(t time.Time) error      { return c.h.f.SetDeadline(t) }
func (c *tcpConn) SetReadDeadline(t time.Time) error  { return c.h.f.SetReadDeadline(t) }
func (c *tcpConn) SetWriteDeadline(t time.Time) error { return c.h.f.SetWriteDeadline(t) }

// DialTCP connects to addr, a "host:port" string. The host resolves names.
func DialTCP(ctx context.Context, addr string) (net.Conn, error) {
	return dial(ctx, "tcp", addr)
}

func dial(ctx context.Context, network, addr string) (net.Conn, error) {
	switch network {
	case "tcp", "tcp4", "tcp6":
	default:
		return nil, &net.OpError{Op: "dial", Net: network, Err: net.UnknownNetworkError(network)}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	np, nl := strPtr(network)
	ap, al := strPtr(addr)
	var fd int32
	if err := errnoOf(hostTCPConnect(np, nl, ap, al, unsafe.Pointer(&fd))); err != nil {
		return nil, &net.OpError{Op: "dial", Net: network, Err: fmt.Errorf("%s: %w", addr, err)}
	}
	h, err := newHostFile(fd, "tcp")
	if err != nil {
		return nil, err
	}
	// Connection setup happens on a host thread; wait for it to report
	// completion through write readiness, as with a nonblocking connect(2).
	stop := context.AfterFunc(ctx, func() { h.f.SetWriteDeadline(time.Unix(1, 0)) })
	err = h.writeOp(func() uint32 { return hostTCPConnectStatus(fd) })
	if !stop() && ctx.Err() != nil {
		h.f.Close()
		return nil, &net.OpError{Op: "dial", Net: network, Err: ctx.Err()}
	}
	if err != nil {
		h.f.Close()
		return nil, &net.OpError{Op: "dial", Net: network, Err: fmt.Errorf("%s: %w", addr, err)}
	}
	h.f.SetWriteDeadline(time.Time{})
	return newTCPConn(h), nil
}

type tcpListener struct {
	h    *hostFile
	addr *net.TCPAddr
}

// ListenTCP listens on a loopback TCP address. The host refuses others.
func ListenTCP(addr string) (net.Listener, error) {
	ap, al := strPtr(addr)
	var fd int32
	if err := errnoOf(hostTCPListen(ap, al, unsafe.Pointer(&fd))); err != nil {
		return nil, &net.OpError{Op: "listen", Net: "tcp", Err: fmt.Errorf("%s: %w", addr, err)}
	}
	h, err := newHostFile(fd, "tcp-listener")
	if err != nil {
		return nil, err
	}
	return &tcpListener{h: h, addr: net.TCPAddrFromAddrPort(h.addr(false))}, nil
}

func (l *tcpListener) Accept() (net.Conn, error) {
	var fd int32
	err := l.h.readOp(func() uint32 { return hostTCPAccept(l.h.fd, unsafe.Pointer(&fd)) })
	if err != nil {
		return nil, fixErr("accept", "tcp", l.addr, nil, err)
	}
	h, err := newHostFile(fd, "tcp")
	if err != nil {
		return nil, err
	}
	return newTCPConn(h), nil
}

func (l *tcpListener) Close() error   { return l.h.f.Close() }
func (l *tcpListener) Addr() net.Addr { return l.addr }

// udpConn is a host UDP socket. It implements net.PacketConn and the
// netip-based methods Tailscale's magicsock requires (nettype.PacketConn).
type udpConn struct {
	h     *hostFile
	local *net.UDPAddr
}

func (c *udpConn) ReadFromUDPAddrPort(b []byte) (int, netip.AddrPort, error) {
	var n uint32
	var w wireAddr
	err := c.h.readOp(func() uint32 {
		return hostUDPRecvFrom(c.h.fd, bufPtr(b), uint32(len(b)), unsafe.Pointer(&n), unsafe.Pointer(&w))
	})
	if err != nil {
		return 0, netip.AddrPort{}, fixErr("read", "udp", c.local, nil, err)
	}
	return int(n), w.addrPort(), nil
}

func (c *udpConn) WriteToUDPAddrPort(b []byte, to netip.AddrPort) (int, error) {
	w := toWire(to)
	err := c.h.writeOp(func() uint32 {
		return hostUDPSendTo(c.h.fd, bufPtr(b), uint32(len(b)), unsafe.Pointer(&w))
	})
	if err != nil {
		return 0, fixErr("write", "udp", c.local, net.UDPAddrFromAddrPort(to), err)
	}
	return len(b), nil
}

func (c *udpConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, ap, err := c.ReadFromUDPAddrPort(b)
	if err != nil {
		return n, nil, err
	}
	return n, net.UDPAddrFromAddrPort(ap), nil
}

func (c *udpConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	ua, ok := addr.(*net.UDPAddr)
	if !ok {
		return 0, &net.OpError{Op: "write", Net: "udp", Err: syscall.EINVAL}
	}
	return c.WriteToUDPAddrPort(b, ua.AddrPort())
}

func (c *udpConn) Close() error                       { return c.h.f.Close() }
func (c *udpConn) LocalAddr() net.Addr                { return c.local }
func (c *udpConn) SetDeadline(t time.Time) error      { return c.h.f.SetDeadline(t) }
func (c *udpConn) SetReadDeadline(t time.Time) error  { return c.h.f.SetReadDeadline(t) }
func (c *udpConn) SetWriteDeadline(t time.Time) error { return c.h.f.SetWriteDeadline(t) }

func listenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	switch network {
	case "udp", "udp4", "udp6":
	default:
		return nil, &net.OpError{Op: "listen", Net: network, Err: net.UnknownNetworkError(network)}
	}
	np, nl := strPtr(network)
	ap, al := strPtr(address)
	var fd int32
	if err := errnoOf(hostUDPBind(np, nl, ap, al, unsafe.Pointer(&fd))); err != nil {
		return nil, &net.OpError{Op: "listen", Net: network, Err: fmt.Errorf("%s: %w", address, err)}
	}
	h, err := newHostFile(fd, "udp")
	if err != nil {
		return nil, err
	}
	return &udpConn{h: h, local: net.UDPAddrFromAddrPort(h.addr(false))}, nil
}

// hostNetwork implements netns.HostNetwork.
type hostNetwork struct{}

func (hostNetwork) ListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	return listenPacket(ctx, network, address)
}

func (hostNetwork) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return dial(ctx, network, address)
}

func (hostNetwork) Dial(network, address string) (net.Conn, error) {
	return dial(context.Background(), network, address)
}

// hostInterface is one entry of the host's interfaces JSON.
type hostInterface struct {
	Name     string   `json:"name"`
	Index    int      `json:"index"`
	MTU      int      `json:"mtu"`
	Up       bool     `json:"up"`
	Loopback bool     `json:"loopback"`
	P2P      bool     `json:"p2p"`
	Mcast    bool     `json:"multicast"`
	Addrs    []string `json:"addrs"` // CIDR prefixes
}

func interfaces() ([]netmon.Interface, error) {
	buf := make([]byte, 16<<10)
	var n uint32
	if err := errnoOf(hostInterfaces(bufPtr(buf), uint32(len(buf)), unsafe.Pointer(&n))); err != nil {
		return nil, fmt.Errorf("host interfaces: %w", err)
	}
	var his []hostInterface
	if err := json.Unmarshal(buf[:n], &his); err != nil {
		return nil, fmt.Errorf("host interfaces: %w", err)
	}
	ret := make([]netmon.Interface, 0, len(his))
	for _, hi := range his {
		ni := &net.Interface{Index: hi.Index, MTU: hi.MTU, Name: hi.Name}
		if hi.Up {
			ni.Flags |= net.FlagUp | net.FlagRunning
		}
		if hi.Loopback {
			ni.Flags |= net.FlagLoopback
		}
		if hi.P2P {
			ni.Flags |= net.FlagPointToPoint
		}
		if hi.Mcast {
			ni.Flags |= net.FlagMulticast
		}
		addrs := []net.Addr{}
		for _, s := range hi.Addrs {
			p, err := netip.ParsePrefix(s)
			if err != nil {
				continue
			}
			addrs = append(addrs, &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
		}
		ret = append(ret, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	return ret, nil
}

var errNoResolver = errors.New("hostnet: DNS lookups are performed by the host; dial by name instead")

// Install routes all of the process's networking through the host.
func Install(logf func(string, ...any)) {
	netns.SetHostNetwork(hostNetwork{})
	netmon.RegisterInterfaceGetter(interfaces)
	if t, ok := http.DefaultTransport.(*http.Transport); ok {
		t.DialContext = dial
		t.Proxy = nil
	}
	// The host resolves names passed to DialTCP. Make any other lookup fail
	// fast and visibly instead of reaching the net package's fake network.
	net.DefaultResolver.PreferGo = true
	net.DefaultResolver.Dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		if strings.HasPrefix(network, "tcp") || strings.HasPrefix(network, "udp") {
			logf("hostnet: unexpected DNS lookup via %s %s", network, address)
		}
		return nil, errNoResolver
	}
}
