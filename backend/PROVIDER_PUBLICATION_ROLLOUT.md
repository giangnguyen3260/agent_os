# Guarded provider evidence publication rollout

This next slice implements guarded publication, not a running external observer.
SQL cannot prove that a network/key verification really occurred. Only a separately
configured trusted worker may attest that result. HTTP principals, arbitrary
Metadata constructors and the existing web database administrator are not evidence
producers. No successful production observation may be seeded for convenience.

## Required separation

Use separate migration/operator, HTTP-runtime and publisher LOGIN principals. The
HTTP process must not possess migration credentials, own evidence objects, create
objects in the function search path or assume publisher/definer roles. Existing
Compose administrator reuse does not meet this boundary and is not silently fixed
by installing a function. No tool action here applies SQL to the user's Mac.

The V025 publisher capability is NOLOGIN and initially ungranted. Grant only
EXECUTE on reviewed functions after installation; never grant generic evidence
INSERT/UPDATE/DELETE/TRUNCATE or ownership. Function ownership belongs to a
separate NOLOGIN role that public/runtime/publisher principals cannot assume.
Revoke PostgreSQL's default PUBLIC function execution in the same installation
transaction. Fixed search_path and schema-qualified object references prevent
object shadowing; deny untrusted schema CREATE and function replacement.

Registration binds the publisher UUID and organization to the actual database
LOGIN name and role OID. Publication authenticates the original session LOGIN,
not a caller-supplied name or a SET ROLE group. A dropped/recreated LOGIN with the
same spelling has a different OID and cannot inherit the old registration. Only
operators may register, revoke or rotate epochs/fences; publication cannot repair
its own authority. Old evidence must not obstruct fence invalidation.

## Worker responsibilities

Capture a database observation-start time before bounded external secret-version
and historical HMAC-key checks. Perform network/key work outside DB transactions.
Publish the original time/deadline; never re-age cached results using publication
time. A generic credential-boundary failure is not proof of cryptographic mismatch.
The worker must classify safe typed outcomes and bind them to the exact immutable
credential reference/revision. No provider generation or model request is required.

SQL guards must recheck registration/epoch/fence, exact binding and active-version
rules after lock waits; enforce CAS, increasing observations, finite timestamps,
60-second maximum age and no overflow. Confirmed integrity failure is sticky for
its immutable version. Retired-version evidence cannot affect a replacement.
Prepared/verified provenance, failure dominance and completion uncertainty follow
the reviewed publication contract, not a generic status-update function.

Do not retry automatically after uncertain commit. Optional, separately authorized
private/operator readback can confirm only a known exact candidate descriptor that
still matches every scope, binding, publisher, revision, state and timestamp field,
with linked integrity provenance when applicable. Missing, mismatching, overwritten
or unreadable state, or a lost descriptor, leaves historical completion UNKNOWN.
Latest state cannot prove an older command's absence or success. No seventh recovery
function, automatic replay or health receipt ledger is introduced. No assumption of
rollback, orphan cleanup or successful publication is justified by a lost acknowledgement. Provider credentials never enter SQL arguments, rows,
errors, logs or evidence. References/verifiers remain private internal metadata.

## Acceptance evidence

Use disposable PostgreSQL and actual nonsuperuser LOGIN connections for registered,
unregistered, reader and retired/recreated principals. SET ROLE under a superuser
alone cannot establish session authentication. Verify exact row preservation on
all rejections, sticky failure and rotation races, observed lock waits, expiration
at equality, epoch loss and transaction rollback. Keep existing V025/H2 migrations
and old role tests intact. Record exact tested commit and actual counts; test-source
fakes do not establish a configured production observer or hardened deployment.

## Implementation ownership

BE explicitly owns the six-function PostgreSQL installer, exact scalar/grant API
manifest and a production non-Spring JDBC client with tests. The client is given an
explicit worker DataSource, owns short transactions, does not expose capture before
known commit, and emits fixed sanitized results after publication completion.
It contains no observer/provider/key adapter or default credentials. Production-client
behavior must be tested directly; sanitizing a test helper is not equivalent.
Main owns this runbook and Quality integration; SA owns the reviewed contract;
QC owns its independent review. Final SQL implementation follows corrected source
freeze and exact-signature review, not a guessed protocol.
