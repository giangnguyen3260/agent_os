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
