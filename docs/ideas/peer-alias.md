# PeerAlias

`PeerAlias` turns one certificate fingerprint into a short, stable label of the
form `AAAA-72E2FE`. It is a pure JVM function of its input and is intended for
places where a full 64-character fingerprint is too long to display: roster
rows, log lines and confirmation prompts. It does not look up a peer, a pin map
or a saved name.

## Input rules

A fingerprint must be an even-length hex string of 64 to 128 characters. The
64-character form is the SHA-256 certificate fingerprint used on the wire; the
128-character form accommodates a longer digest. Before any other work, the
raw string is checked against a 256-character cap. That check comes first so a
very large input is refused without being copied or hashed.

Accepted inputs are then trimmed, colon separators are removed, and the
remaining characters are required to be hex. Uppercase and lowercase are
equivalent. The canonical value is the uppercase hex string. Anything else —
null, blank, short, over-long, odd-length, non-hex, or containing embedded
whitespace or control characters — yields `null` rather than an exception.

## Derivation

The label has two halves.

The stem is the first four canonical hex characters, copied from the
fingerprint. It lets a reader compare the label with the fingerprint shown
elsewhere in the app.

The suffix is the first six hex nibbles (24 bits) of a SHA-256 digest over the
bytes `FLITALIAS1`, a zero separator, and the canonical fingerprint in ASCII.
The fixed tag keeps these digests separate from other hashing in the project.
Because the digest covers the entire canonical fingerprint, two fingerprints
that share a stem get different suffixes, as long as their digests do not
agree on those 24 bits. An alias collision therefore needs both a four-hex
stem match and a 24-bit digest match; the stem alone is not enough.

## Bounds and invariants

- Output text is always exactly 11 characters: four stem hex characters, one
  hyphen, six suffix hex characters, all uppercase.
- The same canonical fingerprint yields the same alias on every call, on every
  JVM, regardless of call order. There is no mutable state in the module.
- Work is bounded by the 256-character input cap and one SHA-256 of at most
  139 bytes (10-byte domain tag, one separator and up to 128 ASCII hex
  characters). Output length is fixed.
- `isValid` checks only the printed shape. `matches` recomputes the alias for a
  supplied fingerprint and compares both halves, case-insensitively; a malformed
  alias or an unusable fingerprint is always false.

## Limitations

The suffix is a truncated digest, so its collision bound is 24 bits, not the
full 256 bits of SHA-256. With a large enough set of fingerprints, aliases will
collide; this module detects such a collision only when `matches` is given two
different fingerprints to compare. It does not assign aliases, keep a registry,
or resolve a collision by adding more characters. Callers that need a unique
label for a large set must do that at a layer that owns the set.

The alias is derived from a fingerprint, which is public data in this protocol.
It is not a secret and must not be used as an authentication token. The module
does not verify that a fingerprint belongs to any particular peer, and it does
not interpret the fingerprint bytes as a certificate.

`CircleUi` and `MainActivity` use this module for fingerprint-derived display
labels. It holds no state and adds no route, header or DTO field.
