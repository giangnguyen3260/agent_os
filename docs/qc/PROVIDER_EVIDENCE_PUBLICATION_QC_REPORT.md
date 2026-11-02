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
