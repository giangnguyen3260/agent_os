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
