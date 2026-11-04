# Provider create transport checkpoint

4 November 2026. Parent: PR50 cbfac85d3abfbb77c5f9080b2260a82e59ce56be.

## Implemented boundary

ProviderCreateBody is an internal, isolated decoder for the frozen CREATE JSON
shape. It is not a Spring bean or public endpoint. All three exact provider/mode
pairs use the established CommandBoundary.Create validation and normalization.
Anthropic requires exactly workspaceId (nullable); other profiles require empty
options. Duplicate/unknown/missing fields, coercions, trailing JSON, malformed
UTF-8, invalid visible-ASCII keys and oversized bodies fail with fixed
INVALID_INPUT and no original exception cause.

consume takes exclusive ownership of the supplied body buffer and erases it on
success/failure. The returned AutoCloseable owns the transient credential buffer,
which must be closed even on command failure. Its credential accessor lends that
buffer only until close. Invalid decoded credential text is erased before the
parser releases its buffers. No raw body/key is retained in command metadata,
exceptions or toString. JVM/parser/TLS memory erasure remains best effort.

## Verification and limits

Five isolated JUnit methods passed locally, compiled with ECJ on Java17 against
the actual CommandBoundary and Jackson2.18.2. Cases include all three profiles,
strict schema/encoding rejection, body cleanup on failure, borrowed key cleanup
and maximum4096-byte visible key preservation. Google Java Format1.28 applied.
This isolated check is not the full project Java21/Maven/CI gate or an HTTP,
secret-manager, database, privacy-log or end-to-end acceptance result.

## Required next wiring

An ingress reader must bound bytes before allocation and invoke consume only
after current human/ADMIN and transport/CSRF/idempotency gates. Do not use
Spring/Jackson request DTO binding or body logging for credentials.

Reuse historical receipt/HMAC lookup before external preparation. Network secret
writes remain outside every business transaction, followed by locked current
authorization and receipt recheck through the existing completion boundary.
The application DB receives only opaque reference and keyed verification
metadata. Configure real external secret and managed HMAC adapters explicitly;
no fake/in-memory production vault, sample key or success fallback.

Expose CREATE only after those dependencies, durable preparation reconciliation,
uncertain commit recovery and native concurrency/no-secret-row tests are wired.
Then connect the reviewed create form and test UNKNOWN/replay/denied/outage flows.
Rotation is a separate decoder/application/UI slice; this candidate does not
implement it, call providers, grant runtime privileges, merge or deploy.

## Bounded stream reader follow-up

The same decoder now offers read(InputStream): fixed8193-byte owned scratch,
at most8193 bytes consumed, reject on the first excess byte, and erase scratch
on success, malformed body or I/O failure. No Content-Length is trusted and no
unbounded body collector is used. Caller retains ownership of stream close and
must enforce server read deadlines/concurrency; the synchronous reader itself
does not implement timeout scheduling. A zero-progress stream fails immediately.

Five additional tests cover fragmented reads, exact8192-byte input, overflow
without draining a100000-byte stream, failure after a partial read with safe
exception text/no cause, empty/null/nonprogressing input and scratch erasure.
This remains an internal helper; no new public command or DB persistence path.
