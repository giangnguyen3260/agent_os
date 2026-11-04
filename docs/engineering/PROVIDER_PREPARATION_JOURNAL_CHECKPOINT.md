# Durable preparation journal candidate

5 November2026. V026 adds metadata-only preparation rows: random ID, exact org/
connection/provider/credential revision, unique immutable Vault locator, state,
external version and DB timestamps. No secret, HMAC key, digest or body exists in
this journal. No connection FK is possible before CREATE; the org FK deliberately
prevents hard org removal while preparations exist. Account deletion, org
lifecycle/reconciliation disposition must be resolved before wiring live writes.

ProviderPreparationJournal is an explicit internal writer, not an HTTP route or
automatic bean. begin commits PENDING using its own physical JDBC transaction
before any future Vault call is allowed. Begin failure/commit uncertainty forbids
the caller from starting that write, even if an evidence row may have committed.
Current ambient transactions/synchronization are refused, not suspended.

Updates lock and match the full preparation identity/scope/locator. Unknown
outcome is retained; an acknowledgement can promote PENDING/UNKNOWN to WRITTEN
(version1). Repeating either notification is idempotent. UNKNOWN never erases
an already recorded acknowledgement. WRITTEN proves only Vault write
acknowledgement, not connection commit, current reachability, credential integrity
or cleanup eligibility. No automatic deletion, orphan classification, terminal
resolution, expiry lease or evidence garbage collection is added.

Tests execute the actual V026 on H2 and share durable/scoped-transition assertions
with a new method in the disposable native PostgreSQL publication suite. They
cover separately visible commits, locator uniqueness, scope refusal, monotonic
acknowledgements and refusal inside a caller-owned transaction. These are authored
tests until the matching CI logs pass; no fault-injected commit-ack-loss acceptance
is asserted for this journal.

This intentionally prepares storage only. Vault adapter must still receive a
pre-created journal identity/locator before network I/O; current random-locator
store is not changed here. Durable reconciliation of business references/
receipts, in-flight pins and deletion eligibility must be frozen/tested before
enabling a cleanup worker. No provider CREATE/ROTATE completion, production grant,
runtime adapter activation, merge or deployment is claimed. Existing framework
session/CSRF one-way storage audit remains open.
