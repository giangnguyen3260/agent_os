# Task owner history QC

Status: accepted within the frozen history contract on `feature/task-owner-history`, draft [PR35](https://github.com/giangnguyen3260/agent_os/pull/35), stacked on PR34. No merge or deployment. Sonar scanning remains paused.

Accepted application/test candidate C4: `7bd502f5761ae47376b8bf168c17436620ca71b7`, tree `4b2d58f9b0508544436c1bbdeefaf8f5eb40ae93`. Both workflows checked out merge `0ef279d02bc63882960de0cf1600030572d2ca4f` against base `1d955d1a378fa6f2056e8fd1cdbb8eeeaf09731d`. Later Markdown-only closure preserves every application, test, fixture, probe and workflow byte; its own runtime is not implied by C4 evidence.

Current enabled credential-matching ADMIN can inspect actual V020 owner events, immutable before/after snapshots and current same-organization member display names (explicit null after membership removal). Fixed20 pages use descending revision, exact structural scope and an actual total. No synthetic initial assignment, lifecycle gate, current Agent/foreign-name lookup, receipt internals, schema migration or business writer. See [contract](../engineering/TASK_OWNER_HISTORY_CONTRACT.md), [requirements review](TASK_OWNER_HISTORY_REQUIREMENTS_REVIEW.md), [cases](TASK_OWNER_HISTORY_TEST_CASES.md) and [source review](TASK_OWNER_HISTORY_REVIEW.md).

## Completed runtime evidence

| Gate | Accepted result |
| --- | --- |
| [Code quality40910064492](https://github.com/giangnguyen3260/agent_os/actions/runs/40910064492), job123575299260 |849 tests;0 failures,0 errors,0 skips. Includes43 history H2 and44 history PostgreSQL. Google Java formatting, frontend lint/format, production validation/state harnesses, dependency checks/audit and Angular build passed. |
| [Docker40910064618](https://github.com/giangnguyen3260/agent_os/actions/runs/40910064618), compose job123575299350 |Completed success; build regular599 tests with0 failures/errors/skips, healthy actual stack and migrations. |
| Native history HTTP/PostgreSQL |PASS:21 actual owner commands; exact revision pages and count; current scoped actor rename/null; immutable Agent snapshots after metadata move; foreign privacy; lifecycle-independent reads; strict query/body/envelope; selected-record corruption; unchanged committed business values and tuple identities. |
| New history browser |42 passed:14 cases at1440/768/390. Actual API/DB comparison; current session/raw ADMIN workspace chain; empty/out-of-range; historical/current-name distinction; error clearing and GET-only retry; strict projection; scoped privacy; held response account/role/route/page and stale-denial fences; native Back/menu. |
| Retained browser |81 owner +780 other retained =861 passed. With42 new,903 disjoint browser invocations. Focused INV-B08 adds3 repeat checks, not3 new unique scenarios. No retry/selection/assertion relaxation. |
| Retained native and durability |Owner atomicity/recovery/authority and all retained native steps passed. Session survives backend restart; logout rejects replay. |
| Responsive evidence |15 PNGs/JSONL,5 states ×3 widths, actual hashes/ZIP binding and captured review; see [visual manifest](TASK_OWNER_HISTORY_VISUAL_MANIFEST.md) and [independent final review](TASK_OWNER_HISTORY_FINAL_REVIEW.md). |

Local checks cover source/tooling only: frontend lint/full formatting/production harness/build, Python AST, workflow YAML, browser syntax/scoped formatting and42-case collection. Local Java17 lacks Maven/Java21 and Docker; actual backend/PostgreSQL/browser runtime results above come from completed CI logs. Isolated SQL/browser fixtures are not production hardcoding. Business snapshot proof concerns committed values/tuple identities, not forensic observation of rolled-back SQL attempts; native PostgreSQL read-only/isolation tests and SELECT-only source review supply the transaction boundary. Offset pages are independent snapshots and may shift under concurrent writes.

All15 screenshots were checked for scoped names, wrapped historical values, dark readable Forest styling, pagination and cleared private content in error/denied states. No blocking captured finding. This is not all-browser/every-pixel inspection, formal accessibility certification, production deployment, Figma frame matching or completion of execution/scheduling. No Figma frame was edited. Existing writes, receipts and fingerprint behavior remain covered by the retained suite.

## Earlier candidates and corrections

The following entries record earlier failures and their disposition; their pending wording describes those attempts, not C4 acceptance above.

Before C1, independent QC found two native-oracle gaps. Main included account/membership rows and xmin/ctid in business snapshots, retained raw error bytes privately, required strict safe JSON envelopes/codes/messages/details, and checked known private markers. Offline negative error counterexamples now reject HTML/private JSON and invalid-calendar fallback timestamps; no HTTP/SQL or successful business DTO was mocked in that check. Snapshot evidence concerns committed row values/tuple identities, not forensic observation of rolled-back SQL attempts; native PG read-only/isolation tests and source SELECT-only review supply the transaction boundary. See independent review dispositions.


C1a5897c0/Quality40906071322/job123561051452 failed the required Google Java formatting gate before runtime acceptance. Applied canonical CI format patch from artifact12912465272, ZIPsha660ffdb71e1ccdcc32efc8357724fc81db47ce418719574b0f56fb6da518c0b6 matching REST/download. C2 also makes the native API client's Accept:application/json explicit, retaining strict rejection of HTML/private error bodies. C2 compile/native/browser and visual results remain pending.


C2Quality40906550159/job123562771360 compiled and ran849 invocations, with57failures,2errors,0skips. The shared new history-test command helper used MockMvc csrf() as a request parameter; the accepted owner API correctly rejects unknown query/body parameters. C3 sends CSRF as a header. The lifecycle fixture also violated existing ck_tasks_pending for WAITING/BLOCKED; C3 supplies real pending reason/time/resolver for those states and nulls for other states. No application parser, authorization, database constraint or assertion is relaxed. Runtime acceptance remains pending the corrected tests.


C3 `63363d6a3a696005c7607d14b891ae7c6251ca95`, tree `c2f24c8938186ce7d983a31bd9e545cbdb254f90`, tested merge `367042a05f394320956c1ea5c9331712696f3e8a`: Quality40907306012/job123565468230 passed849 tests,0 failures/errors/skips (history H2 43 and PostgreSQL44). Docker40907305804/job123565467992 passed both native history and retained owner probes, but new browser step stopped after1passed/1failed/40notrun. The page-navigation assertion expected one session read although the always-running route auth guard and component each perform a fresh session read. C4 corrects only that exact expected pagination sequence; focus, visibility and manual refresh still require exactly one session→workspace→history chain. Authorization and application code remain unchanged. The partial artifact12912197651 has only two desktop PNGs plus manifest and is not accepted as complete visual evidence. Retained browser and restart/logout steps were skipped; no full Docker or UI acceptance is claimed.


C4 corrects the exact pagination authority oracle, preserving single-session checks for focus/visibility/reload and requiring two sessions for always-guarded page navigation. It also validates actual authority response bodies against database identity/workspace values and labels each action. No application, security policy or backend assertion was weakened. C4 completed the full required runtime sequence and supersedes earlier pending acceptance.
