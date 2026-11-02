# Provider evidence storage — independent QC review

Date: 2 November 2026. Branch: `feature/provider-evidence-storage`. Inspected HEAD: `fae8aa2e55cdc9e9dd1d2e56836765df6cdbb86f`; new SQL/operations files and workflow changes were uncommitted concurrent work. Reviewer owns only this document. This is a source-review checkpoint, not an executed PostgreSQL/H2 result or full list-feature acceptance.

## Disposition and concrete findings

**Source foundation is bounded and fail-closed in its stated scope; runtime acceptance remains PENDING. No protected deployment, working publisher, PRESENT projection or public list endpoint is certified.** The list requirements review's C1–C4 are not closed wholesale by this foundation.

| ID | Finding | Required disposition |
|---|---|---|
| Q1 — CLOSED in updated source | Initial script granted only integrity/health SELECT, contrary to V025's header. Concurrent owner update now grants SELECT on `provider_credential_versions`, `provider_evidence_publishers`, `provider_credential_integrity` and `provider_credential_health`, plus schema USAGE; operations prose matches. | This is broader private metadata read access than the initial snapshot, including verifiers/references, and remains SELECT only. No public serialization permission follows. Effective native privileges still require verification. |
| Q2 — PENDING runtime; native source now reviewed | `ProviderEvidencePostgresIT`, shared assertions, H2 test and dedicated workflow now exist. Source uses full Flyway migration chains and actual role SQL; no completed execution reports were supplied. | Missing-class checkpoint is superseded. Attribute completed native/H2 execution and zero-skip counts to tested source; resolve Q5/Q6 source gaps below. |
| Q3 — OPEN, deployment/full-feature blocker | Neither V025 nor the role script enforces retained credential-version immutability for the actual Compose application principal. V022 has no immutability guard; the new FK is not a general UPDATE/DELETE prohibition, and does not freeze the verifier. Compose shares its bootstrap administrator with the application. | Keep the operations guide's explicit unsecured-deployment limitation. Before protected rollout, inspect effective nonowner/nonsuperuser runtime privileges, including inherited and column grants, and prove forbidden version mutations fail. This is not a demand to run SQL on a user DB in this slice. |
| Q4 — OPEN, future publication/full-feature blocker | Publisher identity/epoch/fence fields are representations only. DDL provides no authenticated producer, immutable preparation provenance, current-fence enforcement, active-version/CAS protocol, sticky FAILED, monotonic observation rule or restore invalidation mechanism. | Preserve the intentionally ungranted publisher. Review and verify the guarded producer/epoch/provenance design before any write grant or PRESENT/full-feature claim. Arbitrary well-shaped metadata is not trusted verification. |
| Q5 — OPEN, catalog coverage gap to BE | Native catalog checks assert evidence FK definitions, integrity/health column names, new-column NOT NULL/no-defaults and timestamp precision, but omit exact publisher column allowlist, general column types/lengths, exact PK/unique/check inventories and the retained eight-column unique target. | Add discriminating native catalog assertions for all three new tables and retained binding uniqueness. Assert publisher PK/fence uniqueness behavior with separate IDs sharing a fence. One valid publisher and nonpositive epoch failures do not establish the complete catalog contract. |
| Q6 — OPEN, capability/state coverage gap to BE | SET ROLE checks reject evidence-table DML/TRUNCATE/ALTER and credential-version updates/deletion/truncation/ALTER, but publisher registry checks only SELECT and rejected INSERT. No successful FAILED or UNAVAILABLE fixture establishes acceptance of both allowed enum alternatives. | Add registry UPDATE/DELETE/TRUNCATE/ALTER denial checks for both capabilities, with SQLSTATE 42501; use a populated registry so a row mutation cannot be vacuous. Add valid FAILED/UNAVAILABLE rows in isolated fixtures. These remain source-test gaps, not observed SQL privilege defects. |

Q3/Q4 are honestly deferred prerequisites, not undisclosed implemented controls. They need not be invented to accept an empty storage-only foundation after Q2/Q5/Q6 are resolved; they remain blockers to claims of deployed protection or the complete contracted feature.

## Source evidence

Reviewed the complete [list contract](../engineering/PROVIDER_CONNECTION_LIST_READ_CONTRACT.md), [requirements review](PROVIDER_CONNECTION_LIST_REQUIREMENTS_REVIEW.md), retained V022, V025, [role script](../../backend/operations/provider-evidence-roles.sql), [operations guide](../../backend/PROVIDER_EVIDENCE_OPERATIONS.md), Compose database/runtime identity wiring and both quality/evidence workflows, plus all three evidence Java test sources. Findings below describe SQL text and intended fresh-role behavior, not measured effective privileges.

| Area | Actual source behavior and limit |
|---|---|
| Role capabilities | Two explicit `NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS` roles. Updated reader receives schema USAGE and SELECT on exactly credential versions, publisher registry, integrity and health. Publisher receives no explicit schema or table grants. No LOGIN, password, role membership, runtime wiring or credential provisioning is introduced. PUBLIC privileges and other deployment paths remain independent. |
| Script safety/scope | One transaction; a pre-existing capability-role name causes fixed refusal before creation. No automatic privilege repair or membership reassignment. No revocation of ambient PUBLIC/owner/superuser rights. New restricted role declarations cannot secure an unrestricted application administrator. |
| Exact binding | V025 adds a unique target on `(organization_id, connection_id, provider, credential_revision, external_locator, external_key_version, hmac_key_version, hmac_domain_version)`. Both integrity and health use matching eight-column FKs and four-part identity PKs. These bind evidence to retained version metadata rather than just connection/key ID. No cascading update/delete clause is declared. Actual catalog metadata and rejection behavior still require native verification. |
| Integrity | Required exact binding fields, state restricted to VERIFIED_AT_WRITE/FAILED, required microsecond timezone timestamp and positive bigint publication revision. UTC civil-range checks exclude PostgreSQL infinities by comparison and deliberately narrow the accepted finite range. No state default or automatic VERIFIED row. This table does not itself make FAILED sticky or establish preparation provenance. |
| Health | Required exact binding fields, USABLE/UNAVAILABLE enum, three required microsecond timezone timestamps, positive bigint publication revision and required publisher UUID/positive epoch/fence UUID. Checks encode `observed_at <= published_at < valid_until <= observed_at + 60 seconds`, and civil-range checks on all three times. Future observation versus live DB time and stale/equal replay rejection are not DDL controls. |
| Publisher registry | UUID PK, required positive bigint epoch and required globally unique UUID fence. No health-to-registry FK is intentional so old observations do not obstruct fence invalidation. Future read/publication logic must establish an exact current publisher/epoch/fence match. Registry presence alone is no authentication. |
| Empty migration and privacy | V025 contains DDL only: no INSERT/backfill, success default, producer, trigger, function or grant. New columns describe identity/reference/key versions, states, timestamps and revisions; no credential bytes, ciphertext, reversible envelope or password column. The role script likewise stores no credential. Source absence of seeds is not an executed migration row-count assertion. |
| Operations claims | Guide accurately calls this empty storage, distinguishes absent evidence from unavailable relations, defers guarded publication and documents current Compose administrator access. It explicitly avoids successful observer, verified producer, public publisher/list and protected-deployment claims. H2 is described as structural compatibility only. |

The exact composite FK protects referential binding, not authenticated publication or global credential immutability: privileged writers can still insert arbitrary matching evidence, alter an unreferenced version, or modify the verifier. Ownership remains a trusted migration capability, not an HTTP identity.

## Runtime evidence and remaining acceptance checks

**Executed by this reviewer: no test suites, SQL, migrations, role changes, provider requests or deployment actions.** Read-only shell inspection and this report are the only reviewer actions. No completed CI notification/run was supplied; no CI pass/count/commit is attributed here. Existing historical runs cannot certify V025. H2 compatibility and native PostgreSQL behavior are unverified at this checkpoint. Both native and H2 source now invoke the full Flyway migration chain, superseding the initial H2 selected-script checkpoint. Shared assertions cover empty storage before fixtures, eight binding substitutions per evidence table, invalid states/revisions/times/nulls/duplicates and microsecond/60-second positive fixtures. Native source adds infinities, unsupported civil dates, maximum ASCII bindings, exact FK text and disposable-role checks. This is prospective test coverage, not execution evidence.

## Native fixture and workflow follow-up

Source review requested after native-class availability; BE/main own test/workflow changes. The following is the concrete handoff for those owners, with Q5/Q6 identifying remaining changes:

| Requested boundary | Source finding |
|---|---|
| Explicit disposable gate | Test requires exactly `PROVIDER_EVIDENCE_ALLOW_ROLE_TESTS=true`, explicit TASK_EDIT_TEST JDBC/user/password inputs and PostgreSQL 16. Both quality and dedicated evidence workflows now use that same guard. No remaining `TASK_EDIT_TEST_DISPOSABLE` mismatch in the reviewed native source/workflows. The flag is an explicit opt-in; it cannot independently prove a manually supplied URL is disposable. |
| Isolated random database | Fixture generates an internal UUID-based database and owner name, refuses pre-existing fixed capability-role names before creating objects, then creates its own database. Test data and migrations target that database, not the supplied bootstrap database. Fixed capability roles remain cluster-wide, so simultaneous suites sharing one cluster should fail refusal rather than be treated as isolated successes. Workflows use separate service clusters per job. |
| Migration owner | Random owner is created NOLOGIN/NOSUPERUSER/NOCREATEDB/NOCREATEROLE. Flyway connects with disposable administrator credentials and uses connection init `SET ROLE owner`; it does not provision an owner password/login. Native assertion requires all four relevant tables to belong to that owner. This is a separate migration execution role, not a separate authenticated connection principal. |
| Actual script and SET ROLE | Reads the actual repository operator SQL and executes it after migration. For each capability, checks seven false role flags, absent inherited memberships and actual current_user after SET ROLE. Reader SELECT succeeds on evidence, version metadata and registry; publisher SELECT fails. Denials require 42501, so arbitrary syntax/constraint errors do not count as privilege proof. Table owner/admin intentionally retains fixture corruption capability. Registry denials need Q6 completion. |
| Cleanup ownership flags | ownerCreated/databaseCreated/capabilityRolesCreated are set only after respective creation calls return. Existing role refusal leaves these false; teardown drops only its random database, then capability roles only if this fixture's script completed, then its random owner. It does not indiscriminately drop pre-existing shared roles. Partial setup can leave owned artifacts if cleanup itself fails; no successful cleanup runtime claim follows from these flags alone. |
| Repeat application | Native test expects fixed refusal P0001 on actual script rerun, explicitly rolls back and compares grants before/after. This checks refusal without implicit privilege repair. |
| Epoch representation | Test advances registry epoch/fence and shows the old health row remains stored but no longer joins on all three publisher fields. This establishes a fixture join condition only, not actual production reader validation, guarded epoch authority, immutable provenance or restore protocol. |
| Workflow discovery | Quality explicitly selects the native class alongside retained suites and default Test classes. Dedicated workflow runs H2 and native commands with PostgreSQL 16 and uploads Surefire reports even after failure; native step runs unless cancelled. No conditional test annotation silently skips missing native inputs. Completed discovery/counts and formatting remain CI evidence to inspect. |

No independent agent messaging transport is exposed in this session. The BE handoff is recorded here and in the session update; no external message or other owner's file was written.

When BE/main supplies completed artifacts, inspect these discriminating assertions before changing the disposition:

1. Actual retained Flyway migration chain succeeds on disposable PostgreSQL and H2; all three new tables start empty. H2 results establish portability/structure only.
2. Native catalog assertions cover exact column/type/nullability/default/check/unique/FK allowlists. Both evidence tables reject each independently changed scope/provider/revision/locator/external-key/HMAC-key/domain binding. Exercise valid retained versions rather than relying only on malformed fixtures.
3. Accept equality at observed/published and exactly 60 seconds; reject published equal to expiry, reversed times, over-60-second validity, null/nonfinite/out-of-civil-range timestamps, invalid states and nonpositive bigint revisions/epochs. Positive bigint maxima must not be mistaken for proven safe increment/overflow handling.
4. Execute the actual operator script only against explicitly privileged disposable storage. Use real SET ROLE and successful evidence reads plus rejected INSERT/UPDATE/DELETE/TRUNCATE and credential-version mutations for both capabilities. Inspect publisher-registry access separately; prove the publisher has no accidental write path. Check role flags, absent memberships, repeat-run refusal and effective privileges rather than grant text alone. Fixtures must not add production grants to make assertions pass.
5. Review tests for exact stored-column/value privacy allowlists, no success startup seed/default producer, and meaningful non-skipped native discovery. Confirm workflow environment reaches the native class and final reports identify tests/failures/errors/skips and tested source.

These checks certify only storage/capabilities. Publication races, epoch loss/restore, sticky integrity failure, trusted verification provenance, RR page-clock/completion behavior and public HTTP DTO/admission remain later feature gates, not implied results of migration tests.

## Inspected source fingerprints

These hashes identify the snapshot reviewed, not a published/tested commit. Concurrent changes require re-review.

| Artifact | SHA-256 |
|---|---|
| List read contract | `a663e72d7692ee847da173794b30837dcd4463d5a72618754d7adaa5452ba657` |
| List requirements review | `2f7e6808e740f50b3930341a6b1fdc1be0dd147c5d6840e58985126d1667778c` |
| Role script | `19cec2d5e3fd04d6c90919fe6c3e18f28c63b8add53d4663fd2c9de42cc0dbc7` |
| Operations guide | `16b86a549b6fa19b12795e42650dc5eb6723875260e582b89f2045f961e0cd28` |
| V025 migration | `93dcc2c8bf19b1031b133e7ca9718e26919a62c9579dff0876b0124aaca40339` |
| Native evidence test | `7d6879d3a37f46ba73736b6c0b4344c592fc855775ff9345a700608eafe587fc` |
| Shared evidence assertions | `ac7c2fc05fd6267c14e78a67180aec99f93726d2d7c21a6608cb9588d686fb50` |
| H2 evidence test | `4888a198631c091f2c6c720a8c45f3acb6837492da25038d4242999ce405d2a0` |
| Dedicated evidence workflow | `ff6a60bd1237a47bedf58a0cef055cd4dea1fae86115b5a710c35717f2060539` |
| Quality workflow | `b43b5455260815aab719308167283b302e730a5b110f4e0f5a1bc9479ebcd6a4` |

Only `docs/qc/PROVIDER_EVIDENCE_STORAGE_REVIEW.md` was written by this reviewer. No code/test/workflow edits, commit, merge, CI publication or deploy were performed. Main retains integration and publication ownership.
