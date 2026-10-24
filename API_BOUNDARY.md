# AgentOS API Boundary v0.1

## 1. Purpose

This document defines the API and service boundaries for AgentOS v0.1.

It answers:

- Which frontend REST APIs exist.
- Which backend modules own which commands and queries.
- Which operations are synchronous.
- Which operations emit events.
- Which actions must go through Model Gateway, Tool Gateway, Governance Engine, or Resource Manager.

This file is the bridge between product/domain design and Spring Boot implementation.

## 2. Boundary Principles

1. Frontend talks only to public REST APIs.
2. Agents do not call tools directly.
3. Agents do not call model providers directly.
4. Cross-module writes happen through application services, not repositories.
5. Long-running mission execution is event-driven.
6. Risky operations must pass through Governance Engine.
7. Cost/token-sensitive operations must pass through Resource Manager.
8. Every important command emits an audit event.

## 3. API Style

Use REST for MVP.

Base path:

```text
/api/v1
```

Response envelope:

```json
{
  "data": {},
  "meta": {
    "requestId": "req_123"
  }
}
```

Error envelope:

```json
{
  "error": {
    "code": "MISSION_NOT_FOUND",
    "message": "Mission not found",
    "details": {}
  },
  "meta": {
    "requestId": "req_123"
  }
}
```

## 4. Public API Groups

| API Group | Owner Module | Purpose |
|---|---|---|
| Organization API | organization | Workspace, teams, members |
| Agent API | agent | Agent Studio |
| Skill API | skill | Skill definitions and assignment |
| Tool API | tool | Tool registry and permissions |
| Model API | model | Providers and model configs |
| Mission API | mission | Mission creation and task views |
| Approval API | governance | Human decisions |
| Memory API | memory | Memory explorer |
| Observability API | observability | Trace, audit, cost, metrics |
| Runtime API | orchestration | Realtime status and mission control |

## 5. Organization API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/organizations` | Create organization |
| GET | `/organizations` | List organizations |
| GET | `/organizations/{organizationId}` | Get organization |
| PATCH | `/organizations/{organizationId}` | Update organization |
| POST | `/organizations/{organizationId}/teams` | Create team |
| GET | `/organizations/{organizationId}/teams` | List teams |
| GET | `/organizations/{organizationId}/teams/{teamId}` | Get team |
| PATCH | `/organizations/{organizationId}/teams/{teamId}` | Update team |

### Commands

| Command | Emits |
|---|---|
| CreateOrganizationCommand | ORGANIZATION_CREATED |
| UpdateOrganizationCommand | ORGANIZATION_UPDATED |
| CreateTeamCommand | TEAM_CREATED |
| UpdateTeamCommand | TEAM_UPDATED |

### Notes

Organization APIs are synchronous.

## 6. Agent API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/organizations/{organizationId}/agents` | Create agent |
| GET | `/organizations/{organizationId}/agents` | List agents |
| GET | `/organizations/{organizationId}/agents/{agentId}` | Get agent |
| PATCH | `/organizations/{organizationId}/agents/{agentId}` | Update agent identity |
| PATCH | `/organizations/{organizationId}/agents/{agentId}/status` | Change status |
| PUT | `/organizations/{organizationId}/agents/{agentId}/contract` | Create new active contract version |
| GET | `/organizations/{organizationId}/agents/{agentId}/contract` | Get active contract |
| PUT | `/organizations/{organizationId}/agents/{agentId}/model-config` | Configure model |
| PUT | `/organizations/{organizationId}/agents/{agentId}/skills` | Assign skills |
| PUT | `/organizations/{organizationId}/agents/{agentId}/tools` | Assign tools |
| PUT | `/organizations/{organizationId}/agents/{agentId}/permissions` | Configure permissions |

### Commands

| Command | Emits |
|---|---|
| CreateAgentCommand | AGENT_CREATED |
| UpdateAgentCommand | AGENT_UPDATED |
| ChangeAgentStatusCommand | AGENT_STATUS_CHANGED |
| UpsertAgentContractCommand | AGENT_CONTRACT_VERSION_CREATED |
| ConfigureAgentModelCommand | AGENT_MODEL_CONFIGURED |
| AssignAgentSkillsCommand | AGENT_SKILLS_ASSIGNED |
| AssignAgentToolsCommand | AGENT_TOOLS_ASSIGNED |
| ConfigureAgentPermissionsCommand | AGENT_PERMISSIONS_UPDATED |

### Guardrails

Agent API must reject:

- Agent granting permissions to itself
- Agent installing tools for itself
- Agent editing governance policy
- Agent activating itself after suspension

Those operations require human or authorized manager actor.

## 7. Skill API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/organizations/{organizationId}/skills` | Create skill |
| GET | `/organizations/{organizationId}/skills` | List skills |
| GET | `/organizations/{organizationId}/skills/{skillId}` | Get skill |
| PATCH | `/organizations/{organizationId}/skills/{skillId}` | Update draft skill |
| POST | `/organizations/{organizationId}/skills/{skillId}/versions` | Create skill version |
| POST | `/organizations/{organizationId}/skills/{skillId}/activate` | Activate version |
| POST | `/organizations/{organizationId}/skills/{skillId}/deprecate` | Deprecate skill |

### Commands

| Command | Emits |
|---|---|
| CreateSkillCommand | SKILL_CREATED |
| CreateSkillVersionCommand | SKILL_VERSION_CREATED |
| ActivateSkillVersionCommand | SKILL_VERSION_ACTIVATED |
| DeprecateSkillCommand | SKILL_DEPRECATED |

### Notes

Agents can propose skill changes, but cannot apply them in v0.1.

## 8. Model API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/organizations/{organizationId}/model-providers` | Add provider |
| GET | `/organizations/{organizationId}/model-providers` | List providers |
| GET | `/organizations/{organizationId}/model-providers/{providerId}` | Get provider |
| PATCH | `/organizations/{organizationId}/model-providers/{providerId}` | Update provider metadata |
| POST | `/organizations/{organizationId}/model-providers/{providerId}/test` | Test provider connection |
| GET | `/organizations/{organizationId}/model-providers/{providerId}/models` | List available models |

### Model Gateway Internal API

This is not exposed directly to frontend.

```java
public interface ModelGateway {
    ModelResponse execute(ModelRequest request);
    Publisher<ModelChunk> stream(ModelRequest request);
}
```

### Required ModelRequest Fields

| Field | Required | Notes |
|---|---|---|
| organizationId | Yes | Budget and provider scope |
| missionId | Optional | Trace scope |
| taskId | Optional | Trace scope |
| executionRunId | Yes | Every model call belongs to execution |
| agentId | Yes | Calling agent |
| providerId | Yes | Selected provider |
| model | Yes | Provider model |
| messages | Yes | Normalized messages |
| maxInputTokens | Optional | Enforced limit |
| maxOutputTokens | Optional | Enforced limit |
| metadata | Optional | Trace metadata |

### Model Gateway Must Call

1. Resource Manager
2. Provider adapter
3. Observability logger

## 9. Tool API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/organizations/{organizationId}/tools` | Register tool |
| GET | `/organizations/{organizationId}/tools` | List tools |
| GET | `/organizations/{organizationId}/tools/{toolId}` | Get tool |
| PATCH | `/organizations/{organizationId}/tools/{toolId}` | Update tool |
| POST | `/organizations/{organizationId}/tools/{toolId}/test` | Test tool |
| GET | `/organizations/{organizationId}/tools/{toolId}/capabilities` | List capabilities |

### Tool Gateway Internal API

This is not exposed directly to frontend.

```java
public interface ToolGateway {
    ToolResult execute(ToolActionRequest request);
}
```

### Required ToolActionRequest Fields

| Field | Required | Notes |
|---|---|---|
| organizationId | Yes | Scope |
| missionId | Optional | Trace scope |
| taskId | Optional | Trace scope |
| executionRunId | Yes | Every tool call belongs to execution |
| agentId | Yes | Calling agent |
| toolId | Yes | Tool |
| capability | Yes | Example: repository.write |
| actionType | Yes | Example: CREATE_BRANCH |
| payload | Yes | Exact tool input |
| idempotencyKey | Recommended | Prevent duplicate side effects |

### Tool Gateway Flow

```text
ToolActionRequest
  -> Agent permission check
  -> Governance Engine
      -> DENY: return denied
      -> APPROVAL_REQUIRED: create approval and pause
      -> ALLOW: continue
  -> Resource Manager
  -> Tool Adapter
  -> ToolCall persisted
  -> Audit event emitted
```

## 10. Mission API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/organizations/{organizationId}/missions` | Create mission |
| GET | `/organizations/{organizationId}/missions` | List missions |
| GET | `/organizations/{organizationId}/missions/{missionId}` | Get mission |
| PATCH | `/organizations/{organizationId}/missions/{missionId}` | Update mission metadata |
| POST | `/organizations/{organizationId}/missions/{missionId}/plan` | Start planning |
| POST | `/organizations/{organizationId}/missions/{missionId}/start` | Start mission |
| POST | `/organizations/{organizationId}/missions/{missionId}/pause` | Pause mission |
| POST | `/organizations/{organizationId}/missions/{missionId}/resume` | Resume mission |
| POST | `/organizations/{organizationId}/missions/{missionId}/cancel` | Cancel mission |
| GET | `/organizations/{organizationId}/missions/{missionId}/tasks` | List tasks |
| GET | `/organizations/{organizationId}/missions/{missionId}/graph` | Get task graph |
| GET | `/organizations/{organizationId}/missions/{missionId}/artifacts` | List artifacts |

### Commands

| Command | Emits |
|---|---|
| CreateMissionCommand | MISSION_CREATED |
| StartMissionPlanningCommand | MISSION_PLANNING_REQUESTED |
| StartMissionCommand | MISSION_START_REQUESTED |
| PauseMissionCommand | MISSION_PAUSED |
| ResumeMissionCommand | MISSION_RESUMED |
| CancelMissionCommand | MISSION_CANCELLED |

### Async Rules

`POST /plan` and `POST /start` return quickly.

They do not wait for agent execution to finish.

Response example:

```json
{
  "data": {
    "missionId": "mission_123",
    "status": "PLANNING"
  }
}
```

## 11. Task API

Task APIs are nested under Mission API.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/organizations/{organizationId}/missions/{missionId}/tasks/{taskId}` | Get task |
| PATCH | `/organizations/{organizationId}/missions/{missionId}/tasks/{taskId}` | Update task metadata |
| POST | `/organizations/{organizationId}/missions/{missionId}/tasks/{taskId}/assign` | Assign agent |
| POST | `/organizations/{organizationId}/missions/{missionId}/tasks/{taskId}/retry` | Retry task |
| POST | `/organizations/{organizationId}/missions/{missionId}/tasks/{taskId}/review` | Submit human review |
| GET | `/organizations/{organizationId}/missions/{missionId}/tasks/{taskId}/runs` | List execution runs |

### Commands

| Command | Emits |
|---|---|
| AssignTaskCommand | TASK_ASSIGNED |
| RetryTaskCommand | TASK_RETRY_REQUESTED |
| SubmitTaskReviewCommand | TASK_REVIEW_SUBMITTED |

## 12. Approval API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/organizations/{organizationId}/approvals` | List approval requests |
| GET | `/organizations/{organizationId}/approvals/{approvalId}` | Get approval details |
| POST | `/organizations/{organizationId}/approvals/{approvalId}/approve` | Approve action |
| POST | `/organizations/{organizationId}/approvals/{approvalId}/reject` | Reject action |

### Target decision request (superseded for the delivered bounded adapter below)

```json
{
  "reason": "Approved for development environment only",
  "constraints": {
    "environment": "dev"
  }
}
```

### Commands

| Command | Emits |
|---|---|
| ApproveActionCommand | APPROVAL_GRANTED |
| RejectActionCommand | APPROVAL_REJECTED |

### Rules

- Approval request must be immutable after decision.
- Approval must contain exact payload approved.
- Tool Gateway must verify approval still matches the action before execution.

## 13. Memory API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/organizations/{organizationId}/memory` | Search/list memory entries |
| POST | `/organizations/{organizationId}/memory` | Create memory entry |
| GET | `/organizations/{organizationId}/memory/{memoryId}` | Get memory entry |
| PATCH | `/organizations/{organizationId}/memory/{memoryId}` | Update memory metadata |
| DELETE | `/organizations/{organizationId}/memory/{memoryId}` | Archive/delete memory entry |

### Query Parameters

| Parameter | Purpose |
|---|---|
| scope | AGENT, TEAM, MISSION, TASK, ORGANIZATION |
| agentId | Filter by owner agent |
| teamId | Filter by team |
| missionId | Filter by mission |
| visibility | PRIVATE, TEAM, ORGANIZATION |

### Internal API

```java
public interface MemoryService {
    List<MemoryEntry> retrieveRelevantMemory(ContextRequest request);
    MemoryEntry createCandidateMemory(CreateMemoryCommand command);
    MemoryEntry promoteMemory(PromoteMemoryCommand command);
}
```

MVP may skip embedding search and use scoped filtering plus recency.

## 14. Observability API

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/organizations/{organizationId}/audit-events` | List audit events |
| GET | `/organizations/{organizationId}/missions/{missionId}/trace` | Get mission trace |
| GET | `/organizations/{organizationId}/missions/{missionId}/usage` | Get mission cost/token usage |
| GET | `/organizations/{organizationId}/agents/{agentId}/usage` | Get agent cost/token usage |
| GET | `/organizations/{organizationId}/model-calls/{modelCallId}` | Get model call details |
| GET | `/organizations/{organizationId}/tool-calls/{toolCallId}` | Get tool call details |

### Trace View Must Include

- Mission timeline
- Task status changes
- Execution runs
- Model calls
- Tool calls
- Approval requests
- Artifacts
- Cost summary
- Token summary
- Errors and reviewer feedback

## 15. Runtime Realtime API

Use SSE for MVP unless bidirectional realtime is needed.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/organizations/{organizationId}/missions/{missionId}/events/stream` | Stream mission events |

### Event Payload

```json
{
  "eventId": "evt_123",
  "eventType": "TASK_COMPLETED",
  "missionId": "mission_123",
  "taskId": "task_456",
  "createdAt": "2026-10-01T00:00:00Z",
  "payload": {}
}
```

## 16. Internal Application Services

| Service | Owner | Responsibility |
|---|---|---|
| OrganizationService | organization | Organization/team commands |
| AgentService | agent | Agent lifecycle and config |
| SkillService | skill | Skill versioning |
| ModelGateway | model | All model calls |
| ToolGateway | tool | All tool calls |
| MissionService | mission | Mission/task persistence |
| OrchestratorService | orchestration | Mission and task execution |
| ContextBuilder | orchestration/memory | Build model context |
| GovernanceEngine | governance | Policy decisions |
| ApprovalService | governance | Human approval flow |
| ResourceManager | resource | Token/cost/rate/concurrency |
| AuditService | observability | Append-only event logging |

## 17. Domain Events

### Event Envelope

```json
{
  "eventId": "evt_123",
  "eventType": "MISSION_CREATED",
  "organizationId": "org_123",
  "missionId": "mission_123",
  "taskId": null,
  "actorType": "HUMAN",
  "actorId": "user_123",
  "occurredAt": "2026-10-01T00:00:00Z",
  "payload": {}
}
```

### Required Events for v0.1

| Event | Producer | Consumer |
|---|---|---|
| MISSION_CREATED | MissionService | AuditService |
| MISSION_PLANNING_REQUESTED | MissionService | OrchestratorService |
| MISSION_PLANNING_STARTED | OrchestratorService | AuditService, UI stream |
| MISSION_PLANNED | OrchestratorService | MissionService, AuditService |
| TASK_CREATED | OrchestratorService | MissionService, AuditService |
| TASK_ASSIGNED | OrchestratorService | AuditService |
| EXECUTION_STARTED | OrchestratorService | AuditService |
| MODEL_CALL_COMPLETED | ModelGateway | ResourceManager, AuditService |
| TOOL_ACTION_REQUESTED | ToolGateway | GovernanceEngine, AuditService |
| APPROVAL_REQUESTED | GovernanceEngine | ApprovalService, UI stream |
| APPROVAL_GRANTED | ApprovalService | OrchestratorService, ToolGateway |
| APPROVAL_REJECTED | ApprovalService | OrchestratorService |
| TASK_COMPLETED | OrchestratorService | MissionService, AuditService |
| TASK_REJECTED | OrchestratorService | MissionService, AuditService |
| MISSION_COMPLETED | OrchestratorService | MissionService, AuditService |
| MISSION_FAILED | OrchestratorService | MissionService, AuditService |

## 18. Synchronous vs Asynchronous Operations

| Operation | Mode |
|---|---|
| Create organization | Sync |
| Create team | Sync |
| Create agent | Sync |
| Assign skill/tool/model | Sync |
| Create mission | Sync |
| Plan mission | Async |
| Start mission | Async |
| Execute task | Async |
| Model call | Sync inside async execution |
| Tool call | Sync or paused by approval |
| Approval decision | Sync command, async resume |
| Trace query | Sync |

## 19. Authorization Rules

Actor types:

| Actor | Meaning |
|---|---|
| HUMAN | Logged-in user |
| AGENT | AI agent |
| SYSTEM | Internal system |

Default permission:

| Operation | Human | Agent | System |
|---|---|---|---|
| Create organization | Yes | No | No |
| Create agent | Yes | No | No |
| Update agent permissions | Yes | No | No |
| Create mission | Yes | Optional later | No |
| Plan mission | Yes | Manager Agent via Orchestrator | Yes |
| Execute task | No direct | Assigned Agent via Orchestrator | Yes |
| Execute tool | No direct | Via Tool Gateway | Yes |
| Approve action | Yes | No | No |
| Change policy | Yes | No | No |

## 20. Versioning Requirements

Persist versions for:

- Agent contract
- Skill
- Policy
- Model config
- Prompt template
- Tool config

ExecutionRun must store references to the versions used.

This is required for replay and debugging.

## 21. Idempotency

Use idempotency keys for side-effect operations:

| Operation | Needs Idempotency |
|---|---|
| Mission creation | Recommended |
| Tool action | Required |
| Approval decision | Required |
| Artifact creation | Recommended |
| Model call | Recommended |

## 22. Initial Controller Package Plan

```text
com.agentos.organization.api.OrganizationController
com.agentos.agent.api.AgentController
com.agentos.skill.api.SkillController
com.agentos.model.api.ModelProviderController
com.agentos.tool.api.ToolController
com.agentos.mission.api.MissionController
com.agentos.mission.api.TaskController
com.agentos.governance.api.ApprovalController
com.agentos.memory.api.MemoryController
com.agentos.observability.api.ObservabilityController
com.agentos.runtime.api.MissionEventStreamController
```

## 23. Initial Application Service Package Plan

```text
com.agentos.organization.application.OrganizationService
com.agentos.agent.application.AgentService
com.agentos.skill.application.SkillService
com.agentos.model.application.ModelGateway
com.agentos.tool.application.ToolGateway
com.agentos.mission.application.MissionService
com.agentos.orchestration.application.OrchestratorService
com.agentos.orchestration.application.ContextBuilder
com.agentos.governance.application.GovernanceEngine
com.agentos.governance.application.ApprovalService
com.agentos.resource.application.ResourceManager
com.agentos.observability.application.AuditService
```

## 24. MVP Implementation Order

1. Organization API
2. Agent API
3. Skill API
4. Model API and Model Gateway stub
5. Tool API and Tool Gateway stub
6. Mission API
7. Task API
8. Orchestrator state transitions
9. Approval API
10. Observability API
11. SSE mission event stream
12. Real provider adapters
13. Real tool adapters

## 25. Open Decisions

| Decision | Default for v0.1 |
|---|---|
| REST vs GraphQL | REST |
| SSE vs WebSocket | SSE |
| Queue/Kafka | Transactional outbox first |
| Vector DB | Skip for v0.1 |
| Multi-tenancy | Organization-scoped rows |
| Billing | Usage tracking only |
| Agent-to-agent chat | Structured messages only |

## Delivered Approval Center read boundary

The organization-scoped GET `/api/v1/organizations/{organizationId}/approvals` is implemented by the Approval Center read slice with `group=PENDING|NEEDS_INFORMATION|DECIDED` and fixed20-row `page` pagination. It returns safe metadata only under fresh ADMIN/MEMBER membership checks. The detail/approve/reject endpoints listed in the target design above remain planned and are not delivered by this slice; read membership is not an approval grant. See [delivery contract](docs/engineering/APPROVAL_CENTER_CONTRACT.md).

## Delivered Approval Detail boundary (draft PR20)

Detail GET and exact approve/reject POST routes above now have a bounded implementation under `/api/v1`. The current command is `{snapshotVersion, snapshotSha256, acknowledgement, reason}` with CSRF and UUID `Idempotency-Key`; arbitrary `constraints` are rejected. First decision returns201, identical authorized receipt replay200. Fresh explicit human grants match organization, Team, action type and risk; ADMIN/MEMBER membership only permits reading/transport and never supplies a grant. Same-org requester membership, non-self identity, current sealed complete parameters and parent/action validity are required for a new decision.

Only PUBLISH_PREVIEW's reviewed parameters are supported. One transaction records approval status, immutable decision, safe audit event and idempotency receipt. No Task/Mission transition, worker resume, execution token or Tool Gateway call occurs. Request-information/reply, public request producer and grant-management endpoints remain unimplemented. The broader commands/event consumers above are target architecture, not delivered runtime behavior. See [current contract](docs/engineering/APPROVAL_DETAIL_CONTRACT.md).

## Delivered information exchange boundary (draft PR21)

GET `/api/v1/organizations/{org}/approvals/{id}/information` returns the persisted, bounded QUESTION/ANSWER conversation and current operation eligibility. POST `/request-information` requires an explicit exact scoped approver grant; POST `/reply` requires the exact persisted human requester with fresh same-org membership. Commands carry the expected conversation revision, CSRF and actor/org-scoped UUID idempotency key. An ask changes PENDING to NEEDS_INFORMATION; a requester reply returns PENDING. Both are recorded with atomic safe audit and durable receipts, never Task/runtime changes.

GET `/information/receipts/{key}` recovers the exact actor/org/path-scoped original result after fresh original operation authority. Mutable revision advancement alone never proves an unknown submission outcome. Information-operation keys share one namespace across ask/reply; decision receipt keys retain their separate existing namespace. New decision commands bind `informationRevision` in addition to the unchanged sealed snapshot. Omission defaults to0 only, so old commands cannot approve after an information round. Current conversation and detail must match revisions before UI decision controls appear. Old revision0 decision receipt fingerprints remain recoverable. See [contract](docs/engineering/APPROVAL_INFORMATION_CONTRACT.md).

Prior references to request-information/reply being unavailable describe earlier slices. Producer/grant-management, other action schemas and execution remain unavailable.

### Member access inventory (stacked development slice)

GET `/api/v1/organizations/{org}/members` and `/members/{accountId}/approval-grants` expose bounded persisted membership and grant-lifecycle inventory to a fresh enabled/version-matching organization ADMIN only. Fixed20-row pagination and literal name/email search or lifecycle filters run in a read-only repeatable-read snapshot. DTOs exclude password/session/credential internals. Disabled targets remain inspectable while current membership exists. Grant lifecycle is not effective decision authority. No issuer, role edit, invitation or grant writer is delivered; see [contract](docs/engineering/MEMBER_ACCESS_CONTRACT.md). Runtime acceptance is recorded separately in QC.


## Approval grant management — verified bounded scope (PR23)

The accepted [contract](docs/engineering/APPROVAL_GRANT_MANAGEMENT_CONTRACT.md) supersedes earlier statements that grant writers are absent. All routes use `/api/v1/organizations/{org}/members/{account}/approval-grants` and fresh enabled/versioned organization ADMIN authorization.

| Method | Suffix | Purpose |
| --- | --- | --- |
| GET | `/management-context` | Current recipient, revision and optional exact Team/risk issue preflight |
| POST | collection | Issue explicit PUBLISH_PREVIEW LOW/MEDIUM/HIGH, 1–168 hours, never self-issue |
| GET | `/{grant}/revocation-context` | Scoped single-grant fingerprint, lifecycle and remaining recorded matching rows |
| POST | `/{grant}/revoke` | Revoke this ID with expected revision, fingerprint, reason and acknowledgement |
| GET | `/management-receipts/{key}?operation=ISSUE|REVOKE` | Minimal actor-scoped historical receipt, including after target removal |
| GET | `/{grant}/management-history?page=0` | Restricted paginated management provenance; legacy grants have no invented history |

POST requires CSRF and UUID Idempotency-Key, returns 201 first/200 exact replay, and performs one atomic grant/ledger/event/receipt/safe-audit transaction. Org mutex precedes globally ordered account locks; fresh authorization follows waits. Read-only preflights never reserve authority. Historical receipt replay is not current eligibility. No provider, Task/Mission or execution side effect is added.

Final candidate838b1d9:176 backend and435 browser instances, native PostgreSQL and Docker restart/logout passed. See [final QC](docs/qc/APPROVAL_GRANT_MANAGEMENT_QC_REPORT.md). PR23 remains draft/unmerged.


## Member role management — verified development scope (PR24)

Current contract: [Member role management](docs/engineering/MEMBER_ROLE_MANAGEMENT_CONTRACT.md). Base `/api/v1/organizations/{org}/members/{account}/role-management` exposes ADMIN-only service reads GET `/context` (optional `newRole`), GET `/history?page=0`, GET `/receipts/{key}` and CSRF/idempotent POST collection. New role is ADMIN/MEMBER, target is another enabled current same-org member; no self change. Separate approval grants and global sessions remain unchanged. Read preflight never reserves authority, and historical receipt never proves current role.

Exact commands/DTOs/status precedence, retained removed-target history and actor-scoped replay are defined by the contract. Existing outer security denial precedes controller syntax mapping where applicable. No Owner, invitation, bootstrap/recovery, account enable/disable or membership removal is added. Earlier read-only-role statements describe previous slices. Candidate81bc489 passed192BE/471browser plus native/Docker/restart gates; see [final QC](docs/qc/MEMBER_ROLE_MANAGEMENT_QC_REPORT.md). PR24 remains draft/unmerged.
