# Trust-gate certificate binding — design record

The `onlyTrusted` and `qrOnly` receive gates currently compare the fingerprint
string carried in `PrepareUploadRequest.info` against a persisted pin or a
known-device row. The field is caller-declared and unsigned. A sender who knows
a trusted fingerprint can pass either gate. This record keeps the two designs
that were evaluated and the reason neither is shipped in v0.3.1.

## Option A — bind the TLS peer certificate

The server asks for a client certificate during the TLS handshake, computes the
SHA-256 digest of the presented DER, and passes that digest to the delegate
instead of the declared string. The gate then requires the digest to equal both
the declared fingerprint and a persisted pin or known-device row. When a gate
is on and no certificate is presented, the prepare request is declined.

Ktor 3.2.2 does not expose a client-auth choice on `sslConnector`: Netty sets
`needClientAuth` only when a trust store is configured, which would reject
cert-less v2.2 senders before the HTTP refusal could be sent. Implementing the
option means replacing Ktor's `"ssl"` handler in place with a Flit-built
`SslHandler` using `clientAuth(OPTIONAL)` and the existing trust-all manager,
then plumbing the peer certificate through `StreamTls`, `TlsLink`, `Link`,
`LinkServer` and `HttpExchange` into a defaulted `ServerDelegate.onPrepare`
overload.

Review found three required corrections to the first sketch: the replacement
handler must keep Ktor's position before the HTTP codec, the replacement
`SslContext` must use the trust-all manager explicitly for self-signed Flit
certificates, and `getPeerCertificates()` must be guarded because an optional
client flight may be empty. Bluetooth and relay pipes already run server-mode
TLS through `StreamTls` but expose no peer certificate, so they would silently
fail closed unless wired explicitly. The persisted stores, not the in-memory
pin cache, must be the gate source; pins are not rotated from a presented
certificate, or the property being established is lost.

## Option B — bind a certificate header at prepare time

Panic and Circle already carry a self-signed certificate in a header, compute
its own DER digest at the receiving route, require the digest to equal the
claimed fingerprint, and verify a signature before pinning. Extending that
pattern to `prepare-upload` closes the same spoof without changing the TLS
listener: the client sends the certificate, the route compares its digest to
the declared fingerprint, and the gate checks the resulting identity against
the pin or known-device row. A sender without the header is declined when a
gate is on; a v2.2 receiver ignores the extra header. Refusals keep the
existing `403` and `X-Flit-Refusal: declined` shape.

This option adds header content on a Flit-only request and therefore changes
observed wire behavior for senders, even though no route, DTO field or status
code changes. It also leaves the gate off by default, so the declared-fingerprint
behavior stays the shipped default until a gate is enabled.

## Disposition

Both designs were reviewed before implementation. Option A is feasible with
the pinned dependency versions but needs carrier surgery across three
transports and several compatibility tests. Option B is smaller but changes the
prepare request's header set and still relies on an opt-in gate. No concrete
requirement currently justifies either wire change, and neither design should
be shipped on the strength of a design review alone. The work is parked here.

If the requirement returns, Option B is the first candidate: it reuses an
existing primitive, keeps the normal refusal shape, and is testable at the
route without TLS adapter work. Whichever option is chosen must include tests
for no certificate, claimed/presented mismatch, plain HTTP, Bluetooth, relay,
restart and pin rotation, and must log only a short fingerprint code.
