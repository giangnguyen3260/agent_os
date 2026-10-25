# Team Settings S06 independent QC review

Status: authored, not acceptance-approved. This continuation owns only scripts/team-settings-probe.py and these two QC documents. Existing fixture and browser work is preserved; no browser, fixture, contract, application, migration, CI or baseline tests were edited in this continuation. No commits or publication.

Contract was read fully. Main clarification accepted: unchanged manager (including null or ineligible legacy level/status) and unchanged recognized-status members survive metadata/limits edits; new/changed manager requires MANAGER plus DRAFT/ACTIVE, and membership/pointer delta is DRAFT-Team-only. Revision is INT_MAX bounded. History alone does not block removal. Foreign historical references return only generic blockers. GET receipt has no command hash; POST replay checks the canonical body before mutable guards.

Native probe uses existing urllib cookie/CSRF and docker-compose psql patterns and has a hard 299-second alarm. It must be run only by main in the disposable CI environment after V016 and fixture loading. There is no SMTP dependency. Real PostgreSQL waits use a persistent transaction plus observed lock wait, not a sleep-as-proof. A controlled SQL mutation before POST tests fingerprint invalidation only; it proves nothing about universal writer serialization. Temporary audit trigger is organization-scoped and cleaned in finally. Global history snapshots assume the isolated CI database is otherwise quiescent.

Fixture has separate native and per-width browser organizations. qc@example.test must already exist; absence raises rather than silently creating zero fixture accounts. No plaintext credential is persisted. Fixture IDs are canonical version4/variant UUIDs. Fixture is disposable and intentionally not idempotent: use a clean database, do not apply twice.

Browser assertions use supplied labels and hooks, real backend data and route.fetch-first faults. The real first response must be 201 before a reply is lost or corrupted, and replay must be 200 with the original receipt. Manual malformed receipt does not imply failure. No automatic response screenshots/traces/video are enabled by this spec. Fourteen named states per width are requested, not captured evidence.

## Precision and freeze blockers

Actual parallel FE HTML was inspected before selector freeze. Tabs are buttons, member/candidate hooks contain checkboxes, manager uses Angular ngValue (selected by option label), and limits inputs have stable IDs. The latter IDs avoid accessible labels that currently include help text despite the supplied exact label list. The supplied edit/review/result/unknown/member/candidate/blockers hooks exist. This is source confirmation only, not runtime accessibility validation. Remaining matrix coverage is listed explicitly in TEAM_SETTINGS_TEST_CASES.md. Main must complete these gaps or record them as blockers; an authored test count is never an acceptance result.

The contract originally left receipt GET for a key bound to a different team as opaque null versus404; no invented oracle is asserted for that ambiguity. Main's manager grandfathering clarification resolves the earlier ACTIVE null/ineligible metadata question.

## Evidence limits

Executed locally: Python AST parsing, Node syntax check and Playwright test collection only (36 new responsive cases collected under exact grep; inverse grep collected522 in23 files; no tests run). No Docker, native probe, backend tests, browser tests, HTTP/Postgres calls, screenshot captures or visual QA were executed. Baseline 271/522 preservation is a required main CI gate, not verified here. Main owns contract/CI/final exact SHA/tree/base report, independent runtime validation, artifact hashes, responsive visual review and restart/logout checks. This review makes no production, merge or rollout claim.

Main CI handoff: describe exactly `team settings independent QC`; visuals use frontend/qa-visuals/team-settings-*.png. Native runs before browser at port18080. Full backend and inverse browser baseline runs remain main-owned. The 400-union replacement is authored; successful execution alone would not prove sorted lock order.

Source-level concern for main/FE: inspected revalidate() returns early when busy(), so focus may skip current-session verification while a committed POST reply is held. The new account-swap test deliberately requires actual different-actor session observation and private cleanup before releasing the old reply. This is an unexecuted source finding, not a runtime failure report; do not relax that oracle to pass the current implementation.

The four requested browser additions are authored using actual HTML labels, existing pushSpaHistory router metadata, sibling-tab shared cookies, real route.fetch responses and manual receipt GET. Dirty Back is a real history action; pushSpaHistory is separately identified as synthetic reuse coverage. Visual helper honors E2E_VISUAL_DIR or qa-visuals. No HTTP/Postgres/browser execution occurred locally.

Final local checks: Python AST, Node --check, Prettier on the owned browser spec, git diff --check, exact-grep Playwright collection36 and inverse-grep collection522. Collection is discovery only; neither count asserts execution success.


## Native integration continuation (authored only)

main() now calls team_create_claim(a,b), then agent_attachment_wait(a,b), then post_wait_account_change for disable and credential-version after the existing settings/settings claim race and before membership-revocation/audit-rollback checks. The 299-second global alarm is unchanged. The NewTeamDraft race queues both real HTTP writers behind the organization holder and requires exactly one201/one409, winner-only Agent assignments, unchanged loser-only Agents/Teams, and no losing settings journal. The attachment check observes the actual AgentDraft HTTP writer queued before submitting settings, then requires attachment201 and fingerprint-conflict409 with unchanged settings revision/journal. Neither is a mock or a timing-only claim.

NewTeamDraftRequest, AgentDraftRequest, OrganizationController, AgentController and both create services were read for exact body fields, POST paths, response DTOs, receipt text decoding and expected history writes. Each external-create helper returns its fully verified immutable snapshot. main asserts the previous baseline immediately before those calls and retains the returned baseline through all subsequent settings checks. Old history rows must remain byte-for-byte intact; only the identified Team-create receipt or Agent draft-config/version/create-receipt additions are allowed. The earlier Studio baseline reset now also validates its exact config-row change and identified version/receipt additions before admitting them. No table-wide history exclusion or count-only replacement is used for the final oracle.

Each account vector obtains a fresh other-ADMIN session, observes the queued settings request behind the organization lock, changes enabled or credential_version in the holder transaction, requires403 FORBIDDEN, and compares business/history/session identity snapshots. Exact original enabled/version values are restored in finally and re-read. Credentials and session identifiers are never written to artifacts. The existing AGENT historical actor vector and configuration-history preservation checks remain.

Continuation validation is limited to Python syntax/AST integration checks and whitespace checks. No local Docker, HTTP/PostgreSQL, backend or browser execution occurred. Earlier browser collection counts above are inherited evidence, not re-executed here. Native runtime success and completion within299 seconds remain main CI handoff gates. The 400-union exercise still does not prove sorted lock acquisition.
