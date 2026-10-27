# Task Draft Edit independent QC cases

Frozen oracle: `docs/engineering/TASK_DRAFT_EDIT_CONTRACT.md`. Main owns the disposable `scripts/task-draft-edit-ci-fixture.sql`, route/detail link, and workflows. QC modifies only its browser spec, native probe, and two reports. Existing authGuard plus component fresh raw-workspace ADMIN checks and API current-authority checks are the agreed boundary; no generic AdminGuard is required.

## Real browser gate

Exact describe: `task draft edit independent QC`. Existing Playwright config supplies one worker, zero retries, and desktop 1440×1000 / tablet 768×1024 / mobile 390×844. The spec asserts one worker and overrides retries to zero. Each case runs on each surface's isolated organization (2/3/4), using real login/CSRF and real context/write/receipt endpoints. There are **15 cases × 3 widths = 45 collected executions**.

| Case | Assertions |
| --- | --- |
| Four-field persistence | Real loaded values; complete actual before/after review; unchecked acknowledgement; 201 receipt; SQL persisted values and revision+1; exact remaining task columns, owner, dependencies, agents and Mission intake retained; actual detail link and reload |
| Legacy completion | Fixture nulls remain blank; required selection; no review/write before completion; legacy null labels; actual 201 with exact nullable before values |
| Blocked states | Tasks 3–14, including all eight non-DRAFT lifecycle states, REVIEW Mission, paused intake, archived Team, max revision; actual blocked contexts, disabled inputs, unchanged SQL task rows and zero POST |
| No-op and validation | Unchanged review and required/overlong title/description/criteria cannot reach review or POST; actual SQL row unchanged |
| Acknowledgement | Unchecked on each review, disabled save until checked, resets on edit/back and focus, zero POST |
| Dirty navigation | Actual detail editor link; native Back cancellation; native reload cancellation; reused task identity cancellation and confirmed discard through Angular history bookkeeping; no write |
| Preflight stale | Actual PG Mission intake-version update; explicit save rejects before POST and retains draft; exact SQL version restoration in finally |
| Lost committed response | route.fetch actual 201, SQL committed revision, abort reply; UNKNOWN frozen form, one POST; malformed actual receipt response stays UNKNOWN; subsequent explicit actual receipt matches full original and recovers |
| Malformed committed success | route.fetch actual 201, SQL commit; malformed JSON delivered; UNKNOWN; explicit real receipt recovers; protected invariants unchanged |
| Same-key explicit retry | Committed lost reply; explicit retry only; byte-identical request body and key; actual 200 exact replay; SQL full task row unchanged |
| Role loss / late write | Hold actual committed 201; PG demotion and focus; denied with form/review/result erased; release cannot restore old private data; role restored in finally |
| Actor swap / late read | Hold actual context GET; sibling tab logs out and logs in as other actor in same browser session; focus clears old data; late context cannot restore it |
| Opaque receipt recovery | Committed UNKNOWN then PG role loss erases body/draft; retry disabled; restore ADMIN; explicit GET-only recovery of exact historical receipt, no additional POST |
| GET faults | Actual successful context fetch precedes malformed/503 response injection; private returned data and review removed; generic error rather than denied; explicit GET reload restores real data; zero POST |
| Actor swap / late write | Hold real committed 201; shared-session account change; old draft/result erased; late success fenced; retry disabled; one POST |

All successful DTOs originate from the real application. Failures are introduced only after `route.fetch()` has returned a successful real response; POST fault handlers also check committed SQL revision/title. All three held cases use a shared helper that captures the original browser Request before route.fetch and synchronizes real response arrival with explicit release. It tolerates a fulfillment exception only after release and only when original.failure() is non-null; broad error-text matches do not bypass failures. Other handler errors reject the completion gate. Release awaits original.response() and response.finished() when a response exists; otherwise actual cancellation is required. It then drains two animation frames before stale-DOM assertions, without assuming cancelled requests emit requestfinished. No retries, timeouts, or expectations were relaxed. Cleanup still restores authority/closes sibling pages if delivery verification fails.

## Capture inventory

Nine asserted states × three widths = **27 target PNGs**. Names are `task-draft-edit-{desktop|tablet|mobile}-{state}.png`.

| State | Actual-state prerequisite |
| --- | --- |
| edit | Scoped context and populated real form |
| review | Actual four-field diff and checked acknowledgement |
| saved | Actual successful persisted receipt |
| legacy | Actual null context and blank fields |
| blocked | Actual READY task blocked context |
| conflict | Actual PG source change, retained draft and preflight error |
| unknown | Lost committed response and visible UNKNOWN |
| denied | Actual role loss and erased private editor |
| recovered | Explicit valid receipt lookup after committed lost response |

Each successful screenshot appends project, width, state, filename and test title to `task-draft-edit-manifest.jsonl`. Append occurs after assertions and capture; the file is evidence of actual captures, not a prewritten expected-state manifest. Use a fresh artifact directory for each run. No screenshot was captured in the authoring environment: **actual count 0/27**, pending live gate.

## Native HTTP/PostgreSQL gate

`python3 scripts/task-draft-edit-probe.py` uses only Python stdlib HTTP/cookie/JSON/threading/process utilities and docker-compose psql. The fixture must be applied first on a disposable database; E2E_PASSWORD is inherited, never embedded. The native surface is organization 1, separate from browser surfaces. The probe is bounded to 299 seconds and prints PASS only at the end of all assertions.

| Area | Native evidence required |
| --- | --- |
| Migration/retention | Context revision 0; legacy task 2 retains exact title/null strings; unrelated existing task rows remain exact |
| Commit | Four canonical fields and only allowed updated_at/revision columns change; exact receipt keys, scope, before/after, persisted event/time, stored original receipt/digest and safe audit references/payload |
| Read/no-op/rejection | Full business-table digest snapshots around rejected commands and receipt/read/replay operations; no revision/event/receipt/audit effect |
| Business isolation | Full Team/Agent/Mission/dependency rows unchanged; all unrelated business-table digests unchanged globally; all existing journal rows retained with exactly three new event/receipt/audit rows |
| Safe Trace | Actual trace event detail exposes only safe projection and scoped Mission/Task references, no form/receipt/raw actor data |
| Historical replay | Exact original success returned after actual later READY state; no rewrite; state restored |
| Canonical replay/conflict | Whitespace canonical equivalent key replays exact receipt; same key changed command conflicts; admin2 receipt lookup returns null |
| Strict wire | Exact keys, missing fields, unknown fields, duplicate JSON keys, non-object/trailing/invalid UTF8 JSON, >65536-byte body, invalid Content-Type, required Idempotency-Key, invalid/noncanonical/combined keys, unknown/duplicate queries, strict revisions/types/fingerprints, text limits/control/surrogate rejection |
| Unicode acceptance | Competing valid boundary requests use 255 Unicode codepoint titles, 8000-character descriptions with Java-strip-compatible surrounding whitespace, 4000-character criteria |
| CSRF/scope/roles | Missing CSRF rejected; MEMBER new reads/write denied but detail allowed; wrong org403, parent/task mismatch404, malformed route400; rejected paths write nothing |
| Blockers | All twelve blocked tasks; specific reason asserted and valid command receives TASK_EDIT_BLOCKED |
| Preflight | Actual PG intake-version mutation changes fingerprint; old source rejected; exact restoration |
| Same-key concurrency | Independent authenticated same-actor sessions synchronized at a barrier; real 201+200, identical receipt, exactly one event/receipt/audit effect |
| Different-key concurrency | Same expected snapshot; real 201+409 conflict, only winner receipt, exactly one revision increment |
| Atomic rollback | Scoped temporary PG BEFORE INSERT trigger fails event, receipt, then audit insertion; each HTTP500 leaves all persisted business state unchanged; trigger/function dropped in finally |
| Wait-time authority | Persistent real org row-lock transaction, server pg_blocking_pids observation before release; role/disabled/credential-version mutation committed by holder; queued write denied and no business write; original security values restored |
| Fresh replay authority | Demoted original actor cannot replay original successful key or read its receipt; restore membership afterward |

The native probe deliberately requires a fresh fixture: an earlier run's changed revision or completed legacy browser task cannot silently count as fresh-fixture coverage. Do not run competing suites against these same disposable rows during the gate.
