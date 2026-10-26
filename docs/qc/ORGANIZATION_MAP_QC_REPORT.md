# Organization Map S02 QC report

Branch feature/organization-map stacks on PR26 closure9629c3ecbd7aaeab4a70205c279910f7ce305ff0. Implementation complete pending exact-candidate CI. See engineering/ORGANIZATION_MAP_CONTRACT.md, engineering/ORGANIZATION_MAP_FIGMA.md, ORGANIZATION_MAP_REVIEW.md and ORGANIZATION_MAP_TEST_CASES.md.

Local FE lint/format/build and actual production DTO/privacy harness passed before final visibility/accessibility adjustment. Java import check, Python syntax and workflow YAML parsed. Earlier attempt to run focused backend tests with checksum-verified temporary Java21/Maven3.9.11 failed before compilation because Maven could not resolve its parent POM (temporary DNS resolution failure); no backend test pass claimed. Current environment restarted and temporary tools are absent. No local Docker engine/runtime acceptance is claimed.

CI will run existing335 backend baseline plus new Map integration cases; actual PostgreSQL Map projection/count/scope/no-write probe,24 new browser instances and all561 baseline, responsive synthetic screenshots, Docker and restart/logout. These are planned counts until logs prove execution. No migration/production seeds, no invented runtime stats or private foreign reporting targets. V005 constraints prevent invalid assigned-Team/manager pointers in normal PostgreSQL; defensive redaction source is retained but corruption tests are not native-schema evidence.

Coordinator resolved search fields, Java.strip-before-length normalization, separate URL paging/selection and auth error precedence in the contract. Added explicit accessible name to Team status select and visible-tab revalidation. Other review findings remain source gates until final runtime verification. Sonar paused; no merge or production/Mac deployment.


## Candidate1 result and scoped-path correction

Candidate23bc7178ea619b9d655f4c8b31c2e561374cb459, treec09109d2d00a36669253476f050086879699728c, testedmergefdcf15bdaa7f52ad2ba06a4fb3920544ebe3f8d9. Quality40253317619/job121349708891 FAILURE:342tests,1failure,0errors/skips. Docker40253317615/job121349708765 failed image verification:319regular tests, same singlefailure. No native/browser/restart execution.

The noncanonical organization-path test used a foreign/nonmember scope and expected400, but the existing transport authorization correctly rejects403 before controller validation, as clarified in the contract. Replacement explicitly asserts that403, then provisions a dedicated owned scope with deterministic alphabetic UUID prefix and verifies uppercase path400 at canonical controller validation. Query UUID rejection assertions remain unchanged. No application or workflow change; no timeout/count relaxation. Backend Googleformat/import check passed; replacement CI still required.


## Candidate2 result and localized UI assertion

Candidate72b14338cccab635819257428d59100196c79a74, treec5c4d55de1db4704d374d6d7a41b489956f70d03, testedmergeefc58f1 (full merge recorded by PR/CI). Quality40253673099/job121350797211 SUCCESS:342tests0failures/errors/skips,7Map tests,23actual PostgreSQL IT and audit0/allFEgates. Docker40253673147/job121350796458 passed319regularBEtests and all native probes including Map, then failed the first desktop browser case because its reporting-label assertion still expected English text after the agreed plain-Vietnamese copy change. Actual DOM correctly displayed 'Báo cáo cho: Không có thông tin'; exact actual receipt/DTO redaction assertions had already passed. Browser0pass/1fail/23unrun; baseline/restart unrun.

Replacement updates the two reporting-label assertions to the actual Vietnamese copy, retaining exact redaction/cycle/row/count oracles. No application changes, retries, count or timeout changes. Coordinator missed this test-copy mismatch during integration; corrected before further acceptance. Actual browser execution remains required.


## Normal-schema browser authority vector correction

Source audit before C3 completion found the authored UNKNOWN role browser vector cannot be applied under V002's membership role CHECK(ADMIN,MEMBER). No production/disposable constraint is dropped. Replacement retains actual membership deletion403/DOM erasure and adds legal ADMIN→MEMBER downgrade: reads remain200 while Add Agent disappears after fresh focus authorization. Eight cases×3=24 remain. Unsupported-role fail-closed source/H2 coverage is distinct from normal PostgreSQL evidence. C3head480984e2ab4c777722cdf762d3e46ba321854c51 (Quality40254343157/Docker40254343193) is superseded; no completion inferred. No application change.
