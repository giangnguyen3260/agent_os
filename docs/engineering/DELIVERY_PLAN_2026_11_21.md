# AgentOS delivery checkpoint — 22 November 2026

PM/BA checkpoint, Asia/Ho_Chi_Minh. Source identities and matching CI are authoritative. All listed PRs remain Draft/unmerged; no deployment or paid-provider acceptance is established. PR count is not a completion percentage.

## Verified Mission loop

| PR | Implemented behavior | Verified source / evidence |
| --- | --- | --- |
| 119 | Explicit bounded text Task Start, durable worker, fresh provider/model authority, scoped budgets/capacity, verified output, REWORK and ACCEPT | Retained runtime foundation; controlled provider acceptance, not paid OpenAI |
| 120 | Explicit review then closure of a single-Task Mission, immutable receipts and verified output reread | Final head `49f083b555d8c3c21a421d691a555c0e4f65cb49`; six successful workflows |
| 121 | Complete configured Task/DAG review and bounded dependency order | `081f492231a1668f816f3eaa5ba8371fffd54e21`; six successful workflows |
| 122 | ADMIN acceptance of immutable full configured plan; READY state; stale detection and explicit reacceptance | `dca273b165a5422aedddcc8afcd50bb8bcfb0303`; six successful workflows |
| 123 | Atomic full-graph Run admission, root readiness and dependency release after exact same-plan accepted/settled outputs | Corrected `477ce690d92a1a2b0b123cfe2a46160cee3069b3`; six successful workflows; native PostgreSQL and real-backend TLS Vault/worker diamond with simulated provider |
| 124 | Full graph REVIEW then COMPLETED, exact source-pinned manifests, accepted-output reread and frozen post-admission Task creation | `69f064f76c16f7b5ec96acf1c189365e0b7750ac`; six successful workflows; 12 native closure cases and real-backend diamond closure/reload |
| 125 | Admission-pinned direct prerequisite output context, exact preview/request/provenance binding and fresh worker checks | `60288ab7f29ae471c0d28493378006aca6c10b4b`; all six workflows successful; 10 native cases and controlled Vault diamond with exact A→B/C, B+C→D content |

PR123's first head failed a mobile test that dispatched synthetic focus before the mandatory current-state reload finished. Corrected tests wait for the completed operation; all assertions remain. That correction passed 45 repeated local cases and matching-head CI. Earlier Vault startup HTTP500 attempts were retried once on unchanged heads; only successful matching-head results are recorded above.

The existing loop now demonstrates configured full graph → human plan acceptance → explicit admission → manual Run Starts → exact prerequisite acceptance/release → full Mission closure. Model-proposed planning and passing previous outputs into new requests are separate capabilities; ordering alone is not content collaboration.

## Active coherent feature: reviewed Agent plan import

The current candidate imports structured proposal data from an exact accepted Run in a different graph-completed Mission in the same Team. It verifies the existing completion manifest and settled provenance, distinguishes exact AGENT_OUTPUT from HUMAN_CORRECTED JSON, and requires separate human owner bindings. ADMIN previews every Task and dependency before one atomic command creates DRAFT Tasks in an empty CREATED target. One intake increment, canonical source/proposal snapshot, scoped normalized Task mapping, exact replay and audit commit together. It neither calls a model nor accepts/admit/Starts the imported plan.

Scope: 1..20 Tasks, at most 100 edges, 64 KiB each source/proposal, 128 KiB expanded snapshot. Existing manual Tasks are preserved by rejecting nonempty targets. Legacy single-Task completion sources and automatic planning/repair are outside this version. See [contract](MISSION_PLAN_PROPOSAL_CONTRACT.md) and [QC](MISSION_PLAN_PROPOSAL_QC.md). Native PostgreSQL and controlled Vault/browser evidence are wired from first publication and remain pending for this candidate until matching-head CI succeeds.

The dependency-context feature in PR125 is complete at its stated controlled-provider scope. It retains 8 KiB context, 16 KiB message content, 32 KiB request, no truncation and immutable admission mode; actual paid OpenAI acceptance remains a separate gate.

## Remaining milestones

1. Complete matching-head validation/publication of the reviewed plan-import feature and retain exact source/evidence. Keep stacked integration, merge and deployment as separate gates.
2. Expand planning beyond reviewed source-output import only where needed: planner setup/schema guidance and correction workflows, without overwriting manual Tasks. Configured-plan review/acceptance already exists; autonomous planning and live paid-provider planning remain unverified.
3. Implement one bounded harmless Skill/Tool through the closed Tool Gateway, with exact scoped permissions/approval continuation/effect receipts and unknown-outcome handling. No implicit tool authority from model text.
4. Expose governed Mission pause/cancel and operator reconciliation through public API/UI. Internal control/reconciliation stores do not constitute a complete public recovery flow.
5. Expand verified results, authorized downloads, execution Trace and actual ledger/token/cost visibility; distinguish recorded data from running operations.
6. Implement scoped versioned Memory/Knowledge context and sharing/retention/isolation before broader search/vector features.
7. Review stacked PR integration and the reproducible demo/release path: migrations, backup/restore/rollback, failure recovery and environment activation. No automatic production deployment.

## Independent real-provider gate

The opt-in OpenAI acceptance harness exists, but an actual paid run remains unverified. It requires operator-supplied private credentials, reviewed model/rates/cap and explicit opt-in. Keys are never requested through chat or committed. Controlled loopback/provider responses, skipped paid tests and green CI do not satisfy this gate. Missing operator settings do not block independent reversible implementation.

## Evidence and ownership rules

PM owns scope, dependencies and truthful delivery status; BA owns user transitions, invariants and acceptance scenarios. Deliver coherent API/UI/schema/test packages, batching independent validation. Never infer product completion from screen/PR/test counts, relabel old-head CI as current, disable checks to obtain green, or retry uncertain provider effects automatically. All features remain opt-in, human-governed and distinct from merge/deployment.
