# Scoped retained-reference observation candidate

The journal gains an internal read-only REPEATABLE_READ snapshot observation.
It matches the entire preparation identity/scope/locator, then checks an exact
credential version and a surviving same-org/provider connection. Historical
credential versions remain retained references when the current revision advances.

RETAINED_REFERENCE_FOUND means the snapshot contains those retained DB references.
UNRESOLVED is deliberately not ORPHAN: an in-flight or unknown commit may still
reference the secret later. Neither result authorizes deletion, resolves a command
receipt, establishes current HMAC integrity or changes a journal row. Missing or
foreign preparation identity fails closed. No public API, cleanup, terminal state,
automatic bean or network call is added.

The real PostgreSQL+Vault IT now prepares through CredentialBoundary with the
actual versioned Vault HMAC reader and journaled Vault writer. Its synthetic test
transaction persists the real opaque reference/HMAC metadata and a scoped
connection. It checks UNRESOLVED before that transaction, retained reference
afterwards and after both services restart, HMAC-verified credential use, foreign
preparation refusal, external-version mismatch refusal and unchanged journal JSON.

The manually inserted test connection is a fixture, not the public CREATE
application/receipt workflow. Both phases retain the existing full public-row
sentinel scan. Matching native CI logs must establish runtime acceptance; authored
assertions alone are not passes. Full command/receipt reconciliation, in-flight
pins, delete eligibility, production grants and API/UI remain separate work.
