# Organization Map S02 QC report

Branch feature/organization-map stacks on PR26 closure9629c3ecbd7aaeab4a70205c279910f7ce305ff0. Implementation complete pending exact-candidate CI. See engineering/ORGANIZATION_MAP_CONTRACT.md, engineering/ORGANIZATION_MAP_FIGMA.md, ORGANIZATION_MAP_REVIEW.md and ORGANIZATION_MAP_TEST_CASES.md.

Local FE lint/format/build and actual production DTO/privacy harness passed before final visibility/accessibility adjustment. Java import check, Python syntax and workflow YAML parsed. Earlier attempt to run focused backend tests with checksum-verified temporary Java21/Maven3.9.11 failed before compilation because Maven could not resolve its parent POM (temporary DNS resolution failure); no backend test pass claimed. Current environment restarted and temporary tools are absent. No local Docker engine/runtime acceptance is claimed.

CI will run existing335 backend baseline plus new Map integration cases; actual PostgreSQL Map projection/count/scope/no-write probe,24 new browser instances and all561 baseline, responsive synthetic screenshots, Docker and restart/logout. These are planned counts until logs prove execution. No migration/production seeds, no invented runtime stats or private foreign reporting targets. V005 constraints prevent invalid assigned-Team/manager pointers in normal PostgreSQL; defensive redaction source is retained but corruption tests are not native-schema evidence.

Coordinator resolved search fields, Java.strip-before-length normalization, separate URL paging/selection and auth error precedence in the contract. Added explicit accessible name to Team status select and visible-tab revalidation. Other review findings remain source gates until final runtime verification. Sonar paused; no merge or production/Mac deployment.


## Candidate1 result and scoped-path correction

Candidate23bc7178ea619b9d655f4c8b31c2e561374cb459, treec09109d2d00a36669253476f050086879699728c, testedmergefdcf15bdaa7f52ad2ba06a4fb3920544ebe3f8d9. Quality40253317619/job121349708891 FAILURE:342tests,1failure,0errors/skips. Docker40253317615/job121349708765 failed image verification:319regular tests, same singlefailure. No native/browser/restart execution.

The noncanonical organization-path test used a foreign/nonmember scope and expected400, but the existing transport authorization correctly rejects403 before controller validation, as clarified in the contract. Replacement explicitly asserts that403, then provisions a dedicated owned scope with deterministic alphabetic UUID prefix and verifies uppercase path400 at canonical controller validation. Query UUID rejection assertions remain unchanged. No application or workflow change; no timeout/count relaxation. Backend Googleformat/import check passed; replacement CI still required.
