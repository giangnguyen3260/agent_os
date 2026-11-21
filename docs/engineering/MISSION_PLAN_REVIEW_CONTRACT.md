# Mission plan configuration review contract

Date: 21 November 2026 (Asia/Ho_Chi_Minh). PM/BA contract for the next bounded product feature after single-Task Mission closure. This document defines a read-only configuration review, not AI planning, accepted execution or a production readiness claim.

## User outcome

An organization ADMIN opens a Mission and reviews all currently configured Tasks, their assigned owners, input/acceptance text and dependency ordering together. The screen explains incomplete configuration and graph problems before any future plan acceptance or execution command is introduced.

UI wording must identify this as **a review of Tasks already configured by the user**. No manager Agent is invoked and no proposal is generated. Do not call the graph AI-generated, accepted, approved for execution, scheduled or running merely because configuration checks pass.

## Endpoint, authority and side effects

GET `/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/plan-review` is opt-in with `agentos.mission-plan-review.enabled=true`; the default is disabled. POST and other mutations are not introduced. A missing route is feature unavailable, not successful validation.

The service requires a current enabled human account with a matching credential version and current organization ADMIN membership, then resolves the exact organization/Team/Mission scope. Anonymous, MEMBER, revoked and foreign-organization callers must not receive Task content, owner metadata or graph details. Missing scope follows the existing bounded API conventions. Responses are no-store.

Read the graph, owners, configuration revisions and parent metadata in one read-only REPEATABLE_READ transaction. GET performs no writes: no Mission state update, accepted plan/snapshot creation, Task admission, Run/Attempt/claim/dispatch creation, grant modification, budget reservation, receipt/audit mutation or provider/tool call. It neither initializes runtime controls nor repairs malformed configuration.

## Complete scope and bounds

The supported assessment covers **all current Tasks in the exact Mission**, with 1–200 Tasks and at most 1,000 dependency edges. Every dependency endpoint must be a Task in that same organization/Team/Mission scope. Preserve every edge; never drop a missing, foreign, unsupported or inconvenient prerequisite to produce a successful graph.

Use explicit whole-scope counts and bounded retrieval. If limits are exceeded, report the entire configuration as unsupported and ineligible. A first page or truncated subset must never be represented as the Mission plan. Do not silently exclude cancelled/non-DRAFT Tasks or Tasks without owners. Zero Tasks is an empty/incomplete configuration, not a valid executable plan.

Task descriptions are limited to 8,000 Java/JavaScript UTF-16 code units per Task and acceptance criteria to 4,000, matching current Task command validation. Their aggregate original UTF-8 content across all Tasks is limited to **1 MiB (1,048,576 bytes)**; this is the combined description/acceptance text budget, not a claim that the entire JSON response fits 1 MiB. Bound database extraction at 8,001/4,001 characters so oversize detection cannot materialize unbounded legacy text. Any per-field or aggregate violation makes the entire configuration unavailable: omit all Task/edge/order collections with an explicit input-limit blocker, rather than hashing or presenting truncated input as a reviewable plan. Descriptions and acceptance criteria are displayed as plain escaped text. Model prompts, credential material, Vault references and internal authorization objects are not added to this read surface. Return only data needed to inspect configuration; broader runtime detail remains on the existing scoped routes.

## Configuration eligibility

`canReviewConfiguration` means that this bounded configuration is structurally ready for human inspection as a coherent candidate. ADMIN may still read the safe assessment and its blockers when that boolean is false. It is not a permission to accept or execute a plan.

A configuration-ready assessment requires:

1. Current organization and Team are ACTIVE and the Mission is CREATED. Other lifecycle states remain visible as unsupported configuration-review conditions, with no state rewrite or attempt to reopen a Mission.
2. The entire graph is within the limits above and every Task is DRAFT.
3. Each Task has a current owner Agent that is ACTIVE in the same organization and Team. A missing, foreign, suspended, archived, DRAFT or otherwise ineligible owner blocks readiness. A saved owner UUID/name alone does not prove eligibility.
4. Required Task content and its stored edit/owner/dependency revisions are present and valid according to existing Task configuration rules: nonblank description and acceptance criteria within the limits above, a supported LOW/NORMAL/HIGH/URGENT priority, and nonnegative bounded stored revisions. Use current stored values, never inferred revisions or rewritten text.
5. Every dependency has valid scoped endpoints; no self-edge, duplicate/invalid relationship or cycle is treated as a usable prerequisite order. The graph must be acyclic and complete.

Model availability/grants, Skills/Tools authority, budget, capacity and worker eligibility are outside these configuration checks. The response must explicitly set **`executionAuthorized: false` in every state**, including a configuration-ready assessment. Missing execution prerequisites must never be supplied with successful defaults.

## Snapshot, fingerprint and ordering

The response contains parent scope/lifecycle metadata, safe complete-scope counts, the bounded Task configuration/owner/dependency data, deterministic prerequisite-first topological order when the complete graph is valid, stable safe blocker codes, `canReviewConfiguration`, `executionAuthorized: false` and a lowercase SHA-256 fingerprint.

The fingerprint binds the exact organization/Team/Mission, relevant parent lifecycle/configuration, complete Task membership and current Task content/status/owner/edit/owner/dependency revisions, current owner scope/status and every dependency edge. Canonicalize Tasks and edges deterministically; repeated reads of unchanged configuration must return the same fingerprint even when database row order differs. Exclude generated-at/read timestamps and other nondeterministic presentation values.

Task content, membership, parent eligibility, owner eligibility, dependency edges or relevant revisions changing must change the fingerprint or make the assessment explicitly unavailable; a stale digest cannot be presented as a current complete graph. A future accepted-plan command will need to revalidate and persist an exact immutable snapshot under its own transaction contract. This read fingerprint does not confer authority or substitute for that command.

Topological ordering places every prerequisite before its dependent Task, includes every supported Task exactly once and uses a deterministic tie-breaker for independent Tasks. It is an inspection order, not a worker schedule or start order. When the graph is cyclic/incomplete/unsupported, do not return a partial ordering as though it were valid; mark the ordering unavailable and show the blocker. Configuration checks that fail for unrelated reasons may coexist with a valid dependency order, but the screen must continue to show ineligibility.

Blockers use concise human-readable text mapped from stable codes for parent lifecycle, empty/excessive scope, non-DRAFT Task, owner eligibility, invalid content/revisions, invalid dependency and cycle. Never render SQL/internal exception text or invent a resolver/approval link from arbitrary stored prose.

## UI behavior

Mission workspace provides a configuration-review entry point. The page shows the Mission identity/state, the supported read-only scope, the exact current Task cards, their owner eligibility and dependency order or graph blockers. Display Task input/acceptance text through escaped interpolation. A visible explanation states that passing checks does not accept the plan or authorize execution.

Provide an explicit refresh. Clear private assessment data on actor/route changes, current authorization denial or malformed response. Validate route UUIDs and response scope before displaying content, fence async work against actor/generation changes and use bounded request timeouts. Revalidate current authority on meaningful refresh/focus rather than retaining a revoked ADMIN assessment indefinitely.

The client validates response shape, counts/bounds (including the aggregate UTF-8 input budget), identifiers, status/owner/priority values, revisions and fingerprint, and requires `executionAuthorized` to be exactly false. A malformed, inconsistent, truncated or execution-authorized response is unavailable; it must not create a locally inferred successful assessment. If an order is supplied, it must cover the returned graph without duplicates or missing Tasks and respect its dependencies.

There are no Accept, Start, Run manager, Generate with AI, Apply plan or tool actions on this page. Existing Task-edit links continue to use their existing ADMIN/lifecycle gates and do not write through plan review. Existing Mission and Task runtime routes are not broadened by this feature.

## Required Done evidence

- A CREATED Mission with multiple DRAFT Tasks, current ACTIVE same-Team owners and a nontrivial DAG returns the complete configuration, stable fingerprint and deterministic prerequisite-first order, while `executionAuthorized` remains false.
- Independent roots, chain/branch/diamond graphs and row-order changes produce complete valid deterministic order. Any missing/foreign endpoint, self-edge or cycle is explicitly ineligible without silently dropping an edge or presenting a partial order.
- Zero, exactly 200 and 201 Tasks; exactly 1,000 and 1,001 edges; input at and above the 1 MiB aggregate cap; per-field 8,000/4,000 boundaries and multibyte/supplementary text; non-DRAFT and owner-missing/foreign/inactive cases; missing/invalid priority or stored configuration/revisions; inactive parents and unsupported Mission lifecycle are bounded and truthful. Use database-valid fixtures for over-aggregate checks; do not remove existing database input constraints to force a legacy test case.
- Content/revision/owner/membership/edge changes invalidate the old fingerprint; unchanged refresh remains stable. Parent, Task and owner snapshots are transactionally coherent under concurrent edits.
- HTTP authentication/current ADMIN/scope and no-store checks; unsupported methods cannot mutate state. Assert that the GET leaves Mission/Task state, accepted-plan/runtime/receipt/audit data and provider/tool send counters unchanged.
- Responsive browser acceptance for complete graph, blockers, escaped content, denied/malformed response, identity/navigation races and refresh. Clearly label mocked API coverage versus real-backend browser evidence.
- Source review, local compile/build/lint/tests and matching-head CI. Native PostgreSQL/read-consistency and real-backend browser evidence are separate from H2/mocked browser checks; do not imply those passed before observing their results.

## Deliberately subsequent contracts

This feature does not close Mission planning/start. The next product work must define an Agent-generated bounded proposal, human correction/rejection/regeneration, immutable complete-DAG acceptance with required/optional membership, explicit Mission start that atomically admits the entire current snapshot, dependency release from accepted prerequisite output, and generalized Mission completion. Those commands must retain current authority, revision/fingerprint, budget/capacity, claim/fencing and unknown-outcome rules.

A fresh Mission still starts at CREATED. Reviewing its existing configuration does not move it to PLANNING, READY or RUNNING. The current single-Task execution/completion path and its real-provider acceptance gate remain separately scoped; they are not evidence of multi-Agent planning.


Acceptance extension: when `agentos.mission-plan-acceptance.enabled=true`, read-only eligibility includes READY as well as CREATED. The default review-only deployment retains CREATED eligibility. Acceptance is a separate explicit command; see [acceptance contract](MISSION_PLAN_ACCEPTANCE_CONTRACT.md).
