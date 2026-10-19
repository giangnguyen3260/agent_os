# Create Team — independent QC report

Status: **Requested final source check complete: no concrete publication blocker identified in reviewed source. CI execution pending; not production acceptance.**

Scope: independent source review against `docs/engineering/TEAM_CREATE_CONTRACT.md`. QC owns this report only; no application, test, fixture, or workflow edits. Backend/frontend work was ongoing during inspection. Missing or changing work is not classified as an application defect. Final source check and main-provided CI evidence remain outstanding.

Branch: `feature/team-create`. HEAD at inspection: `8b44a11df5314823ca303c70eb97bbe32367a413`. This HEAD does not identify the uncommitted implementation. File hashes below identify inspected working-tree contents at report generation; files can change afterward. No tests were executed by independent QC and no attributed execution logs were supplied.

## Final source check — publication scope

Reviewed after backend writer completion; frontend guard/header work was still being finalized. This section supersedes earlier checkpoint observations where they differ. No concrete blocker identified in the inspected backend and current frontend paths. This conclusion is source-only and does not certify tests passed.

- Optional-manager immutable-list failure: fixed by non-null guard before `ids.contains(managerAgentId)`.
- Budget precision: explicit `scale() > 2` rejection present, including a `1.000` rejection assertion. Range and normalization remain present.
- GET locking: detail and eligible reads now use read-only REPEATABLE_READ transactions and nonlocking authorization reads. Creation/replay still use fresh enabled/version/ADMIN checks with organization → account → membership → sorted-agent locks. This is the main-directed read-path adjustment; contract wording that literally requires row locks on every request should be reconciled separately, not reported as an accidental source blocker.
- Receipt lookup still precedes eligibility; conditional claims, manager update and receipt insertion remain in one transaction. Nullable manager detail SQL now explicitly casts the null parameter to UUID.
- Frontend POST callback uses `response?.data`, so null/missing envelopes enter frozen same-key retry with busy cleared. UUID/org/DRAFT checks remain. Network/5xx errors preserve attempt; 409 conflict code matches backend `error.code` and disables another create.
- Successful POST marks navigation finished, bypassing the unsaved guard; failed detail reads offer GET retry. Cancel clears memory after confirmation. Current route guards call `canLeave`; beforeunload protection is present.
- Existing OrganizationController one-argument constructor and OrganizationService constructor/create method remain; no constructor compatibility regression observed.
- Ten new backend `@Test` methods are present. Expected CI: **49 backend tests (39 baseline + 10 new), 72 browser cases (54 baseline + 18 new)**. Counts remain expected, not executed.

Main reports Maven dependency resolution blocked by DNS and will verify in CI. Independent QC did not reproduce that environmental failure or run Maven. No attributed CI logs have been received. Earlier QC-01–05 are advisory evidence limits; they do not block this requested source/publication check. Quoted-budget coercion remains an optional clarification: the contract explicitly singles out strict JSON integer handling, so no budget-string blocker is asserted without a clarified requirement.

### Final-check source fingerprints

Timestamp: 2026-10-19T18:19:04.402511+00:00

HEAD: `8b44a11df5314823ca303c70eb97bbe32367a413`; implementation remains in a shared working tree. Later writer changes require their own checks/log attribution.

| File | SHA-256 |
| --- | --- |
| `backend/src/main/java/com/agentos/organization/application/NewTeamDraftService.java` | `adee996bfccb825258619dcfd8e22ca072c4d2dc9d9582cd0f06ec84592270ee` |
| `backend/src/main/java/com/agentos/organization/api/OrganizationController.java` | `2169cddeea3b65b0f473b57c6fb273924b4ff2717c9df3acc68ec16647edd921` |
| `backend/src/main/java/com/agentos/organization/api/dto/NewTeamDraftRequest.java` | `cdba0a92fadc571c7efb9cb1b826bd13a8dddb411f744ce77f7e440ee345a547` |
| `backend/src/main/resources/db/migration/V005__team_drafts.sql` | `19cc2b4c2595156ae8bf01dd1c7044bd950ff3d468f0034bdcbb593e564d8cda` |
| `backend/src/test/java/com/agentos/organization/TeamDraftIntegrationTest.java` | `f75b874f7f06d070bc6a95a2843a3f48ec509215d065636326d5ae57810d7b36` |
| `frontend/src/app/features/teams/team-page.component.ts` | `104749c32a9e68b700027f78070f98f036b9195ec9327d01fbb7f524dd83114c` |
| `frontend/src/app/features/teams/team.service.ts` | `4a777e8a8608ef92c2f5d40775e409be8028a18ebbbc05dd277036823454b54b` |
| `frontend/src/app/features/teams/team-page.component.html` | `d64646798616887c73b949e568b175420850d6cdf4a4e8136a1f0fc5bba9dc7a` |
| `frontend/src/app/app.routes.ts` | `a9b745cef4f1efa06e328d0bcf0fdb1c14d8fe33740610aa928d0d2d8f2095b6` |

## Coordination update

Main reports backend fixes in progress for immutable `List.of` null membership checks, scalar budget precision, and read-only GET locking. The latest inspected canonicalization guards `ids.contains(managerAgentId)` with a non-null manager check; runtime closure remains pending. Budget and GET-lock work must be reviewed after writer completion, not counted as additional checkpoint defects. Existing immutable backend constructor compatibility is reported preserved and must be retained in final review.

Frontend source explicitly freezes malformed successful responses and handles 409 IDEMPOTENCY_CONFLICT without replacing its key. No blocker is asserted for these implemented paths absent contrary finished-source or runtime evidence.

The agreed browser scope is six new cases across three viewports: **72 total (18 new + 54 baseline)**. The coverage observations below are advisory evidence limits, not requests to expand the case count or release blockers by themselves. Do not classify in-progress missing files as defects. Final review will prioritize concrete defects in completed source and attributed execution results.

## Findings and evidence gaps

No runtime-confirmed application defect at this checkpoint. The following are review/coverage gaps, not claims that production behavior fails:

| ID | Observation | Required closure |
| --- | --- | --- |
| QC-01 | Browser ambiguous-result case compares keys, not serialized POST bodies. Network loss runs on desktop, 503 on tablet, malformed 201 on mobile. | Assert identical body and key on retries; state explicitly whether each failure mode must run on every viewport. Current suite exercises each mode once across the three viewports. |
| QC-02 | Browser validation case checks empty Team name, retains Basics and concurrency through Back, and accepts Cancel on Review. It does not assert invalid Purpose/Description, empty/invalid numeric Setup fields, Review Back retention, or rejected Cancel preserving state. | Extend assertions within the six-case plan as needed for full three-step validation/Back/Cancel evidence. |
| QC-03 | `IDEMPOTENCY_CONFLICT` freeze is implemented in frontend source but not exercised by the six browser cases. | Show a browser assertion that edits/Back/new attempts remain blocked and no replacement key is generated. |
| QC-04 | Integration suite explicitly selects H2 PostgreSQL mode. It contains concurrency and rollback assertions; source presence is not evidence of actual PostgreSQL behavior. | Supply actual PostgreSQL execution evidence for locking, competing claims, rollback, receipt replay, and migration constraints, or record the untested boundary. Browser PostgreSQL runs cover only their asserted scenarios. |
| QC-05 | `monthlyBudget` is an unannotated BigDecimal; strict integer deserialization is applied only to concurrency. No numeric-string budget assertion or global coercion override was observed. | Verify whether quoted budget values are rejected as required by the intended strict numeric contract. Treat as a candidate issue until an HTTP result or definitive mapper configuration is available. |

## Source observations (not test passes)

| Contract concern | Observed implementation | Remaining evidence |
| --- | --- | --- |
| Fresh authorization and lock order | Service locks organization, account, membership, then sorted selected agents; enabled/current credential version checked before receipts. Reviewed auth account-lock sites did not show subsequent organization locking. | Final source recheck; database concurrency/revocation logs. |
| Replay before eligibility | Canonical body hash and stored original response lookup precede agent eligibility; conflicting digest throws IDEMPOTENCY_CONFLICT. | Attributed replay/concurrent-key execution. |
| Atomic claim and rollback | Transaction spans team insertion, scoped conditional agent updates, manager assignment, receipt; conflict is unchecked. Test injects receipt constraint failure and asserts all three effects roll back. | Actual execution, especially PostgreSQL. |
| Scoped agents/detail | Queries filter organization and unassigned DRAFT/ACTIVE status; manager requires MANAGER; detail filters organization. Composite tenant/team/manager foreign keys in V005. | Tenant/ineligible/migration runtime results. |
| Numeric constraints | Custom integer token deserializer; service concurrency range and exact budget scale/range checks; database numeric ranges. Legacy configuration nullable. | Boundary and numeric-string HTTP results; PostgreSQL migration evidence. |
| Ambiguous retries | Immutable body/agent array and key retained on network/timeout/5xx or invalid response; Back/fieldset frozen. POST response checks UUID, organization and DRAFT. | Retry body equality and failure matrix execution. |
| Idempotency conflict | Dedicated flag disables Create draft, retains frozen attempt. | Browser execution. |
| Detail GET failure | Confirmed POST routes to detail; retry invokes detail GET/load, not create. | Browser case explicitly counts one POST; execution pending. |
| Wizard state | Continue validates step; Back retains fields; Cancel confirms and navigates; memory clears on destroy/route/account changes; versions guard stale responses; 401/403 revokes private state. | Full validation/navigation and revocation execution. |

## Planned browser accounting and fixtures

Source inventory: 18 baseline test declarations (login 4, recovery 4, workspace 3, dashboard 7) plus 6 Create Team declarations. Three configured projects: desktop 1440×1000, tablet 768×1024, mobile 390×844. Expected discovery: **72 cases = 54 baseline + 18 new**. This is source accounting, not a discovered-test log or pass count.

New cases: validation/Back/Cancel; persisted draft and scoped assignments/reload; ambiguous retry; member/CSRF denial; admin revocation/private-state clearing; detail GET-only retry.

Team fixture uses separate team admin/member accounts and organizations, plus manager/worker IDs per viewport. Password hash is copied from bootstrap fixture. Revocation mutates the shared team admin role and restores it in finally; current workers=1 and fullyParallel=false are material to its isolation. Do not parallelize those shared-account tests without further isolation.

## Final acceptance checklist

- Re-read finished source; reconcile findings against exact final SHA and dirty state.
- Main supplies CI run URL/job, checked-out SHA, full relevant log results, and artifacts where available.
- Attribute backend results (39 baseline plus new tests), PostgreSQL scenarios, frontend lint/format/build, Java format/import checks, and all 72 browser cases separately. No inferred pass from source or earlier SHA.
- Record skipped tests, failed attempts, retries, environment limits and fixes honestly. Sonar is paused per contract.
- Recheck source after any fix; earlier logs do not validate later changes.

## Working-tree evidence fingerprints

Generated at 2026-10-19T18:17:19.692071+00:00. These are checkpoint hashes, not a claim of an atomic snapshot during concurrent writes.

| File | SHA-256 |
| --- | --- |
| `docs/engineering/TEAM_CREATE_CONTRACT.md` | `a82fdbaa75d2435286f4353fec9055da41d1ee2852fcfb6a0475e87ddfa3e2ba` |
| `backend/src/main/java/com/agentos/organization/application/NewTeamDraftService.java` | `2d62f7e300bd7ab8ca57349a2ea8fd48a9c097d7f158460541f356b58acdd0fb` |
| `backend/src/main/java/com/agentos/organization/api/dto/NewTeamDraftRequest.java` | `cdba0a92fadc571c7efb9cb1b826bd13a8dddb411f744ce77f7e440ee345a547` |
| `backend/src/main/resources/db/migration/V005__team_drafts.sql` | `19cc2b4c2595156ae8bf01dd1c7044bd950ff3d468f0034bdcbb593e564d8cda` |
| `backend/src/test/java/com/agentos/organization/TeamDraftIntegrationTest.java` | `8dfe97124e99a7cce88e2d05325507aceeb5912c619daa184940cc7d11390fa6` |
| `frontend/src/app/features/teams/team-page.component.ts` | `a6806a3ec7e7fa14d4b9be449debe36aa83fe3ddc5dc39beb0111c13d6958ba2` |
| `frontend/src/app/features/teams/team-page.component.html` | `fc4daedb3b06f2da7a7863e99ecd314aa3cc7835fdd6b4b4c726db48355cf20a` |
| `frontend/src/app/features/teams/team.service.ts` | `4a777e8a8608ef92c2f5d40775e409be8028a18ebbbc05dd277036823454b54b` |
| `frontend/tests/e2e/team-create.spec.mjs` | `934cc928b9f4a2ff825904bcea153f8d314f231dcdf8bf0e8a7e307ae28e0ca3` |
| `.github/workflows/docker.yml` | `f69bcba3f73ad6040a5d41106bf5278ee679de80add0621718889ac90da0c5ee` |
| `scripts/team-create-ci-fixture.sql` | `ee799e96b26fd1c5e102c6a229d193cbcf621a06089757e1e779a4ff2144c11b` |
