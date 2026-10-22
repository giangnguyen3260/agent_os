# Approval Detail QC — 23 October 2026

Status: accepted within the bounded PUBLISH_PREVIEW decision-recording development scope. Source, Quality, Docker/native/browser and captured-region visual gates passed. Branch feature/approval-detail stacks on draft/unmerged PR19 (base2eb369b).

## Delivered candidate scope

Responsive S15 requested action/evidence, explicit target/scope acknowledgement, blank required rejection reason and recorded decision readback. Details read real V010/V011 storage. Only the complete bounded PUBLISH_PREVIEW schema is eligible for decisions; other action types, missing/corrupt/expired/stale snapshots and absent/scoped/self grants fail closed. Actual destination/resourcePath/visibility are displayed to the human. Org Admin title is never a grant. No production seed, request/grant-management API, request-information/reply or Task/worker execution flow.

## Checks and provenance

- Local FE format/lint/build passed during integration. Java Google formatting/import and script syntax checked; script syntax and final FE format/lint/build passed; backend execution remains pending.
- Backend worker authored integration cases and native PostgreSQL fixture/probe; coordinator authored11real-backend browser cases, collected as33 instances across3viewports. Collection is not execution.
- Independent QC authored42prospective cases and reviewed source. Its provisional findings must be resolved/qualified before acceptance; source review is not independent runtime execution.
- Local Maven networking/uncached parent and Chromium socket restrictions prevent local backend/browser acceptance. Runtime evidence will come from completed Quality/Docker CI logs and synthetic screenshots.

## Corrections before CI

QC identified reused-route submission identity loss, ambiguous retry-denial handling, stale retry after lost access, nullable approved reasons and missing unload warning. Coordinator added captured immutable submission identity/body/key, exact-route activation guard plus deactivation/unload guards, minimal unknown-outcome correlation after private-body clearing, suppression of denied POST retries and nullable record handling. Verification on actual browser remains pending.

Coordinator/worker review additionally identified an organization-last lock order conflicting with older organization-first writers, omitted executable parameters from the UI snapshot DTO, historical decision readability tied to current head and duplicate blocker codes. Source fixes are integrated and reviewed. Native revocation and organization-first probes now assert an observed database lock waiter before releasing the holder transaction; execution remains pending. Requester human membership must match the request organization before either new decision, and dedicated integration negatives isolate this check.

## Pending acceptance

Record exact candidate/merge/run/job IDs, actual backend/browser counts, native concurrency and rollback evidence, all requested responsive states and screenshot digests. Preserve any failed checkpoints and separate doc-only final head from tested candidate. Sonar paused; no Mac deployment, production load/penetration acceptance or complete Governance execution claim.

## First CI checkpoint

Application candidate17d4421 (merge78077aa into2eb369b) passed Quality run39829456859/job120001414883:142 backend tests including15 new Approval Detail tests, zero failures/errors/skips; FE style/build/tooling checks and npm audit0. Docker run39829456839/job120001414684 built/started the healthy stack, migrated V011 and provisioned detail data, then stopped at native replay assertion line237. The probe incorrectly compared complete ApiResponse envelopes even though ApiMeta.current() intentionally generates a fresh requestId per response. Initial persisted decision fields/time/hash checks passed. Correction must retain status200 and exact immutable data equality while excluding request-specific metadata. Browser/visual acceptance did not run at this failed checkpoint.

## Second CI checkpoint

Candidate03f3b1a fixed the native envelope oracle and added HTTP replay assertions. Quality39830378526/job120004645080 and Docker39830378627/job120004645382 both stopped at the added persisted-JSON assertion: Jackson valueToTree produces LongNode for snapshotVersion while parsing the identical HTTP JSON produces IntNode. Exact first/replay HTTP data equality passed; the additional persisted comparison failed despite identical serialized content. Candidate89279de changes only that assertion to compare parsed serialized JSON consistently, retaining complete data equality, persisted readback and distinct request IDs. No application-source change between these candidates. Native/browser gates did not run in checkpoint2.

## Corrected-candidate Quality acceptance

Candidate89279de483d7d2fc894a75c74a76f1c05c896541, merge e586bc48a84fb4cda5008c7d4fba703a19ea6778 onto2eb369b, passed Quality run39830965706/job120006654080. Completed logs establish142 backend tests including15 Approval Detail tests, zero failures/errors/skips, Maven verify success, FE format/lint/tooling/build and npm audit0. Docker/native/browser/visual acceptance remains pending for this candidate.

## Final runtime and captured-region acceptance

| Gate | Exact evidence |
|---|---|
| Candidate | 89279de483d7d2fc894a75c74a76f1c05c896541; merge e586bc48a84fb4cda5008c7d4fba703a19ea6778 onto2eb369b98d7791e8795cc67d900a7eac9a55b123 |
| Quality | [Run39830965706](https://github.com/giangnguyen3260/agent_os/actions/runs/39830965706), job120006654080:142 backend tests including15 new; zero failure/error/skip, Google style/FE checks and npm audit0 |
| Docker | [Run39830965508](https://github.com/giangnguyen3260/agent_os/actions/runs/39830965508), job120006653739: healthy stack/V011, all native/regression probes, browser suite and restart/logout passed |
| Native decisions | Completed log18:27:25 UTC: exact persisted decisions/events/receipts, scoped ADMIN/MEMBER, identical replay, concurrent opposite decisions, observed org/grant lock waits, revocation, rollback/blockers and no Task/Mission/Agent effects |
| Browser | 354 passed (10.8m), zero retries; all33 AD-B01..B11 viewport instances passed. Completed18:38:54 UTC. Existing321 regressions retained |
| Restart/logout | Exact session survives backend restart; logout rejects replay,18:39:31 UTC |
| Visual | Artifact12449612029 approval-detail-visuals,30 synthetic PNGs tied to89279de. ZIP SHA25684a0be9341007b30c1cab4ffb8f05e5dc8570ba4a27d4adbe14d0607654bc0fb verified after download |

Coordinator inspected all30 images in three contact sheets plus desktop pending/approved, tablet verify and mobile pending/reject/verify at original resolution. Dark readable text, consistent Forest controls, wrapping of hash/path and compact navigation are visible without an observed layout blocker in captured regions. Captures are viewport-only and some retain scroll position; lower decision record content is outside some frames. This is not full-page pixel parity or exhaustive accessibility acceptance. Actual record persistence/reload and navigation/menu/uncertainty interactions are separately covered by passed browser cases.

The42 independent QC inventory entries remain a prospective matrix, not42 independently executed cases. Reviewer performed source review; execution and visual evidence above were obtained by coordinator. No unresolved blocking source finding remains in the reviewed bounded adapter. Initial failed oracle checkpoints above are retained. Final documentation-only commit does not change tested application, fixtures or tests; its head is distinct from this tested candidate.

## Remaining scope and limits

Only reviewed complete PUBLISH_PREVIEW parameters are supported. Request-information/reply, trusted request/snapshot producer, grant management, Tool Gateway/runtime execution, Task/Mission state transitions and other action schemas remain unavailable. Explicit grants are persisted and enforced, but production issuance is not delivered. No production seed, Mac deployment, full Governance completion, high-load/failover/penetration or all-screen acceptance is claimed. Organization-first locks serialize writes by organization; scaling refinements need a later compatible lock contract. API immutability does not protect against a malicious privileged database owner. Browser-injected denials exercise FE classification; actual revocation is established separately by the native probe. Account changes bound late workspace preflight by30seconds rather than immediate cancellation. Sonar remains paused.
