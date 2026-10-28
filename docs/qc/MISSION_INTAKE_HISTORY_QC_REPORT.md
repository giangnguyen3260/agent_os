# Mission intake history QC evidence

## Final coordinator acceptance — 2026-10-28

Accepted C2 source `db94e9498048109a394fb9504ba3574c096445fc`, tree `486d639510c0507f5e69cd3cbb16014e9d3be545`. Both workflows checked merge `09a90144fecebe6043b737c879b52af1efd0ff85` into base `188098c65cb68232022486826a7f6bcb5053ee55`; the merge tree exactly matches the candidate tree. Initial C1 failures remain recorded below and are not reported as passes.

| Gate | Actual executed result |
| --- | --- |
| [Quality40700334115](https://github.com/giangnguyen3260/agent_os/actions/runs/40700334115), job122858097721 | Completed/success.639 backend test invocations, zero failures/errors/skips:493 regular,23 TeamSettingsPostgresIT,48 TaskDraftEditPostgresIT,18 MissionIntakePostgresIT and57 MissionIntakeHistoryPostgresIT. The regular set includes56 history invocations, including inherited tests. Google Java imports/formatting/coverage and frontend lint/format/tooling/build passed; dependency audit zero vulnerabilities. |
| [Docker40700334131](https://github.com/giangnguyen3260/agent_os/actions/runs/40700334131), job122858098014 | Completed/success. Healthy built stack;493 regular backend tests, zero failures/errors/skips. All retained native probes and the new history HTTP/PostgreSQL probe passed. |
| New history UI | Exact describe grep `mission intake history independent QC`:27 passed, one worker, zero retries. Production history harness passed141 wire cases plus authority/privacy/menu checks. |
| Retained UI | Exact grep-invert of the new describe:753 passed, one worker, zero retries. Total780 browser instances across3projects, not780 distinct scenarios. |
| Restart/logout | Exact session survived backend restart; logout rejected replay. |
| Visuals |15 original PNG/15 manifest records, five states × three widths; hashes/widths verified and coordinator reviewed. Artifact12822181217,1234192 bytes; ZIP SHA256 `b53250fc2b512cc5e45c9e298af2a5ceb0d6edbd95793dc3ce427ec3145c4173` matches uploaded log, REST and downloaded bytes. See [manifest](MISSION_INTAKE_HISTORY_VISUAL_MANIFEST.md). |

Native evidence creates26 actual accepted flips across2actors and an actual Task Create counter gap; a controlled high-counter fixture exercises full int64 history, while timestamp ties are induced consistently on actual accepted receipts and restored. Reads/rejections compare all persisted business-table hashes except authentication bookkeeping. Actor rename, MEMBER/disabled author policy, removal of same-org membership while foreign membership remains, explicit null, lifecycle independence, strict real HTTP query/body/scope, selected/unselected corruption, current viewer role/disabled/credential denial and original replay identity all passed. Backend native migration test preserves existing business rows/receipts while adding V019. These are bounded executable cases, not every possible corruption or authorization interleaving.

Initial independent QC identified the C1-controls and native oracle gaps, inspected their corrections and the later test isolation/raw-query fix. [Independent final evidence review](MISSION_INTAKE_HISTORY_FINAL_REVIEW.md) records separately observed CI/artifact/visual results and its precise limits. No formal security/accessibility certification, all-browser or load/failover acceptance is claimed. No new Figma frame or execution/runtime behavior is claimed. Sonar remains paused. Draft PR33 remains unmerged; no deploy.

Closure changes Markdown evidence/status only; application, tests, fixtures, probes, migration and workflows are byte-identical to the accepted candidate. Fresh closure-head CI results are not substituted for or claimed as these candidate results.

### Executable source hashes

| Source | SHA256 |
| --- | --- |
| scripts/mission-intake-history-probe.py | `1a6f335bce4d0124302205b23eeda8a24e60457a13a7a42c89044d741d674158` |
| scripts/mission-intake-history-ci-fixture.sql | `670e97134be8468f4960481a5582073f3ca8a5ca0b50dec84c735c6fd86c4cc9` |
| frontend/tests/e2e/mission-intake-history.spec.mjs | `49de14db42149f98382dadf55f19dc8b6998b1120a9136418d3a8d1da8d81d29` |

## Historical checkpoints

Implementation candidate on feature/mission-intake-history, base188098c65cb68232022486826a7f6bcb5053ee55 (draft/unmerged PR32). No merge/deploy. Bounded read-only actual provenance, not dispatch/execution history. V019 is an additive scoped chronology index with no business-row backfill or production seeds.

Local frontend lint/format/build, production history schema/int64/time-order/authority/privacy/state harness, retained intake harness, Java imports, native/browser syntax and diff checks passed. Backend worker applied Google Java Format1.22. Browser27case discovery is not execution. Host lacks Java21/Maven/Docker; backend/PostgreSQL/browser/visual acceptance pending CI. No prior slice's pass counts are reused.

Main native probe creates26 actual accepted flips across two actors, actual Task Create gap and controlled legacy high counter; tie ordering deliberately adjusts test timestamps consistently on existing accepted receipts then restores them. Read/rejection snapshots cover all persisted business tables, excluding authentication bookkeeping. Required gate covers safe actor projection/null, current authority, exact receipt projection, paging/ties/int64, lifecycle, corruption and no writes. Browser uses separate persisted prefix540 fixtures and creates21 actual accepted changes per viewport; no fabricated DTO success. Five capture states × three widths require actual runtime artifacts and coordinator review.

Dedicated new browser slice precedes full753retained baseline and uploads its visual artifact immediately. One worker, zero retries/skips, existing45minute job timeout unchanged. Sonar remains paused. Frozen contract and independent source/case reviews identify exact evidence boundaries; final acceptance pending.


Independent source/probe review before first publication identified QC-H1 (C1 control rejection mismatch in history names), H2 (Python microsecond truncation in ordering oracle) and H3 (loose native UUID/name validation). Coordinator strengthens only the new history name decoder (including visible workspace name), preserves all9 timestamp fraction digits and cross-page order checks, enforces canonical UUID/safe nonblank255 names, and keeps a foreign membership while testing same-org author removal. These are source corrections; runtime acceptance still pending.


## C1 actual CI failure

C1 a5820736f290b1c8e43678a2f8996bd83b71e418, treea40675cd145a7c1022ad98f70953add6a3d94739. Quality40699538936/job122855269333 executed639 tests,4 failures/4 errors/0 skips, not a pass. Two malformed query inputs per H2/native history suite were normalized by MockMvc URL construction before reaching the controller. Existing inherited intake rollback fixtures used global table CHECK constraints and could not be installed after history tests had created other-org receipt/audit rows. Corrections preserve malformed-wire400 expectations by explicitly setting the raw request query and scope fault-injection constraints to each test organization, without deleting prior data or weakening rollback assertions. No production semantics change is needed. Runtime acceptance still pending on corrected candidate.


## C2 quality checkpoint

C2 source db94e9498048109a394fb9504ba3574c096445fc, tree486d639510c0507f5e69cd3cbb16014e9d3be545, tested merge09a90144fecebe6043b737c879b52af1efd0ff85 against PR32 base188098c65cb68232022486826a7f6bcb5053ee55. Quality40700334115/job122858097721 completed/success:639 tests, zero failures/errors/skips, including56 MissionIntakeHistoryTest and57 MissionIntakeHistoryPostgresIT; Java Google format/import/coverage and frontend lint/format/tooling/build passed, dependency audit zero vulnerabilities. Docker40700334131/job122858098014 runtime/browser/visual acceptance remains pending. Independent QC inspected the correction diff and found no assertion weakening, production regression or new blocker; that is source review, not runtime evidence.
