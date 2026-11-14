package invite

import (
	"strings"
	"testing"
)

func TestRoundTrip(t *testing.T) {
	in := New("tcEXAMPLE", "26.3")
	s, err := in.Encode()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(s, Prefix) {
		t.Fatalf("missing prefix: %q", s)
	}
	out, err := Decode("  " + s + "\n")
	if err != nil {
		t.Fatal(err)
	}
	if out != in {
		t.Fatalf("got %+v, want %+v", out, in)
	}
}

func TestDecodeRejects(t *testing.T) {
	cases := map[string]string{
		"no prefix":     "abc",
		"old prefix":    "mcl1_eyJ2ZXJzaW9uIjoxLCJ0YWlsY2F0IjoidGNYIn0",
		"bad base64":    Prefix + "!!!",
		"unknown field": Prefix + "eyJ2ZXJzaW9uIjoxLCJ0YWlsY2F0IjoidGNYIiwiZXZpbCI6MX0",
		"bad version":   Prefix + "eyJ2ZXJzaW9uIjoyLCJ0YWlsY2F0IjoidGNYIn0",
		"not tailcat":   Prefix + "eyJ2ZXJzaW9uIjoxLCJ0YWlsY2F0IjoieHgifQ",
		"too long":      Prefix + strings.Repeat("A", maxEncodedLen),
	}
	for name, s := range cases {
		if _, err := Decode(s); err == nil {
			t.Errorf("%s: Decode(%q) succeeded", name, s)
		}
	}
}
