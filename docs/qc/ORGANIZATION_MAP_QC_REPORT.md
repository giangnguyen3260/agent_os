# Organization Map S02 QC report

## Verified development outcome — 26 October 2026

Organization Map S02 is verified on candidate `07968e39659988b5e148d05b0db4bfb5f6146a00`, tree `b9940b0d1a8707f3f22f5e1422766c6d451cec5a`, tested merge `984ce10e49769bc8956ff99df712b6cc5b75ce18`, base `9629c3ecbd7aaeab4a70205c279910f7ce305ff0`. Draft [PR27](https://github.com/giangnguyen3260/agent_os/pull/27) remains stacked on PR26 and unmerged. Earlier pending/failed-candidate notes below are historical and superseded by this exact-candidate outcome.

| Gate | Actual result |
| --- | --- |
| Code Quality | [40254448797](https://github.com/giangnguyen3260/agent_os/actions/runs/40254448797), job121353181468 SUCCESS:342 backend tests,0failures/errors/skips, including7 Map integration and23 actual PostgreSQL IT; Java formatting/imports, frontend production DTO/privacy harness, lint/format/build/tooling and dependency audit0 |
| Docker/native | [40254448830](https://github.com/giangnguyen3260/agent_os/actions/runs/40254448830), job121353181654 SUCCESS:319 regular backend tests,0failures/errors/skips during image verification; healthy stack and all native probes including Map passed |
| Browser Map |24/24 passed (47seconds),8 cases each at1440/768/390: scoped counts/selection/pages/reporting/cycles/links; filters/literal search; empty; foreign scope; actual response faults/retry; membership deletion and legal ADMIN downgrade; held reply after revocation; shared-session actor change |
| Browser baseline |561/561 passed (17.5minutes),585 actual passes combined;1worker,0retries; no discovery count substituted |
| Restart/logout |Exact session survives backend restart; logout rejects replay; later native read/concurrency/reset-barrier probes passed |
| Independent QC |ORGANIZATION_MAP_FINAL_REVIEW.md independently binds exact head/tree/merge, source and completed CI evidence; no actionable blocking finding remains |
| Responsive artifact |12622194571,24 PNGs,8 matching named states per width; ZIP SHA256 `ece889789a95735e543c032d4d785db36c2ae59432f0cfea90b37d2c18c74855` matched after download |

Coordinator inspected all8 contact sheets across24 captures: overview, selected, memberspage2, Unassigned, filteredempty, emptyorg, error and denied. Forest ink text and controls are readable, selected Team is clear, mobile filters/pagination wrap, and no observed overlapping or horizontal clipping; browser capture helpers also asserted document width does not exceed viewport. Long lists were inspected in top/middle/bottom sections; original mobile selected header/inspector crops were additionally reviewed at native width. Empty/error/denied show no stale private rows. Native data replaces Figma sample/runtime values; added filters/pages explain content-height differences. This is bounded responsive review, not complete pixel/accessibility or every long-list pixel certification.

Delivered GET-only scoped Team/Agent inventories, selected-Team inspector, explicit Unassigned, literal filters/stable bounded pages, retained lifecycle and safe reporting labels, existing destination links, current ADMIN-only create link, live focus/visible-return revalidation and callback fencing. Native no-write comparison covers the isolated organization's organization/Team/Agent/mission/task/audit rows; session housekeeping and intentional restored test authority mutations are separate. Normal PostgreSQL constraints remain intact. Invalid manager/assignment corruption branches and unsupported membership-role corruption are not claimed normal-schema runtime coverage. Broader prospective matrix rows, dedicated visible-return browser interaction and full keyboard/accessibility/load/failover certification are not silently accepted by these counts.

No migration, production seed, role/grant mutation, graph authority, Agent activation or fabricated runtime metrics. Sonar remains paused. No production or user's Mac deployment. Final closure changes Markdown only; application/tests/workflows remain byte-identical to the tested candidate above.

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


## Current candidate4 checkpoint

Head07968e39659988b5e148d05b0db4bfb5f6146a00, treeb9940b0d1a8707f3f22f5e1422766c6d451cec5a; unchanged base9629c3ecbd7aaeab4a70205c279910f7ce305ff0. Quality40254448797/job121353181468 completedSUCCESS:342tests0failures/errors/skips,7Map and23real PostgreSQL IT; audit0 and allFEqualitygates. Docker40254448830/job121353181654 native steps including Map passed; browser step in progress.24new/561baseline/24expectedimages/restart remain pending until exact log evidence.

Superseded C3Docker40254343193/job121352860686 completedFAILURE exactly at illegalUNKNOWNrole SQLvector after5desktop browser cases passed;1failed18unrun,baseline/restart skipped. C4corrects that test vector without application changes. C3partial8desktop captures are upload evidence only, not final responsive acceptance.
