# Approval grant management — independent review

Status: **contract reviewed; implementation and execution pending**. Normative source: `docs/engineering/APPROVAL_GRANT_MANAGEMENT_CONTRACT.md`, accepted option A on 2026-10-23. Historical proposal/review OPEN policy language is superseded. No further policy confirmation is required.

## Scope and ownership

This worker owns only this review, `APPROVAL_GRANT_MANAGEMENT_TEST_CASES.md`, and `frontend/tests/e2e/approval-grant-management.spec.mjs`. No application, backend, workflow, baseline test, or commit changes are authorized for this worker. Screenshots are disposable synthetic test evidence. Baseline 168 backend / 402 browser counts are inherited requirements, not results for this candidate.

## Contract review

No concrete contract blocker identified. Exact immutable result fields cover recovery correlation. ADMIN issuance is deliberately distinct from existing approval decision authority. HIGH and integer 1–168 hours are authorized. Legacy duplicates, reciprocal administrator issuance, and restored membership reuse before expiry are accepted behavior.

| Critical boundary | Required independent implementation check |
| --- | --- |
| Receipt versus authority | POST replay compares the normalized command first, then returns historical result only with fresh actor authority; receipt GET has no current recipient/Team/grant/revision precondition. Decision receipt replay still requires a live decision grant. |
| DTO privacy | Exact field allowlists and scoped IDs before rendering; recipient has no email; restricted history alone exposes reason; no fabricated legacy provenance. |
| Scope and revision | Selected-scope GET is required for review; null selection never authorizes final save. Revision/scope/authority change invalidates acknowledgement. |
| Atomicity and replay | One org/actor management key namespace across targets and operations; replay does not increment; new mutation advances exactly once with grant/event/audit/receipt in one transaction. |
| Global account locks | Org first, deduplicated UUID-sorted human accounts, deterministic memberships, fresh checks after waits; no actor-first helper before acquiring recipient. |
| Ambiguous results | Forward original POST to actual backend before dropping/corrupting its response. Exact captured key/body retry only. Null receipt and later state do not prove failure or success. |
| Denial and account change | Denial erases private DTO/reason/body and disables POST retry, retaining only same-account opaque recovery correlation. Account change erases that correlation too. |
| Single grant revoke | Other legacy exact-scope rows remain; UI count describes recorded rows, never guaranteed effective authority. Archived/disabled/removed/expired/unsupported grants remain revocable. |
| Navigation | Dirty/unknown Back, reused route and unload warn. Abandonment/reload may lose exact-key recovery. Late results cannot restore old private or success state. |
| Validation | Java strip/code-point/control semantics; explicit integer bounds; strict UUID/lowerhex64; acknowledgement exactly true; no coercion or unknown fields. |

## Implementation findings

Initial source arrival reviewed: response records match the exact contract field sets; V013 is additive, preserves legacy rows, and has no membership cascade for management history. Command deserialization explicitly checks exact fields, integer ranges, acknowledgement, hash/UUID syntax, Java strip/code-point/control semantics. These are source observations, not executed passes.

### AGM-R01 — replay membership removal after lock wait returns wrong denial status

Source corrected; native regression execution pending. Medium protocol correctness. In `ApprovalGrantManagementService.mutate`, the existing-receipt branch locks the actor account, then uses `one("select account_id from organization_memberships ... for update", ...)` before `authorize`. If membership disappears after initial authorization but before that lookup (for example while waiting for the global account lock), `one` raises `EntityNotFoundException` and returns 404. The contract requires fresh absent issuer authority to return 403. Use an optional membership lock lookup followed by fresh `authorize`, as the new-write path already does. No target disclosure or committed write is demonstrated by this finding. Add a controlled-wait native regression and verify no receipt replay success.

Runtime dependency: this worker environment has no `docker` or `mvn` executable and no configured `E2E_BASE_URL`. Main/CI runtime and the dedicated fixture handoff are required for execution. No multiagent address/send tool is available in this worker registry; worker ID alone does not enable direct handoff.

## Execution evidence

Collection-only check: Node v24.19.0; `cd frontend && ./node_modules/.bin/playwright test --list` exited 0 and collected exactly **402 tests in 20 files** across the three existing projects. No baseline test was edited. This verifies collection, not passing behavior. The new browser spec now contains ten cases per viewport (30 new cases); collection was repeated by the main agent. No new browser case executed locally yet. No browser, backend, concurrency, rollback, restart, screenshot or CI acceptance is claimed. The planned cases live separately in the test-case document.

## Final acceptance gates — not yet satisfied

- Record source freeze commit and clean/explicitly enumerated candidate diff; independently review the exact FE/BE/migration candidate.
- Run new real-backend browser cases at 1440×1000, 768×1024 and 390×844, preserving the existing 402 browser cases and 168 backend tests.
- Inspect synthetic edit/review/issued/revoke/revoked/unknown/denied screenshots for all three widths, focus and horizontal overflow.
- Obtain exact-candidate Quality/Docker/full-regression/native PostgreSQL/restart evidence and immutable CI logs/artifact hashes. Independently read published logs before final disposition.
- Keep Sonar paused and PR draft/unmerged; no production-scale or runtime execution claim.

## Main-agent continuation checkpoint

- Frontend lint, format check and production build passed after inventory route integration.
- Production grant validator probe passed actor/org/account/key/operation/revision correlation, private-field rejection, nanosecond timestamps, exact maximum TTL boundary, revoked legacy expiry and Java code-point/strip/control semantics. This probe runs in Code quality CI.
- Java import rules, Python probe syntax and browser spec syntax passed.
- AGM-R01 source now uses optional membership locking followed by fresh authorization. The native PostgreSQL probe includes an observed actor-lock wait with concurrent membership removal and requires 403. Runtime confirmation remains pending.
- Local environment has no Maven or Docker executable. Backend tests, PostgreSQL concurrency and real browser cases remain CI acceptance gates; no runtime pass is claimed by this checkpoint.

## First runtime candidate findings

Candidate `9d244f6ffbf96ff4695da190d22fb8c6da0a8147`: Code quality run39997316702/job120564106259 executed176 backend tests:174 passed,2 failed. Docker run39997316683 failed at the same backend test gate before runtime stack acceptance.

- Canonical UTF-8 hash: Jackson byte serialization escaped supplementary Unicode as surrogate sequences, producing a different digest from the accepted literal UTF-8 vector. The service now serializes the canonical TreeMap to a JSON string then encodes UTF-8; the independent vector value is unchanged.
- Result field oracle: the test expected13 fields while the frozen contract defines12. Replaced cardinality-only assertion with an exact field-name set; no wire field was removed and no authorization oracle was relaxed.

Fix candidate `902a8263ff41e59af00dabefd48bde5bdfee73ef` is under CI. Local independent Python canonical vectors, Java formatter/import checks, frontend tooling/auth/member/grant validator probes all passed. Full runtime result remains pending.

Quality run39997798610/job120565756728 succeeded for `902a8263ff41e59af00dabefd48bde5bdfee73ef`:176 backend tests passed (8 management tests), Google Java format/import checks, frontend dependency audit, lint/format/tooling, auth/member/grant validator probes and production build passed. Docker/native PostgreSQL and432 real-browser executions remain pending; Quality success alone is not final acceptance.

Docker run39997798599/job120565756352 applied V013 and reached the native management probe. Earlier issue/replay/concurrent issue/audit rollback/legacy revoke/reversed-account/fresh-issuer-wait assertions completed before the post-revoke waiter setup timed out during login: login correctly requires the deliberately locked recipient account. Move competitor authentication and sealed snapshot reads before acquiring the deliberate account lock. Both observed org-lock waiter and subsequent403 assertions are unchanged. This run did not reach browser execution or final native acceptance. The continuation also renders exact UTC receipt timestamps with semantic time elements, avoiding DatePipe precision loss.
