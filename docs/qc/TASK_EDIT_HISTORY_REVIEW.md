# Task edit history — independent BA/QC review

Reviewed 2026-10-27 against `docs/engineering/TASK_EDIT_HISTORY_CONTRACT.md`, HEAD `ae1f19660b039edf1719dd9c15dee88658519dad` plus the live worktree. This is an implementation-in-progress snapshot, not release approval. Other agents may change source after inspection.

## Verdict and evidence boundary

**Acceptance pending.** Static inspection only; no history API, database, browser or visual acceptance executed by this sidecar. Prior Task Draft Edit PASS evidence does not establish history acceptance. Authored test cases/spec are not passing results.

Initial inspection found no history implementation. A subsequent live-worktree read found the concurrent BE endpoint/DTO/service/parser and FE route/component/validator/template. Missing implementation is therefore no longer a current finding. The follow-up reviewed the BE decoder alignment in source; runtime acceptance remains pending. See the remaining Task Detail link blocker below.

## Policy and underlying source findings

- ADMIN-only is consistent with private description/input snapshots. Existing service `authorize` checks enabled account, matching credential version and current org membership ADMIN before `resolve` checks the complete parent chain. History must reuse that policy, not rely on token role or frontend link visibility. Unauthorized callers must not learn scope existence from business responses.
- V017 provides persisted revision, actor FK, timestamp and before/after text JSON with scoped unique revision. It creates no baseline event. Existing `update` writes exactly one event, receipt and safe audit atomically; same-key replay returns saved receipt before mutable lifecycle eligibility and no-op rejects. History must read events rather than reconstruct from task/current DTO, receipts or generic audit events.
- History cannot depend on edit `blockers` or `canEdit`: those reject non-DRAFT, archived team and paused intake. The existing Task Detail edit link is DRAFT-gated; do not put the new history link under that gate.
- Actor label must join current `human_accounts.display_name` by stored actor ID, including actors now disabled or no longer members. Do not use the viewer's name, stored session display name, email fallback or invented legacy author. Current-name caption is required; it is not an immutable identity claim.
- V017 constraints do not validate JSON shape, JSON duplicate keys, field values, continuity or cryptographic integrity. The append-only comment is not a database UPDATE/DELETE prohibition. Do not claim tamper-proof provenance; no schema hardening is authorized in this scope.
- `TaskDraftEditWire` strict UUID means lowercase canonical versions 1–5 and RFC variant, not merely UUID.fromString. GET query/body checks should preserve existing wire behavior. `Values` serialization retains nulls. Existing FE `values(v, false)` checks basic types but is not complete strict validation of legacy strings; blind reuse needs review against the history decoder.

## Resolved policy questions

The authoritative contract now defines legacy before values versus canonical strict after values, safe nonblank current actor names, rejection of no-op events, selected-page structural validation, and fresh offset snapshots. These resolve the earlier policy blockers: null title is invalid; safe unknown legacy priorities and blank legacy strings are preserved; after must be nonblank/stripped with known priority; malformed selected rows fail the whole page. Off-page corruption/chain integrity and cryptographic tamper detection are not promised.

The UI now explicitly says new edits can shift page positions. Static25 traversal must remain disjoint20+5, but concurrent cross-request overlap is documented rather than misrepresented as a cursor guarantee. Count and rows still require the same repeatable-read transaction snapshot.

Repeatable-read authorizes against the request's transaction snapshot. Revocation during an already-running request is not promised immediate DB linearizability; FE must erase on its next mandated authority check and fence the older response. No polling or new lock policy is implied.

## Browser QC handoff

New owned file: `frontend/tests/e2e/task-edit-history.spec.mjs`; describe `task edit history independent QC`. Inherits desktop/tablet/mobile projects and requires workers=1/retries=0. Uses prefix520 isolated fixtures and specified `task-history-{project}-{kind}@example.test` users. The 25 event snapshots are **synthetic persisted fixture provenance**, not 25 actual HTTP edits. Main's native probe must independently prove a real HTTP edit appears.

FE assumptions reconciled against the newly created template: root `task-edit-history`; each row `task-history-event`; states `task-history-loading`, `task-history-empty`, `task-history-error`, `task-history-denied`. Row revision heading is `Revision N`; one `time[datetime]`; four `section.diff` in title/description/acceptanceCriteria/priority order, each with `.comparison > div` before then after and a value paragraph. No new event-ID attributes required: API event IDs establish pagination uniqueness, while visible revision and full snapshots establish API/render correspondence. Labels: ADMIN detail link `Lịch sử chỉnh sửa`, back `Quay lại task`, buttons `Trang trước`, `Trang sau`, retry `Tải lại lịch sử`. Main should relay this exact template coupling to FE. No direct messaging tool was used.

### Follow-up source findings (live worktree)

BE `history` now uses current authorization then scoped resolution in a read-only repeatable-read transaction, exact scoped count and revision-desc limit20/offset, left-joining current actor name. The parser rejects duplicate/extra/missing JSON members and invalid types/strings. FE clears before load, checks session/workspace ADMIN, fences epochs/actor/destruction, validates scope/count/order/time and uses escaped interpolation. These are static observations, not runtime passes.

**Decoder mismatch resolved in source:** BE now preserves safe legacy before values, invokes `historyValues(after, true)` for strict canonical after values, calls `historyActorName`, and rejects `before.equals(after)`. These checks align with the clarified contract and FE. New integration/wire test source covers legacy priority/blank values, null/incomplete/noncanonical after, no-op events and unsafe actor names; those tests have not been executed by this sidecar.

**Remaining confirmed source blocker:** In the inspected `frontend/src/app/features/missions/task-detail.component.html`, the only history link is under `@if (error())`. The normal `@if (snapshot(); as data)` actions contain only the edit link, without history. Consequently normal authorized Task Detail has no history link, and the browser round-trip link assertion will fail. FE must place the link in the successful view gated by current ADMIN, independently of DRAFT. QC has not modified source or relaxed the browser assertion.

**Remaining transport evidence gap:** Stored-provenance failures currently throw `IllegalStateException`; the controller-scoped advice handles TaskDraftEditFailure, IllegalArgumentException and IOException, and global advice does not handle IllegalStateException. New corruption assertions invoke the service directly, not an actual HTTP corruption request. Verify through real HTTP that corruption yields a safe error without raw provenance and is not error-dispatched to denied `/error`/403 (which FE treats as denied). This is an unverified transport outcome, not a claimed observed runtime failure. Pin actual safe behavior in CI/native evidence.


Captures after assertions only: populated, secondpage, empty, error, denied; filenames `task-edit-history-{project}-{state}.png`, manifest `task-edit-history-manifest.jsonl`. Expected inventory is 15 PNGs and 15 manifest rows only after all three projects actually run successfully. Missing/failed captures are not evidence.

## Local verification and corrected fixture

Re-ran `node --check frontend/tests/e2e/task-edit-history.spec.mjs`: successful. Re-ran `playwright test task-edit-history.spec.mjs --list`: **21 collected cases** (7 ×3 projects). This is collection only: **zero browser/runtime tests executed by this sidecar**, no PNG/manifest produced, no runtime PASS claimed. Upcoming CI must establish actual behavior and visuals.

The browser spec is frozen and unchanged in this follow-up: SHA-256 `3077ddd8c8c91c266777f6b59d9542321116ffae414584457a525437b7b31830`. The fixture now explicitly deletes local memberships for `other` accounts then grants ADMIN only in `52000000-0000-4000-8000-000000000005`; the prior foreign-user fixture blocker is resolved by source inspection.

Final source reading includes the clarified contract, V017, history controller/DTO/service/wire/advice and new BE test source, FE route/authority component/validator/template/CSS, Task Detail template and prefix520 fixture. HEAD remains `ae1f19660b039edf1719dd9c15dee88658519dad`; implementation changes are uncommitted shared-worktree changes. Review is bound to inspected content, not a frozen CI candidate. Relevant SHA-256: contract `1321ce40895ce6e705d335857479e3ced6b278523260fa6b8a5c89823ff5986f`; service `235574628c2ad205d3895c0dafa285d6661f3e6478fb61124297d77d74a09b98`; wire `c434680d7839a36c8bf436e4d483a3e33b525ef020143f20a31aa2c4709b3d55`; Task Detail `8415fa59449a5f5279d9a1d9665f19b28bd79379783aa43ad711f7ddcdf4e35c`. Re-review subsequent fixes before clearing the source blocker.

## Ownership

This follow-up changes only this review and `TASK_EDIT_HISTORY_TEST_CASES.md`. The previously authorized browser spec remains frozen. No source, contract, existing E2E, fixture, workflow or native-probe modifications; no commits/push. Main owns implementation, actual execution, artifacts and final evidence binding.
