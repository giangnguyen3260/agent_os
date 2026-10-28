# Mission intake independent test cases

Frozen authority: `docs/engineering/MISSION_INTAKE_CONTRACT.md`. Test implementation: `frontend/tests/e2e/mission-intake.spec.mjs`, describe exactly `mission intake independent QC`. All cases run once per desktop/tablet/mobile project, one worker, zero retries. Real fixture and backend are mandatory.

## Fixture contract

UUID form: `prefix-0000-4000-8000-` plus twelve-digit suffix. Project n: native=1 (coordinator probes), desktop=2, tablet=3, mobile=4; foreign organization n=5. Organization prefix53000000 suffix n; Team prefix53010000 suffix n×100+1, archived Team +2; Mission prefix53020000 suffix n×100+1 open eligible, +2 paused eligible, +3 COMPLETED, +4 Long.MAX_VALUE, +5 archived Team2. Existing real draft Task prefix53030000 suffix n×1000+1 belongs to Mission1. Browser uses dynamically read source flag/version, never resets shared data or assumes a hardcoded task count.

Accounts: `mission-intake-{project}-{admin|admin2|member|other}@example.test`. Other belongs only to foreign organization5. Authentication uses real form login and actual CSRF token for direct commands. Password comes from environment and is not captured in manifest.

## Executable browser cases

| Case | Required evidence |
| --- | --- |
| MI01 edit/review/cancel/save | Scoped names, exact GET keys, canonical string version; unchanged cannot review; before/after reflects actual flag; unchecked acknowledgment disables save; back writes nothing; 201 receipt exact fields; +1 via BigInt; actual reload persists flag/version. Capture edit/review/saved. |
| MI02 terminal blocked | COMPLETED returns canChange=false and MISSION_NOT_EDITABLE; real POST returns 409 MISSION_INTAKE_BLOCKED. |
| MI03 exhausted blocked | Version exactly string 9223372036854775807, REVISION_EXHAUSTED, no UI command; real POST blocked. Capture blocked. |
| MI04 archived Team blocked | Exact archived Team scope, TEAM_ARCHIVED, no UI write; real POST blocked. |
| MI05 paused eligible/no-op | Paused fixture has no blockers, can change; direct unchanged command returns MISSION_INTAKE_NO_CHANGE; actual reviewed flip succeeds. |
| MI06 stale review | Real competing committed command changes source; final save detects staleness; no saved claim; explicit reload refreshes flag. Capture conflict. |
| MI07 dirty navigation | Dismiss real native confirm and beforeunload, preserve draft, then accept cancel to scoped Mission Control; no POST or persisted change. |
| MI08 historical replay/isolation | Real first201, later flip201, original same key/body replay200 with identical original receipt; changed body key conflict; second ADMIN cannot retrieve first actor's receipt; only two increments. |
| MI09 committed lost response | Actual route.fetch POST returns201 and persisted GET matches receipt before transport abort; UI unknown; manual retry exact original key AND raw body; backend replay200, same receipt, one increment. Capture unknown. |
| MI10 committed malformed response | Same as MI09, but corrupt only client success body after actual backend commit; identical retry, no fabricated business response. |
| MI11 explicit receipt recovery | Actual committed lost response; manual receipt GET restores historical saved result; only one POST; receipt lookup exact {receipt}. Capture recovered. |
| MI12 member denied | Real member login, real intake403, denied hook, no editable flag/review. Capture denied. |
| MI13 foreign actor denied | Foreign-only account cannot view intake; real403 and denied surface. |
| MI14 fresh actor fence | Review as ADMIN, change actual server session to different ADMIN, trigger focus; old review/context erased, zero POST. |
| MI15 unknown authority loss | Lose authority after actual committed unknown; erase private source/review/frozen body; restore original actor; explicit receipt recovery allowed, replay disabled without body; no additional POST. |
| MI16 held GET actor fence | Hold real successful context after backend response; switch actual session to member, await denied, release and drain response/render; old private context cannot repopulate. |
| MI17 held POST route fence | Hold actual committed201; navigate reused route to terminal Mission; await blocked, release/drain old response; no old saved receipt/private Mission title. |

Screenshot manifest must contain eight required states per project (24 rows for one complete clean run). Capture includes full page and checks no horizontal document overflow. Review visual evidence for readable dark text, wrapping, native keyboard controls and mobile menu; overflow alone does not establish visual quality. Manifest uses actual capture timestamp/hash and never records password, cookies or frozen command body.

## Coordinator native/database matrix (not represented as completed browser tests)

| Area | Required probes |
| --- | --- |
| Strict request wire | Duplicate/unknown/missing fields; numeric/string boolean; null/arrays; trailing object; malformed UTF8; exact4096 and4097 bytes; media type/charset; duplicate/comma/upper/non-RFC UUID key; every GET body/query; bad IDs and auth-before-parser401/403. |
| int64 | 0, 9007199254740991, 9007199254740992, MAX−1, MAX; negative, leading zero, +sign, exponent, MAX+1; exact string serialization; MAX−1 accepted once; exhausted Task Create domain409 and no partial effects. |
| Policy | Team DRAFT/ACTIVE/ARCHIVED/unknown; Mission CREATED/READY/RUNNING/WAITING_APPROVAL writable, PLANNING/REVIEW/terminal noneditable, unknown fail closed; exact ordered combined blockers; pause and resume identical eligibility. |
| Race serialization | Same key/body gives first201 plus replay200 and one event; same key/different body conflict; different keys/same snapshot one success; actual Create and Draft Edit collisions serialize and invalidate preexisting version/fingerprint. |
| Authority under waits | Revoke role, disable account or change credential while waiting on organization/account/membership/Team/Mission locks; no unauthorized effect; fresh receipt reads deny too. |
| Atomicity | Force receipt insert and audit insert failures independently; flag/version/updated_at, receipt and audit all roll back; task rows/status/owners/dependencies/pending/run/approvals untouched. |
| History/privacy | Own historical replay after lifecycle/intake changes still original; actor/scope/key isolation; malformed persisted receipt safe500 without raw JSON/SQL; safe audit fields only; old migration rows retained. |

Current result: syntax and 51-case discovery pass; runtime browser/native/visual acceptance pending coordinator stack and CI evidence. See independent review for source findings. No skipped or retried scenario may be called a pass.


Read-only native probe review is recorded in MISSION_INTAKE_REVIEW.md (MI-P1–MI-P5). The presence of real requests, races and failure triggers does not by itself establish original replay response equality, exact race effect counts, complete safe audit metadata or full parser coverage. Keep those acceptance rows pending unless supported by strengthened probe assertions or separately named executed backend tests. MI-R2's explicit DRAFT/ACTIVE source fix and unknown-state/replay regression test are now visible; execution remains independently unverified here.

## Coordinator executed outcome — 2026-10-28

Candidate562ef49: all17 browser scenarios ran across three projects,51 passed, one worker, zero retries. Existing702 instances also passed on the same candidate. Quality526 tests and Docker/native/restart/logout passed;24 final captures accepted. This supersedes the historical pending execution status above for the executable browser cases. The native/database table remains a design matrix: see MISSION_INTAKE_FINAL_REVIEW.md for named suite/probe coverage and explicit unexercised permutations. See MISSION_INTAKE_QC_REPORT.md for run/job/commit/tree evidence; no independent final reviewer result is implied.
