# Member access inventory verification

**Accepted for this bounded development read flow.** Tested candidate `9fdc56f4457820772681809fa4bddcc9302e508f` passed168 backend tests and402 real-backend browser instances, including8/24 new Member checks, native PostgreSQL scope/freshness/no-effects probes, restart/logout and coordinator review of12 responsive synthetic captures. Grant/role/invitation writes remain unavailable. Final documentation-only commit does not change this tested application/workflow tree.

Branch `feature/member-access` stacks on draft/unmerged Approval Information PR21, baseline6b639c7. Scope: ADMIN-only actual membership inventory and per-member grant-lifecycle inspection. No migration, production seeds or role/grant/invitation writes. Existing approval eligibility and execution boundaries remain separate.

Figma desktop71:6673/tablet71:7477/mobile71:8281 contexts and screenshots inspected by coordinator. Native Angular controls and existing Forest tokens used; mapped React design-system buttons cannot be imported into this Angular application. No visible image/SVG assets. Full pixel equality/accessibility certification is not claimed.

Local coordinator checks: FE lint/format/build, production validator precision/context/privacy/Unicode script, Java import checks and git whitespace passed. Maven/Docker are absent locally; real backend, PostgreSQL and rendered browser acceptance await CI. Source review and prospective cases are separate from runtime evidence. Sonar is paused.

The pending statements below describe historical checkpoints. Current bounded acceptance is the final candidate evidence below; no merge or Mac deployment is claimed.

## Frozen candidate and Quality result

Published candidate `3a4fd4cc85a17703827d3ba329fbb0d21c28d879`, tree `6aafda54fd408bd4be4b94e29abe9bde548d0ee0`, PR22 base `6b639c7c80086df79c3d1f6f3c81d1b7f4e0a625`, synthetic merge `08cad72af983ed4dff58e5c9e04c1cfbbfd5087f`. [Quality run39956106550/job120422224357](https://github.com/giangnguyen3260/agent_os/actions/runs/39956106550/job/120422224357) completed SUCCESS. Coordinator retrieved completed logs:168 backend tests,8 Member access tests, zero failures/errors/skips; Google imports/formatting, npm audit0, FE lint/format/tooling/auth-generation/member-wire validator/build all passed. This does not establish native PostgreSQL or browser/visual acceptance; Docker is pending.

## Historical first Docker failure and correction

[Docker run39956106533/job120422224180](https://github.com/giangnguyen3260/agent_os/actions/runs/39956106533/job/120422224180) failed on candidate3a4fd4c. Actual native Member inventory/grant PostgreSQL comparison/fresh authorization/no-business-effects probe PASS at2026-10-23T12:52:00.824Z; prior native probes passed. Browser:64 passed,1 failed,337 not run, because the first Member case timed out at exact-label role select. Restart/follow-on gates were skipped, so this is not acceptance.

Corrective candidate `9fdc56f4457820772681809fa4bddcc9302e508f`, tree `a1abceb058c4575f8932d312fe5959a619d558ee`, changes only explicit native role/status select aria-labels and Docker total job budget30→45 minutes. All402 tests, retries0, workers1, fail-fast and assertions remain unchanged. Local FE lint/format/build reran successfully. Isolated local Playwright diagnosis was blocked by absent browser binary; wrapping-label option text is a source inference, not an executed diagnosis. Full candidate rerun required.

Initial artifact12502939214 ZIP SHA256 `4a631b38b7b07001936072645eefacf8d5d838a6ddae9d54158068488c3ad85c` was downloaded/hashed by coordinator; it contains only the desktop Members capture from failed3a4fd4c. Coordinator inspected its top region as early layout evidence. It is not final responsive/browser acceptance.

## Final candidate acceptance

Candidate `9fdc56f4457820772681809fa4bddcc9302e508f`, tree `a1abceb058c4575f8932d312fe5959a619d558ee`; PR22 merge `70b23f1b57f25e88c6204910cb9361c21681223c` onto baseline6b639c7. Both completed checkout logs bind their results to that merge/candidate.

| Evidence | Actual outcome |
| --- | --- |
| [Quality39958789208/job120431212111](https://github.com/giangnguyen3260/agent_os/actions/runs/39958789208/job/120431212111) | SUCCESS.168 H2/MockMvc backend tests,8 Member methods, zero failures/errors/skips. Google formatting/imports, npm audit0, FE lint/format/tooling/auth-generation/Member validator/build pass. |
| [Docker39958789206/job120431212204](https://github.com/giangnguyen3260/agent_os/actions/runs/39958789206/job/120431212204) | SUCCESS. Actual container build/schema/healthy stack;168 backend tests during image build; all earlier native probes retained. |
| Member native PostgreSQL probe | PASS2026-10-23T13:12:50.612Z: HTTP projections compared with stored PostgreSQL rows/counts, literal filters/stable pages/lifecycle, org/target scope, fresh role/enabled/credential-version/membership denial, restored controls and no business-row effects. No forced concurrent PostgreSQL read snapshot race or load test is claimed. |
| Browser regression |402 passed (12.6m), retries0, workers1, all three existing projects.378 baseline plus24 new (8 cases ×3 widths). Explicit select labels fix the failed first-run path without changing its oracle or other tests. |
| New browser scope | Search/literal/email/role/page/reload/back and keyboard tab; disabled account/grant states/paging/empty; independent MEMBER/foreign org and existing foreign target denial; malformed/failed read cleanup/retry; actual role demotion; held real-response route exit/reused-filter suppression; shared-session logout and reload-based account change. No immediate cross-tab notification or live account-switch race is claimed. |
| Restart/logout | PASS2026-10-23T13:26:40.519Z: exact session survives backend restart and logout rejects replay; recovery race/native baseline gates pass. |
| Final visual artifact |12504258561 `member-access-visuals`,12 PNGs, tied to9fdc56f. ZIP SHA256 `528cf7a8cf3e1f16181f407a0dbda5ceb3810959ca5f1b6efa1436ff027adf35` matches metadata, log and coordinator local download hash. |

Coordinator inspected top/bottom review crops of all12 captures (Members, Grants, empty, denied ×1440/768/390), plus the full mobile empty image. Full-page captures retain width1440/768/390; long ledger content scrolls vertically. Readable dark text, wrapping cards, mobile stacked filters, navigation and denied-state private clearing show no captured blocking issue. This is captured-region review, not exhaustive examination of every repeated middle row, pixel equality, all browser engines or full accessibility certification. Browser overflow assertions passed. QC independently verifies logs/source; visual attribution belongs to coordinator.

The27-case QC inventory is prospective coverage planning, not27 separately executed passes. Independent review has zero remaining blocking source finding; final execution disposition is in MEMBER_ACCESS_REVIEW.md. Scope excludes grant issuance/revocation/authority policy, invitations/role editing/team-role schema, runtime/execution, production load/failover/security certification, merge and Mac deployment. Sonar remains paused. No migration or production seed was added. Final publication after this candidate changes Markdown documentation only; code/tests/workflows remain exactly the verified candidate.
