// Package app implements the helper's commands and its JSON event protocol.
//
// It is shared by cmd/tcmc-helper (the WebAssembly build the mod runs on
// every platform, also buildable as a native executable for testing) and
// cmd/libtcmc (the optional native library dedicated servers can load for
// more throughput).
//
// Commands:
//
//	host --target IP:PORT [--mc VERSION] [--key K --psk P [--region N]] [--verbose]
//	join --invite tcmc1_... [--verbose]
//	keygen
//
// Without --key, a host gets a fresh identity and so a new invitation every
// run. A dedicated server passes a persisted --key and --psk (from keygen)
// and the --region reported by its first ready event, which makes the
// invitation stable across restarts until the server rotates its keys.
package app

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/netip"
	"runtime/debug"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/wgengine/filter"

	"tailcatmc/helper/internal/hostnet"
	"tailcatmc/helper/internal/invite"
)

const (
	// virtualPort is the only port served inside the tunnel.
	virtualPort = 25565
	// maxConnections bounds concurrent tunnelled streams per session. Each
	// Minecraft connection (including server-list pings) is one stream.
	maxConnections = 64
	// statusInterval is how often the host reports peer path changes.
	statusInterval = 2 * time.Second
)

// Run executes the command in args (without argv[0]) until it finishes or
// ctx is cancelled, then writes a final "stopped" event. Each event is one
// JSON object terminated by a newline, written with a single Write call;
// diagnostics go to logf.
//
// Run recovers panics in the goroutines it owns and never exits the process:
// in the native library, the process is the Minecraft server.
func Run(ctx context.Context, events io.Writer, logf func(string, ...any), args []string) {
	out := &eventWriter{enc: json.NewEncoder(events), logf: logf}
	err := func() (err error) {
		defer func() {
			if p := recover(); p != nil {
				err = fmt.Errorf("internal error: %v", p)
				logf("panic: %v\n%s", p, debug.Stack())
			}
		}()
		return run(ctx, out, args)
	}()
	if err != nil && !errors.Is(err, context.Canceled) {
		code := "helper_failed"
		var ce *codedError
		if errors.As(err, &ce) {
			code = ce.code
		}
		out.send(event{Type: "error", Code: code, Message: err.Error()})
	}
	out.send(event{Type: "stopped"})
}

// event is one line of the protocol. Fields are omitted when empty.
type event struct {
	Type    string `json:"type"`                    // ready, peers, error, stopped, keys
	Mode    string `json:"mode,omitempty"`          // ready: host or join
	Invite  string `json:"invite,omitempty"`        // ready/host: secret invitation
	Region  int    `json:"region,omitempty"`        // ready/host: DERP region in the invitation
	PrivKey string `json:"private_key,omitempty"`   // keys: generated node key
	PSK     string `json:"preshared_key,omitempty"` // keys: generated pre-shared key
	Address string `json:"address,omitempty"`       // ready/join: loopback address to connect to
	Code    string `json:"code,omitempty"`          // error: stable machine-readable code
	Message string `json:"message,omitempty"`       // error: human-readable detail
	Peers   []peer `json:"peers,omitempty"`         // peers: connected players' paths
}

type peer struct {
	ID     string `json:"id"`     // short, non-secret peer identifier
	Direct bool   `json:"direct"` // true when traffic flows peer-to-peer
	Relay  string `json:"relay"`  // DERP region used when not direct
}

type eventWriter struct {
	mu   sync.Mutex
	enc  *json.Encoder
	logf func(string, ...any)
}

func (w *eventWriter) send(e event) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.enc.Encode(e); err != nil {
		w.logf("write event: %v", err)
	}
}

// tailcatLogf is the logger handed to Tailcat, which is chatty.
func (w *eventWriter) tailcatLogf(verbose bool) func(string, ...any) {
	if verbose {
		return w.logf
	}
	return func(string, ...any) {}
}

// recoverPanic, deferred in goroutines the helper starts, logs a panic
// instead of letting it end the process.
func (w *eventWriter) recoverPanic() {
	if p := recover(); p != nil {
		w.logf("panic: %v\n%s", p, debug.Stack())
	}
}

// codedError carries a stable error code for the JVM side.
type codedError struct {
	code string
	err  error
}

func (e *codedError) Error() string { return e.err.Error() }
func (e *codedError) Unwrap() error { return e.err }

func coded(code string, err error) error { return &codedError{code, err} }

func run(ctx context.Context, out *eventWriter, args []string) error {
	if len(args) < 1 {
		return coded("usage", errors.New("expected host or join command"))
	}
	switch args[0] {
	case "host":
		return runHost(ctx, out, args[1:])
	case "keygen":
		return runKeygen(out)
	case "join":
		return runJoin(ctx, out, args[1:])
	default:
		return coded("usage", fmt.Errorf("unknown command %q", args[0]))
	}
}

func runHost(ctx context.Context, out *eventWriter, args []string) error {
	fs := flag.NewFlagSet("host", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	target := fs.String("target", "", "loopback address of the integrated Minecraft server")
	mcVersion := fs.String("mc", "", "Minecraft version, recorded in the invitation")
	verbose := fs.Bool("verbose", false, "log Tailcat's networking diagnostics")
	keyText := fs.String("key", "", "persistent node private key (from keygen)")
	pskText := fs.String("psk", "", "persistent pre-shared key (from keygen)")
	region := fs.Int("region", 0, "DERP region to use (0 picks the nearest)")
	if err := fs.Parse(args); err != nil {
		return coded("usage", err)
	}
	if err := validateTarget(*target); err != nil {
		return coded("usage", err)
	}
	if (*keyText == "") != (*pskText == "") {
		return coded("usage", errors.New("--key and --psk must be given together"))
	}

	srv := &tailcat.Server{
		Logf:           out.tailcatLogf(*verbose),
		ServedTCPPorts: []filter.PortRange{{First: virtualPort, Last: virtualPort}},
		RegionID:       tailcfg.DERPRegionID(*region),
	}
	if *keyText != "" {
		if err := srv.Key.UnmarshalText([]byte(*keyText)); err != nil || srv.Key.IsZero() {
			return coded("bad_key", errors.New("invalid --key"))
		}
		if err := srv.PresharedKey.UnmarshalText([]byte(*pskText)); err != nil || srv.PresharedKey.IsZero() {
			return coded("bad_key", errors.New("invalid --psk"))
		}
	}
	defer srv.Close()

	startCtx, cancelStart := context.WithTimeout(ctx, 30*time.Second)
	ln, err := srv.Listen(startCtx, "tcp", fmt.Sprintf(":%d", virtualPort))
	cancelStart()
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return coded("relay_unreachable", fmt.Errorf("start Tailcat server: %w", err))
	}
	go func() {
		<-ctx.Done()
		ln.Close()
	}()

	encoded, err := invite.New(string(srv.TailcatAddr()), *mcVersion).Encode()
	if err != nil {
		return err
	}
	ready := event{Type: "ready", Mode: "host", Invite: encoded}
	if ci, err := tailcat.ParseAddr(srv.TailcatAddr()); err == nil {
		ready.Region = int(ci.RegionID)
	}
	out.send(ready)
	go func() {
		defer out.recoverPanic()
		reportPeers(ctx, out, srv)
	}()

	sem := make(chan struct{}, maxConnections)
	var wg sync.WaitGroup
	defer wg.Wait()
	for {
		tunnel, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fmt.Errorf("accept: %w", err)
		}
		select {
		case sem <- struct{}{}:
		default:
			out.logf("connection limit reached; refusing stream")
			tunnel.Close()
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			defer tunnel.Close()
			defer out.recoverPanic()
			dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			backend, err := hostnet.DialTCP(dialCtx, *target)
			cancel()
			if err != nil {
				out.logf("dial Minecraft server: %v", err)
				return
			}
			tailcat.ProxyConns(tunnel, backend)
		}()
	}
}

func runJoin(ctx context.Context, out *eventWriter, args []string) error {
	fs := flag.NewFlagSet("join", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	encoded := fs.String("invite", "", "invitation from the host")
	verbose := fs.Bool("verbose", false, "log Tailcat's networking diagnostics")
	if err := fs.Parse(args); err != nil {
		return coded("usage", err)
	}
	inv, err := invite.Decode(*encoded)
	if err != nil {
		return coded("invalid_invite", err)
	}
	if _, err := tailcat.ParseAddr(tailcat.Addr(inv.Tailcat)); err != nil {
		return coded("invalid_invite", fmt.Errorf("invitation's Tailcat address: %w", err))
	}
	client := &tailcat.Client{Server: tailcat.Addr(inv.Tailcat), Logf: out.tailcatLogf(*verbose)}
	defer client.Close()

	// Establish the tunnel before handing Minecraft an address, so that a bad
	// or expired invitation fails on the join screen instead of as a vague
	// connection timeout.
	probeCtx, cancelProbe := context.WithTimeout(ctx, 30*time.Second)
	probe, err := client.DialTCPPort(probeCtx, virtualPort)
	cancelProbe()
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return coded("host_unreachable", fmt.Errorf("reach host: %w", err))
	}
	probe.Close()

	ln, err := hostnet.ListenTCP("127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("listen on loopback: %w", err)
	}
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	out.send(event{Type: "ready", Mode: "join", Address: ln.Addr().String()})

	sem := make(chan struct{}, maxConnections)
	var wg sync.WaitGroup
	defer wg.Wait()
	for {
		local, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fmt.Errorf("accept local: %w", err)
		}
		select {
		case sem <- struct{}{}:
		default:
			local.Close()
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			defer local.Close()
			defer out.recoverPanic()
			dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
			tunnel, err := client.DialTCPPort(dialCtx, virtualPort)
			cancel()
			if err != nil {
				out.logf("dial host: %v", err)
				return
			}
			tailcat.ProxyConns(local, tunnel)
		}()
	}
}

// reportPeers emits a peers event whenever the set of connected peers or
// their paths change, so the game can show whether play is direct or relayed.
func reportPeers(ctx context.Context, out *eventWriter, srv *tailcat.Server) {
	t := time.NewTicker(statusInterval)
	defer t.Stop()
	last := "[]" // don't report an empty set at startup
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		st := srv.Status()
		if st == nil {
			continue
		}
		peers := []peer{}
		for k, p := range st.Peer {
			if !p.Online && p.CurAddr == "" && p.Relay == "" {
				continue
			}
			peers = append(peers, peer{ID: k.ShortString(), Direct: p.CurAddr != "", Relay: p.Relay})
		}
		b, _ := json.Marshal(peers)
		if string(b) == last {
			continue
		}
		last = string(b)
		out.send(event{Type: "peers", Peers: peers})
	}
}

// validateTarget checks the Minecraft server address. It is numeric so the
// host's network policy, which only allows the configured server address,
// sees exactly what is dialed.
func validateTarget(target string) error {
	if target == "" {
		return errors.New("--target is required")
	}
	if _, err := netip.ParseAddrPort(target); err != nil {
		return fmt.Errorf("invalid --target: %w", err)
	}
	return nil
}

// runKeygen prints a new persistent identity for a dedicated server.
func runKeygen(out *eventWriter) error {
	k := key.NewNode()
	kt, err := k.MarshalText()
	if err != nil {
		return err
	}
	pt, err := tailcat.NewPresharedKey().MarshalText()
	if err != nil {
		return err
	}
	out.send(event{Type: "keys", PrivKey: string(kt), PSK: string(pt)})
	return nil
}
