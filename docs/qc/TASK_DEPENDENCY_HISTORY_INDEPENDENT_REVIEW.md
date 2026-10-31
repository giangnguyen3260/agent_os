# BA-01 — independent PR38 dependency-history review

Review date: 2026-10-31. Verdict: **no blocking finding identified in this bounded slice**. No Critical/High/Medium defect was established by this review. This is independent source/evidence review, not a new full runtime execution or whole-product acceptance.

## Frozen scope and independence

- Reviewed source HEAD `cef8d77b402f9fb9473c9458579a9a3cc06c6fb3` against base `60a7f834b523ab2319a20a5e51ab2af6e16195a3`.
- Application candidate C4: `6860c3106449c8b91472a428f1b49f5f66b79a5a`; documented tested merge `d8f2719656b487fae6b70fb0cb3d25748384d81b`. Local comparison confirms C4→frozen HEAD changes only five Markdown files, with no application/test/workflow change.
- Inspected the frozen contract, controller/DTO/service and reused security/snapshot/transport validators, history route/Task Detail entry/component/template/styles, integration/native-PG tests, production state harness, feature browser suite, native probe/fixture and relevant workflow selections. Read all four existing TASK_DEPENDENCY_HISTORY QC_REPORT, TEST_CASES, VISUAL_MANIFEST and DELIVERY_CHECKPOINT documents.
- No code changes, migrations, runtime-lifecycle document review, other agents, merge or deployment. Pre-existing unrelated working-tree changes were left alone. Only this report is authored by this reviewer.

## Findings by severity

| Severity | Result | Disposition |
| --- | --- | --- |
| Critical / High | None established | No blocker identified |
| Medium | None established | No blocker identified |
| Low | No actionable implementation defect established | Evidence limits below remain explicit |

The implementation matches the principal business distinction: actual recorded configuration provenance, without claiming execution/readiness or inferring a baseline from legacy edges. History resolves the exact structural parent chain but does not invoke current graph eligibility/lifecycle validation. Counts and selected rows use the full organization/team/mission/task scope in a read-only REPEATABLE_READ transaction. Revision ordering, fixed twenty-row paging, gaps and out-of-range totals are handled independently of the current dependency revision.

The existing interceptor covers the new history subpath and checks current enabled account/credential version/organization ADMIN before controller parsing; service authorization repeats that check. Actor names join membership in the event organization, return explicit null after membership loss, and do not depend on enabled status of the historical author. Snapshot decoding reads immutable event arrays rather than current target rows. Selected malformed records fail with the generic history-unavailable code; SQL pagination occurs before decoding, preserving off-page isolation. No new business writer or migration is introduced.

The UI checks fresh raw session and workspace ADMIN for loads, uses strict runtime DTO validation, and clears prior rows/names before reload/navigation or failure. Cancellation and epoch/account/scope/page fences protect success and error publication. Visibility loss/destruction clear private state; focus/visible transitions revalidate. The template renders complete before/after arrays with nine human status labels and blank historical-title/missing-member fallbacks. The ADMIN Task Detail link has no lifecycle restriction.

## Independently checked execution evidence

GitHub connector retrieval succeeded for both run job lists and decoded job logs. These are independent checks of prior CI execution, **not reviewer reruns**:

| Evidence | Independent check |
| --- | --- |
| [Quality 41192779464](https://github.com/giangnguyen3260/agent_os/actions/runs/41192779464), job 124512789515 | Job and steps completed success; fetched log reports 1002 tests, zero failures/errors/skips. Frontend audit/style/build steps succeeded. |
| [Docker 41192779432](https://github.com/giangnguyen3260/agent_os/actions/runs/41192779432), job 124512789593 | Job and steps completed success; fetched log reports 694 backend tests, zero failures/errors/skips. |
| Feature native probe | Fetched log at 10:30:40 UTC reports PASS for actual history paging/snapshots, member privacy, lifecycle independence, strict query, selected/off-page corruption and no-write reads. |
| Browser gates | Fetched log confirms 33, 75, 42, 81, 90 and 780 passes, totaling 1101; an additional focused gate reports 3 passes. Inspected workflow selectors exclude the dedicated suites from retained regression. |
| Retained native PostgreSQL/SMTP | Retrieved job steps record successful native gates, including PostgreSQL/SMTP invitation acceptance. These are existing CI outcomes, not locally repeated probes. |
| Restart/logout | Fetched log at 11:24:44.016 UTC explicitly reports exact session survival across backend restart and logout replay rejection. |

Quality and Docker backend counts overlap and are not summed. The coordinator's 38 H2 + 38 PostgreSQL + 926 retained breakdown and full including-dev audit-zero statement remain coordinator detail; independently fetched job success and aggregate 1002 are corroborated here. The tested-merge identity above is coordinator checkpoint evidence; this review did not independently query run-level head metadata.

Locally executed `node frontend/scripts/test-task-dependency-history-validation.cjs`: **PASS**, reporting production DTO, pagination, authority and stale-response harness success. This harness transpiles production validators/component methods with controlled dependencies; it is not an Angular browser or backend run. No local Java21/Maven/Docker/native-PG/browser business suite rerun is claimed.

## Visual inspection and provenance

Local evidence directory: `/workspace/scratch/24b4696504b1/dependency-history-c4-visuals/`. Independently read all 15 JSONL records and verified all 15 actual PNG SHA256 hashes and IHDR dimensions against that manifest: **PASS**. This verifies local file/manifest consistency, not an independently downloaded ZIP digest. Artifact ID 13039892469, ZIP size/digest and coordinator inspection of all fifteen remain coordinator evidence.

Actually opened with `view_image` and visually inspected **5 of 15 original PNGs**:

| Capture | Dimensions | Observed result |
| --- | --- | --- |
| desktop-second-page | 1440×1000 | Readable dark text, scope, version 1 on page 2, before/after columns and pagination |
| tablet-second-page | 768×1024 | Compact header, wrapped scope, readable comparison and controls |
| mobile-second-page | 390×1097 | Stacked before/after, wrapping and full-page controls without captured clipping |
| mobile-error | 390×844 | Explicit error, retry/reset actions, no stale scope/rows visible |
| mobile-denied | 390×844 | Explicit current-ADMIN denial, no private scope/rows visible |

No captured blocker was identified in those five images. The ten remaining empty/populated/desktop-tablet error-denied captures were hash/dimension checked only, not visually inspected by this reviewer. Long populated-page flow, loading state, keyboard behavior and dynamic privacy outcomes rely on source plus successful CI tests; static images alone cannot prove them. Full Figma parity, exhaustive accessibility and all product states are outside this review.

## Test adequacy and bounded acceptance

Authored coverage is substantive rather than collection-only: real 21-command event creation; exact DB snapshot comparisons; tied timestamps/revision gaps; out-of-range totals; legacy no-history; current graph/lifecycle independence; actor rename/removal; selected/off-page corruption; strict query/body denial; immutable historical targets including a moved foreign target; role/account/route late-response fences; native Back and compact menu Escape/focus. The PostgreSQL subclass reuses the HTTP invariants against explicit native PostgreSQL. Fixture SQL seeds configuration and accounts, not successful provenance events; commands generate history.

The native probe checks committed business values and tuple identity globally with explicit authentication/session bookkeeping exclusions. Browser business snapshots are organization-scoped; that narrower browser oracle should not be described as a global all-table no-write proof. Combined with the read-only implementation and native checks, no release-blocking gap was established for this slice.

**BA-01 conclusion: bounded independent review complete; no blocker identified for the frozen Task dependency history slice.** Existing coordinator documents accurately describe their own evidence as non-independent at the time written; this report supplies this review without rewriting those historical statements. This conclusion does not authorize merging/deployment, accept new runtime-lifecycle work, or certify the full product.
