# Team Settings PR26 — independent final source QC

Disposition: P2 reused-route UNKNOWN finding source-closed after independent inspection of main's working-tree fix; actual CI2 validation remains pending. Source review only; no application changes, tests authored, runtime execution, commits, or publication by this reviewer.

## Source identity and bounds

- HEAD: `485cd4ab32b97bd6459f1c8fc097173ec5daad72` (verified).
- Tree: `6f988be19171a08e01dfd678cc5451bfc2d86da1` (verified before this new document).
- Supplied base: `18bcd6b9dd1ae891ef70b552285138bab1df6419`.
- Supplied merge: `0e382e7c2506be83cd8cf6c3a5410bff8ec1a126`.

Read the shared engineering contract, all six production `com.agentos.teamsettings` classes, V016, the settings component/template/service/navigation guard/production validators, actual production-validator harness, AuthService, and relevant schema and writer authorization/reference paths. This is a bounded source inspection, not proof of every possible writer interleaving or frontend execution order.

## Finding — source-closed, pending actual CI2

### P2 — changing route identity from UNKNOWN strands the destination Team

Status update (2026-10-25): independently verified main's narrow working-tree fix against the original HEAD above. The constructor param subscription now calls `clearPrivate()`, clears `locator`, sets `phase` to `edit` and `tab` to `info`, then assigns new IDs/actor and calls `load()`. The diff contains only these two assignments and their explanatory comments. The initial same-identity `load()` UNKNOWN guard is unchanged. `clearPrivate()` still increments the epoch and clears private state; `postFrozen()` checks `active(epoch)` before accepting a response or processing an error, and the save finalizer also checks it. Consequently an old held reply cannot restore its receipt or reset the destination's busy state through those paths. This closes the reported source defect; it is not a browser execution claim.

Inspected component SHA-256: `64b7d668605ed78869598c582856d451299e0c6a8affac015501b649939b8a96`. HEAD remained `485cd4ab32b97bd6459f1c8fc097173ec5daad72`; the fix was present as an uncommitted application diff, so the original tree identifier does not identify this corrected working-tree state.

Main reports authoring a thirteenth real browser case holding an actual 201 POST reply, accepting reused navigation, observing the destination GET/edit state, then releasing the old reply. Main also reports adding stale/denied/archived visual states. These additions and their execution are pending evidence, not independently verified in this update. CI2 must validate the corrected source and held-reply route transition before runtime closure.

Original finding and required correction follow as historical rationale:

Location: `frontend/src/app/features/teams/team-settings.component.ts`, route param subscription lines 121–127, `clearPrivate()` lines 141 onward, and the initial guard in `load()`; related navigation guard and UNKNOWN template.

Trigger: a settings save enters UNKNOWN after a lost/malformed reply; the user then accepts navigation to a different Team's settings using the same Angular route configuration (including Back/Forward between settings URLs). The settings navigation guard expressly supports reused component identities and permits the transition after `canLeave()` confirmation.

The subscription calls `clearPrivate()`, explicitly clears `locator`, updates route IDs, and calls `load()`. Neither `clearPrivate()` nor that subscription resets `phase`. `load()` immediately returns when `phase() === 'unknown'`, before its own `phase.set('edit')`. Thus the destination never requests authorization or Context. It still renders the old UNKNOWN workflow with no locator and no frozen body. `recover()` returns immediately for a missing locator, replay is disabled, and the ordinary GET retry is hidden during UNKNOWN. A full reload or leaving the component is needed to escape. This also presents an unresolved-save warning for the wrong Team after the user agreed to discard the old recovery information.

Required correction: after an accepted route-identity change discards the previous locator and fences callbacks, initialize the new identity's phase to `edit` before invoking `load()`. Keep the same-identity UNKNOWN protection intact so normal refresh/retry cannot discard an unresolved original operation. Check the transition from UNKNOWN to another Team settings URL, including browser history with component reuse: new identity loads via GET, old locator/body stay erased, and late old callbacks remain fenced. This check is a handoff requirement, not a newly authored or executed test.

## Other inspected high-risk invariants

No additional actionable blocker established within this review:

- DTO binding: backend named record construction and frontend exact-shape/org/team/actor/key validation agree on the inspected paths; receipt validation also binds a retained frozen body and original state when available.
- Legacy grandfathering: an unchanged manager can retain an ineligible known level/status for metadata/limit edits; changing membership with opaque unsupported member values is blocked. ACTIVE membership and manager pointer remain fixed. Nullable current configuration is represented without fabricated defaults.
- UNKNOWN replay: original body/key are frozen, manual replay does not substitute a fresh revision, null or malformed receipt stays unresolved, and observed authority loss erases private state while retaining only the opaque locator.
- Busy focus/account/role loss: current source does not skip authority observation merely because POST is busy. Epoch and actor checks fence callbacks after private cleanup. The old busy early-return concern in `TEAM_SETTINGS_REVIEW.md` describes superseded source and is not carried forward as a finding.
- Backend serialization/history: writer takes organization, actor, membership, Team, sorted union Agent, then ledger locks; checks current authority again after waits; checks revision/fingerprint and global removal references; performs manager clearing before membership movement. Original configuration/history rows are not rewritten by settings. Full membership contributes to the fingerprint even with bounded DTO projection. Audit/event/receipt writes are inside the update transaction.
- Strict request wire: isolated duplicate detection, exact required fields, canonical UUIDs, lexical budget parsing, integral-number checks, UTF-8/size/text validation, declared query parameters, and body/header handling were inspected. Scoped advice does not relax global Jackson settings.

These observations are source evidence only, not assertions that PostgreSQL races, browser privacy behavior, or all wire vectors passed.

## Runtime and CI evidence boundary

No Docker, local Java 21, HTTP/PostgreSQL integration, browser, screenshot/visual, restart, or logout validation was performed in this review. The prior ephemeral runtime is unavailable. CI quality `40209985754` (PG16 sidecar with explicit IT) and Docker `40209985757` are main-owned; their completion/results were not queried here. Main's latest report: CI1 Quality SUCCESS, 335 tests with zero failures/errors/skips, including 23 PostgreSQL IT; Docker still in native validation. This is attributed main-provided evidence and predates CI2 validation of the narrow fix. Earlier supplied counts of 36 new plus 522 baseline = 558 planned browser cases and 41 previously observed regular backend tests are historical context, not current acceptance counts. Existing CI/tests were neither duplicated nor modified by this reviewer.

Only this new QC document is owned by this review. Source freeze remains in place. No source blocker remains from this finding at the inspected component hash; actual CI2/runtime validation is still outstanding and main-owned.

## Final narrow accessibility check and reviewer freeze

Independently inspected the final template diff: Manager now has the explicit accessible name `Manager`; Concurrency and Monthly budget (USD) have matching explicit names and `aria-describedby` references to the existing help text with matching unique IDs. Labels, form bindings, disabled conditions and help content remain intact. The browser field helper now uses exact `getByLabel` for every field, with no numeric ID fallback. No new critical source issue identified in this narrow change; browser accessibility execution remains a CI2 gate.

The thirteenth browser case is now present in source: it holds a real fetched 201, accepts reused navigation, requires the destination GET and edit values, releases the original reply, and asserts no old receipt/private text restoration or extra POST. The four added screenshot calls cover stale source, authority denied, archived blocked, and accepted UNKNOWN route reuse. Presence and assertions were inspected only; no runtime or visual acceptance is claimed.

Latest main-reported C1 evidence: Quality 335 PASS including 23 PostgreSQL IT; Docker 312 regular backend PASS and all native PostgreSQL probes including settings PASS, then the first browser case failed exact Manager label lookup. Main reports local frontend gates PASS after the label correction. C2 remains pending: 39 new browser cases, 522 baseline cases, and 54 expected screenshots are planned counts, not observed passes/captures.

Reviewer frozen after this update for main's six-file publication preparation. No further edits by this reviewer after acknowledgement; no app/test edits, commits or publication performed here. P2 remains source-closed pending actual CI2.
