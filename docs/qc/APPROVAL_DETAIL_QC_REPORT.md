# Approval Detail QC — 23 October 2026

Status: source review clear; local static gates passed; runtime gates pending. Branch feature/approval-detail stacks on draft/unmerged PR19 (base2eb369b).

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
