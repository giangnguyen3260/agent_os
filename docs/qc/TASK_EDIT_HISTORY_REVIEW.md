# Task edit history — independent BA/QC review

Reviewed 2026-10-27 against `docs/engineering/TASK_EDIT_HISTORY_CONTRACT.md`, HEAD `ae1f19660b039edf1719dd9c15dee88658519dad` plus the live worktree. This is an implementation-in-progress snapshot, not release approval. Other agents may change source after inspection.

## Verdict and evidence boundary

**Acceptance pending.** Static inspection only; no history API, database, browser or visual acceptance executed by this sidecar. Prior Task Draft Edit PASS evidence does not establish history acceptance. Authored test cases/spec are not passing results.

Initial inspection found no history implementation. A subsequent live-worktree read found the concurrent BE endpoint/DTO/service/parser and FE route/component/validator/template. Missing implementation is therefore no longer a current finding. Source is still changing and runtime acceptance remains pending.

## Policy and underlying source findings

- ADMIN-only is consistent with private description/input snapshots. Existing service `authorize` checks enabled account, matching credential version and current org membership ADMIN before `resolve` checks the complete parent chain. History must reuse that policy, not rely on token role or frontend link visibility. Unauthorized callers must not learn scope existence from business responses.
- V017 provides persisted revision, actor FK, timestamp and before/after text JSON with scoped unique revision. It creates no baseline event. Existing `update` writes exactly one event, receipt and safe audit atomically; same-key replay returns saved receipt before mutable lifecycle eligibility and no-op rejects. History must read events rather than reconstruct from task/current DTO, receipts or generic audit events.
- History cannot depend on edit `blockers` or `canEdit`: those reject non-DRAFT, archived team and paused intake. The existing Task Detail edit link is DRAFT-gated; do not put the new history link under that gate.
- Actor label must join current `human_accounts.display_name` by stored actor ID, including actors now disabled or no longer members. Do not use the viewer's name, stored session display name, email fallback or invented legacy author. Current-name caption is required; it is not an immutable identity claim.
- V017 constraints do not validate JSON shape, JSON duplicate keys, field values, continuity or cryptographic integrity. The append-only comment is not a database UPDATE/DELETE prohibition. Do not claim tamper-proof provenance; no schema hardening is authorized in this scope.
- `TaskDraftEditWire` strict UUID means lowercase canonical versions 1–5 and RFC variant, not merely UUID.fromString. GET query/body checks should preserve existing wire behavior. `Values` serialization retains nulls. Existing FE `values(v, false)` checks basic types but is not complete strict validation of legacy strings; blind reuse needs review against the history decoder.

## Actual policy gaps / decisions needed

| Priority | Gap | Acceptance interpretation / action |
| --- | --- | --- |
| High | Fixed offset pages with fresh repeatable-read transactions cannot guarantee no overlap across separate requests when new edits arrive. E.g. revisions 25..6 then append 26: page 1 now starts at 6. | Require disjoint 20+5 on a static 25-row fixture. Per-response count/items must share one DB snapshot. Do not claim cross-request snapshot stability or invent a cursor/schema change. Main should record this limitation; an unconditional concurrent no-duplication requirement needs contract clarification. |
| High | “Malformed stored provenance” lacks precise field/domain and integrity boundaries. Legacy before values may legitimately contain nulls and do not necessarily meet today's edit command rules. | Require exact four keys, proper JSON types, no duplicate/trailing JSON, valid identifiers/revision/time/name and fail the whole returned page on a malformed selected event. Main must define whether null title, unknown legacy priority, overlength/control text, revision gaps/chain mismatch, and corrupt off-page rows are rejected. Never normalize historical values or silently filter bad rows. |
| Medium | Error status/code for corrupt provenance and exact actor-name validation are unspecified. | Use safe error with no raw JSON, SQL, actor IDs or secrets; FE displays error and clears rows. Pin exact status/code with BE before exact assertions. Do not invent it in tests. |

Repeatable-read authorizes against the request's transaction snapshot. Revocation during an already-running request is not promised immediate DB linearizability; FE must erase on its next mandated authority check and fence the older response. No polling or new lock policy is implied.

## Browser QC handoff

New owned file: `frontend/tests/e2e/task-edit-history.spec.mjs`; describe `task edit history independent QC`. Inherits desktop/tablet/mobile projects and requires workers=1/retries=0. Uses prefix520 isolated fixtures and specified `task-history-{project}-{kind}@example.test` users. The 25 event snapshots are **synthetic persisted fixture provenance**, not 25 actual HTTP edits. Main's native probe must independently prove a real HTTP edit appears.

FE assumptions reconciled against the newly created template: root `task-edit-history`; each row `task-history-event`; states `task-history-loading`, `task-history-empty`, `task-history-error`, `task-history-denied`. Row revision heading is `Revision N`; one `time[datetime]`; four `section.diff` in title/description/acceptanceCriteria/priority order, each with `.comparison > div` before then after and a value paragraph. No new event-ID attributes required: API event IDs establish pagination uniqueness, while visible revision and full snapshots establish API/render correspondence. Labels: ADMIN detail link `Lịch sử chỉnh sửa`, back `Quay lại task`, buttons `Trang trước`, `Trang sau`, retry `Tải lại lịch sử`. Main should relay this exact template coupling to FE. No direct messaging tool was used.

### Follow-up source findings (live worktree)

BE `history` now uses current authorization then scoped resolution in a read-only repeatable-read transaction, exact scoped count and revision-desc limit20/offset, left-joining current actor name. The parser rejects duplicate/extra/missing JSON members and invalid types/strings. FE clears before load, checks session/workspace ADMIN, fences epochs/actor/destruction, validates scope/count/order/time and uses escaped interpolation. These are static observations, not runtime passes.

**Concrete decoder mismatch to resolve:** BE invokes the same `historyValues` for before and after, accepting null description/criteria/priority, blank strings and before==after, while FE requires `values(after, true)` and rejects no-op events. BE also only checks actorName nonnull, whereas FE requires nonblank safe text <=255. Thus some malformed persisted events get HTTP200 containing provenance that FE refuses, rather than failing closed at the API. Main/BE should align strict post-edit/actor validation; preserve legitimate legacy before snapshots. BE rejects unknown before priority while FE before validator accepts arbitrary safe priority; record the intended legacy rule. No source changes made by QC.


Captures after assertions only: populated, secondpage, empty, error, denied; filenames `task-edit-history-{project}-{state}.png`, manifest `task-edit-history-manifest.jsonl`. Expected inventory is 15 PNGs and 15 manifest rows only after all three projects actually run successfully. Missing/failed captures are not evidence.

## Local verification and fixture blocker

`node --check frontend/tests/e2e/task-edit-history.spec.mjs` succeeded. Prettier formatted only the new spec. `playwright test task-edit-history.spec.mjs --list` collected **21 cases** (7 cases ×3 projects); this is collection only, **zero browser tests executed by this sidecar**, and no PNG/manifest produced. Runtime/visual acceptance remains pending main's execution.

At follow-up inspection, `scripts/task-edit-history-ci-fixture.sql` gives every `task-history-{surface}-other` account ADMIN membership in that surface's organization via its general membership INSERT. This conflicts with foreign-denial expectations in the new spec and task brief. Main owns the fixture: place `other` only in the foreign org or otherwise remove its local membership. Do not weaken the expected403. This is a fixture blocker, not proof of broken API authorization.

## Ownership

Only this review, `TASK_EDIT_HISTORY_TEST_CASES.md`, and the newly authorized browser spec are owned. No source, contract, existing E2E, fixture, workflow or native-probe modifications; no commits/push. Main owns implementation, actual execution, artifacts and final evidence binding.
