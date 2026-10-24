# Approval grant management — final QC

Accepted for the bounded development scope below. PR23 remains draft/unmerged, stacked on feature/member-access; this is not production release or Mac deployment acceptance.

## Tested candidate and evidence

- Application/test candidate: `838b1d9e091cd985e6937280e03e8be3cd5ef767`.
- Tree: `e1cd05808d3edb8919c0a61d6e014ad09b049245`.
- Tested PR merge: `155ac287c2266b24fbeaf839c1aa6537c25c5130`, base `5f40a6be7a6d05877adef12c9ec1bc1473570dc9`.
- Final documentation changes after this candidate do not modify application, migration, workflow or test sources.

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend + quality | 176 tests passed, including8 management tests; Java format/import, FE audit/lint/format/tooling, production wire validators and build passed | [Quality run40007020133](https://github.com/giangnguyen3260/agent_os/actions/runs/40007020133), job120597404879 |
| Docker + PostgreSQL | Healthy stack and V013 migration; all native probes passed | [Docker run40007019847](https://github.com/giangnguyen3260/agent_os/actions/runs/40007019847), job120597403681 |
| Real browser | 435 passed, zero failures/skips:402 baseline +33 management instances (11 cases ×3 viewports),13.6minutes | Same Docker log, completed2026-10-23T19:12:43Z |
| Restart/logout | Exact persisted session survives backend restart; logout rejects replay | Same Docker log,2026-10-23T19:13:23Z |
| Visual review | 21 synthetic PNGs, seven states at1440×1000,768×1024,390×844; full-page heights may exceed viewport | Artifact12525274001, approval-grant-management-visuals |

Artifact SHA256 independently verified after download: `bfb2b0d1f5ce74d0cb6e9819bf47b610b5c33922e8a09e01afdd86d8655d4d38`. States: edit, review, issued, revoke/history, revoked, unknown and denied. Coordinator inspected all21 captures, grouped by state: readable dark text, wrapped identifiers and explanations, visible form/actions/focus outline, no clipping or horizontal overflow in captured layouts. Browser overflow/focus assertions also passed. This does not claim exhaustive accessibility or all-device acceptance. Captures contain disposable synthetic QC data only.

## Delivered behavior

Fresh enabled/version-matching Org Admins issue to another current enabled MEMBER/ADMIN, never self-issue, with explicit Team/PUBLISH_PREVIEW LOW|MEDIUM|HIGH and integer1..168hours. Review precedes acknowledgement and POST. Revoke targets one exact grant ID; legacy expired/disabled/removed-recipient/unsupported grants remain revocable, and other recorded matching grants are preserved.

V013 stores revision ledgers, restricted management events and durable actor/org/key receipts without inventing legacy provenance. Strict scoped DTOs and commands, canonical UTF-8 hashes, exact duration/precision checks, Java reason semantics, safe audit and atomic rollback are exercised. Organization-first locking and globally ordered account locks are covered by an observed reversed-account barrier. Actual post-revoke question/decision waiters are observed blocked on the writer and denied after release. No Task/Mission/provider/worker mutation is added.

UI reads real paginated Team/member/grant data. Tests cover blank edit, review, real issue/readback/reload/revoke/history, stale revision and duplicate conflicts, private DTO rejection, dirty navigation/reused routes, real committed lost/malformed replies, exact same-key replay, null receipt with advanced state remaining unknown, denial erasing private data while retaining opaque GET-only recovery, account change suppressing an old committed response, and late history403 during an unresolved real REVOKE. B10's delayed-context Back/Forward test and B11's late-denial recovery passed at all three widths.

## Review disposition and limits

Prior scoped independent FE/BE source findings were corrected: fresh issuer denial after a lock wait; Unicode byte hashing; pending-write correlation on late read failure; private DOM gating and session revalidation; obsolete route/workspace response handling. Earlier test failures exposed fixture sharing, browser document-vs-SPA/history assumptions and premature removal of the null-receipt oracle. Corrections retained privacy/authority/exact replay assertions. Final B10 waits for real current-context completion before history traversal, with a bounded delayed-response observation; no timeout increase, retry or assertion removal was used to mask failure.

Runtime logs above are the final acceptance evidence; historical partial counts are not used as passes for this candidate. SonarQube stays paused. Invitations, role changes, approval producer APIs, other action schemas, Task execution, provider integration and production scale/security hardening remain outside this slice. No branch was merged and no user Mac was accessed or deployed to.

Independent final evidence review (separate QC worker) retrieved both completed GitHub job logs, confirmed176/435 and33 management browser instances, native probes, restart/logout, merge parent/tree binding, artifact upload count/digest and draft/unmerged PR state. No blocker was found in that evidence scope. Download hash verification and all21-image visual review were performed by the coordinator rather than claimed as independently repeated.
