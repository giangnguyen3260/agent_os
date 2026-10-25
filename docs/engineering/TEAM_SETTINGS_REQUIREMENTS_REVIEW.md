# Team Settings S06 — bounded BA/SA requirements review

Review basis: repository HEAD `18bcd6b9dd1ae891ef70b552285138bab1df6419` (unmerged base supplied by the requester). Advisory sidecar only; this document is not the normative contract or evidence of PO approval. PR25 completion is requester-provided context, not independently verified here. No Figma screens were fetched by this reviewer; main owns Figma inspection and the normative screen-to-contract mapping. No code, migration, contract, or Figma changes are proposed as already implemented.

## Recommended boundary and eligibility

Updated handoff recommendation following main’s reported Forest S06 inspection: actual organization ADMIN may edit metadata and saved limits for DRAFT/ACTIVE teams; membership edits require a DRAFT team. ARCHIVED is read-only. No human TeamManager authorization binding exists to infer from an agent manager pointer. Current enabled credential-version ADMIN membership must be revalidated on reads of edit candidates, writes, and receipt replay. Ordinary current members may read existing team data consistently with current services. ACTIVE membership is read-only; existing ACTIVE agents may be retained in a DRAFT team. No activate/archive/unarchive operation belongs here. This is a conservative recommendation for main to decide explicitly, not an existing approved requirement.

A DRAFT team and DRAFT removed agent are necessary but not sufficient for membership removal: current MissionCreateService and TaskCreateService admit non-ARCHIVED teams, including DRAFT. A DRAFT team can already have missions, task owners, approval requesters, or grants. Never infer “no retained references” from the team status.

| Surface | Bounded recommendation |
| --- | --- |
| Team info | Name stripped 1..255, purpose stripped 1..2000, description nullable max4000, matching NewTeamDraftService. Explicitly define optional null/empty canonical semantics. |
| Manager | Nullable, same organization/team, level MANAGER, status DRAFT or ACTIVE. Newly attached manager must be unassigned; retained current manager may already belong to this team. Never steal from another team. |
| Agents | Full desired set of non-manager IDs, unique, non-null, max50 as in creation; manager excluded from this list. Candidate union is unassigned eligible agents plus current team assignments, with current but ineligible rows visible and explained rather than silently dropped. Newly selected status DRAFT/ACTIVE only. |
| Limits | MANUAL intake only; concurrency integer1..1000; monthlyBudget numeric0..1000000, at most2 decimal places; server-owned USD. Saved requested configuration, no enforcement or budget-reservation claim. Preserve legacy nulls honestly; decide explicit completion rules rather than populate illustrative defaults. |
| Excluded | Status changes, execution/routing engine, automatic dispatch, grant issuance/revocation, approval decisions, agent configuration/level/autonomy/reporting changes, historical reassignment, deletion. |

ACTIVE metadata/saved-limit edits remain configuration changes only; ACTIVE membership changes are excluded. Lowering saved limits has no runtime enforcement implementation here. ARCHIVED edits must not reopen intake or restore permissions. DRAFT/ACTIVE **agent** eligibility follows current create behavior and is distinct from DRAFT-only **team membership-edit** eligibility. Existing inactive/archived members are not implicit candidates for reactivation. Do not force their omission from a submitted full set merely because candidate filtering hides them; specify retain-or-explicit-remove behavior.

## Observed domain and reference consequences

Sources inspected: NewTeamDraftService; AgentDraftService; AgentConfigurationService; AgentActivationService; TeamDirectoryService; TeamWorkspaceService; MissionCreateService; MissionWorkspaceService; TaskCreateService; TaskDetailService; ApprovalCenterService; ApprovalDetailService; ApprovalGrantManagementService; migrations V001 and V004–V013; AGENT_STUDIO_CONTRACT. There is no Java class named AgentStudioService at this HEAD. Studio is backed by AgentConfigurationService/AgentDraftService.

| Reference | Actual DB invariant and read behavior | Safe S06 behavior |
| --- | --- | --- |
| teams.manager_agent_id | V005 composite FK `(organization_id,id,manager_agent_id)` to agents `(organization_id,team_id,id)` guarantees current manager membership. No FK enforces MANAGER level/status. | Attach replacement before setting pointer; clear/change old pointer before detaching former manager. Changing manager role alone may retain former manager as ordinary member. |
| agents.team_id | V005 composite org/team FK; nullable assignment. No separate membership history table. | Detach means set team_id NULL, never delete agent or change organization. Preserve config/status/identity. |
| missions.manager_agent_id | V001 FK to agents(id) only; mission org/team composite FK exists separately. MissionCreateService and MissionWorkspaceService join manager on org AND current team. | Detachment leaves ID valid but hides manager name in reads. Block removal when ANY retained mission references agent, including terminal missions. Never rewrite old mission manager to new team manager. |
| tasks.owner_agent_id | V009 FK to agents(id) only; task/mission/team scope is strongly composite constrained. TaskDetailService joins owner on org AND current team. | Detachment can hide retained owner data. Block removal for ANY task reference, including completed/cancelled. Never null/reassign ownership as a side effect. |
| approval_requests.requested_by_agent_id | V010 org/agent FK, not team/agent FK. Center/detail lookup includes current team. ApprovalDetailService explicitly reports REQUESTER_MOVED when current team differs. | Block removal for ANY retained request reference, including decided/expired/invalidated history. Do not invalidate, approve, delete, or migrate requests in settings. |
| agents.reports_to_agent_id | V001 simple agent FK; no same-org/team invariant and no automatic relation to team manager pointer. | Do not rewrite reporting links when manager changes. Conservatively block detach with inbound or outbound reporting references until reporting semantics are decided; inspect globally for inbound links, return only generic blockers to avoid cross-org disclosure. |
| Agent Studio | AgentDraftResponse reads current teamId; configuration revision/version/receipt is separate. Saved configuration snapshots include historical response identity/team. | Current GET shows detached agent unassigned; historical snapshots/receipt responses retain original team. Do not modify config revision/history to simulate a configuration edit. Stale Studio replay must not restore assignment or become a live membership assertion. |
| grants / approval action heads | Grants bind human account + org/team/action/risk; they do not bind manager agent. Action heads and snapshots have their own revision/hash. | No grant transfer/revoke/issue and no manufactured provenance. Team manager replacement grants no authority. Retain all existing history. Settings revision is not an approval-head revision. |

Conservative all-history blockers intentionally make some removals unavailable until a separate membership-history/read-model design exists. If product requires those removals, main must explicitly expand scope to historical attribution (or immutable assignment records), reader updates, and approval invalidation policy. Merely weakening a join or FK is insufficient.

## Concrete SQL invariants and checks (proposals, not migrations)

Use bound parameters throughout. After locking scoped team and the sorted union of current and requested agents, compute `removed = currentAssignments - desiredAssignments` (manager included in both). Reject if any removed agent has retained references. Query each removed ID or use a bound UUID array:

```sql
-- :org, :team, :removed are bound values; no status filter on retained history.
select exists (
  select 1 from missions m
    where m.manager_agent_id = any(:removed)
  union all
  select 1 from tasks t
    where t.owner_agent_id = any(:removed)
  union all
  select 1 from approval_requests r
    where r.requested_by_agent_id = any(:removed)
  union all
  select 1 from agents a
    where a.reports_to_agent_id = any(:removed)
       or (a.id = any(:removed) and a.reports_to_agent_id is not null)
);
```

Global reference checks are deliberate: mission/task/reporting single-column FKs can represent inconsistent legacy scope. Do not disclose foreign rows, counts, names or IDs. Scope every actual update to organization/team. Also verify no other team's manager pointer references a removal (composite FK already prevents a valid cross-team pointer, but do not turn integrity errors into success). Do not add same-team composite FKs to historical mission/task/requester references without choosing history semantics; those FKs would prevent future detachment forever and require legacy-data validation.

Recommended new settings revision: server-owned nonnegative integer default0 for legacy teams, guarded increment with upper bound; no updated_at-as-revision. A settings-specific append-only version table should key `(organization_id,team_id,revision)` with composite FK to teams; receipt key `(organization_id,account_id,team_id,idempotency_key)`, composite target FK, canonical body SHA-256, original response, actor/timestamp. These tables do not exist in V001–V015. Do not repurpose team_create_receipts or agent_configuration_receipts.

```sql
update teams
set name=:name, purpose=:purpose, description=:description,
    manager_agent_id=:manager, intake='MANUAL', concurrency=:concurrency,
    monthly_budget=:budget, currency='USD',
    settings_revision=settings_revision+1, updated_at=current_timestamp
where organization_id=:org and id=:team and status in ('DRAFT','ACTIVE')
  and settings_revision=:expected;
-- Metadata/limits path only; membership changes additionally require DRAFT.
-- Exactly one affected row; otherwise rollback/conflict.
```

For FK-safe sequencing in one transaction: attach newcomers with conditional `team_id is null` and eligible status, clear the old manager pointer if needed, set the new valid pointer, detach only approved removals with conditional old-team predicates, then persist final revision/snapshot/receipt. Check every affected row count. No intermediate state commits. The example revision column is proposed, not present today. Add necessary reference indexes only after inspecting plans; owner/requester/reporting predicates must not become unbounded interactive scans at scale.

## Transaction, concurrency and replay

Use the existing organization mutex convention rather than introduce an incompatible local lock scheme: organization → acting human account → organization membership → scoped team → agents sorted by UUID. Read current assignments after team lock, then lock the union of current/requested agent IDs in that order and revalidate all eligibility. Perform existence/blocker reads after these locks. Do not lock missions/tasks after agent locks: existing TaskCreateService locks team → mission → owner agent → dependency tasks; that reversal becomes deadlock-prone if the organization mutex is bypassed. If additional parent locks are necessary, explicitly integrate with parent-before-agent ordering.

The mutex protects cooperative writers, not arbitrary SQL. Mission/task single-column references permit a concurrent uncooperative writer to add a reference even after detach; locking an agent does not itself enforce team equality. Audit all assignment/status/reference writers, including generic AgentService, MissionService and orchestration paths, before claiming a closed concurrency proof. If they bypass the mutex, require a compatible locking/validation protocol or DB enforcement; do not claim a blocker EXISTS query alone closes the race. Keep broader refactors outside this slice unless necessary to establish the invariant.

Fresh writes: current auth + CSRF + typed allowlist validation → scoped target existence → canonical receipt lookup → field-sensitive team/agent eligibility → expected revision and full-state fingerprint → locked candidate/reference recheck → no-op rejection → atomic mutation, version, receipt (and scoped audit if specified). Hash includes expectedRevision, normalized text/decimal, sorted IDs and all editable values; reject unknown fields and malformed numbers instead of accepting coercion/security-field mass assignment. Currency/status/organization/permissions are never client-controlled.

Receipt replay: reauthorize current enabled account, credential version and ADMIN membership BEFORE returning stored data. Same key/body returns exact original committed snapshot even after subsequent revisions or archival; same key/different canonical body conflicts. Receipt check precedes current editability/revision/candidate checks so retry cannot become a second mutation. Never reapply old assignments from a receipt. Two keys/same expected revision yield exactly one commit; identical key/body yields one version and one receipt. Every failure rolls back manager pointer, assignments, limits, revision and history together. Revision must cover effective membership changes from AgentDraftService/create and any other writer, or the review must explicitly revalidate membership preconditions before save; adding only a settings-writer revision does not make roster previews stable.

Approval snapshots may depend on mutable team fields; existing head revisions do not automatically change when team settings change. Do not claim saved edits reapprove actions, invalidate prior approval, or preserve validity by design without inspecting producer payload semantics. This slice emits no approval decisions or grants. Future ACTIVE editing requires explicit treatment of pending action payloads and heads.

## Screen flow and real-data acceptance recommendations for main

Main must enumerate every actual S06 desktop/tablet/mobile frame and state from Figma; this review supplies behavior, not invented node IDs or claims that all screens were covered.

1. Settings: fetch real scoped team/manager/assignments/limits/revision and capability/blocker information. Represent legacy nulls, empty roster, missing manager and read-only statuses honestly. Use real paginated/searchable candidate data; render unavailable fields with reasons, not fake business examples or runtime promises.
2. Edit info/manager/agents/limits: load actual persisted values; keep manager distinct from non-manager IDs; show removal blockers and saved-limit semantics. No write on field change or step navigation. Back preserves local edits; cancel/departure explicitly handles dirty input.
3. Review: derive actual before/after diff at source revision, including attachment/removal and manager change separately. Explain retained references; no-op review blocked. Confirm is the only write trigger. Review is not a security authorization token.
4. Save: freeze exact canonical body + expectedRevision + idempotency key while result is unknown. Disable divergent saves; retry identical request after timeout. Validate returned org/team/revision and saved snapshot; do not infer success from local state.
5. Saved: display committed response and honest saved-configuration/MANUAL semantics. Return to settings reloads latest DB state; historical replay may be older than current live revision. Failed refresh after known save retries GET only.
6. Conflict/access/error: stale revision preserves edits with explicit reload/discard decision; no automatic merge or revision bump. Changed eligibility/reference blockers require renewed review. Clear private data and ignore stale requests on account/org switch or access loss. Scoped foreign/missing targets must not leak candidate or blocker details.

Recommended implementation evidence (not executed by this docs-only review): native PostgreSQL persistence/reload and FK-safe manager replacement; detach blocked by terminal as well as live mission/task/requester/reporting references; allowed unreferenced detach leaves agent config/history unchanged; stale candidate attach; two-key revision race; same-key committed ambiguous retry; rollback after pointer/assignment update; retained replay after later revision/status change; replay denied after ADMIN/account revocation; foreign IDs, CSRF, unknown fields and numeric coercion denial; legacy nulls; current Studio team versus historical snapshot; concurrent agent creation into team invalidates/rechecks reviewed roster. Browser coverage must follow every verified Figma step at all three widths with real API-backed fixtures. No database execution or Figma validation is claimed here.


## Final quick handoff — main-reported Forest S06 and stronger preconditions

Main reports Forest S06 specifies one agent per team and blocking removal for nonterminal tasks/active runs. This reviewer did not independently fetch those screens. The recommendations below are deliberately stricter than that minimum where retained attribution is at risk; none represents past PO approval. Runtime/run enforcement is absent from the inspected schema/services, so do not fabricate a run query, active-run count, scheduler integration, or enforcement claim.

- Metadata + saved limits: current organization ADMIN, DRAFT/ACTIVE team. ARCHIVED read-only. Agent manager is not a human authorization principal.
- Membership: DRAFT team only. New unassigned DRAFT/ACTIVE agents follow create policy and the single nullable agents.team_id enforces one current assignment. Retain existing ACTIVE agents. Remove only DRAFT agents, and only when every historical-reference check passes.
- Manager pointer: select eligible same-team/newly attached MANAGER; an old ACTIVE manager may remain an ordinary member while pointer changes. Compare assignment sets separately from manager-role changes so this is not misclassified as removal. Old manager detachment still requires DRAFT-agent and historical checks.
- Removal blockers extend the table/SQL above to ANY historical audit attribution and Agent Studio references. V001 audit_events.actor_id is polymorphic and has no agent FK: inspect actor_type and the actual payload producers, use known agent actor types, and do not match arbitrary UUID text or assume the audit FK protects removal. Conservatively block unresolved attribution. Agent configuration versions/receipts reference agents by org/id and survive detach; for the proposed stricter policy, any such history blocks removal. Current draft-config existence versus historical Studio reference must be explicitly defined by main; do not label every newly created agent permanently nonremovable solely because agent_draft_config exists. Configuration saved history is a concrete minimum check.

Concrete additions to the removal EXISTS probe (bound removed UUID array):

```sql
select 1 from agent_configuration_versions v where v.agent_id = any(:removed)
union all
select 1 from agent_configuration_receipts r where r.agent_id = any(:removed);
-- Audit: actor_id = ANY(:removed) AND actor_type IN (verified agent types).
-- Also inspect structured agent references in known audit payload schemas;
-- no invented actor-type constant or generic payload substring search.
```

Use both own settings revision and a server-generated fingerprint of the entire current settings aggregate. Include sorted current member IDs and each member’s level/status/reports_to_agent_id, manager pointer, nullable persisted settings, and each member’s configuration_revision (plus relevant configuration values if “entire configuration” includes them). Revision-only checks miss NewAgent attachment, Agent Studio writes and other writers. Include current member updated_at only as supplementary data, not as a substitute for typed state. Recompute the exact same canonical aggregate inside the write transaction after organization/team/union-agent locks; compare the review baseline fingerprint before applying a fresh write. Fingerprint and expectedRevision enter the receipt body hash. A matched historical receipt still replays before fresh-state comparisons. GET must obtain aggregate/fingerprint in one repeatable-read snapshot. Never accept a client-computed hash as proof of current DB state.

The existing organization mutex serializes cooperative NewTeam/AgentDraft/Studio/MissionCreate/TaskCreate paths before finer locks. It is the primary reason a recomputed roster cannot gain an unseen member mid-transaction. Agent locks alone cannot lock absence of another newly attached member under READ COMMITTED. Audit generic writers before asserting universal safety; maintain organization-first order across them. No need to lock configuration child rows independently when all relevant Studio writers lock the same organization/agent first. Historical audit/Studio blocker insertion must likewise follow compatible serialization, or the absence check remains racy.

For main: the earlier DRAFT-only whole-settings recommendation is superseded by this field-sensitive split. Keep retained-history blockers and fingerprint semantics explicit in the normative contract, and separately record whether the stricter removal policy is accepted. No code/tests/SQL were executed by this reviewer beyond document creation and whitespace checking.

## Review of main TEAM_SETTINGS_CONTRACT.md — worker handoff precision

Reviewed the concrete shared contract after its creation. Field-sensitive DRAFT/ACTIVE metadata and limits, DRAFT-only membership, immutable history, actor/org/key receipts, and sorted union locking are coherent with the inspected FK design. The following are concrete fixes/clarifications for main; this sidecar does not amend the contract.

1. **Correct domain level vocabulary before DTO/UI work.** Contract recognizes MANAGER/SPECIALIST/WORKER, but AgentLevel.java is JUNIOR/MID/SENIOR/LEAD/MANAGER; AgentDraftService creates MID. Treating four normal levels as unknown legacy will incorrectly block ordinary selections or mislabel them. Accept actual domain levels; only new manager selection requires MANAGER.
2. **Use an exactly representable wire revision.** Contract JSON number revision/expectedRevision through Long.MAX_VALUE is unsafe in Angular/JavaScript beyond 9007199254740991. Choose an integer range through Number.MAX_SAFE_INTEGER (or smaller) with matching DB checks/exhaustion tests, or use decimal strings consistently throughout. Do not leave BE long and FE number to disagree silently. Same issue applies to receipt revisions, not just expectedRevision.
3. **Make manager validation delta-sensitive.** Compute full assigned-member set as manager UNION agentIds; roster delta and pointer delta are separate. Enforce DRAFT team for either delta, but removal rules only for IDs actually leaving the full set. An old ACTIVE manager retained in agentIds is allowed; pointer swap alone never checks old manager removal eligibility. Enforce MANAGER/DRAFT-or-ACTIVE for a newly selected manager. An unchanged null manager on ACTIVE or unchanged manager with now-ineligible legacy status must not block metadata/limits edits, given the explicit unchanged-member allowance. Current wording “Manager null allowed on DRAFT” and unconditional manager eligibility conflicts with ACTIVE membership-must-remain-unchanged. State grandfathering or deliberate integrity blocker explicitly.
4. **Historical Studio policy differs from earlier requested conservative proposal.** Main contract deliberately retains configuration/create snapshots/receipts as historical records and does not list them as removal blockers. This is technically defensible: org/agent FKs survive detachment, current Studio GET shows current team, and old snapshots do not assert present membership. It supersedes this sidecar’s earlier ANY-Studio-reference blocker only if main explicitly chooses that boundary. If ANY historical references is intended literally, add agent_configuration_versions/receipts checks; do not claim they are already covered by mission/task/requester/AGENT audit checks. No PO approval is inferred either way.
5. **Lock bound is union, not just desired size.** A 200-current/200-desired complete replacement can lock 400 distinct agent IDs. Bound and sort the union, scope every lookup by org, validate all additions under locks, and check conditional claim/detach row counts. A removed-only check or desired-only lock misses races on the other half. Current oversized sets must fail fresh writes before unbounded DTO/lock work; old receipt replay must bypass current size guards as specified.
6. **Fingerprint precision.** Define explicit serialized fields rather than “actual Team row/all Agent rows” while enumerating only a subset. Include every editable persisted team field, manager, assignment IDs, member level/status/reportsTo, configuration_revision and retained settings revision. Stable timestamp normalization must be identical in GET/write; exclude checkedAt and dynamic canRemove/blocker computations. Current source does not need to hash every historical row: references are rechecked under locks at commit. New unassigned desired additions are absent from current roster fingerprint, so fingerprint matching does not validate them; eligibility must be checked independently after locking. Both GET and final writer reads need one consistent view; a writer must not establish a stale REPEATABLE_READ snapshot before waiting for the organization mutex and then claim to have reread post-wait state. READ_COMMITTED final reads under cooperative locks are the straightforward existing-writer pattern.
7. **Receipt paths need distinct wording.** “GET replay ... body hash” cannot literally apply to GET /receipts/{key}, which has no command body. GET authenticates/scopes org+actor+key and verifies stored receipt target equals path team; POST retry additionally compares canonical body hash. Same org/actor/key on a different team must never leak the original team receipt; POST target mismatch conflicts, GET target mismatch returns the chosen opaque null/404. Retrieve historical receipt before revision/MAX, status, size, currency, candidate, and removal checks, with no ledger materialization or new audit on replay. Reauthorization remains mandatory after waits. Shape/size validation of incoming POST can still precede lookup; do not apply mutable eligibility to canonicalization.
8. **Auth mutex proof is cooperative, not universal.** AgentService.create lacks organization row locking, but current AgentController calls AgentDraftService instead; MissionService.create is GONE and planning NOT_IMPLEMENTED. Do not list those generic methods as serialized writers without a reachability check. AuditService.record also lacks organization locking, and audit actor IDs have no FK: arbitrary/internal AGENT audit insertions can race a removal absence check unless their producer follows the same org-first protocol. Existing AuditService emits SYSTEM events, so it is not evidence of an active AGENT producer. Document this exact supported-writer boundary; do not fabricate runtime enforcement or claim an FK closes audit/reporting absence races.

FK sequence in main (clear pointer → eligible detach → conditional additions → final manager) is valid within a single rollback-safe transaction with immediate V005 constraints. The earlier sidecar’s attach-first variant is also valid; neither requires deferrable constraints. Never clear pointer for a metadata-only ACTIVE update unnecessarily. Check the final manager belongs to the desired assigned set and same organization/team before update. Agent reporting references must be queried globally for protected removals without returning foreign identities. AGENT audit predicate must explicitly include actor_type='AGENT'; SYSTEM/HUMAN UUID coincidence alone is not agent attribution.

Main’s 200 limit supersedes this sidecar’s original create-compatible 50 recommendation for S06; it is an explicit new safety bound, not an observed create constraint or past policy approval. Only this requirements document was edited during this review; no app implementation or DB tests performed.

## Confirmed main decisions and remaining implementation precision

Main explicitly decides that Agent configuration/create receipts and Studio version history are immutable historical snapshots, not live assignment assertions. **Do not block removal solely for that history.** This supersedes the earlier stricter Studio-history proposal in this document. Historical replay never mutates assignment. Global retained mission-manager/task-owner/approval-requester/reporting and actor_type='AGENT' audit references continue to block. Main verified ActorType.AGENT.

Main confirms actual levels JUNIOR/MID/SENIOR/LEAD/MANAGER and statuses DRAFT/ACTIVE/DEGRADED/SUSPENDED/ARCHIVED; preserve unchanged existing statuses and apply DRAFT/ACTIVE eligibility only to additions/new manager choices. Settings revision is integer0..2147483647, which resolves the prior JavaScript precision issue once every API/schema/exhaustion clause is updated consistently. Cap200 is an explicit endpoint engineering bound, distinct from create50.

Remaining pre-worker clarifications: (1) manager validation must distinguish unchanged pointer, new pointer and actual roster removal, especially unchanged null/ineligible managers during ACTIVE metadata edits; (2) GET receipt lookup has no submitted command hash, unlike POST replay; (3) sorted union may contain400 IDs even though current and desired are each capped200; (4) fingerprint must be recomputed from stable explicit fields after organization/team/agent lock waits, and new unassigned additions require independent eligibility checks. Audit/reporting absence safety still relies on compatible producer serialization; no runtime enforcement claim follows. Main’s clear-pointer/detach/claim/final-pointer sequence is compatible with immediate V005 FKs and atomic rollback.
