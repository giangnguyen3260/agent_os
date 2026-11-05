# Internal create coordinator candidate

Explicit ProviderCreateCoordinator composes the decoded owned body, actual
transactional receipt proxy, command HMAC, credential preparation and existing
completion boundary. No Spring bean or public endpoint is installed.

The RR own-receipt probe rechecks current human/admin authority before any key
service or external-secret preparation. Existing receipts use historical command
HMAC versions and go through the locked completion replay path without a new
secret write. Fresh calls compute command HMAC outside DB transactions, prepare
a new scoped credential outside locks and pass only metadata into completion.
Completion retains its late authorization and atomic metadata/event/receipt
rules. A raced PREPARATION_REQUIRED permits one bounded re-probe and historical
HMAC computation, reusing the already prepared credential rather than writing
Vault again. No automatic resubmission occurs after UNKNOWN.

The decoder is consumed/closed on every return; transient character copies are
cleared. Unexpected exceptions after entering completion conservatively map to
UNKNOWN. Earlier dependency failures map to unavailable; current authority
failures retain401/403. External preparations remain pinned by the configured
journaled secret port; failed command completion does not license deletion.

Two shared H2/native PostgreSQL fixture methods are authored: create/replay during
secret-store outage plus revoked replay authority, and revocation during external
preparation with no business rows committed. These use test-only key/secret ports
and actual receipt/persistence/completion code. They do not establish combined
Vault/application/HTTP or concurrency acceptance until corresponding tests run.
Google format/diff checks are separate from project Java21/native CI results.

This is NOT the complete command application gate. Deployment provider policy,
fresh-write quota/capacity, full ingress/CSRF/idempotency transport, prepared-secret
lifecycle resolution, runtime grants and public safe DTO mapping remain before
activation. The constructor needs the actual transactional receipt proxy; arbitrary
custom proxies are not a supported substitute. No automatic production adapter,
UI create/rotate completion, merge or deployment is claimed.
