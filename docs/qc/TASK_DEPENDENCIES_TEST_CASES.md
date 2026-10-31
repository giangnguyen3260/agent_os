# Task dependencies independent QC test cases

Authored suite: `frontend/tests/e2e/task-dependencies.spec.mjs`. Describe title is exactly `task dependencies independent QC`. Playwright collection actually reports **75 tests: 25 scenarios × desktop/tablet/mobile** at 1440×1000, 768×1024 and 390×844. Collection is not execution. One worker, zero retries and the default 30-second per-test timeout are asserted; no timeout increases, skips, config changes or workflow changes are introduced by this worker.

## Live fixture and isolation

Consume the frozen contract and main-owned `scripts/task-dependencies-ci-fixture.sql`. Browser organizations use prefix570, n=2/3/4 and project-qualified admin/admin2/member/other accounts; native n=1 belongs to main's probe. Team/Mission suffix n×100+1; Task suffix n×1000+t. Task36 has suffix+2 parent scope. No synthetic provenance is seeded.

Scenario writes use tasks19,20,26,27,30,31,32,34 and task11's deliberate legacy-null save. Task19 is intentionally reused in the final correlation scenario, using freshly fetched actual state and toggling to a different selection. Task34 is reused serially across the three held-response scenarios; each derives a different selection from current context. Scenario11/12 competitors use real API commands. Other local drafts on tasks21–29/33/35 are never committed. Task1's foreign owner stays untouched, checked both through protected snapshots and direct legacy-owner assertions. No test requires the original candidate total after commands, and page contents come from actual responses.

Controlled SQL setups: literal-search title on task7; selected-target title on task4 *after the save's source preflight and before its actual target read*; disconnected off-path title on task7; and current membership ADMIN→MEMBER for authority fences. Titles and exact original timestamps are restored in `finally`; memberships are restored to ADMIN. Read-only baselines are taken after these explicit setups. No SQL business mutation occurs inside no-write assertions apart from those named setups and their subsequent restoration. Run against a disposable, exclusively used fixture, once per freshly provisioned job.

## Scenario matrix

| # | Source | Browser oracle |
| --- | --- | --- |
| 01 | 19 | Actual Task Detail link; held real context loading; edit/review; double-click save produces one POST, one revision/event/receipt/audit; saved receipt and revisit |
| 02 | 20 | Actual API setup of two targets; deliberate clear-all; complete before/empty after; unchecked acknowledgment on each review; zero target reads for empty desired set; explicit save |
| 03 | 21 | Actual first/second page selection and search selection retained together; compact menu only at compact widths; Escape restores chooser/menu focus |
| 04 | 22 | Twenty actual first-page choices; next-page adds disabled; no twenty-first choice; complete20 review; removal re-enables add |
| 05 | 23 | Actual literal `%`, `_`, `!`, `%_!` queries match only controlled literal title; no wildcard expansion |
| 06 | 11 | Blank visual fallback, unchanged empty no-op, null description/acceptance/priority preserved by actual save; source1 private owner never projected or changed |
| 07 | 4,12–18 | All eight non-DRAFT source statuses blocked; no candidate reads or writes; blocked screenshot |
| 08 | 24 | Malformed delivery of real successful context shows error, never fake empty/current/saved; explicit reload recovers |
| 09 | 24 | Real zero-match query shows dedicated empty candidate state, distinct from error/loading/blocked |
| 10 | 25→26 | Real native Back canceled via one discard dialog; native beforeunload canceled; synthetic reused-route accepted discard; clean sibling reload has no stale unload prompt |
| 11 | 26 | Real competing source command before review makes source stale, preserves draft and sends no browser POST |
| 12 | 27 | Real competing source command before save makes source stale; conflict screenshot; no browser POST |
| 13 | 28 | Real chosen-target metadata setup after unchanged source preflight; actual new Candidate4 fingerprint differs; target preflight conflict sends no POST |
| 14 | 29 | Disconnected off-path node metadata invalidates save; preserves draft and sends no POST |
| 15 | 30 | Real API setup of full20 across all nine statuses; independently hydrate all IDs on revisit; blank retained row; complete19 review after explicit removal; no selection reconstructed from page |
| 16 | 31 | Actual committed POST response abort → dedicated UNKNOWN; exact same raw body/key explicit retry → actual200 same receipt; one effect |
| 17 | 32 | Actual committed POST malformed delivery → UNKNOWN; explicit actual receipt GET resolves same receipt without another POST |
| 18 | 33 | Transport abort before POST reaches backend; actual receipt GET returns null; stays UNKNOWN with frozen identical retry available and no write |
| 19 | 19 | MEMBER retains detail access with no editor affordance; context/candidate/detail/receipt reads403, including malformed query; direct route denied |
| 20 | 19 | Other-org ADMIN receives same403 API boundary; denied editor; no private projection or write |
| 21 | 34→35 | Actual committed reply held while UNKNOWN navigation confirmation accepts route change; old reply drained and cannot restore saved UI |
| 22 | 34 | Actual committed reply held across real role revocation and focus revalidation; private state/body erased and retry disabled; reply drained |
| 23 | 34 | Actual committed reply held across real logout/admin2 login in shared cookie context; old actor UI/locator cannot reappear after drain |
| 24 | 35 | Current ADMIN loss immediately before both review and save clears private draft and sends no command; restore each time |
| 25 | 19 | Actual own receipt/key correlation; same-key identical body replay200; changed body same key409; stale same body new key409; another ADMIN receives actual null own-actor lookup |

## Database oracles

Read-only REPEATABLE READ snapshots enumerate public organization-scoped tables from `information_schema` and include the organization row. Only table names plus SHA256 of ordered JSON values leave the snapshot helper. No-write assertions compare the entire per-organization snapshot, including dependency edges/events/receipts/audit and available runtime/grant/approval tables. Snapshot SQL contains no mutating statements.

Successful saves compare protected task columns exactly by digest (only source dependency_revision/updated_at may differ), other task rows, unrelated source edges/events/receipts and non-dependency audit rows, and every other organization-scoped table. Separate source checks require revision+1, persisted sorted outgoing IDs equal receipt.after, event+1/receipt+1/audit+1, exact event before/after arrays, eventId, request-header/persisted-key correlation, independently recomputed canonical command SHA256, and audit payload exactly `{eventId,revision}`. UNKNOWN replay/recovery compares full committed snapshots before and after recovery, so another event or receipt fails.

Limits: organization-scoped value snapshots do not prove tuple identity, global/non-org-scoped immutability, foreign-organization immutability or same-source historical row immutability across a successful save. Main's native probe and backend tests own those broader claims. SQL values remain in process memory; assertion output uses digests except deliberate safe fixture fields/IDs and provenance. Browser failures should still be treated as authenticated QA artifacts.

## Visual evidence and command

Capture exactly seven requested states per project: loading, edit, review, blocked, conflict, unknown, saved. Expected **21 unique PNGs**, not a claim that21 already exist. Filenames are `task-dependencies-{desktop|tablet|mobile}-{state}.png`. `task-dependencies-manifest.jsonl` appends real route, four-ID scope, project, viewport, timestamp, test title, SHA256, actual PNG width/height. The writer checks PNG signature and width against the browser viewport and rejects horizontal viewport overflow. There are no static mock screenshots or invented manifest entries.

Main should provide a clean per-job `E2E_VISUAL_DIR`, real fixture password and actual backend URL, and retain these21 alongside existing baseline artifacts. Do not weaken timeout, worker or retry settings to collect this suite.

```bash
cd frontend
E2E_PASSWORD='<fixture password>' E2E_BASE_URL='http://127.0.0.1:18080' E2E_VISUAL_DIR='<job visuals>' \
  ./node_modules/.bin/playwright test tests/e2e/task-dependencies.spec.mjs --workers=1 --retries=0
```

Local execution status, source findings and probe review are in [TASK_DEPENDENCIES_REVIEW.md](TASK_DEPENDENCIES_REVIEW.md).
