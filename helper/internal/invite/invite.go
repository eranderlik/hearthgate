// Copyright (c) 2026 Tailscale Inc & contributors.
// Copyright (c) 2026 Tailcat for Minecraft contributors.
// SPDX-License-Identifier: BSD-3-Clause
//
// Derived from github.com/tailscale/tailcat-for-minecraft's invite package.

// Package invite implements Tailcat for Minecraft invitation strings.
//
// An invitation is the prefix "tcmc1_" followed by unpadded base64url JSON.
// It wraps a secret Tailcat address: anyone holding the invitation can reach
// the shared world for as long as the host keeps sharing.
package invite

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
)

const (
	Prefix         = "tcmc1_"
	CurrentVersion = 1
	// maxEncodedLen bounds decoding work on pasted text. Addresses with full
	// embedded DERP metadata are a few hundred bytes.
	maxEncodedLen = 4096
)

type Invitation struct {
	Version int    `json:"version"`
	Tailcat string `json:"tailcat"`
	// Minecraft is the host's game version, so that joiners on another
	// version get a clear message instead of a protocol error. Optional.
	Minecraft string `json:"mc,omitempty"`
}

func New(tailcat, minecraft string) Invitation {
	return Invitation{Version: CurrentVersion, Tailcat: tailcat, Minecraft: minecraft}
}

func (i Invitation) Validate() error {
	if i.Version != CurrentVersion {
		return fmt.Errorf("unsupported invitation version %d", i.Version)
	}
	if !strings.HasPrefix(i.Tailcat, "tc") {
		return errors.New("invalid Tailcat address")
	}
	if len(i.Minecraft) > 32 {
		return errors.New("invalid Minecraft version")
	}
	return nil
}

func (i Invitation) Encode() (string, error) {
	if err := i.Validate(); err != nil {
		return "", err
	}
	b, err := json.Marshal(i)
	if err != nil {
		return "", err
	}
	return Prefix + base64.RawURLEncoding.EncodeToString(b), nil
}

func Decode(s string) (Invitation, error) {
	s = strings.TrimSpace(s)
	if len(s) > maxEncodedLen {
		return Invitation{}, errors.New("invitation is too long")
	}
	rest, ok := strings.CutPrefix(s, Prefix)
	if !ok {
		return Invitation{}, fmt.Errorf("invitation must start with %s", Prefix)
	}
	b, err := base64.RawURLEncoding.DecodeString(rest)
	if err != nil {
		return Invitation{}, fmt.Errorf("decode invitation: %w", err)
	}
	var i Invitation
	d := json.NewDecoder(strings.NewReader(string(b)))
	d.DisallowUnknownFields()
	if err := d.Decode(&i); err != nil {
		return Invitation{}, fmt.Errorf("parse invitation: %w", err)
	}
	if d.Decode(new(any)) != io.EOF {
		return Invitation{}, errors.New("parse invitation: trailing data")
	}
	if err := i.Validate(); err != nil {
		return Invitation{}, err
	}
	return i, nil
}
