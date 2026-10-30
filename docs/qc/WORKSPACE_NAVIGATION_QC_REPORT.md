# Workspace navigation completion QC

Status: accepted within the frozen navigation contract on `feature/workspace-navigation-completion`, draft [PR36](https://github.com/giangnguyen3260/agent_os/pull/36), stacked on PR35. No merge/deploy; Sonar remains paused.

Accepted source C2 `57ba58f7838a77a8e7b5d2e20d24153237453b82`, tree `58e6ff47f98cc165616b5f9e03594d1a2a845e37`, tested merge `b31366ff4d8cec47d3d7af323929c45ae5210e33`, base `06eb8b500dc80f5e72e6051b4a968f4a208cd9a9`. Later Markdown-only closure preserves all application/test/workflow bytes and does not imply an independently rerun runtime pass for the documentation head.

Scope:24 Forest desktop/compact shells expose existing destinations, including17 previously disabled Approvals placeholders. Task Detail explicitly links to organization-wide approvals without a task filter. Mission Control copy describes available draft input/owner configuration while preserving unavailable planning/outputs/execution. No backend/security/DTO/schema/route/guard/command implementation change. See [contract](../engineering/WORKSPACE_NAVIGATION_CONTRACT.md), [BA/SA review](../engineering/POST_PR35_DELIVERY_REVIEW.md) and [independent cases](WORKSPACE_NAVIGATION_TEST_CASES.md).

## Completed execution

| Gate | Result |
| --- | --- |
| [Quality41059733009](https://github.com/giangnguyen3260/agent_os/actions/runs/41059733009), job124076163321 |849 tests,0 failures/errors/skips; Java formatting and frontend dependency/lint/format/harness/build gates passed. |
| [Docker41059733022](https://github.com/giangnguyen3260/agent_os/actions/runs/41059733022), job124076163398 |Completed success. Regular backend599 tests,0 failures/errors/skips; healthy migrated stack. |
| New navigation browser |90 passed:24 source-route cases plus6 focused cases ×1440/768/390. Real scoped Approvals/Organization Map GET arrivals, organization-wide Task Detail action, MEMBER access, wrong-account privacy, dirty cancellation/accepted departure without save, keyboard/native-menu and anonymous guard. |
| Retained browser |42 owner history +81 owner +780 other retained =903 passed. Total993 disjoint invocations with90 new. Existing focused INV-B08 adds3 repeat executions, not new unique scenarios. No retries/timeouts/retained selection weakened. |
| Native and durability |All preceding/retained native PostgreSQL probes passed; exact session survives backend restart and logout rejects replay. |
| Visual |Three original PNGs plus JSONL inspected at1440/768/390, actual hashes/ZIP verified. No blocking captured finding; [manifest](WORKSPACE_NAVIGATION_VISUAL_MANIFEST.md), [independent final review](WORKSPACE_NAVIGATION_FINAL_REVIEW.md). |

Local checks: frontend lint/full formatting/Angular production build; new browser syntax/scoped formatting/90-case collection and diff checks. Local Java17 has no Maven/Java21/Docker. Runtime results above are from actual completed CI logs. No browser pass is inferred from collection.

Each browser case requires zero organization API mutations and matching before/after business-table digests of sorted values plus xmin/ctid, including identity/membership and history/receipt rows. Only bounded digests return from SQL, avoiding raw row output and subprocess buffer growth. This establishes unchanged committed observations, not forensic absence of rolled-back SQL attempts. Existing backend authorization and guards remain authoritative. Opening Approvals confers no decision grant and never starts execution. No production hardcoding, Figma fidelity claim, formal accessibility certification, deployment or full-product completion.

## Earlier failed candidate

C1 `1f24ad706bd4927d7224b54bd848171ce6670b52`, tree `690be6228d234d602ccac16c978bb19c0faa187a`, tested merge `78b7975ebbc65802137a47c7b5f7015514e1b74d`: Quality40985797360/job123836393594 passed849 tests,0 failures/errors/skips. Docker40985797187/job123836393489 passed dedicated owner-history42 and owner81 browser invocations,3 focused invitation repeats and preceding native probes, then new navigation stopped at27passed/1failed/62notrun. Its foreign-account case expected403 from auth workspace discovery, but the existing AuthController intentionally membership-joins and returns404 when absent. C2 pins this endpoint to404, checks its response for known private markers, and preserves the distinct organization Approvals403 check. No production/security or assertion relaxation. Partial artifact12947279508 has one desktop PNG plusJSONL and is not complete responsive acceptance. Retained780 browser and restart/logout were skipped; full acceptance remains pending.


C2 corrects only auth workspace discovery to its existing exact404 response and adds a known-private-marker check. The organization Approvals403 requirement remains exact. C2 completed all required execution and responsive evidence, superseding the earlier pending status above.
