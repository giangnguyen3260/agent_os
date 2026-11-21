# Admitted Mission graph completion contract

Date: 21 November 2026 (Asia/Ho_Chi_Minh). PM/BA contract following complete-DAG admission and dependency release. This contract now has a local implementation candidate. Native PostgreSQL and Vault/browser closure evidence require matching-head CI; local implementation does not imply merge, deployment or paid-provider acceptance.

## User outcome and supported scope

A current organization ADMIN reviews the immutable completion manifest for the entire admitted graph, submits the Mission to final REVIEW, then explicitly confirms COMPLETED. Every Task in the pinned accepted/admitted snapshot is required. Task ACCEPT, the last model response, a completed-task count or an Agent's statement never completes the Mission automatically.

This protocol supports a validated receipt-backed admitted graph of 1–200 Tasks and at most 1,000 edges, retaining the accepted-plan input bounds and complete membership. It includes a one-Task graph admitted through the graph protocol. It does not adopt legacy Runs, infer optional Tasks, omit cancelled Tasks or redefine the required set from whatever happens to be completed.

Use a separate opt-in service/API/UI, `agentos.mission-graph-completion.enabled=true`, disabled by default. The scoped resource is `/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/graph-completion`. Missing prerequisite validation services fail closed; enabling completion does not enable provider transport, workers or a different audience.

The legacy single-Task `/completion` path must reject **new** completion of a Mission with a persisted `mission_graph_admissions` row, even when that graph has one Task. Preserve exact historical legacy receipt replay before mutable eligibility gates. A graph Mission uses one manifest-backed closure protocol rather than competing single-Task and graph receipts.

## Frozen scope and Task creation

Completion requires the pinned admitted required set to equal the entire current scoped Task set and entire current scoped Run set, with exactly one matching admission mapping per required Task. Extra Task, extra Run, missing mapping or mismatched scope is an explicit blocker; never silently ignore or delete it.

To make that invariant achievable, this feature also freezes **new Task creation after graph admission**:

- `TaskCreateService` is the sole current production Task INSERT path. After fresh authority and exact historical create-receipt replay checks, a new command must deny when the scoped Mission has a persisted graph admission. Its read context must return `canCreate=false` with a safe explanation.
- This guard is based on persisted admission, regardless of the graph-admission/completion feature flags. Turning a feature off cannot reopen Task membership.
- CREATED/READY editing before admission remains available through existing gates. This feature does not freeze a merely accepted but unadmitted configuration.
- Historical same-actor/key/body Task-create replay returns its stored result without inserting another Task or resetting current state. Different/new keys cannot bypass the freeze.
- Current Mission intake resume only changes pause/version and does not create Tasks. Resuming intake cannot override the central Task-create admission guard; any future creation/import/proposal path must use the same freeze rule.
- Existing pre-feature out-of-plan Tasks remain an explicit unsupported completion case. No automatic deletion, optional classification, replan or database repair is introduced. The feature makes no production-adoption claim for such records.

Task creation and graph admission/completion share organization-first serialization. A Task created before admission changes the complete snapshot and makes an old admission proposal stale; admission committed first makes a new Task-create command ineligible. No race may produce an admitted graph plus an unreviewed extra Task.

## Lifecycle and current authority

| Current Mission | Explicit action | Result |
| --- | --- | --- |
| RUNNING or WAITING_APPROVAL | SUBMIT_REVIEW | REVIEW |
| REVIEW with a matching graph review manifest | COMPLETE | COMPLETED |
| COMPLETED | No new action | Terminal; exact historical receipts remain readable/replayable |
| Other/unknown lifecycle | None | Ineligible; no implicit adoption/reopening |

Both new actions require current enabled credential-matching human account, organization ADMIN membership, exact parent scope and ACTIVE organization/Team. This is closure of accepted results, not a model operation: current provider/model grant, current owner availability or a new budget window is not required merely to retain already accepted output. Historical owner/configuration identities still must match the pinned evidence.

For a new action, the Mission execution control must exist, have a valid revision with the headroom below and be OPEN or quiescent PAUSE_REQUESTED. A terminal COMPLETED read or exact historical replay may retain a valid maximum revision because it performs no increment. CANCEL_REQUESTED blocks closure and remains owned by cancellation reconciliation. Completion sends no provider/tool action and does not bypass pause/cancel controls. The graph-completion flag controls these new commands; validation of persisted graph evidence cannot be omitted merely because execution/admission is disabled.

Reserve revision headroom for all remaining transitions: SUBMIT_REVIEW requires enough space for two increments (source revision at most `Long.MAX_VALUE - 2`); COMPLETE requires source revision at most `Long.MAX_VALUE - 1`. Exhausted revisions cannot strand a newly submitted REVIEW.

There is no direct RUNNING → COMPLETED command, automatic closure, Mission reopen/reject/replan command or retry. The user has already ACCEPTed each required Task output and now confirms the complete manifest. A separately specified lifecycle would be needed to change outputs after Mission review begins.

## Pinned graph and fresh execution evidence

Revalidate under the current read or command transaction, including when Mission is REVIEW or COMPLETED:

1. The exact selected accepted plan, admission revision and immutable source schema/JSON/SHA are valid and agree. Validate complete source field/input/count/graph/topological bounds and V036 membership, owners and revisions; do not trust a stored boolean alone.
2. Current scoped Tasks, Runs and admission mapping exactly cover that required snapshot, with no extras/duplicates/missing entries. Every Run belongs to the same organization/Team/Mission/plan revision and the correct mapped Task. Current saved Task input/content/priority/owner/edit/owner/dependency revisions and all dependency edges still match the pinned source.
3. Every required Task and Run is COMPLETED. Every Run's latest Attempt is SUCCEEDED with the exact current ACCEPT decision; ACCEPT source revision is `Run revision - 1` and the corresponding review-submission source revision is one less. Earlier REWORK or an output from another plan/Attempt cannot satisfy the manifest.
4. Reuse the independent `AcceptedRunOutputEvidence` proof for each mapped Run: valid SUCCEEDED reconciliation, exact accepted content/digest, exact immutable request bytes/digest, committed handoff, FINISHED dispatch, matching authorization/connection/credential/model/Attempt/fence, usage and SETTLED reservation. Verify cost arithmetic and stored cap. Missing records are not proven success by zero-row counts.
5. All historical Attempts are terminal; no live claim, HELD/UNKNOWN reservation, unfinished dispatch or unresolved accounting remains under any required Run. Extra Run scope is independently denied rather than ignored.
6. Validate admitted graph/source consistency without calling configuration-review readiness or `matchesCurrent` as runtime authority. Those readiness predicates intentionally cease to be true after Tasks leave DRAFT and Mission leaves READY. The reusable pinned-content validator must work for RUNNING, WAITING_APPROVAL, REVIEW and COMPLETED closure reads.

A failed/missing proof is a blocker, never a repair, synthetic success or trigger to resend a provider request. Neither assessment nor completion settles budgets, reconciles unknown outcomes, invalidates claims, changes Task/Run/Attempt states, modifies selection or rewrites artifacts.

## Immutable manifest and hashes

The completion manifest contains the exact graph identity and source snapshot digest plus a deterministic complete list of required entries. Each entry binds Task ID, mapped Run ID, accepted latest Attempt ID, Run revision and accepted output/request hashes; retain any additional bounded provenance digest needed to bind the verified chain. Sort deterministically and hash canonical stored UTF-8 JSON with SHA-256. The manifest is metadata, bounded at 1 MiB, not concatenated output text.

Do not embed aggregate raw outputs. At the supported maximum, concatenating 200 outputs could reach 50 MiB and conceal individual version identity. The UI shows the pinned graph/manifest hash, every required Task/Run/Attempt and an individual accepted-output link. A manifest summary or task count alone cannot replace complete membership.

Keep **manifest hash** separate from **assessment fingerprint**. The immutable manifest identity excludes changing Mission status, execution-control revision and read timestamps, so it remains stable across REVIEW and COMPLETE when evidence is unchanged. The assessment fingerprint binds scope, plan/admission identity, current Mission status/control revision, eligible action, current manifest hash and blocker state.

SUBMIT_REVIEW atomically stores the exact immutable manifest JSON/hash and the review result. COMPLETE recomputes every proof and the canonical manifest, requires the same manifest hash and pinned source as that submitted REVIEW, and compares the expected current assessment fingerprint/control revision. A changed accepted result, source graph or accounting validity must not be silently accepted from an old screen.

Verify stored manifest schema, bounded JSON, exact SHA and internal scope/membership consistency before trusting historical review/receipt data. REVIEW/COMPLETED labels without matching valid graph-completion records are unsupported, not automatically adopted.

## Individual accepted-output read

An output link must actually open the exact accepted content, not only a Run status page. The existing Task-review output route requires IN_REVIEW and therefore cannot be assumed to work after ACCEPT. Provide a bounded graph-completion output read, for example `GET .../graph-completion/outputs/{runId}?manifestSha256=<digest>`, or an equivalent explicitly scoped accepted-output API.

That read requires fresh current ADMIN authority, exact graph/manifest membership and the pinned Run/Attempt/output identity. Revalidate content UTF-8/size/SHA and accepted settled proof before returning at most 256 KiB of escaped text for one output. The expected manifest hash is a concurrency assertion, never access authority. Wrong scope, stale/tampered manifest or unavailable evidence denies the read; a foreign Run cannot be used to retrieve another Mission's output.

Return no-store JSON with Run/Attempt/output hash and text. The client verifies identity/digest, clears private content on denied/malformed response or actor/route change, and does not save it in browser storage. Reading output performs no lifecycle/accounting/provider side effect.

## Commands, receipts and transactions

POST has the strict existing completion-style command:

```json
{
  "idempotencyKey": "<UUID>",
  "action": "SUBMIT_REVIEW",
  "expectedFingerprint": "<64 lowercase hexadecimal characters>"
}
```

Action is SUBMIT_REVIEW or COMPLETE. The fingerprint binds selected/admitted plan revision and manifest identity; the confirmation displays those identities. Require canonical textual fields, duplicate/unknown-field rejection, no coercions/trailing JSON, current session CSRF and safe bounded API errors.

For a **new** command, use the existing organization-first lock protocol, current actor authorization and exact parent/control scope, then freshly rebuild the assessment and compare action/fingerprint. Atomically write only Mission status, CAS-incremented control revision, immutable review/command receipt and safe audit event. Receipt/audit failure rolls back everything. A new COMPLETE requires the matching submitted graph manifest and its resulting control revision.

Each immutable receipt binds organization/Team/Mission, actor/key/action, source fingerprint/control revision, result control revision/status, selected/admitted plan revision and manifest hash. There is one successful record per scope/action. Store the review manifest once; COMPLETE references that immutable version rather than substituting a newly selected graph. Audit payloads contain bounded identities/hashes and no raw output or arbitrary private input.

A matching actor/key/scope/body replay follows fresh authority and stored-record integrity checks, then returns the original historical result. It does not require the old lifecycle still to be current and never rewrites Mission/control/Task/Run state. In particular, replaying SUBMIT_REVIEW after COMPLETE returns the historical REVIEW receipt while current Mission stays COMPLETED. Reused keys with changed action/body/scope conflict; new keys cannot bypass terminal or stale conditions. Historical replay does not manufacture current proof or conceal a failed current reassessment.

## UI and required Done evidence

The Mission workspace links to graph completion only through the correct graph protocol. The page distinguishes current Mission status, immutable submitted manifest and historical command receipts. It displays all required entries and functional per-output reads, explains blockers and requires separate explicit confirmations for REVIEW and COMPLETED. Passing readiness or clicking a button never claims completion before a validated receipt and refreshed state.

Retain an exact pending command for explicit retry after an ambiguous result, clear stale confirmation after known conflict, and clear private state on authority/actor/route changes. Use scope/shape/hash/revision validation, bounded requests and generation fencing. No automatic POST, provider retry, Task rewrite or substitute command/key is introduced.

Required evidence:

- A real-backend controlled-provider diamond: configured acceptance → atomic admission → four explicit Runs and exact Task ACCEPTs → complete manifest → REVIEW → COMPLETED → reload. Verify all required Task/Run evidence is unchanged and no extra provider call is sent by completion.
- Manifest contains all and only required mappings, stable hash across both phases, and functional exact accepted-output reads. Test count/identity/order/digest/schema/size corruption and current-source changes while REVIEW/COMPLETED.
- Deny missing, failed, cancelled, reworked, cross-plan or stale accepted output; all historical UNKNOWN/live claim/unsettled budget/dispatch scenarios; extra/missing Task/Run/mapping; legacy labels or missing review manifest; cancellation/inactive parents and revision-headroom exhaustion.
- Post-admission new TaskCreate/context denial, historical create receipt replay, intake-resume non-bypass, TaskCreate-vs-admission race and ordinary unadmitted CREATED/READY Task editing retained.
- Legacy single-Task completion denies new graph closure; graph protocol supports a one-Task admitted graph without bypassing the manifest.
- Same/different-key and competing-action concurrency, state/control CAS conflicts, injected manifest/receipt/audit rollback and historical replay after completion without state resurrection.
- Current ADMIN/session/CSRF, strict wire/no-store, foreign scope, revoked credentials/authority, per-output hash/scope checks; responsive browser blockers/confirmations/reload/ambiguous exact retry/navigation fencing.
- Matching-head Java/build/lint/CI, explicit isolated native PostgreSQL tests and live-backend browser evidence. Distinguish H2/mocked coverage and controlled provider from actual OpenAI calls. No merge, deployment or invoice-reconciliation claim follows from these tests.

Generated planning, automatic scheduling, prerequisite-output context assembly, Skills/Tools and broader recovery remain separately scoped after this closure feature.


## Implemented output read and evidence checkpoint

The implemented read is `GET /graph-completion/outputs/{runId}?attemptId={attemptId}&outputSha256={outputSha256}` under the scoped Mission resource. It re-evaluates current complete-plan provenance and requires that exact Run/Attempt/output hash in the current valid output set, then returns one bounded verified text. A manifest-hash query is not needed: scope, exact accepted output identity, current source validation and fresh output-byte hash bind the read. Completed outputs remain readable after final closure.

The canonical stored manifest wraps organization/Team/Mission, plan revision, the exact immutable source snapshot SHA-256 and the complete ordered output metadata list. Output provenance binds request/action/provider/credential/model/owner revision/fence/control identities and validated tokens/cost/rates/cap. No plaintext credentials, raw requests or aggregate output content enter this manifest.
