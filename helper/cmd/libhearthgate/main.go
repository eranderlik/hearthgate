// Command libhearthgate builds the helper as a native shared library
// (-buildmode=c-shared) that a dedicated Minecraft server loads into its JVM
// with the Foreign Function & Memory API. It runs exactly the same commands
// as the WebAssembly helper (package app), but as native Go, for servers
// whose players need more throughput than the WebAssembly build provides.
//
// The ABI is deliberately small and never calls back into the JVM: Java
// starts a session, then a Java thread per session blocks in hg_next to
// receive its output. Each record starts with one tag byte:
//
//	'E' an event line (JSON, as on the WebAssembly helper's stdout)
//	'L' a log line
//	'X' the session finished; no more records follow
//
// All strings are UTF-8. Functions are safe to call from any thread.
package main

/*
#include <stdint.h>
*/
import "C"

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"runtime"
	"sync"
	"time"
	"unsafe"

	"hearthgate/helper/internal/app"
)

// abiVersion changes whenever a function's signature or meaning changes.
const abiVersion = 1

// buildID identifies the helper sources this library was built from. The
// mod refuses a library whose ID differs from its own WebAssembly helper's,
// so a stale add-on can't run against a newer mod. Set with -ldflags -X.
var buildID = "dev"

const (
	// maxRecord bounds one record; longer log lines are truncated. Events
	// are far smaller (an invitation is at most 4 KiB).
	maxRecord = 16 << 10
	// maxPendingLogs is how many log lines may wait for the JVM before
	// further ones are dropped. Events are never dropped.
	maxPendingLogs = 1024
)

type session struct {
	cancel  context.CancelFunc
	records chan []byte
	// logs counts log records queued but not yet taken.
	mu      sync.Mutex
	logs    int
	dropped int
}

var (
	mu       sync.Mutex
	sessions = map[int64]*session{}
	nextID   int64
)

//export hg_abi_version
func hg_abi_version() C.int32_t { return abiVersion }

var buildIDC *C.char

//export hg_build_id
func hg_build_id() *C.char {
	mu.Lock()
	defer mu.Unlock()
	if buildIDC == nil {
		buildIDC = C.CString(buildID) // lives for the process
	}
	return buildIDC
}

// hg_start starts a session. argsJSON is a JSON array of strings: the
// command and its flags, as for the helper executable. It returns a handle
// (> 0), or 0 if argsJSON is malformed.
//
//export hg_start
func hg_start(argsJSON *C.char) C.int64_t {
	var args []string
	if err := json.Unmarshal([]byte(C.GoString(argsJSON)), &args); err != nil {
		return 0
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{cancel: cancel, records: make(chan []byte, maxPendingLogs+64)}

	mu.Lock()
	nextID++
	id := nextID
	sessions[id] = s
	mu.Unlock()

	go func() {
		defer func() {
			// app.Run recovers its own panics; this is a last resort, as a
			// panic here would take the Minecraft server down with it.
			if p := recover(); p != nil {
				s.event('L', []byte(fmt.Sprintf("panic: %v", p)))
			}
			s.event('X', nil)
		}()
		app.Run(ctx, eventSink{s}, s.logf, args)
	}()
	return C.int64_t(id)
}

// hg_next waits up to timeoutMs for the session's next record and copies
// it into buf (truncated to capacity bytes). It returns the record's length,
// 0 on timeout, or -1 if handle is unknown or its 'X' record was already
// returned, in which case the handle has been released.
//
//export hg_next
func hg_next(handle C.int64_t, buf *C.char, capacity C.int32_t, timeoutMs C.int32_t) C.int32_t {
	mu.Lock()
	s := sessions[int64(handle)]
	mu.Unlock()
	if s == nil || capacity <= 0 {
		return -1
	}
	t := time.NewTimer(time.Duration(timeoutMs) * time.Millisecond)
	defer t.Stop()
	var r []byte
	select {
	case r = <-s.records:
	case <-t.C:
		return 0
	}
	if r[0] == 'L' {
		s.mu.Lock()
		s.logs--
		s.mu.Unlock()
	}
	if r[0] == 'X' {
		mu.Lock()
		delete(sessions, int64(handle))
		mu.Unlock()
	}
	n := min(len(r), int(capacity))
	copy(unsafe.Slice((*byte)(unsafe.Pointer(buf)), n), r)
	return C.int32_t(n)
}

// hg_stop asks a session to shut down, like closing the helper's stdin.
// Its remaining records, ending with 'X', are still delivered by hg_next.
//
//export hg_stop
func hg_stop(handle C.int64_t) {
	mu.Lock()
	s := sessions[int64(handle)]
	mu.Unlock()
	if s != nil {
		s.cancel()
	}
}

// hg_heap_bytes reports the Go heap in use by all sessions, for diagnostics.
//
//export hg_heap_bytes
func hg_heap_bytes() C.int64_t {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return C.int64_t(m.HeapInuse)
}

func (s *session) event(tag byte, line []byte) {
	r := make([]byte, 0, 1+len(line))
	r = append(r, tag)
	r = append(r, line...)
	if len(r) > maxRecord {
		r = r[:maxRecord]
	}
	s.records <- r // events wait for the JVM rather than being lost
}

func (s *session) logf(format string, args ...any) {
	s.mu.Lock()
	if s.logs >= maxPendingLogs {
		s.dropped++
		s.mu.Unlock()
		return
	}
	dropped := s.dropped
	s.dropped = 0
	s.logs++
	s.mu.Unlock()
	msg := fmt.Sprintf(format, args...)
	if dropped > 0 {
		msg = fmt.Sprintf("(%d log lines dropped) %s", dropped, msg)
	}
	s.event('L', []byte(msg))
}

// eventSink receives one JSON event per Write.
type eventSink struct{ s *session }

func (e eventSink) Write(p []byte) (int, error) {
	e.s.event('E', bytes.TrimRight(p, "\n"))
	return len(p), nil
}

func main() {}
