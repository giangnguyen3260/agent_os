# Approval information exchange QC — 23 October 2026

Status: integrated source and prospective tests; exact candidate CI and visual acceptance pending. Branch feature/approval-information stacks on draft PR20 head99df98b.

Delivered scope: real persisted QUESTION/ANSWER thread, exact requester reply/evidence, PENDING -> NEEDS_INFORMATION -> PENDING, explicit scoped approver grant, shared whitespace normalization, version-bound decision acknowledgement and actor/org/key receipt recovery. No request/grant producer, execution, Task/Mission transition or notifications. Figma Ask/Reply desktop47:2656/2697, mobile60:7444/7495 and tablet60:14060/14111 contexts and screenshots inspected; native Angular Forest tokens/controls used; no visible static assets or installable React controls.

Local FE lint/format/build passed before final freeze; Java Google formatting/import, Python/browser syntax checks run. Backend worker authored18 new integration tests (expected160 total); main authored8 real-backend browser cases (collected24 viewport instances; expected378 full suite). These are expectations, not runtime passes. Local Maven/Docker unavailable; CI supplies execution evidence.

Independent QC's initial42 cases are prospective. Concrete review corrections: removed unsafe revision-advance inference for unknown outcomes; added exact scoped receipt GET plus thread-entry correlation; normalized optional blank evidence to null and aligned Java/JS whitespace; requester browser contexts explicitly inherit test viewport; current supplemental conversation is shown on detail/verify with matching revision before decision controls appear. Source review is separate from runtime verification.

Pending: exact published candidate/merge/run/job IDs, actual tests and native proof, restart/logout, full-page responsive screenshots and artifact digest. Keep draft/unmerged; Sonar paused. No production load/security or Mac deployment claim.
