# Journal-before-Vault composition candidate

JournaledVaultSecrets explicitly composes the existing JDBC journal and Vault
credential adapter. It is not an automatic bean or public API. The wrapper
creates/commits PENDING with the exact locator before any Vault POST. The adapter
uses that pre-created locator and CAS0; it no longer chooses another locator for
this path. Failure or uncertain journal begin forbids network I/O.

A returned Vault reference is exposed only after WRITTEN acknowledgement commits.
Vault failure or journal-acknowledgement failure attempts UNKNOWN and returns a
redacted failure. If UNKNOWN persistence also fails, the already committed
PENDING row remains indefinitely pinned. Neither state licenses deletion; no
cleanup or automatic orphan inference exists. An uncertain WRITTEN commit may
leave WRITTEN while the operation still fails; UNKNOWN never downgrades that fact.

CredentialBoundary can subsequently fail HMAC derivation even after store returns.
Its existing preparationFailed callback still delegates to the explicitly
configured reconciler, retaining the journal row. Reconciliation completion,
receipt/business-reference matching, lifecycle/pinning and cleanup are separate
and not claimed by this composition. Direct unjournaled adapter use remains
available for isolated tests; production wiring must choose the wrapper.

The existing real local HTTPS fixture now composes the actual H2 migration/journal
and adapter. Authored cases witness PENDING on a separate connection at network
arrival, successful WRITTEN, server-side write followed by503/UNKNOWN, Vault success
followed by a deliberately rejected DB acknowledgement/UNKNOWN, and begin FK
failure with zero HTTP calls. No provider key or actual Vault is used in this
combined fixture. Actual PostgreSQL journal and actual Vault adapter gates remain
separate evidence; combined PostgreSQL+Vault/crash/commit-ack-loss verification is
still required. This entry records authored assertions until matching CI passes.

No production activation, CREATE/ROTATE completion, merge or deployment.

## Combined native integration candidate

The disposable Vault harness now also provisions its own PostgreSQL16 container
through compose.vault-ci.yaml, random private password file and dynamic loopback
port. This overlay is CI-only. Its volume belongs to the random test project and
is removed at exit; no application database is used.

The real Java IT migrates the complete schema, creates its isolated organization
and writes via JournaledVaultSecrets. It verifies exact scoped WRITTEN/version1
evidence plus byte-exact Vault readback. Both Vault and PostgreSQL are restarted;
a second JVM repeats readback and journal checks using the same reference.
Each phase scans all public-table JSON rows for the synthetic credential/plaintext
and base64 form and the three generated HMAC key base64 fixtures. Assertions do
not print stored rows or secret values on failure. This is a scoped sentinel
inventory, not proof against every representation, production log or memory leak.

Native execution remains pending until this candidate's workflow passes. Remote
write/acknowledgement fault injection remains the separate H2/HTTPS fixture;
combined PostgreSQL+Vault process-kill/unknown-commit cases are not claimed here.
