# AgentOS feature delivery plan — 21 November 2026

PM/BA plan, timezone Asia/Ho_Chi_Minh. Goal: deliver a usable human-governed Mission loop in coherent feature milestones, with implementation, tests, publication, merge and deployment reported separately. PR count is not a completion percentage.

## Verified starting point

Published baseline is Draft [PR #119](https://github.com/giangnguyen3260/agent_os/pull/119), head `4999f939d6c7d043afef4ab68a63af71abe3d3fb`, stacked on #118. Its six matching workflows passed, including Docker/browser and managed TLS Vault. It remains unmerged; production deployment has not been established.

The baseline supports an ADMIN's bounded text Task Start, durable worker dispatch, model/credential authority, four-scope budget/capacity admission, verified output publication, reasoned REWORK with explicit new Start and human ACCEPT. UNKNOWN delivery retains accounting holds and never automatically resends. The provider in acceptance tests is controlled loopback; real OpenAI execution remains unverified.

Local commit `8c5d8ff` adds opt-in real-OpenAI acceptance source, six operator-setting tests, frontend fixture support and instructions. Eleven selected local tests passed across settings, transport and persistence, with the persistence case rerun using an explicitly loaded Byte Buddy agent. No real-provider run or matching new-head CI is claimed. Automatic publication review rejected sending the seven changed files to the external repository; they remain local with no new PR. Do not bypass that publication block or describe these changes as published.

The old handoff ending at PR #94 and older tracker sections are historical. Resumption must inspect actual PR head, CI and local changes.

## Milestones and dependency order

| Order | Feature / owner | User-visible outcome | Dependency and Done evidence |
| --- | --- | --- | --- |
| 1 | Single-Task Mission completion — PM/BA, backend, frontend, QC | After accepted Task output, ADMIN reads verified output text, sees blockers, submits the Mission to REVIEW and explicitly confirms COMPLETED | Reuses PR119 evidence; implement the [single-Task contract](MISSION_COMPLETION_CONTRACT.md), API/UI/receipts/audit and tests together. No hidden required-Task inference. Matching source tests, HTTP/wire, browser and native PostgreSQL evidence required. |
| 2 | Real-provider execution acceptance — operator/QC | A dedicated test key proves the existing Start → REWORK → Start → ACCEPT loop against actual OpenAI | Publish/review the opt-in harness when allowed, provide reviewed model/rates/cap and HTTPS egress, run without retries, retain exact source/run evidence. A skipped or synthetic run is not acceptance. This gate can be prepared alongside feature 1 and remains open without credentials. |
| 3 | Reviewed Mission plan and dependency execution — BA/architecture/backend/frontend/QC | Human reviews an Agent-proposed versioned task graph, accepts it, starts the complete Mission, and sees dependencies release only after accepted prerequisite outputs | Freeze proposal/correction, required/optional Tasks, immutable full-DAG acceptance and Mission start contracts. Extend current claim/authority/budget rules. Prove no stale/partial admission, no overwritten manual tasks, graph validation, competing workers and restart behavior. Generalize completion only after full-plan provenance exists. |
| 4 | First scoped Skill/Tool and approval continuation — BA/backend/frontend/QC | Agent can perform one bounded harmless tool action; governed actions pause for human approval and resume after fresh checks | Select the initial Skill version/tool action and scope. Implement server-side deny-by-default permission, exact approval identity/input binding, continuation claims, effect receipts and unknown-outcome handling. Test revocation/expiry/duplicate/no-resend. The existing Tool Gateway intentionally remains closed until then. |
| 5 | Operational Mission controls and reconciliation — backend/frontend/QC | Human can request pause/cancel, inspect exact blockers and resolve uncertain effects through a trusted process | Existing internal control and reconciliation stores are prerequisites, not a completed public flow. Bind public commands/UI to control revisions, reconcile in-flight effects, retain immutable outcomes and prohibit unsafe restart. Prove races with dispatch, publication, expiry and cancellation. |
| 6 | Results, trace and actual cost visibility — backend/frontend/QC | Human browses authorized versioned artifacts, downloads results, follows execution Trace and sees actual token/cost/capacity data | Use verified stored output and actual ledger data. Implement safe downloads, scope/integrity checks and truthful pending reasons. Do not populate operational statistics from fixture values. |
| 7 | Minimum Memory/Knowledge and context builder — BA/backend/frontend/QC | Agent receives inspectable versioned context with organization/Team/Mission/Agent boundaries | Define provenance, permitted sharing, retention and version selection. Implement basic scopes before broader search/vector features. Prove isolation, revocation, context bounds and input lineage. Current MemoryService is empty. |
| 8 | Integrated demo and release — PM/architecture/QC/operator | The selected multi-Agent Mission scenario runs reproducibly with human governance and recoverable state | Review stacked PR integration order; run end-to-end provider/tool/rework/completion scenario, Docker demo, migrations, backup/restore/rollback and failure tests. Record separate approvals/evidence for merge and deployment. No automatic production deployment. |

Feature 1 is the **active implementation milestone**. It gives a concrete closure path to the already built Task loop while the real-provider gate awaits environment inputs. It is intentionally limited to exactly one Task with a complete PR119 OpenAI execution chain; imported recorded-output or legacy completed-state rows are not eligible. It does not claim general planning or multi-Agent orchestration complete.

## Active feature acceptance

The Mission closure milestone is one reviewable package: frozen BA contract, transaction/receipt backend, scoped ADMIN/CSRF API, responsive assessment/confirmation UI, integration/browser checks and updated tracker. It has two durable actions: RUNNING/WAITING_APPROVAL → REVIEW and REVIEW → COMPLETED. Both revalidate the exact accepted output, current single-Task scope, control revision and complete absence of unresolved execution/accounting. The assessment includes bounded accepted output text, verified by server and browser SHA-256 checks, so final review does not rely on a Task-review endpoint that closes after ACCEPT. Task ACCEPT alone never closes the Mission. Backend activation is opt-in with `agentos.mission-completion.enabled=true`.

PM owns scope, dependencies, evidence and blocker reporting. BA owns user transitions, edge cases, required evidence and acceptance scenarios. Implementation/QC may proceed on independent parts after the shared contract is fixed, then review the complete feature and publish once when authorized. Do not divide a coherent feature into one tiny PR per helper unless a real dependency or risk requires separation.

## Local implementation checkpoint

Implementation is complete locally for the bounded Mission closure feature. The coordinator reports **24 passing Java tests**: twelve Mission completion tests plus twelve retained regression tests. The feature cases cover two-step state persistence, exact historical replay, missing provenance, request/output integrity, accounting, current authority, malformed wire data, same/different-key concurrency and an actual receipt-insert failure that rolls back earlier state writes. Frontend production build and lint passed. The final responsive mocked-browser selection passed all 24 cases across desktop/tablet/mobile, including explicit workspace HTTP403 handling and the corrected asynchronous receipt assertion, with retries disabled.

This is local evidence only: new-head CI, native PostgreSQL completion races, real-backend browser completion and real-provider execution remain separate gates. No new PR, merge or deployment is established by this checkpoint. Entry begins with a RUNNING/WAITING_APPROVAL Mission and a sole Task whose output already has valid accepted PR119 execution provenance. Fresh Mission creation still produces CREATED; planning/full start remain milestone 3 and are not claimed by completion fixtures.

## Evidence and progress rules

1. Each milestone records source identity, tests actually executed and their limits, CI on the exact head, publication/PR status and remaining release gate. Historical green CI cannot validate a changed source tree.
2. Batch routine local checks and publish reviewed code once per feature; diagnose failed gates before retrying. Mocked UI/provider tests remain clearly labeled. Do not disable audit, permission or unknown-outcome controls to obtain green CI.
3. No completion percentage is inferred from screens, PR numbers or test counts. The product target is demonstrated behavior: plan → human start → controlled execution → approval when needed → review/rework → accepted outputs → completed Mission, with actual usage and trace.
4. Operator secrets are supplied through private files/configuration, never chat, source or browser storage. Paid acceptance requires explicit opt-in and reviewed model/rates/cap. Missing credentials block only real-provider acceptance, not independent reversible implementation.
5. Keep draft PRs unmerged until integration is deliberately reviewed. Source publication and deployment have separate status; runtime is opt-in and disabled by default.

## Current delivery checkpoint

- PR120 implements bounded single-Task Mission completion; its final head has six green workflows.
- PR121 implements full configured Task graph review. Head `081f492231a1668f816f3eaa5ba8371fffd54e21` has six green workflows, including the preserved MEMBER workspace regression. Both remain Draft/unmerged.
- PR122 adds explicit ADMIN acceptance of the complete graph, immutable content, READY lifecycle, stale detection and new revisions after edits. It does not start execution. See [acceptance contract](MISSION_PLAN_ACCEPTANCE_CONTRACT.md) and [validation](MISSION_PLAN_ACCEPTANCE_QC.md).

## Next implementation steps

1. Publish the reviewed acceptance change as a stacked Draft PR and inspect matching-head CI, including native PostgreSQL acceptance tests.
2. Specify and implement complete-graph execution admission against the selected current accepted revision: full configuration freshness, all-or-nothing Runs, controlled task readiness and budget/control boundaries. Existing single-Task execution remains bounded and separate.
3. Continue DAG scheduling and Mission completion semantics across the full required graph; perform real-provider acceptance only with operator-supplied private configuration and explicit paid opt-in.

No merge, deployment or real-provider completion is implied by local tests or Draft PR publication.


## Graph admission implementation checkpoint

PR122 final published head `dca273b165a5422aedddcc8afcd50bb8bcfb0303` is six-workflow green. The next graph admission candidate is implemented locally, with its contract and precise validation limits in [contract](MISSION_GRAPH_ADMISSION_CONTRACT.md) and [QC](MISSION_GRAPH_ADMISSION_QC.md). Its native PostgreSQL and real-backend Vault/browser suites are wired for matching-head CI. Publication, matching CI, merge and deployment remain distinct gates.
