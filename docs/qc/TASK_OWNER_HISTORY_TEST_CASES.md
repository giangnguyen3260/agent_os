# Task owner history independent QC cases

Frozen basis: `docs/engineering/TASK_OWNER_HISTORY_CONTRACT.md`. Suite: `frontend/tests/e2e/task-owner-history.spec.mjs`, describe exactly `task owner history independent QC`. Collection is **42 cases: 14 definitions × desktop/tablet/mobile**, not 42 passes. One worker and zero retries are asserted; existing configuration is retained.

## Fixture and provenance

Main owns bootstrap, fixture SQL, native probe, production and workflow changes. QC only consumes those fixtures. Prefixes are organization `56000000`, Team `56010000`, Mission `56020000`, Task `56030000`, Agent `56040000`; canonical UUID suffix mapping is native=1, desktop=2, tablet=3, mobile=4, foreign=5. Browser Team/Mission suffix is n×100+1, Tasks n×1000+1/+2, Agents n×1000+1/+2. Team is ACTIVE, Mission CREATED/unpaused, Tasks DRAFT with valid input. Team/Mission names end in `1`; Task/Agent names end in `1` or `2`. Accounts use `owner-history-{surface}-{admin|admin2|member|other}@example.test` and `E2E_PASSWORD` from bootstrap. Admins belong to their organization, member is MEMBER, other belongs only to foreign organization as ADMIN.

On fresh fixtures, beforeAll verifies Task 1 revision zero and raw foreign owner Agent 5001. It makes **21 actual commands per browser Task 1** through authenticated source, individual eligible candidate, CSRF and unique idempotency-key APIs. First command clears UNAVAILABLE foreign ownership. Commands then cycle Agent 1, Agent 2, clear; no command is a no-op. Admin writes revisions 1–10, admin2 writes 11–21. Each command must return 201 and the expected revision. Task 2 stays null-owner/event-empty. No event, receipt or audit seed is authored. A restarted worker accepts only an already complete 21-event scope; partial populations fail rather than being repaired or deleted. Acceptance evidence must come from a clean fixture run.

## Collected matrix

Each row below runs at 1440, 768 and 390 pixels.

| Case definition                                  | Principal assertions                                                                                                                                                                                                                                                                                                                                                                                                 |
| ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Real persisted pagination, Back, entry and shell | Exact scope/DTO/event projection against DB; revisions 21→2 then 1; total21; unique IDs; initial foreign UNAVAILABLE clear; disabled boundaries; real browser Back; scoped Task Detail link; compact native Menu/Escape/focus/Close only below1024; desktop224 sidebar; 44px page/reload controls; no dirty dialog.                                                                                                  |
| Fresh authority and persistence                  | Focus, visible-document event and explicit refresh require exactly session→workspace→history. Page navigation requires exactly guard-session→component-session→workspace→history because the route always reruns authGuard. Every observed session body must match the viewer; the raw workspace must have exactly id/name/role and current ADMIN. No history event IDs or snapshot fields in local/session storage. |
| Empty and out-of-range                           | Task2 total0 exact empty label; populated pages2/100000 retain total21 with no items; distinct out-of-range label, disabled next and reset to first page.                                                                                                                                                                                                                                                            |
| Actor and Agent metadata                         | Admin2 current rename and disabled flag retain its events with the current name; removing own-org membership while adding foreign membership yields explicit actorName:null and exact fallback label; Agent1 rename, move to same-org Team n×100+2 and SUSPENDED status do not change committed snapshots. Restore original names, Agent scope/status and all writer memberships in finally.                         |
| Failed delivery                                  | Derive response from actual successful GET, deliver503; erase rows and scoped metadata; show exact error, never empty success; real GET-only retry restores data.                                                                                                                                                                                                                                                    |
| Malformed delivery                               | Deliver malformed JSON over actual successful GET; same fail-closed and retry assertions.                                                                                                                                                                                                                                                                                                                            |
| Late real denial                                 | Temporarily downgrade viewer while fetching a genuine403, restore role, hold denial, load newer authorized200, release/drain old403; newer data remains intact.                                                                                                                                                                                                                                                      |
| Corrupt actual DTO delivery                      | Wrong scope, extra root key, missing actor, duplicate event ID, reversed order, negative count, invalid timestamp, after UNAVAILABLE, extra snapshot key all fail closed; real GET-only retries restore valid data. No fabricated successful business DTO.                                                                                                                                                           |
| Scoped privacy                                   | Real wrong-Team/Mission/Task requests404; MEMBER and foreign readers403; safe rejection bodies, denied direct route, no history detail entry.                                                                                                                                                                                                                                                                        |
| Invalid local query                              | Negative, leading-zero, excessive, duplicate, exponent and unknown-key URLs show exact local error without sending history GET.                                                                                                                                                                                                                                                                                      |
| Held success / role fence                        | Real fetched200 withheld; reload clears data immediately; real role downgrade denies; release/drain cannot resurrect history.                                                                                                                                                                                                                                                                                        |
| Held success / account fence                     | Shared-session logout/login to independently authorized admin2 fences old admin1 response.                                                                                                                                                                                                                                                                                                                           |
| Held success / route fence                       | Same-document route change to empty Task2 fences old Task1 response.                                                                                                                                                                                                                                                                                                                                                 |
| Held success / page fence                        | Same-document page1 read completes before held page0 release; page1's single actual event remains.                                                                                                                                                                                                                                                                                                                   |

SQL comparison projects committed before/after JSON and current actor through same-org membership, normalizing only timestamp representation. Each observed history response is awaited through body completion and compared against business-table snapshots. Snapshots cover scoped tasks, teams, missions, agents, dependencies, owner events/receipts, edit events and audit; PostgreSQL xmin/ctid detect same-value UPDATEs. Every test also asserts zero browser organization API writes and unchanged business rows. Controlled Agent metadata restoration establishes a restored baseline only after each intervening GET has been independently checked. Authentication/session tables are excluded because authentication is expected to change them. Main's complete native/PG gate remains necessary for all other business tables and wire-level effects.

Held-response cleanup is idempotent, runs in finally, awaits fulfillment and original response body completion or an observed original-request cancellation, unregisters interception and drains two animation frames before final privacy assertions. Role changes and sibling tabs are restored/closed in finally. Same-document `pushSpaHistory` is used only for route/page parameter fences; the pagination case separately exercises real browser Back.

## Visual evidence and execution

A clean `E2E_VISUAL_DIR` is required. Full execution writes exactly **15 PNGs** named `owner-history-{project}-{state}.png`, plus `owner-history-manifest.jsonl`, matching main’s `task-owner-history-visuals` artifact:

| Width / project | States                                       |
| --------------- | -------------------------------------------- |
| 1440 / desktop  | empty, populated, second-page, error, denied |
| 768 / tablet    | empty, populated, second-page, error, denied |
| 390 / mobile    | empty, populated, second-page, error, denied |

Manifest records include project, width, state, file, organization/team/mission/task scope, URL, test title, timestamp and SHA256. Capture checks horizontal overflow, PNG width, duplicate project/state and duplicate image hashes. Per-project completion checks exactly five distinct states, scopes, widths and file hashes. Actual human visual review is still required; generation alone does not prove readable contrast, wrapping or visual quality.

Local checks (backend unavailable):

```sh
node --check frontend/tests/e2e/task-owner-history.spec.mjs
frontend/node_modules/.bin/prettier --check frontend/tests/e2e/task-owner-history.spec.mjs docs/qc/TASK_OWNER_HISTORY_TEST_CASES.md docs/qc/TASK_OWNER_HISTORY_REVIEW.md
cd frontend
./node_modules/.bin/playwright test tests/e2e/task-owner-history.spec.mjs --list
```

Actual-backend run, after main's disposable fixture/bootstrap preparation, from frontend:

```sh
./node_modules/.bin/playwright test tests/e2e/task-owner-history.spec.mjs --workers=1 --retries=0
```

Do not substitute collection for execution, weaken frozen hooks/controls to obtain green results, or alter retained baseline selection/timeouts. Main owns complete PostgreSQL wire/body/provenance/malformed-persisted/off-page/lifecycle/native gates and the full retained suite run.

Actual retained-selection collection: inverse excluding both owner suites **780** tests /31 files; old owner **81** /1 file; history **42** /1 file. Unique total **903**, retained baseline **861**. Focused `member-invitations.spec.mjs --grep INV-B08` still collects **3** extra re-executions; it is already included in the baseline, so does not increase the unique count. No runtime pass count is claimed.
