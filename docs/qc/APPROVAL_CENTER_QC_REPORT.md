# Approval Center QC — 22 October 2026

Status: accepted within the bounded Approval Center read scope. Final candidate Quality, Docker/PostgreSQL/browser and visual gates passed. Draft [PR19](https://github.com/giangnguyen3260/agent_os/pull/19), branch `feature/approval-center`, remains unmerged and stacks on tooling PR18.

## Revision

- Candidate: `3156977fa8d8b12398691578402b6da4a5d3e208`.
- Checked merge: `861234cfb57ebc43d7e5b1793355bddee368eb2d` into base `3cfaa56bd1ec65d6ab81fb0bb190932061ed6c93`.
- The follow-up documentation commit records completed evidence and the frozen independent review only; the tested candidate remains the revision above.

## Delivered scope

Forest S14 desktop1440/tablet768/mobile390: real metadata list, three approval groups/counts, fixed 20 paging, stable ordering, URL/reload, safe inline Inspect and existing scoped Mission/Task links. V010 adds a metadata ledger with composite Team/Mission/Task scope constraints and no production seed. Requester ID/name are scrubbed when the current Team no longer matches. Counts and rows share a repeatable-read snapshot within each response; separate offset pages are not a cross-request snapshot.

Pending tasks never become synthetic approvals. Empty storage is truthful until a future authorized Governance producer exists. Decided retains individual APPROVED/REJECTED/EXPIRED/CANCELLED/INVALIDATED labels and does not imply all records are human decisions. ADMIN/MEMBER read access is not an approval grant. No decision endpoint, executable snapshot, runtime resume, provider call or business write is delivered.

## Final candidate evidence

| Gate | Evidence |
| --- | --- |
| Code Quality | [Run 39822748692](https://github.com/giangnguyen3260/agent_os/actions/runs/39822748692), job 119978007848: success. Completed logs confirm127 backend tests (6 new), zero failures/errors/skips at17:28:16 UTC; Maven BUILD SUCCESS17:28:17; including-dev audit 0 vulnerabilities17:28:27, style/build and actual CLI/dev-server tooling smoke passed |
| Docker/PostgreSQL/browser | [Run 39822748603](https://github.com/giangnguyen3260/agent_os/actions/runs/39822748603), job 119978007314: success. Healthy stack/V010 migration, SPA/API/private management and all native probes passed. Approval Center exact projection/count/order/paging/moved-requester/tenant/no-write probe 17:30:16 UTC. All 321 browser instances passed 17:40:49 (10.0m), no retries. AC07 passed desktop 17:32:17 (31.8s), tablet 17:35:37 (31.7s), mobile 17:38:56 (31.7s). Exact session survival after backend restart and logout replay denial passed 17:41:24. Dev-inclusive npm audit 0 and actual CLI/dev-server consumers passed in Alpine |
| Local frontend | Final application lint/format/build and Git whitespace checks passed. Java formatter/import checks and browser/probe syntax checks passed |
| Local backend/browser | Maven could not reach registry via environment proxy before compilation; Chromium launch was blocked by host socket permission. No local backend or rendered pass claimed |
| Independent source review | Carver reviewed original slice, fixes for both low UX findings, and the corrected AC07 harness. No blocking source finding. Review is source-only, not independent test execution; see APPROVAL_CENTER_REVIEW.md |
| Visuals | Final candidate artifact 12445883421:6 synthetic QA screenshots. Download SHA256 `a3f068cdca637400165c9fd77b4fa326d46d000b3dee27ce1a6cec75e7aac7c3` matches GitHub digest. All 6 PNG files are byte-identical to the inspected checkpoint; coordinator additionally inspected final tablet-expanded metadata. Desktop/tablet/mobile rows/cards/expanded metadata follow Forest tokens, readable dark text and long-ID wrapping; no static imagery is used |

The final component/HTML hashes match independent follow-up review: `7ca9572086d9cae8de65b1a77000ca7ebb1e4c82` and `5054fdcde130ef3d241f13e9493eb2f2dc3a4881`. AC07 harness was reviewed again at the final candidate. Review execution provenance stays separate from coordinator-verified logs.

## Corrections and historical checkpoints

1. Candidate dd9b1d5 Quality39818790331/job 119964193319 compiled and ran 127 tests:126 passed; a new test incorrectly expected405 for MEMBER POST. Security correctly returned403. Tests now assert MEMBER403 and ADMIN405; authorization was not weakened.
2. Candidate600cadf Quality39819318947/job 119966082110 passed 127 tests. Docker39819318976/job 119966085250 stopped on an ambiguous `id` in isolated QA membership fixture. Qualifying `a.id`/`a.email` corrected the fixture; application/migration unchanged.
3. Candidate6b8eada Quality39820043018/job 119968599949 and Docker39820043041/job 119968600411 passed:127 backend,318 browser instances, native probes, restart/logout. Native Approval Center probe 17:11:45 UTC, browsers17:18:10 (5.9m), restart/logout17:18:45. This checkpoint preceded the two low UX fixes.
4. Independent review identified misleading beyond-last-page text and unbounded workspace preflight. Both were fixed and independently source-reviewed: distinct empty-page message/First page and 30-second component preflight timeout with retry. Underlying read-only Promise may finish later but cannot resume the unsubscribed timed-out await; epochs protect superseded loads.
5. Candidate364f190 Quality39821005373/job 119971941578 passed 127 tests/audit 0. Docker39821005188/job 119971941494 passed native probes and 318 browser cases, but all 3 new AC07 instances failed after timeout/retry because the Playwright harness tried to fulfill an already handled route. Restart probes were skipped, so this run was not accepted. Final harness explicitly retains the first async route on a gate, continues retry to the real backend, releases the late foreign response, then waits for response completion/delivery and paint before checking state. No application source changed for that correction.

## Coverage and remaining limits

Tests include fresh enabled/versioned membership and actual backend revocation, foreign tenant denial, invalid filters, safe projections, stable group/page counts, moved requester, schema constraints, no writes, URL/reload, empty-page recovery, failure/malformed clearing, native retry and compact Escape/focus/overflow. AC07 uses a real 30-second preflight stall at all 3 widths; its delayed foreign response is failure-only interception. Positive rows always use real HTTP/PostgreSQL. Synthetic screenshot artifacts capture only dedicated QA fixture display data, never session storage/credentials/traces or real user records.

Remaining evidence limits from independent review: no exhaustive existing foreign Mission/Task/requester negative matrix, concurrent cross-request pagination snapshot test, actual browser account/route-change race, live revocation push or production load/penetration/Windows test. Safe display text is a future producer obligation, not a guarantee established by field names. Complete Governance/grant/decision/execution acceptance remains open. Sonar paused, not passed. No Mac deployment claimed.
