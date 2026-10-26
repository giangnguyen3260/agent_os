# Trace Explorer PRnext independent QC review

Status: **authored; runtime acceptance pending**. Review date: 2026-10-26. No merge/closure approval. Only the assigned browser spec, native probe and two QC documents were authored. No commits. Shared application files remain owned by main and FE/BE workers.

## Executed evidence

| Check | Result |
| --- | --- |
| `node --check frontend/tests/e2e/trace-explorer.spec.mjs` | PASS |
| Python `compile` of native probe, without executing HTTP/SQL | PASS |
| Playwright `--list --grep 'trace explorer independent QC'` | PASS: exactly24 tests, eight per desktop/tablet/mobile |
| Playwright `--list --grep-invert 'trace explorer independent QC'` | PASS: existing609 tests in26 files retained |
| `git diff --check` | PASS at review time |
| Real native probe | NOT EXECUTED: no Docker executable; no healthy stack |
| Real24 browser cases and24 screenshots | NOT EXECUTED: localhost18080 and8080 have no listener; Docker unavailable |
| Full609 regression/backend/Docker/restart/logout | NOT EXECUTED by this author; main's retained CI evidence required |

Collection proves test discovery only. There are no passing runtime claims or generated screenshots in this review. Existing main workflows discover the exact describe and upload `frontend/qa-visuals/trace-explorer-*.png`; this author did not modify them.

## Source review and issues for main

Source was inspected from the live shared tree after the FE template/CSS and BE controller/service/query/DTO/tests appeared. No explicit worker-ready notification or final immutable application revision was available; findings are provisional and must be rechecked after workers finish.

1. **Frozen authorization implementation discrepancy:** `backend/src/main/java/com/agentos/trace/TraceService.java` implements a private SQL authorization check rather than calling the frozen contract's `MissionCreateService.authorize(org,human,false,false)`. The existing helper is package-private in `mission/application`. The duplicated query appears to check the same read invariants (org, membership role, enabled account and matching credential version), but the implementation requirement has not been met literally. Main must resolve or explicitly adjudicate this; QC expectations remain frozen.
2. **Desktop Trace selection absent in observed template:** the desktop sidebar Trace anchor has neither `class="selected"` nor `aria-current="page"`, while the compact dialog contains both an unselected Trace anchor and a second selected Trace anchor. Contract requires selected Trace navigation; duplicate compact entries are also incorrect. TE01 requires desktop `aria-current=page`; TE02 requires exactly one compact Trace entry. Do not weaken those checks to accommodate the observed template. FE owner must fix and main must recheck.
3. **Execution gap:** source reasoning cannot prove actual PostgreSQL semantics, CSRF/session transport, delayed-response lifecycle behavior, rendering, screenshots, or retained regressions. Completed native/24 browser/609 baseline and existing pipeline evidence are required before closure.

Positive source observations, subject to runtime evidence:

- `TraceService` is REPEATABLE_READ/readOnly. Projection explicitly names safe metadata columns, without actor identity or payload. Visibility JOINs resolve same-org Mission/Task/Team, reject dangling/foreign/inconsistent references, allow task-derived Mission and mission-only null Team, and use the same source for count/page/detail. Stable order is created_at DESC,id ASC.
- `TraceQuery` allowlists and deduplicates query keys, uses canonical lowercase RFC4122 UUIDv1–5 and bounded canonical page syntax, Java strip/100 code points and literal SQL escaping. DTOs explicitly include nulls. Detail has no query parameters.
- FE clears page/selection/context before session/workspace checks, validates exact success shapes and selected ID/org, cancels subscriptions, and fences account/route/destruction generations. Raw workspace403 goes through local HTTP failure handling instead of changing shared AuthService behavior. Error and denied hooks are distinct from empty/unavailable success. List and detail are independently fetched for selection.
- Forms, timeline and selected metadata use native controls and Angular interpolation. Scoped Task and Mission links match existing route definitions; Mission workspace link includes `/workspace`. Compact dialog has Escape/focus-return code; actual responsive rendering remains unverified.
- Main's revised `observability/api/ObservabilityController.java` maps both retired legacy routes directly to EntityNotFoundException, has no service dependency and performs no query. Existing outer organization authorization remains responsible for403/401. The appended legacy contract is covered by native ADMIN/MEMBER404 and foreign403 assertions, including foreign/missing Mission404 preservation and raw-response payload scanning. Old internal service is not exposed by this controller.

## Acceptance handoff

`TRACE_EXPLORER_TEST_CASES.md` maps the24 authored cases,24 capture names and native boundaries. Native oracle is explicit accepted fixture IDs (33), not a reproduction of BE visibility SQL. Error injections derive from fetched real200; success never comes from fabricated data. Real membership/account changes and event99 writes are disposable and restored in finally blocks. Receipt/business snapshots hash payload in SQL and include organizations, approval_grants, agent_draft_config, agent_configuration_versions and all installed receipts, as well as existing business surfaces. Only digests leave PostgreSQL; authentication/session state remains excluded. Browser before/after hooks compare this boundary for every case; isolated audit writes are removed and authority values restored before comparison.

Run the real native probe and the exact describe with one worker/retries0 against the fixture; collect all24 PNGs. Fix any discrepancy in the owner application files, then rerun affected checks. Execute the retained609 baseline plus backend/Docker/restart/logout checks and attach completed CI evidence. Do not equate authored checks, source review or test collection with acceptance.


## Coordinator integration resolution

Rechecked after FE freeze: desktop Trace is selected with aria-current=page; compact dialog contains exactly one selected Trace link. The package-private authorization discrepancy is explicitly resolved in the contract: local equivalent current organization/account/membership/version read predicates, without shared security changes. Removed the obsolete untracked TraceService copy left by the worker rename, retaining TraceExplorerService to avoid Spring bean-name collision with the legacy observability service. Retained meaningful behavioral tests and privacy/transaction checks; removed SQL-spelling assertions. Strengthened held-request delivery to observe original browser response/body completion or cancellation before stale-DOM assertions. These are source/local integration resolutions; runtime acceptance remains pending CI.
