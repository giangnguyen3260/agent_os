# AgentOS Domain Model v0.1

## 1. Domain Boundaries

AgentOS has twelve core bounded contexts:

| Context | Responsibility |
|---|---|
| Organization | Workspaces, teams, human members, constitution |
| Agent | AI worker identity, role, contract, autonomy |
| Skill | Reusable instruction package |
| Model | AI provider, model config, model calls |
| Tool | External capabilities and permission enforcement |
| Mission | User goal and task graph |
| Orchestration | State transitions, assignment, retries, escalation |
| Memory | Operational memory with scope and access control |
| Knowledge | Documents, repositories, and reference material |
| Governance | Policy evaluation and approvals |
| Resource | Token, cost, rate, concurrency budgets |
| Observability | Audit events, traces, metrics, evaluations |

## 2. Core Entity Map

```text
Organization
  ├── Team
  │     ├── TeamMember
  │     └── Agent
  │           ├── AgentContract
  │           ├── AgentModelConfig
  │           ├── AgentSkill
  │           ├── AgentTool
  │           ├── AgentPermission
  │           └── AgentMemory
  │
  ├── Skill
  ├── Tool
  ├── ModelProvider
  ├── Policy
  ├── Mission
  │     ├── Task
  │     │     ├── TaskAssignment
  │     │     ├── ExecutionRun
  │     │     ├── ModelCall
  │     │     ├── ToolCall
  │     │     └── Artifact
  │     └── ApprovalRequest
  │
  ├── MemoryEntry
  ├── KnowledgeSource
  └── AuditEvent
```

## 3. Important Domain Separations

### Agent vs Model

Agent is an organizational worker identity.

Model is the AI engine used by the agent.

An agent can have:

- Primary model
- Fallback model
- Cheap model
- Reasoning model
- Long-context model

### Skill vs Tool

Skill means the agent knows how to perform a type of work.

Tool means the agent can interact with an external system.

Example:

| Concept | Example |
|---|---|
| Skill | Spring Boot development |
| Tool | GitHub repository write |

### Memory vs Knowledge

Memory is operational experience and extracted facts.

Knowledge is source material such as documents, repositories, pages, specs, or uploaded files.

## 4. Organization

Represents the top-level workspace.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| name | string | Organization name |
| slug | string | Unique URL-safe name |
| description | text | Optional |
| status | enum | ACTIVE, SUSPENDED, ARCHIVED |
| created_at | timestamp | Audit |
| updated_at | timestamp | Audit |

Relationships:

- Has many teams
- Has many agents
- Has many policies
- Has one constitution
- Has many missions

## 5. Team

Represents a group inside an organization.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| name | string | Team name |
| purpose | text | Optional |
| status | enum | ACTIVE, ARCHIVED |

Relationships:

- Belongs to organization
- Has many agents
- Has many human members
- Has scoped memory

## 6. Agent

Represents an AI worker.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| team_id | UUID | Nullable |
| name | string | Human-readable name |
| role | string | Backend Developer, QA, Manager |
| level | enum | JUNIOR, MID, SENIOR, LEAD, MANAGER |
| status | enum | DRAFT, ACTIVE, DEGRADED, SUSPENDED, ARCHIVED |
| autonomy_level | enum | OBSERVE, SUGGEST, EXECUTE, CONDITIONAL, MANAGED |
| reports_to_agent_id | UUID | Nullable |
| created_at | timestamp | Audit |
| updated_at | timestamp | Audit |

Agent must not contain raw provider credentials.

## 7. Agent Contract

Defines expected behavior, boundaries, escalation rules, and deliverables.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| agent_id | UUID | Agent |
| version | integer | Contract version |
| mission | text | Role mission |
| responsibilities | jsonb | Responsibilities |
| constraints | jsonb | Hard rules |
| escalation_rules | jsonb | When to ask manager/human |
| output_format | jsonb | Expected outputs |
| active | boolean | One active version per agent |

## 8. Skill

Reusable instruction package.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Null means global/template skill |
| name | string | Unique within scope |
| version | string | Semantic version |
| description | text | Summary |
| instructions | text | Main skill instructions |
| required_tools | jsonb | Tool capabilities required |
| optional_tools | jsonb | Optional tool capabilities |
| status | enum | DRAFT, ACTIVE, DEPRECATED, ARCHIVED |

## 9. Tool

Represents a capability provider.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| name | string | GitHub, Terminal, HTTP |
| type | enum | API, MCP, CLI, DATABASE, FILESYSTEM, BROWSER |
| config | jsonb | Non-secret configuration |
| status | enum | ACTIVE, DISABLED, ARCHIVED |

Secrets must be stored separately in a secret manager.

## 10. Tool Permission

Defines what an agent can do with a tool.

Example capabilities:

| Tool | Capability | Policy |
|---|---|---|
| GitHub | repository.read | ALLOW |
| GitHub | repository.write | ALLOW |
| GitHub | pull_request.merge | APPROVAL |
| Database | select | ALLOW |
| Database | update | APPROVAL |
| Database | drop_table | DENY |

## 11. Model Provider

Represents an AI provider.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| provider | enum | OPENAI, ANTHROPIC, GEMINI, LOCAL |
| display_name | string | Human name |
| status | enum | ACTIVE, DISABLED |
| rate_limit_config | jsonb | Provider-level limits |

## 12. Agent Model Config

Maps an agent to model choices.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| agent_id | UUID | Agent |
| primary_provider_id | UUID | Provider |
| primary_model | string | Model name |
| fallback_provider_id | UUID | Nullable |
| fallback_model | string | Nullable |
| strategy | enum | FIXED, SMART, CHEAPEST, FASTEST, QUALITY |
| max_input_tokens | integer | Limit |
| max_output_tokens | integer | Limit |

## 13. Mission

Represents a user goal.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| team_id | UUID | Responsible team |
| created_by_user_id | UUID | Human creator |
| manager_agent_id | UUID | Planning agent |
| title | string | Mission title |
| goal | text | User goal |
| status | enum | See state machine |
| priority | enum | LOW, NORMAL, HIGH, URGENT |
| budget_limit | decimal | Optional mission budget |
| created_at | timestamp | Audit |
| updated_at | timestamp | Audit |

## 14. Task

Represents a unit of planned work.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| mission_id | UUID | Parent mission |
| title | string | Task title |
| description | text | Work details |
| status | enum | See task state machine |
| task_type | enum | ANALYSIS, DESIGN, IMPLEMENTATION, REVIEW, QA, TOOL_ACTION |
| priority | enum | LOW, NORMAL, HIGH, URGENT |
| depends_on | jsonb | Task dependency ids |
| created_by_agent_id | UUID | Nullable |
| assigned_agent_id | UUID | Nullable |

## 15. Execution Run

One task can have multiple execution attempts.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| task_id | UUID | Parent task |
| agent_id | UUID | Executing agent |
| status | enum | CREATED, RUNNING, WAITING_APPROVAL, FAILED, COMPLETED, REJECTED |
| attempt_number | integer | Starts at 1 |
| started_at | timestamp | Nullable |
| completed_at | timestamp | Nullable |
| input_summary | text | Snapshot |
| output_summary | text | Result |
| error_message | text | Nullable |

## 16. Approval Request

Represents an action that requires human approval.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| mission_id | UUID | Nullable |
| task_id | UUID | Nullable |
| execution_run_id | UUID | Nullable |
| requested_by_agent_id | UUID | Agent |
| action_type | string | DATABASE_SCHEMA_CHANGE, DEPLOY_PRODUCTION |
| risk_level | enum | LOW, MEDIUM, HIGH, CRITICAL |
| impact_summary | text | Human-readable impact |
| status | enum | PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED |
| approved_by_user_id | UUID | Nullable |
| decision_reason | text | Nullable |

## 17. Memory Entry

Represents scoped memory.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| scope | enum | AGENT, TEAM, MISSION, TASK, ORGANIZATION |
| owner_agent_id | UUID | Nullable |
| team_id | UUID | Nullable |
| mission_id | UUID | Nullable |
| task_id | UUID | Nullable |
| content | text | Memory text |
| source_type | enum | USER, AGENT, TOOL, ARTIFACT, FILE |
| source_ref | string | Provenance |
| confidence | decimal | 0 to 1 |
| visibility | enum | PRIVATE, TEAM, ORGANIZATION |
| expires_at | timestamp | Nullable |

## 18. Audit Event

Represents an immutable trace event.

Fields:

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Parent organization |
| mission_id | UUID | Nullable |
| task_id | UUID | Nullable |
| actor_type | enum | HUMAN, AGENT, SYSTEM |
| actor_id | UUID | Nullable |
| event_type | string | TASK_ASSIGNED, APPROVAL_REQUESTED |
| payload | jsonb | Event details |
| created_at | timestamp | Event time |

## 19. Entity Rules

- Every model call must be linked to an execution run.
- Every tool call must pass through Tool Gateway.
- Every risky tool call must create a governance decision record.
- Every approval must link to the requested action.
- Every mission must have a trace.
- Agent, skill, contract, policy, and prompt versions must be persisted for reproducibility.
