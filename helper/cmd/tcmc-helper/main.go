// Command tcmc-helper is the networking half of the Tailcat for Minecraft mod.
//
// It is built for GOOS=wasip1 and runs inside the game's JVM on a WebAssembly
// runtime, so the mod ships no native executables. The same source also builds
// natively, which is how its tests run and how the protocol can be exercised
// from a terminal. The commands are documented in package app.
//
// The JVM starts one instance per sharing session. The helper writes one JSON
// event per line to stdout and logs to stderr. It shuts down when stdin
// reaches EOF, which is how the JVM stops a session.
package main

import (
	"context"
	"io"
	"log"
	"os"

	"tailcatmc/helper/internal/app"
	"tailcatmc/helper/internal/hostnet"
)

func main() {
	log.SetFlags(log.Ltime | log.Lmicroseconds)
	hostnet.Install(log.Printf)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		// The JVM closes stdin to stop the session.
		_, _ = io.Copy(io.Discard, os.Stdin)
		cancel()
	}()
	app.Run(ctx, os.Stdout, log.Printf, os.Args[1:])
}
