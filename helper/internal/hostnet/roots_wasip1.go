//go:build wasip1

package hostnet

// WASI has no system certificate store. Embed Mozilla's root bundle (via
// x509roots/fallback, which applies only when the platform has no roots) so
// TLS to the DERP map and relays verifies as it would natively.
import _ "golang.org/x/crypto/x509roots/fallback"
