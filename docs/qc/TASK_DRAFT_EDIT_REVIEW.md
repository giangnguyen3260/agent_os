# Task Draft Edit independent QC review

Status: **all four owned QC files frozen; authored and collected; live acceptance pending**. This is not CI publication approval or a PostgreSQL/browser pass claim.

Only assigned files were authored: `frontend/tests/e2e/task-draft-edit.spec.mjs`, `scripts/task-draft-edit-probe.py`, this review, and `TASK_DRAFT_EDIT_TEST_CASES.md`. App, contract, main fixture, migrations, routes, workflows, and other agents' files were read but not edited. No commit was made.

## Evidence as of source review

| Check | Result |
| --- | --- |
| Browser JavaScript syntax | Passed node --check |
| Native Python syntax | Passed compile without executing/importing main |
| Browser suite collection | **45** executions: 15 cases × 3 widths |
| Full browser collection | **678** in 28 files, preserving the existing **633** plus 45 |
| Production FE validation/privacy/idempotency/state harness | Independently rerun: passed |
| New real browser execution | Not run; no listening app on 127.0.0.1:8080 or :18080 |
| Native PostgreSQL/HTTP execution | Not run; docker executable absent on this authoring host |
| Screenshot target / actual | **27 / 0**; no visual pass claimed |
| Existing baseline execution | Not rerun here; collection retention is not execution evidence |

The main-integrated workflows now explicitly select TaskDraftEditPostgresIT in Quality, provision the disposable SQL fixture, invoke the native probe, and run the exact new browser describe before the disjoint baseline. These were inspected, not changed by QC. The production FE harness pass does not replace the real backend browser gate.

## Source feedback to main before publication

The current BE service/controller/wire parser/DTOs/V017 and FE component/template/validation/service/navigation guard were inspected against the frozen contract. Current main route/detail integration uses actual TaskDraftEditComponent and the existing authGuard plus taskEditNavigationGuard/taskEditLeaveGuard. Fresh session and raw workspace ADMIN checks run in the component, with independent current ADMIN checks in the backend. There is no requirement for a nonexistent generic AdminGuard.

No confirmed application defect was established by this source review. In particular, the current service locks organization, account/membership and scoped Team/Mission/Task; replay is resolved before mutable-state checks; four-field update, immutable event, receipt and safe audit are transactional; wire parsing locally rejects coercion, duplicate keys and malformed Unicode. FE uses epoch/subscription fences, actual API requests, frozen retry body/key, and fresh authority checks. This is source evidence only, not a guarantee against runtime failures.

Resolved integration feedback: main reports the visual artifact glob now retains PNGs plus `task-draft-edit-manifest.jsonl`. Expected complete artifact inventory is 27 PNGs and one manifest, with nine states at each width. The manifest records actual successful captures; actual count remains zero on this host pending CI. Workflow ownership remains with main.

Held-response strengthening is complete in all three privacy cases. A shared helper captures the original browser Request before route.fetch, holds only a real successful backend response, and requires explicit release. Fulfillment exceptions are tolerated only if that captured original request reports a non-null failure after release; no broad error-message regex is used. Other route/fetch/assertion errors reject the observed completion gate. Release awaits original.response() and, when present, response.finished(); a missing response must have an actual failure. Two requestAnimationFrame turns drain rendering before stale-DOM assertions. Release is idempotent; security restoration and sibling cleanup run even if delivery verification fails. No timeout/retry/acceptance expectation was relaxed.

Latest DTO source confirms explicit JsonInclude.ALWAYS on Values and ReceiptLookup (also the other edit records). Legacy nullable fields and null receipt envelopes remain asserted by exact-schema native/browser checks. Main separately reports its full production FE harness/lint/format/build passed; QC independently reran only the production harness and its own spec formatting/syntax/collection checks.

The native probe file is complete and available for the workflow invocation. Its full gate includes actual PostgreSQL same/different-key races, observed row-lock authority changes, real event/receipt/audit failure rollback, historical replay after state change, exact canonical four-field persistence and retained owner/dependencies/lifecycle/intake, strict wire/CSRF/scope, safe Trace, and global unrelated-business digests. Tests never construct a successful fake DTO. Browser faults fetch actual successful backend responses before transport/JSON corruption, including proving SQL commit for POST faults.

## Resolved test fixture isolation

Main reported 12 AuthenticationIntegrationTest setup errors in the broad backend worker run (377 reported tests): setup deletes all human_accounts after authentication/membership cleanup, while another suite's retained approval rows still reference those accounts. QC inspected the current source and diff: AuthenticationIntegrationTest now supplies its own `jdbc:h2:mem:authentication_isolated` datasource through @SpringBootTest, retaining PostgreSQL mode, DATABASE_TO_LOWER, DB_CLOSE_DELAY and the TIMESTAMPTZ domain initialization. The diff changes only that test annotation; cleanup and assertions are unchanged. TaskDraftEditIntegrationTest already uses its separate `task_edit_isolated` H2 database. QC agrees this test-only isolation addresses the reported cross-suite fixture collision without dropping foreign keys or relaxing application, production, or security behavior. The original worker failure count is main-reported, not independently reproduced here. Main is running the full `*Test` regression with Java21/Maven offline and will record the actual result from `/tmp/task-edit-main-regression.log`; no full-regression pass is claimed yet. Existing 633-browser baseline and all QC acceptance expectations remain unchanged. All four owned QC files are frozen again after this review-only update.

## Run and evidence handoff

On the disposable real app/database, provision main's fixture once, then run the native probe before the browser surfaces. Supply E2E_PASSWORD through the existing environment and E2E_BASE_URL for the real app.

```sh
python3 scripts/task-draft-edit-probe.py
cd frontend
npx playwright test --workers=1 --retries=0 --grep 'task draft edit independent QC'
npx playwright test --workers=1 --retries=0 --grep-invert 'task draft edit independent QC'
```

Require 45/45 new browser passes, native terminal PASS, 633/633 baseline passes and the existing Quality/Docker/restart/logout checks before closure. Independently count unique `(project,state)` records in the actual manifest; require exactly the nine documented states for each of desktop/tablet/mobile and all 27 corresponding nonempty PNGs. A fail-fast job can legitimately produce fewer screenshots; report the observed count, not the target.

Use a fresh fixture/database and clean artifact directory for a rerun: native context revision0 and browser legacy null assertions intentionally reject reused mutated fixture state. Temporary SQL security/preflight mutations and rollback-injection objects restore in finally; successful task edits/history remain as real acceptance evidence. See the companion test-case document for the exact coverage inventory and limitations.
