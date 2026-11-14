// Command hearthgate-helper is the networking half of the Hearthgate mod.
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
	"bufio"
	"context"
	"log"
	"os"
	"runtime"

	"hearthgate/helper/internal/app"
	"hearthgate/helper/internal/hostnet"
)

func main() {
	log.SetFlags(log.Ltime | log.Lmicroseconds)
	hostnet.Install(log.Printf)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		// The JVM closes stdin to stop the session. A "stacks" line logs
		// every goroutine's stack, for diagnosing stalls.
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			if sc.Text() == "stacks" {
				buf := make([]byte, 1<<20)
				log.Printf("goroutine dump:\n%s", buf[:runtime.Stack(buf, true)])
			}
		}
		cancel()
	}()
	app.Run(ctx, os.Stdout, log.Printf, os.Args[1:])
}
