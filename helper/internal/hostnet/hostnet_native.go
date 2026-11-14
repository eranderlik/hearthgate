//go:build !wasip1

// Package hostnet supplies the helper's sockets.
//
// On native builds it is a thin layer over the net package. On GOOS=wasip1 the
// JVM that embeds the helper provides every socket through host functions (see
// hostnet_wasip1.go), and Install routes Tailscale's socket creation there.
package hostnet

import (
	"context"
	"net"
)

// Install prepares the process's networking. It is a no-op on native builds.
func Install(logf func(string, ...any)) {}

// ListenTCP listens on a local TCP address such as "127.0.0.1:0".
func ListenTCP(addr string) (net.Listener, error) {
	return net.Listen("tcp", addr)
}

// DialTCP connects to a TCP address.
func DialTCP(ctx context.Context, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, "tcp", addr)
}
