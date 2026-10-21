# Team Workspace delivery contract

Branch feature/team-workspace stacks on Team directory PR11. Forest S05 references: Figma5wUjDKXg8vtRaJcO9XHfml overview42:1371, pending43:1667, agents43:1728, activity43:1800; mobile59:3614, tablet59:3820 and corresponding60-series tab frames. Existing Forest tokens, Inter, native controls and card conventions apply. No static imagery is required in these frames.

## Screen and API

/workspace/:id/teams/:teamId/workspace adds an actual Team read workspace while preserving existing detail/settings routes and creation receipts. Overview shows current stored Team identity/configuration, real counts and eight bounded task lanes. INTAKE=DRAFT, READY=READY, RUNNING=RUNNING, REVIEW=IN_REVIEW, PENDING=WAITING/BLOCKED, DONE=COMPLETED, FAILED and CANCELLED retain their own lanes. Initial lanes contain at most5 newest rows; View all opens a20-row read with deterministic created_at DESC,id order. Pending shows the actual reason, waiting timestamp and resolver. It does not invent typed INPUT/APPROVAL categories absent from persistence.

Current enabled ADMIN/MEMBER may read within their organization. ADMIN may use the existing Add Agent route for a non-ARCHIVED Team. Settings opens the existing persisted detail. Create Mission/Create Task remain disabled with an unavailable explanation; no fake success, acceptance/readiness, grants, effective capacity or spend values are shown. Configured concurrency/budget are configuration, not allocation/spend.

GET /api/v1/organizations/{org}/teams/{teamId}/workspace returns {generatedAt,team,summary,lanes}. Team has id,organizationId,name,purpose,status,managerAgentId,managerName,intake,concurrency,monthlyBudget,currency. Summary has agentCount,totalTasks,runningTasks,pendingTasks,reviewTasks. Each lane has lane,total,items (first5). Task fields: id,organizationId,teamId,missionId,title,status,pendingReason,waitingSince,resolver.

GET .../workspace/tasks?lane=PENDING&page=0 returns {items,page,size:20,total}; strict lane/page0..100000. Agents uses the existing organization Agent directory with fixed teamId and scoped response validation. Activity uses GET .../workspace/activity?page=0, returning page20 metadata {id,eventType,actorType,createdAt}. Pagination, tab/lane/search URL state and browser navigation must agree with the read; malformed input and duplicate control parameters are rejected by the UI.

## Activity and data boundaries

Activity is limited to recorded mission/task events related to the Team, not a claim of complete configuration history or execution traces. audit_events.organization_id must match and every non-null mission/task reference must resolve within that same Team; at least one must be present. Mixed-reference foreign data is excluded. No raw payload or actor ID is returned. Empty event history is not fabricated.

Every endpoint revalidates current account/credential/membership, then scoped Team existence. Invalid session/account401; authenticated denied organization403; foreign/missing Team within an allowed organization404; invalid recognized parameters400. Reads use repeatable-read snapshots, bound parameters, scoped joins and independent counts. Summary/lane preview is one snapshot; subsequent pages refresh independently. No business writes or whole-organization write locks.

Loading/tab/account/scope/error transitions clear prior data and cancel/ignore late responses. Null/malformed/scoped-wrong payloads fail closed. Compact Menu is a native keyboard-accessible modal. No tasks or sensitive metadata from another Team may be shown.

## Acceptance scope

Dedicated org14141414-1414-4414-8414-141414141414 contains an ACTIVE Team with24Agents,31tasks (24Pending) and25safe activity events, an empty legacy Team, and foreign/mixed-reference records. Fixture IDs were checked against existing fixtures; these are disposable CI data, never product seeds. Backend integration, real PostgreSQL/browser and native snapshot/no-write probes must pass on the final candidate; discovery is not execution. No migration. Sonar remains paused. Positive execution, mutations, complete audit/permissions and production scale/security acceptance remain incomplete.
