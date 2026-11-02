# Guarded provider evidence publication verification

Date: 2 November 2026. Baseline643357e7e734286fb937a4490161174918917fc1;
branch feature/provider-evidence-publication. This slice adds a separately invoked
PostgreSQL installer, six guarded capture/publication functions and an explicit
worker JDBC boundary. No Flyway V026, observer adapter, public list/publisher route,
provider/key request, success seed, protected current Compose rollout or deployment.

Requirements/source freeze and scalar/grant representation were reviewed separately
in PROVIDER_EVIDENCE_PUBLICATION_REVIEW.md. Installer/client implementation and
native execution acceptance remain pending. Registered producer assertions are an
explicit trust assumption, not cryptographic proof that an external check happened.

Initial authored coverage is12 production-client unit methods and16 real-LOGIN
PostgreSQL methods. These are not passes. Google Java format/import, syntax/YAML
and whitespace checks passed before publication; dependency compilation/JUnit/native
execution were unavailable locally. CI Java21/PostgreSQL is authoritative.

Quality retains the bounded Spring-context cache and all earlier selections, adds
the publication native class and explicit disposable opt-in, and starts a separate
SCRAM PostgreSQL service with masked random credentials/private logging. Old native
suites keep their existing15432 service; publication uses15433. No user DB is touched.

Capture commits before external checks; publication data is provisional until commit
acknowledgement. Client failure results retain no driver exception or private SQL
payload and do not retry. UNKNOWN cannot be resolved from overwritten latest state.
Real-LOGIN, timing, scope/rotation, CAS/failure dominance, epoch, capacity/cleanup,
privilege and fault assertions require actual completed logs with source attribution.

No merge/deploy, Sonar pass, universal server-log privacy or actual external-worker
verification is claimed. Q3 protected deployment and future observer/list gates stay
open. Record findings/retests and exact executed counts below as CI completes.

## C1 execution — rejected, not accepted

Published source commit `2143f7e7d641397b8702b9f7574b7ad6e03d2fc0`,
tree `66f2db954cf8997a4124bb93e951ff5180ec1c8e`.
Focused run 41447619210/job125284535186 completed with 28 tests,
4 failures,5 errors,0 skips: all12 client unit tests passed; the16 native tests
had4 failures and5 errors. Registered captures unexpectedly returned REJECTED,
several dependent cases dereferenced absent capture values, and one fixture update
failed. These are unresolved failures, not evidence that the rejection contract works.

Quality run41447619067/job125284534923 completed with1479 tests,
4 failures,5 errors,0 skips and BUILD FAILURE, reporting the same nine publication
native failures/errors. Frontend checks were skipped after backend failure.
Retained provider-options and evidence-foundation workflows succeeded; they do not
substitute for publication acceptance. Docker formatting passed; Compose was still
running when inspected. Independent QC also identified the outstanding publisher
preflight SELECT-grant refusal gap (S2-read). Corrections and a new completed run
are required; no acceptance, merge or deployment is recorded.

## Correction prepared after C1

QC identified invalid PostgreSQL regex repetition bounds (`{1,256}`) in the
metadata predicate. The helper caught the resulting data exception and converted
it to false/BINDING. Corrected SQL checks character length 1–256 separately from
the unchanged ASCII character allowlist. Canonical verifier validation is retained.
Native boundary cases are being added; this source diagnosis is not yet a native
execution result. The fence fixture now updates only the intended publisher,
avoiding the registry's unique-fence collision. Publisher SELECT preflight and a
dirty column-SELECT refusal/no-repair case address S2-read. Test-only diagnostic
probes expose enums/SQLSTATE/numeric position without driver messages or causes.

The current local runtime cannot start the downloaded PostgreSQL binaries under a
non-root account (the user namespace maps only uid0). Native SQL acceptance remains
assigned to the explicit disposable Java21/PostgreSQL16 CI job. No user database
was provisioned or modified.

## C2 focused execution — basic correction passed

Commit `f926b2608b17d3d8afb70e03508448aac3d6227e`, tree
`05326b290f7c38638b39ff37300174af931617ed`; CI checked out PR merge
`95cb350` (head f926b260 into base643357e7). Focused run41478729927,
job125391103189 completed BUILD SUCCESS:12 production-client unit tests and20
native PostgreSQL tests, total32,0 failures,0 errors,0 skips. The three added
boundary methods exercise27 successful real-LOGIN captures (three fields × three
lengths × three kinds),18 empty/invalid-character BINDING rejections with exact
fingerprint preservation, and three storage-level length257 rejections (22001).
These are cases within methods, not extra JUnit test counts. The dirty publisher
SELECT refusal/no-repair method also ran. Native source uses actual installer and
separate authenticated worker connections; external assertions remain controlled.

C1's rejected results are retained above. C2's focused success resolves the initial
native execution failure; it does not close the broader protocol-gap table, full
Quality/Docker retest or independent Q4 acceptance. Additional discriminating MAX,
sticky-state, expiry and capacity cases are being implemented before the next source
checkpoint. Q3 deployment and actual observer/public list remain outside this slice.

C1 Docker run41447619085 separately completed successfully: compose job125284534787
and format job125284535031. Its build ran941 regular backend tests with0 failures,
errors/skips; browser groups33+75+42+81+90+780 total1101 disjoint invocations plus3
focused repeats. Native/SMTP probes and exact-session restart/logout passed. This is
retained C1 application regression evidence, not publication-native acceptance or a
result attributed to C2's changed SQL/tests.

## C3 protocol regression checkpoint — execution pending

Commit `ed6519154c69dbfd1e8c455da9eef13451b7085b`, tree
`f6a7b7b8f09cbc00ffc328d420a02befc2bff29c`, adds only seven native methods;
production SQL/client remain byte-identical to C2. Authored native count is27,
with12 client methods retained. New cases cover VERIFIED/health MAX refusal with
exact no-write fingerprints, failure-before-preparation stickiness, independent
health advancement before a captured mismatch, invalid optional-deadline pairs
including NULL presence/nonfinite values, valid absent/shorter/later deadlines,
and full-capacity no-write rejection with existing publication/cross-org success.
The synthetic VERIFIED-at-MAX fixture keeps its current provenance revision aligned.
Google formatting/import and whitespace checks passed; CI and independent source/
assertion review are required before attributing executed results to these cases.
Remaining P01–P15 obligations are recorded by independent QC, not waived here.

## C2 full Quality completed

Run41478730049/job125391102562 completed SUCCESS on the same `95cb350`
C2 merge checkout. Backend:1483 tests,0 failures,0 errors,0 skips, BUILD SUCCESS.
Frontend dependency install/security audit found0 vulnerabilities; lint, format,
type/build gates completed successfully. This includes the corrected20-method
publication native class and retained suites, not C3's later seven methods.
Independent full-protocol acceptance remains separate from an aggregate pass.

## C3 focused execution completed

Run41479437357/job125393523883 checked out PR merge `c4e8aea`, merging
`ed6519154c69dbfd1e8c455da9eef13451b7085b` into base643357e7. Maven
completed BUILD SUCCESS:12 client units plus27 native PostgreSQL methods, total39,
0 failures,0 errors,0 skips. All seven added methods executed within that native
class. This supersedes the authored-only execution status above for C3's focused
suite; the earlier chronology and C1 failure evidence are retained. Full C3 Quality
and Docker statuses are recorded separately when completed, not inferred here.
The passed methods establish their explicit MAX/sticky/deadline/capacity assertions;
the remaining independent P01–P15 gaps and Q3/Q4 holds are unchanged.

## C3 full Quality completed and checkpoint limits

Run41479437808/job125393524558 completed SUCCESS on PR merge `c4e8aea`
(head ed651915, base643357e7). Backend:1490 tests,0 failures,0 errors,0 skips,
BUILD SUCCESS. The completed log reports12 publication client and27 publication
native methods within the full selection. Frontend dependency audit found0
vulnerabilities and lint/format/type/build gates succeeded. No Sonar result is
claimed; Sonar remains paused.

C2/C3 Docker reruns were still running at this evidence checkpoint; formatting
jobs had succeeded. The completed C1 Docker evidence is attributed above, not
relabeled as a later pass. This correction changes operator SQL/test fixtures;
C3 changes only native tests, with production SQL/client/workflows byte-identical
to C2. A final evidence-only Markdown commit does not alter the tested C3 source.

Bounded correction/regression work is verified; full guarded publication Q4 remains
HOLD. Remaining independent obligations include authority/fencing/clock races,
injected atomic rollback and native uncertain completion/privacy, complete catalog/
privilege controls and cleanup/grace/pinning serialization. Q3 deployment separation
and actual external observer verification are still separate open prerequisites.
No public connection list/PRESENT projection, worker wiring, merge or deployment
was performed. Follow the independent review's prioritized P01–P15 gap table next.

## C4 authority, fencing and atomic rollback checkpoint

Source `62b9b144bcbf49eebf8d3b03f4ca32459ed5dbb9`, tree
`3660fdb257e53214bcf4940ae93732ffe42e4966`, adds five native methods (32
authored native methods total) without changing production SQL/client/workflows.
Coverage adds foreign org/provider/connection denial, disabled registration and
removed HEALTH permission invalidating committed captures, separate valid metadata/
verifier substitutions, an actual registry lock wait identified by blocker PID with
fence change, and four injected INSERT failures at integrity/health/assertion/
provenance boundaries. Rejection/rollback paths require NULL data and the unchanged
eleven-table fingerprint; each injected-fault case has a fresh-capture successful
control after fault removal. The fence baseline includes the operator's intended
uncommitted fence update while the worker remains blocked. This avoids accepting
a forbidden write that completed before a late baseline was measured.

Google formatting/import and whitespace checks passed. Independent QC accepted
the bounded source for execution; actual C4 focused/full results are pending.
INSERT-path fault injection does not prove VERIFIED→FAILED UPDATE-path rollback,
actual network uncertainty or server-log privacy. All remaining Q4 gaps retain
their separate dispositions. Only disposable fixtures install temporary triggers;
production installer/schema/permissions are unchanged.

### C4 focused execution completed

Run41486600473/job125418505578 completed BUILD SUCCESS on PR merge
`c43c9ee`, merging62b9b144 into base643357e7. Actual result:12 client units
and32 native PostgreSQL methods, total44,0 failures,0 errors,0 skips. This
executes the five new authority/fence/binding/atomic-INSERT rollback methods and
the retained protocol cases. Full Quality is tracked separately; aggregate focused
success does not waive missing UPDATE-path rollback, actual transport loss, privacy,
clock/cleanup and catalog/effective-privilege obligations.

## C5 controlled durable-commit uncertainty checkpoint

Source `4b9a39cae2db0ae22a1fc6a5b473666c28c1f189`, tree
`ee1d6ead1e45628da0b2d0420ca59be87b1c69f3`, adds one native method
(33 authored native methods total). The explicit worker DataSource wraps an actual
JDBC connection: real commit completes before a controlled08006 exception is
raised. The client must return UNKNOWN/COMPLETION_UNKNOWN with NULL value; operator
reads verify durable health revision1 and consumed attempt. A fresh independent
capture/publication replaces latest health at revision2; the original result stays
UNKNOWN. Counters require one borrow, one statement factory, one commit and zero
rollbacks before/after replacement. All three statement factories are counted.

This is a controlled Java post-commit fault on a real database, not actual TCP or
PostgreSQL-wire acknowledgement loss, universal query tracing, historical-recovery
API execution or application/server-log sentinel proof. Unchanged production source
separately confirms no retry/readback. Formatting/import and whitespace checks
passed; independent QC accepted bounded source for execution. C5 results remain
pending. No production SQL/client/workflow changes, merge or deployment.

### C4 Full Quality completed

Run41486600525/job125418505594 completed SUCCESS on merge `c43c9ee`
(head62b9b144, base643357e7):1495 backend tests,0 failures,0 errors,0
skips, BUILD SUCCESS. Frontend style/type/build gates passed; dependency audit
found0 vulnerabilities. This is C4's32-native tree; it does not cover the later
C5 controlled acknowledgement-loss method. Full Q4 acceptance remains separate.

### C5 focused execution completed

Run41487482046/job125421515379 completed BUILD SUCCESS:12 client units
plus33 native PostgreSQL methods, total45,0 failures,0 errors,0 skips. This
executes the controlled real-commit/Java08006/latest-replacement case with its
explicit proof limits. It is not relabeled as a TCP-loss test. Full Quality is
tracked separately. A further test-only loopback transport-drop fixture is under
development to address the actual driver/wire acknowledgement-loss distinction.
No result or source acceptance is claimed for that future fixture.

### C5 Full Quality completed

Run41487482117/job125421516619 completed SUCCESS on merge `5b939e2`
(head4b9a39ca, base643357e7):1496 backend tests,0 failures,0 errors,0
skips, BUILD SUCCESS; frontend style/type/build gates and audit0 passed.
This remains C5's controlled Java post-commit fixture, not the later C6 loopback
transport-drop source or evidence.

## C6 real loopback commit-acknowledgement loss checkpoint

Source `04efc15b42da3019412286d0d7bd63bdf53b48be`, tree
`906e13910664767a61ab7d818eddc83d97c21711`, adds the test-only
PostgresCommitAckProxy and one native method (34 authored native methods total).
The bounded one-connection proxy binds IPv4 loopback and accepts only the validated
local disposable fixture URL. It relays PostgreSQL3 startup/typed frames with
TLS/GSS disabled for this local fixture, withholding backend CommandComplete COMMIT
and the subsequent idle ReadyForQuery before closing both sockets. The native
case requires that exact phase, a real PGJDBC class08 commit exception represented
only by a boolean, UNKNOWN/NULL data, one borrow/statement/commit and no rollback,
durable health revision1/consumption, then direct fresh revision2 replacement while
the historical result stays UNKNOWN. No Java-thrown commit fault is injected here.

Proxy buffers are bounded and cleared; no frontend SQL/authentication or packet
payload is parsed into diagnostics or logged. Connect/read/accept and cleanup waits
are bounded; resources close on success/failure. The helper compiled separately;
main Google formatting/import and whitespace checks passed. Independent QC gave
bounded source PASS for execution; native/full CI results remain pending. This is
controlled real loopback transport loss, not an internet partition, every transport
failure, historical-recovery API or whole server/application-log privacy proof.

Framing reference: PostgreSQL16 primary documentation,
https://www.postgresql.org/docs/16/protocol-message-formats.html (CommandComplete
and ReadyForQuery). This reference supports framing; actual execution must establish
the delivered JDBC behavior. Production SQL/client/workflows remain unchanged.

## Completed C3 Docker regression evidence

Run41479437511 completed SUCCESS: compose125393523676 and formatting
125393524225. Checkout was merge `c4e8aea` for C3 ed651915/base643357e7.
Actual build summary:941 regular backend tests,0 failures,0 errors,0 skips.
Browser groups33+75+42+81+90+780 total1101 disjoint invocations plus3 focused
repeats, all passed. Log confirms the exact session survives backend restart and
logout rejects replay. These are completed C3 Docker results, not extrapolated
C4/C5/C6 reruns. Subsequent C4–C6 production SQL/client/build/workflow source is
unchanged; later native test additions have separate focused/Quality evidence.
Newer Docker run statuses must retain their own source identities.

### C6 focused execution completed

Run41489057175/job125426924601 completed BUILD SUCCESS on merge
`15e1947`, merging04efc15b into base643357e7. Actual result:12 client units
plus34 native PostgreSQL methods, total46,0 failures,0 errors,0 skips. The
real loopback transport-drop case therefore executes within the passing native
class: committed-drop phase, real driver class08 failure, durable revision1, fresh
revision2 replacement and retained UNKNOWN/control counters all passed. This is
separate from C5's Java exception fixture. Full Quality is recorded separately.
The single observed transport case does not close all native uncertainty, crash/
network recovery, runtime/server-log privacy, clock, cleanup or catalog obligations.

### C6 Full Quality completed and final checkpoint

Run41489056730/job125426924786 completed SUCCESS on merge `15e1947`
(head04efc15b, base643357e7). Actual backend result:1497 tests,0 failures,
0 errors,0 skips, BUILD SUCCESS. The full selection includes12 client and34
publication native methods. Frontend style/type/build gates passed and dependency
audit found0 vulnerabilities. Sonar remains paused. Independent completed source/
execution dispositions are maintained in the owned QC review.

This continuation added seven native methods across C4–C6 plus a test-only bounded
loopback proxy. Production SQL/client and build/workflow files remain unchanged
from the earlier corrected checkpoint. A final evidence-only Markdown commit leaves
the tested C6 source unchanged. C3 Docker's completed941/1101/3 evidence keeps
its original source; later Docker reruns are not assumed successful here.

Bounded authority/fencing/binding/INSERT-rollback and the two post-commit loss
scenarios have passed their attributable focused/full gates. Full implementation/
Q4 remains HOLD for the independent gap table: UPDATE/post-consumption rollback,
combined clock/precedence and authority matrices, cleanup/grace/pinning races,
complete catalog/effective-privilege checks and application/server-log sentinel
privacy. One controlled real loopback loss does not establish every network/crash
recovery path. Q3 deployment separation, the real trusted observer and public
PRESENT/list projection remain separate. No merge, deployment or user DB action.
