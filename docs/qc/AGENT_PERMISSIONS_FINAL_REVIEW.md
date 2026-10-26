# PR28 Agent Permissions — final independent evidence review

Status: **C2 completed Quality, Docker, native inspection, browser and restart/logout evidence accepted within the read-inspection scope.** Reviewed 2026-10-26 UTC by authorized project QC. This final review supersedes the predecessor document’s C1/current-pending ledgers and C2 publication-pending sections. Coordinator visual review is recorded below with explicit attribution; QC did not independently inspect image contents.

QC changed only this document. No application, security, probe, fixture, browser spec, workflow or other documentation edits; no rerun, merge or deployment performed.

## Candidate and tested merge provenance

PR: https://github.com/giangnguyen3260/agent_os/pull/28

| Identity | Verified value |
| --- | --- |
| C2 published head | `af6508f227027b52c8f01843c649d38b198e0162` |
| C2 head tree | `68797f82cb51f9588d1bbb67a9aae222113a2fcb` |
| Head parent (C1) | `e7582b0a35359b2116a46f7dd889abd25239a06f` |
| Tested synthetic merge | `c7f80442ba62eed9f9dadc99ef2cf9e97c367440` |
| Ordered merge parent 1 / base | `d64206fc0da14b6a47fcf46b98063c90d67f6c18` |
| Ordered merge parent 2 / head | `af6508f227027b52c8f01843c649d38b198e0162` |
| Tested merge tree | `68797f82cb51f9588d1bbb67a9aae222113a2fcb` — exactly C2 head tree |

Independently fetched GitHub Git-commit object: https://api.github.com/repos/giangnguyen3260/agent_os/git/commits/c7f80442ba62eed9f9dadc99ef2cf9e97c367440. Local Git object independently confirms C2 head/tree/parent. Both decoded checkout logs fetch `refs/remotes/pull/28/merge` and print the full merge SHA: Quality at **14:27:06.2823335Z**, Docker at **14:26:46.2038219Z**. This establishes exact tree attribution; a synthetic merge is not evidence PR28 has actually merged.

C1→C2 changes are exactly `scripts/agent-permissions-probe.py`, `docs/engineering/AGENT_PERMISSIONS_CONTRACT.md`, and `docs/qc/AGENT_PERMISSIONS_QC_REPORT.md`. No app/security/browser/workflow changes in that candidate delta.

## Independently fetched completed GitHub evidence

Directly fetched run metadata, latest-attempt jobs/steps and decoded logs from GitHub, rather than relying on main’s relay or the predecessor’s in-progress states.

| Workflow | Run / job | Completed result |
| --- | --- | --- |
| Code quality | [40339698664 / verify 121630706060](https://github.com/giangnguyen3260/agent_os/actions/runs/40339698664/job/121630706060) | completed / success; run 119, attempt 1; updated 14:29:09Z |
| Docker build and smoke test | [40339698678 / compose-smoke 121630706396](https://github.com/giangnguyen3260/agent_os/actions/runs/40339698678/job/121630706396) | completed / success; run 119, attempt 1; updated 14:54:26Z |

Both run metadata records identify the exact C2 head. Every step returned by each jobs API is completed/success, including native fixture/probe steps 46–47, browser step 49, visual upload step 50 and restart step 67 in Docker. Required execution is established by detailed log results below, not upload success alone.

## Backend and quality evidence

| Execution | Actual aggregate | Independent per-class reconciliation |
| --- | --- | --- |
| Quality Maven | **351 tests; failures 0, errors 0, skipped 0**, 14:28:29.1583596Z | 34 class-result lines sum to 351/0/0/0 |
| Docker regular backend Maven | **328 tests; failures 0, errors 0, skipped 0**, 14:29:22.4070784Z | 33 class-result lines sum to 328/0/0/0 |

Quality executes `mvn --batch-mode --no-transfer-progress -f backend/pom.xml '-Dtest=*Test,TeamSettingsPostgresIT' verify`; BUILD SUCCESS at **14:28:30.2941186Z**. `AgentPermissionsIntegrationTest` executes **9/0/0/0** at **14:28:22.8153249Z**; `TeamSettingsPostgresIT` executes **23/0/0/0** at **14:28:14.8004351Z**. The 23-test difference is explicit PostgreSQL IT coverage; Docker’s 328 is separately observed execution, not inferred subtraction.

Java import rules, backend formatting/coverage, frontend lint/format/tooling/privacy harnesses and production build steps succeeded. Dedicated `npm audit --include=dev --audit-level=low` reports **found 0 vulnerabilities** at **14:28:37.7390210Z**. Quality upload records artifact **12658547803**, **2,241,609 bytes**, ZIP SHA-256 **`db52fae2f52b6388783a0329b3114817a6e1b648db2f3fe47d640baadc8977f5`**. This is upload-log metadata, not a claim QC unpacked its contents.

## Native PostgreSQL inspection and rejected-write boundary

Docker step 46 successfully provisions the normal-schema isolated fixture; step 47 runs `scripts/agent-permissions-probe.py` to completion. At **14:33:04.4717051Z** it prints:

> PASS: unsupported Permissions PUT is rejected403 for ADMIN and MEMBER under existing transport policy

At **14:33:04.4718410Z** it prints:

> PASS: native PostgreSQL Agent Permissions exact opaque requests, missing/null/empty/lifecycle, scope/authority, real Studio revision refresh, no human-grant inheritance and no inspection writes

Reviewed the executed probe’s sequential assertions and fixture to interpret these markers. Completion establishes:

- Anonymous GET 401; actual ADMIN and MEMBER GETs have the exact 11-key DTO and three ordered MODEL/TOOLS/SKILLS requests, compared to persisted PostgreSQL values and revision. UTC checkedAt, Team identity/state and configuration presence are checked. Fixtures cover missing configuration, SQL null, empty/whitespace requested values, DRAFT, ACTIVE, SUSPENDED and ARCHIVED.
- Foreign Agent 404, unauthorized organization 403, invalid/repeated query parameters and malformed UUID 400; existing unrelated human approval grant count is one without inheritance into Agent effective authority.
- Actual CSRF-bearing unsupported ADMIN PUT **403** and MEMBER PUT **403**. Blocked activation POST **409**. Initial snapshot equality holds after inspection, rejected writes and blocked activation.
- One intentional existing **Studio configuration PUT** succeeds at 200 and advances revision by exactly one. A subsequent MEMBER Permissions GET matches the new persisted revision, model `fresh-model-request` and tools `PUBLISH_PREVIEW=ALLOW; still opaque requested text`. Another activation remains 409; snapshot equality after that intentional Studio write proves subsequent inspection adds no business changes.
- ADMIN→MEMBER remains readable; membership removal denies 403; disabled account and credential-version change deny 401 or 403. Test authority is restored and final business snapshot still equals the post-Studio snapshot.

Snapshot comparison covers organization, teams, agents, missions, tasks, audit_events, approval_grants, agent_draft_config, agent_configuration_versions and agent_configuration_receipts in the isolated organization. This is a bounded assertion-backed no-inspection-write check; no raw row snapshots were emitted for QC to independently recompare. Intentional fixture/authority setup and the explicit Studio write are not mislabeled as inspection writes.

**403 is the observed closed unsupported-write response, not 405.** C2 validates that boundary without proving the internal `/error` redispatch mechanism. That mechanism remains source-supported inference; no security relaxation or Permissions writer was added.

## Actual browser execution: 24 new + 585 baseline

Docker uses separate complementary selectors:

```text
npm run test:e2e -- --max-failures=1 --grep 'agent permissions independent QC'
npm run test:e2e -- --max-failures=1 --grep-invert 'agent permissions independent QC'
```

New suite: **Running 24 tests using 1 worker** at **14:33:36.5269595Z**, **24 passed (1.3m)** at **14:34:55.6043300Z**. All AP-B01 through AP-B08 have individual successful result lines for desktop, tablet and mobile: eight per viewport, no skipped remainder. Baseline: **Running 585 tests using 1 worker** at **14:34:56.6758895Z**, **585 passed (18.6m)** at **14:53:29.5919502Z**. These are disjoint actual executions, totaling **609**, with zero reported failed/skipped tests.

Checked Playwright configuration: workers=1, retries=0, desktop **1440×1000**, tablet **768×1024**, mobile **390×844**. Passing AP-B03 at **14:33:46.8951317Z / 14:34:13.4673182Z / 14:34:39.7387131Z** exercises a real Studio API write and UI Refresh reading persisted opaque text. Other completed cases cover missing/null/empty/lifecycle, scope denial, error/malformed DTO retry, authority changes, delayed responses after revocation and actor/Agent history reuse. Baseline login tests explicitly pass reload persistence/logout revocation at all three viewports.

## Backend restart and logout

Docker step 67 runs session save, `docker compose restart backend`, readiness via `docker compose up --detach --wait --wait-timeout 180`, then session resume. Save marker at **14:53:48.8738816Z**; actual backend restart begins **14:53:48.9528560Z**. At **14:54:16.5771526Z**:

> PASS: exact session survives backend restart; logout rejects replay

Reviewed `scripts/auth-session-probe.py`: resume uses the exact pre-restart SESSION cookie, asserts the same account identity, posts logout with CSRF, and asserts subsequent session replay returns **401**. This establishes session/account identity survival and logout invalidation in the disposable stack; it does not claim a separate post-restart Permissions endpoint check.

## Visual evidence ownership and remaining scope

Docker upload step 50 logs **24 files** at **14:53:30.5294167Z**, then finalizes `agent-permissions-visuals` artifact **12660716351**, **1,415,397 bytes**, ZIP SHA-256 **`b1467b1e3bc2ecc10f6545e32e2ce18da7aed8b0ba4403c3cfa76437e9472ee6`** at **14:53:31.0229227Z–14:53:31.4518522Z**. [Artifact](https://github.com/giangnguyen3260/agent_os/actions/runs/40339698678/artifacts/12660716351).

Expected manifest is `agent-permissions-{desktop|tablet|mobile}-{overview|missing|empty|active|refreshed|error|denied|reused}.png`. QC independently confirms upload count/identity/hash from logs. Main additionally reports the downloaded ZIP is **1,415,397 bytes** with the same SHA-256 and contains exactly **24 PNGs: eight states × three viewports**. QC read the coordinator-provided local `/workspace/scratch/24b4696504b1/permissions-visuals/manifest.json` and checked the exact filename set, dimensions and correspondence of all 24 per-file hashes to [AGENT_PERMISSIONS_VISUAL_MANIFEST.md](AGENT_PERMISSIONS_VISUAL_MANIFEST.md). Desktop images are 1440×1000; tablet full-page images are 768×1024–1592; mobile full-page images are 390×844–1684. These full-page heights differ from configured viewport heights as expected.

**Coordinator visual review (main’s report, not independent QC image inspection):** main reviewed contact sheets covering all 24 images and full desktop/mobile refreshed captures. Main reports readable text on the dark theme, wrapping, three cards, no private-data leak in missing/error/denied states, and no visible blocking layout findings. Artifact download/hash validation and visual judgment remain attributed to main; QC did not independently download or visually inspect these images. No other owner’s document was changed.

## Final scope decision and superseded history

**C2 passes the independently reviewed completed runtime evidence for Agent Permissions read inspection.** Requested configuration remains opaque text; actual Agent grant/effective-authority lookup is unavailable: `sourceStatus=UNAVAILABLE`, and `effective`, `grantedBy`, `scope` remain null. A human approval grant does not become an Agent grant. Neither the rejected Permissions PUT nor successful Studio configuration persistence establishes grant issuance, provider enforcement or effective permission availability.

C1 Quality run 40337503885 succeeded for its old tree; C1 Docker 40337503849 failed at the old ADMIN 405 assertion before browser/restart execution. C1’s actual returned status was not logged and remains unknown. Main’s authorized C2 probe correction asserts the existing closed 403 boundary while preserving the no-write snapshots and every subsequent oracle. C1 history neither overrides C2’s fresh successful evidence nor acquires a retroactively invented 403 result.

No outstanding completed-CI/native/browser/restart evidence remains within this QC assignment. Coordinator visual acceptance is recorded above; no blocking visual findings were reported by main. No production/Mac deployment, completed PR merge, Sonar result, actual Agent grant or positive enforcement claim is made.
