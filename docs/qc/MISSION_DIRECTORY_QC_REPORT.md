# Mission directory QC report

Decision: **PASS for bounded Mission directory delivery**. Draft PR14 stacks on unmerged PR13. This is a read-only directory delivery, not production or runtime execution acceptance.

## Candidate and evidence

Application `6568c94f98985bb807a6a6fce8582dbe0016027e`; tested PR merge `bbc298dc9d7a0d324848312972d5251a71b1c70a` onto `0bd4b1591071ed988c9777007d33ffea246416b8`. All evidence times below are UTC on21October2026. Final evidence documentation is separate from the tested application commit.

| Gate | Actual evidence |
|---|---|
| Code quality | [Run39637173286](https://github.com/giangnguyen3260/agent_os/actions/runs/39637173286), job119350733516, success16:44:31Z:103backend tests0failures/errors/skipped; Java import/format, FE lint/format/tooling/build passed |
| Docker | [Run39637173305](https://github.com/giangnguyen3260/agent_os/actions/runs/39637173305), job119350733245: success16:53:05Z; build/healthy stack/schema, fixtures, all browser/native probes and cleanup passed |
| Native directory probe | Step completed successfully16:45:31Z: exact PostgreSQL projection/order, bounded pagination, literal/status/Team filters, recorded task counts, safe unresolved-manager scope and business-state snapshot unchanged |
| Browser | Completed16:52:20Z:240passed in6.3minutes across1440/768/390 (18new and222baseline), serial execution with no retries |
| Native regressions and session | Existing Mission creation/replay, Agent creation, Studio concurrency, activation no-mutation, directory/Workspace projections and account barrier passed. Exact session survived backend restart and logout rejected replay16:52:56Z |
| Local checks | Full103BEtests passed, FE format/lint/build/import/diff checks passed.35mock rendered/interactions across1440/768/390 passed; screenshots inspected, chooser paging/filter/direct-create/archived-disable/Escape focus, error/null/scope/count rejection, MEMBER visibility and zero business writes checked. Mock API checks are not persistence evidence |
| Independent source QC | Final BE/FE read-only review found no blocking finding in response validation, chooser state, authorization clearing, task counts, manager scope or navigation guards. Reviewer did not independently rerun the full CI suite; coordinator inspects actual completed logs |

## Bounded delivery and cases

Forest S10 lists real organization-scoped Missions in desktop rows and tablet/mobile cards. Literal title search, all nine persisted lifecycle statuses, optional same-org Team filters and fixed20-row pages use URL state. Native Team chooser searches/pages the existing directory; archived Teams can be filtered but cannot be selected for creation. ADMIN can navigate to the existing creation wizard, MEMBER can read. Assigned Mission details and Browse missions use existing scoped routes and unsaved guards. Legacy unassigned rows show no invalid detail link.

Recorded counts independently constrain organization, Team and Mission; completed=COMPLETED and pending=WAITING/BLOCKED. Correlated task lookups apply only to the selected20Mission page. A zero-task Mission says No recorded tasks. No model calls, cost, live capacity or generated task graph is fabricated. Manager ID/name come only from the same-org/same-Team resolved agent join; invalid legacy references return both null. Goal/context/private payloads are excluded from this projection.

Fresh enabled identity, credential version and ADMIN/MEMBER membership protect every request. Count, rows and optional scoped Team metadata share a read-only REPEATABLE_READ snapshot. Bound parameters and escaped wildcards protect literal filters. FE cancels superseded subscriptions and guards results by request/account identity; invalid/null/foreign/inconsistent envelopes clear old rows. Modal Escape/cancel restore focus. No migration or production seed is added.

Six real-backend scenarios, each at1440×1000/768×1024/390×844, cover exact27Mission paging/order/history, literal and lifecycle filters, tenant/parameter denial, exact safe counts/projection, legacy/read navigation,25Team chooser pages/permissions, real membership revocation/restoration, and failure/malformed refresh recovery. See [cases](MISSION_DIRECTORY_TEST_CASES.md).

## Findings and fixes

Source review found a copied Teams-current marker alongside Missions; final sidebar correctly links Teams and marks Missions alone current. Explicit Status labeling, manager pair and filtered-Team-name envelope consistency, private chooser selection clearing and dialog sizing were corrected before publication. The two recorded-count regex assertions were executed from actual test source against representative whitespace and pass. No open blocking source finding remains.

## Remaining limits

Coordinator inspected both completed job logs for the exact application/merge revisions above. Broad accessibility, delayed-response race execution, production scale/failover/security and field encryption/KMS acceptance remain open. Planning, execution, provider grants and activation remain unavailable. Sonar is paused, not passed. No Mac deployment or image publication is claimed; all stacked PRs remain draft/unmerged.
