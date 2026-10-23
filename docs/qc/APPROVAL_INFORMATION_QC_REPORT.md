# Approval information exchange QC — 23 October 2026

Status: accepted for the bounded information-exchange development slice. Final source, Quality, Docker/native/browser and captured-state visual checks passed. Branch feature/approval-information stacks on draft PR20 head99df98b.

Delivered scope: real persisted QUESTION/ANSWER thread, exact requester reply/evidence, PENDING -> NEEDS_INFORMATION -> PENDING, explicit scoped approver grant, shared whitespace normalization, version-bound decision acknowledgement and actor/org/key receipt recovery. No request/grant producer, execution, Task/Mission transition or notifications. Figma Ask/Reply desktop47:2656/2697, mobile60:7444/7495 and tablet60:14060/14111 contexts and screenshots inspected; native Angular Forest tokens/controls used; no visible static assets or installable React controls.

Local FE lint/format/build passed before final freeze; Java Google formatting/import, Python/browser syntax checks run. Backend worker authored18 new integration tests (expected160 total); main authored8 real-backend browser cases (collected24 viewport instances; expected378 full suite). These are expectations, not runtime passes. Local Maven/Docker unavailable; CI supplies execution evidence.

Independent QC's initial42 cases are prospective. Concrete review corrections: removed unsafe revision-advance inference for unknown outcomes; added exact scoped receipt GET plus thread-entry correlation; normalized optional blank evidence to null and aligned Java/JS whitespace; requester browser contexts explicitly inherit test viewport; current supplemental conversation is shown on detail/verify with matching revision before decision controls appear. Source review is separate from runtime verification.

Historical pre-CI acceptance checklist (completed by final evidence below): exact candidate/merge/run/job IDs, actual tests/native proof, restart/logout, responsive captures and artifact digest. Keep draft/unmerged; Sonar paused. No production load/security or Mac deployment claim.

## Quality checkpoint

Candidatecd79cfee3a8dd9bec2737717981c9866d500678f, merge075f9c77bc338759f205b3af673c868f89baac73 onto99df98b, passed Quality run39883738441/job120186937961:160 backend tests including18 new, zero failures/errors/skips; Google formatting/imports, FE style/tooling/build and npm audit0. Docker/native/browser/visual acceptance remains pending.

## First Docker checkpoint

Candidatecd79cfe Docker run39883738578/job120186938340 built/started the healthy V012 stack and passed earlier probes, then stopped before information business assertions at effects() SQL line162: snapshot/head tables were incorrectly sorted by nonexistent id. Correct keys are approval_id/action_id. Probe corrected without changing application source or omitting immutable input comparisons. Browser gates did not run; this checkpoint is not accepted runtime evidence.

## Corrected-candidate handoff

Candidate1111384b68fc86cefd4341c2e47b5650b1e12559 corrects native table ordering and rollback constraint creation (`NOT VALID`), retains all row comparisons, includes decision receipts and starts preauthenticated concurrent clients behind explicit barriers. Shared authority is still restored in finally. It also adds explicit safe HTTP500 handling for DataAccessException, preventing a failed persistence response from falling through a security error dispatch. The existing rollback integration case now asserts MockMvc500/INTERNAL_ERROR, no SQL/constraint/private-question response leakage and unchanged persisted state. This is a narrow application error-handling change; the prior160-test pass does not validate it. Fresh Quality39884513236/Docker39884513161 results are pending.

Corrected Quality acceptance:1111384b68fc86cefd4341c2e47b5650b1e12559, merge b01c25df1c380a0ca92e7d1a19903dbc1abba1b7 onto99df98b, passed run39884513236/job120189434197. Completed logs show160 tests including18 information methods, zero failure/error/skip; Maven verify, Java/FE style/tooling/build and npm audit0. Native/browser/visual evidence still pending for this candidate.

Final UI candidatefd93cdd3a13f4fe8d1cf2d7702f95c9ea6699b57 adds a header status from the validated Detail response and explicit waiting/answered browser assertions. It clears status on scope/account refresh. No business authority or state logic changed. Local FE format/lint/build passed. Candidate1111384 native Information step passed and browser run was ongoing; this is a checkpoint, not final-candidate browser acceptance. Fresh final Quality39885059566/Docker39885059802 evidence is required.

Final UI Quality acceptance:fd93cdd3a13f4fe8d1cf2d7702f95c9ea6699b57 / merge f677ef257e0af0c286f31cc95d3c5297e866c183 onto99df98b passed run39885059566/job120191192216.160 backend tests including18 information methods, zero failure/error/skip; complete FE checks and npm audit0 passed. This is the required final source candidate; Docker39885059802/job120191193847 is still running.

Final privacy candidatea5db75627b58070877dfa34a253d582e63acb2dc adds one explicit conversation signal clear on denied decision submission. Existing DOM clearing was insufficient for the newly added supplemental thread's in-memory privacy invariant. Independent read-only privacy sweep found no other blocker across account/route/denial/late-response paths. Local FE format/lint/build passed. Final candidate must be validated by Quality39885863340/Docker39885863346, independently of the preceding UI checkpoint.

## Browser harness checkpoint and correction

Candidate1111384 Docker run39884513161/job120189433676 passed native Information probe at01:35:56 UTC and160 backend tests, then reported366 browser passes/12 failures (13.9m): AI-B03/B04/B05 incorrectly queried role region while the notice explicitly uses role status; AI-B07 awaited a dialog-blocked click before dismissing the dialog. Each failed on all3 widths. Restart acceptance was skipped. These are concrete observed harness failures, not passing recovery/lifecycle evidence.

Candidate2b278afa1bfa60abb1f298fadf2220472d474a7c corrects only test oracles and CI fail-fast behavior: exact accessible status/name selectors, dialog dismissal registered before actions with prompt count1/2 plus unchanged URL/input assertions. Real commit-before-fault, exact body/key replay, receipt correlation and no-replacement POST checks remain. A successful CI still requires all378 collected cases; max-failures1 only ends a failing run early. Application source is identical to privacy candidatea5db756. Independent source review accepted this narrow oracle correction; final execution pending Quality39886245444/Docker39886245515.

Latest required-candidate Quality acceptance:2b278afa1bfa60abb1f298fadf2220472d474a7c / merge a9b4104841044694f3a610ade69e0829ac587e22 onto99df98b passed run39886245444/job120194979970 with160 backend tests,18 information methods, zero failure/error/skip, Google formatting/imports, FE checks and npm audit0. Docker39886245515/job120194978849 passed the native Information step and is proceeding to browsers; full suite/restart/visual acceptance remains pending.

## Final acceptance — 23 October 2026

| Gate | Verified evidence |
|---|---|
| Application/test candidate | 2b278afa1bfa60abb1f298fadf2220472d474a7c, merge a9b4104841044694f3a610ade69e0829ac587e22 onto PR20 head99df98bee5ec7e0eae2585e95a4bc3d3be889564 |
| Quality | [Run39886245444](https://github.com/giangnguyen3260/agent_os/actions/runs/39886245444), job120194979970:160 backend tests including18 new methods, zero failures/errors/skips; Java Google rules, FE format/lint/tooling/build and dev-inclusive npm audit0 |
| Docker/PostgreSQL | [Run39886245515](https://github.com/giangnguyen3260/agent_os/actions/runs/39886245515), job120194978849: completed success. Healthy V012 stack, earlier regressions and native Information thread/receipt/audit, exact requester/grant, revision-bound decisions, concurrency, rollback and no execution passed |
| Browser | 378 passed (11.5m), no retries; all24 AI-B01..B08 instances passed across1440/768/390 widths, retaining354 baseline cases. Fail-fast did not skip any case in this successful run |
| Restart/logout | Exact persisted session survives backend restart; logout rejects replay. Final log success23October09:06:53 ICT |
| Visual provenance | Artifact12473033054 approval-information-visuals,18 synthetic PNGs tied to2b278af; downloaded ZIP SHA2565db0c67ec0d061e7ed52e4ce534ab41e024a4460605ecc7243a4561689046ae8 matches GitHub artifact digest |

Main coordinator inspected all18 images in three contact sheets and six individual full-resolution captures (desktop ask/answered, tablet reply, mobile reply/unknown/answered). Question, waiting, requester answer/evidence, returned PENDING history and unknown-outcome controls show readable dark text, clear status and wrapping without an observed captured-state layout blocker. Requester contexts explicitly use each project viewport, with innerWidth/overflow assertions. Captures use fullPage:true; mobile answered1015px and reply976px include below-fold content. This is bounded visual review of synthetic states, not pixel-parity certification, exhaustive long-content/accessibility, Safari or physical-device acceptance.

Independent QC source findings are closed. Reviewer independently fetched current Quality and historical failed-run logs; final runtime review records its own provenance separately.42 inventory entries remain prospective, not42 personally executed tests. Actual regression counts above are the completed CI suite. Earlier failed checkpoints/oracle corrections are retained and are not substituted for final evidence.

Final documentation-only head may differ from2b278af. Only Markdown changes are permitted after this accepted candidate; application, migrations, fixtures, workflow and tests stay identical. Draft PR21 remains unmerged, stacking on draft PR20.

## Scope and remaining work

This slice persists human questions/answers/evidence and immutable conversation history; it does not change sealed executable parameters or mint execution authority. Only exact scoped approvers may ask and only the original human requester may reply. Fresh revision binding prevents an acknowledgement predating a round from approving/rejecting after it. Unknown outcomes recover only through an exact authorized receipt; private data is cleared on denied access.

No trusted request/snapshot producer, grant-management capability, other action adapter, external notification, Task/Mission transition or runtime/Tool Gateway execution is delivered. Sensitive credentials must not be put in free-text fields. DB-owner tampering, deployment encryption-at-rest/key management, high-load/failover/penetration and complete production approval workflow acceptance are outside this development gate. Organization-first write serialization remains a throughput tradeoff. GET receipt recovery is repeatable-read and may linearize before a later revocation; native write revocation checks observe real lock waits. Intercepted browser denials prove client handling, while native tests separately exercise actual revocation. No Mac deployment is claimed. Sonar remains paused.
