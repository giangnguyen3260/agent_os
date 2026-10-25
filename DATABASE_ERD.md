# AgentOS Database ERD v0.1

## 1. Database Choice

Use PostgreSQL as the primary database.

Recommended extensions later:

- pgvector for embedding search
- uuid-ossp or gen_random_uuid support

For v0.1, standard relational tables plus JSONB are enough.

## 2. ERD Overview

```mermaid
erDiagram
    ORGANIZATION ||--o{ TEAM : has
    ORGANIZATION ||--o{ AGENT : owns
    TEAM ||--o{ AGENT : contains
    AGENT ||--o{ AGENT_SKILL : has
    SKILL ||--o{ AGENT_SKILL : assigned
    AGENT ||--o{ AGENT_TOOL : has
    TOOL ||--o{ AGENT_TOOL : assigned
    ORGANIZATION ||--o{ MISSION : owns
    MISSION ||--o{ TASK : contains
    TASK ||--o{ EXECUTION_RUN : attempts
    EXECUTION_RUN ||--o{ MODEL_CALL : uses
    EXECUTION_RUN ||--o{ TOOL_CALL : uses
    MISSION ||--o{ APPROVAL_REQUEST : requires
    ORGANIZATION ||--o{ MEMORY_ENTRY : stores
    ORGANIZATION ||--o{ AUDIT_EVENT : records
```

## 3. Core Tables

### organizations

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| name | varchar(255) | Not null |
| slug | varchar(255) | Unique |
| description | text | Nullable |
| status | varchar(50) | ACTIVE, SUSPENDED, ARCHIVED |
| created_at | timestamptz | Not null |
| updated_at | timestamptz | Not null |

### teams

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| name | varchar(255) | Not null |
| purpose | text | Nullable |
| status | varchar(50) | ACTIVE, ARCHIVED |
| created_at | timestamptz | Not null |
| updated_at | timestamptz | Not null |

### agents

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| team_id | uuid | FK teams, nullable |
| reports_to_agent_id | uuid | FK agents, nullable |
| name | varchar(255) | Not null |
| role | varchar(255) | Not null |
| level | varchar(50) | JUNIOR, MID, SENIOR, LEAD, MANAGER |
| status | varchar(50) | DRAFT, ACTIVE, DEGRADED, SUSPENDED, ARCHIVED |
| autonomy_level | varchar(50) | OBSERVE, SUGGEST, EXECUTE, CONDITIONAL, MANAGED |
| created_at | timestamptz | Not null |
| updated_at | timestamptz | Not null |

### agent_contracts

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| agent_id | uuid | FK agents |
| version | integer | Not null |
| mission | text | Not null |
| responsibilities | jsonb | Not null |
| constraints | jsonb | Not null |
| escalation_rules | jsonb | Not null |
| output_format | jsonb | Nullable |
| active | boolean | Not null |
| created_at | timestamptz | Not null |

Constraint:

- Unique active contract per agent.

### skills

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations, nullable for global |
| name | varchar(255) | Not null |
| version | varchar(50) | Not null |
| description | text | Nullable |
| instructions | text | Not null |
| required_tools | jsonb | Nullable |
| optional_tools | jsonb | Nullable |
| status | varchar(50) | DRAFT, ACTIVE, DEPRECATED, ARCHIVED |
| created_at | timestamptz | Not null |
| updated_at | timestamptz | Not null |

### agent_skills

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| agent_id | uuid | FK agents |
| skill_id | uuid | FK skills |
| enabled | boolean | Not null |
| assigned_at | timestamptz | Not null |

### tools

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| name | varchar(255) | Not null |
| type | varchar(50) | API, MCP, CLI, DATABASE, FILESYSTEM, BROWSER |
| config | jsonb | Non-secret config |
| status | varchar(50) | ACTIVE, DISABLED, ARCHIVED |
| created_at | timestamptz | Not null |
| updated_at | timestamptz | Not null |

### agent_tools

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| agent_id | uuid | FK agents |
| tool_id | uuid | FK tools |
| enabled | boolean | Not null |
| assigned_at | timestamptz | Not null |

### tool_permissions

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| agent_tool_id | uuid | FK agent_tools |
| capability | varchar(255) | Example: repository.read |
| decision | varchar(50) | ALLOW, APPROVAL_REQUIRED, DENY |
| conditions | jsonb | Optional |

### model_providers

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| provider | varchar(50) | OPENAI, ANTHROPIC, GEMINI, LOCAL |
| display_name | varchar(255) | Not null |
| status | varchar(50) | ACTIVE, DISABLED |
| rate_limit_config | jsonb | Nullable |
| created_at | timestamptz | Not null |

### agent_model_configs

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| agent_id | uuid | FK agents |
| primary_provider_id | uuid | FK model_providers |
| primary_model | varchar(255) | Not null |
| fallback_provider_id | uuid | FK model_providers, nullable |
| fallback_model | varchar(255) | Nullable |
| strategy | varchar(50) | FIXED, SMART, CHEAPEST, FASTEST, QUALITY |
| max_input_tokens | integer | Nullable |
| max_output_tokens | integer | Nullable |

### missions

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| team_id | uuid | FK teams |
| created_by_user_id | uuid | Human user id |
| manager_agent_id | uuid | FK agents |
| title | varchar(255) | Not null |
| goal | text | Not null |
| status | varchar(50) | Mission state |
| priority | varchar(50) | LOW, NORMAL, HIGH, URGENT |
| budget_limit | numeric(12,4) | Nullable |
| created_at | timestamptz | Not null |
| updated_at | timestamptz | Not null |

### tasks

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| mission_id | uuid | FK missions |
| title | varchar(255) | Not null |
| description | text | Not null |
| status | varchar(50) | Task state |
| task_type | varchar(50) | ANALYSIS, DESIGN, IMPLEMENTATION, REVIEW, QA, TOOL_ACTION |
| priority | varchar(50) | LOW, NORMAL, HIGH, URGENT |
| depends_on | jsonb | Array of task ids |
| created_by_agent_id | uuid | FK agents, nullable |
| assigned_agent_id | uuid | FK agents, nullable |
| created_at | timestamptz | Not null |
| updated_at | timestamptz | Not null |

### execution_runs

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| task_id | uuid | FK tasks |
| agent_id | uuid | FK agents |
| status | varchar(50) | CREATED, RUNNING, WAITING_APPROVAL, FAILED, COMPLETED, REJECTED |
| attempt_number | integer | Not null |
| started_at | timestamptz | Nullable |
| completed_at | timestamptz | Nullable |
| input_summary | text | Nullable |
| output_summary | text | Nullable |
| error_message | text | Nullable |

### model_calls

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| execution_run_id | uuid | FK execution_runs |
| agent_id | uuid | FK agents |
| provider | varchar(50) | Provider name |
| model | varchar(255) | Model name |
| request_id | varchar(255) | Provider request id |
| status | varchar(50) | STARTED, COMPLETED, FAILED |
| input_tokens | integer | Nullable |
| output_tokens | integer | Nullable |
| cost | numeric(12,6) | Nullable |
| latency_ms | integer | Nullable |
| error_message | text | Nullable |
| created_at | timestamptz | Not null |

### tool_calls

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| execution_run_id | uuid | FK execution_runs |
| agent_id | uuid | FK agents |
| tool_id | uuid | FK tools |
| capability | varchar(255) | Capability requested |
| status | varchar(50) | REQUESTED, APPROVED, EXECUTED, DENIED, FAILED |
| input | jsonb | Request payload |
| output | jsonb | Result payload |
| error_message | text | Nullable |
| created_at | timestamptz | Not null |
| completed_at | timestamptz | Nullable |

### approval_requests

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| mission_id | uuid | FK missions, nullable |
| task_id | uuid | FK tasks, nullable |
| execution_run_id | uuid | FK execution_runs, nullable |
| requested_by_agent_id | uuid | FK agents |
| action_type | varchar(255) | Not null |
| risk_level | varchar(50) | LOW, MEDIUM, HIGH, CRITICAL |
| impact_summary | text | Not null |
| request_payload | jsonb | Action details |
| status | varchar(50) | PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED |
| approved_by_user_id | uuid | Nullable |
| decision_reason | text | Nullable |
| created_at | timestamptz | Not null |
| decided_at | timestamptz | Nullable |

### memory_entries

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| scope | varchar(50) | AGENT, TEAM, MISSION, TASK, ORGANIZATION |
| owner_agent_id | uuid | FK agents, nullable |
| team_id | uuid | FK teams, nullable |
| mission_id | uuid | FK missions, nullable |
| task_id | uuid | FK tasks, nullable |
| content | text | Not null |
| source_type | varchar(50) | USER, AGENT, TOOL, ARTIFACT, FILE |
| source_ref | varchar(500) | Nullable |
| confidence | numeric(4,3) | Nullable |
| visibility | varchar(50) | PRIVATE, TEAM, ORGANIZATION |
| expires_at | timestamptz | Nullable |
| created_at | timestamptz | Not null |

### artifacts

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| mission_id | uuid | FK missions |
| task_id | uuid | FK tasks, nullable |
| execution_run_id | uuid | FK execution_runs, nullable |
| name | varchar(255) | Not null |
| artifact_type | varchar(50) | MARKDOWN, CODE, JSON, FILE, LINK |
| content | text | Nullable |
| uri | varchar(1000) | Nullable |
| created_by_agent_id | uuid | FK agents, nullable |
| created_at | timestamptz | Not null |

### audit_events

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| organization_id | uuid | FK organizations |
| mission_id | uuid | FK missions, nullable |
| task_id | uuid | FK tasks, nullable |
| actor_type | varchar(50) | HUMAN, AGENT, SYSTEM |
| actor_id | uuid | Nullable |
| event_type | varchar(255) | Not null |
| payload | jsonb | Not null |
| created_at | timestamptz | Not null |

## 4. Indexing Recommendations

| Table | Index |
|---|---|
| teams | organization_id |
| agents | organization_id, team_id, status |
| missions | organization_id, team_id, status |
| tasks | mission_id, status, assigned_agent_id |
| execution_runs | task_id, agent_id, status |
| model_calls | execution_run_id, agent_id, created_at |
| tool_calls | execution_run_id, agent_id, status |
| approval_requests | organization_id, status |
| memory_entries | organization_id, scope, owner_agent_id, team_id, mission_id |
| audit_events | organization_id, mission_id, created_at |

## 5. Migration Strategy

Use Flyway.

Initial migrations:

| Migration | Purpose |
|---|---|
| V001 | Organization, team, agent |
| V002 | Skill, tool, permissions |
| V003 | Model provider and model config |
| V004 | Mission, task, execution run |
| V005 | Model call, tool call |
| V006 | Approval request, policy |
| V007 | Memory, artifact, audit event |

## Delivered V010 approval metadata subset

The target `approval_requests` definition above describes the future complete workflow. V010 currently delivers only a metadata ledger: id, organization_id, team_id, optional mission_id/task_id/requested_by_agent_id, title, action_type, risk_level, status, created_at and updated_at. Status also supports NEEDS_INFORMATION and INVALIDATED from the approved UI flow. Same-scope composite references bind Team/Mission/Task; no payload, execution run, human decision fields, grants or enforcement is delivered. See [migration scope](backend/APPROVAL_METADATA_MIGRATION.md).

## Delivered V011 decision-recording subset (draft PR20)

V011 extends the V010 ledger without rewriting it or inserting production requests/grants.

| Table | Delivered responsibility |
|---|---|
| approval_requests | Nullable requester_human_id; status is updated only by an accepted decision |
| approval_action_heads | Current org/Team action revision, digest and active flag |
| approval_snapshots | One canonical complete sealed snapshot per approval; version, head revision, expiry/invalidation |
| approval_grants | Exact human/org/Team/action/risk authority with expiry/revocation |
| approval_decisions | Unique decision per approval; immutable actor/name, reason, version/digest and time |
| approval_receipts | Org/actor/key uniqueness, command fingerprint and original decision reference |

Application checks enforce all cross-binding and current same-org requester membership. Global human FKs alone are not authorization. There are no snapshot/grant producer APIs, immutable-DB triggers or runtime/execution tables in this slice. Privileged direct SQL is outside API immutability. See [migration contract](backend/APPROVAL_DECISION_MIGRATION.md).

## Delivered V012 information subset (draft PR21)

| Storage | Responsibility |
|---|---|
| approval_requests.information_revision | Default0 for old requests; increases with each accepted question or answer |
| approval_information_entries | Immutable-by-API plain-text question/answer/evidence, actor snapshot, time, sequential revision and answered question ID |
| approval_information_receipts | Org/actor/key uniqueness across information operations; fingerprint, original scope and immutable transition result |

Unique approval/revision and transactional org-first locks serialize ask, reply and approve/reject. Public service validates same-approval question binding and ordered alternating history; maximum20 rounds/40 entries. Shared Java/JS normalization precedes storage/fingerprinting, blank optional evidence becomes null. No production seed, attachment storage, action parameter rewrite or execution tables. Decision receipts retain the prior table and revision0 fingerprint compatibility. Tables provide API immutability, not malicious-DB-owner protection.


## V013 approval grant management — verified bounded scope (PR23)

V013 adds a unique `(organization_id, account_id, id)` key to existing approval_grants without rewriting legacy rows.

| Table | Keys and relation | Purpose |
| --- | --- | --- |
| approval_grant_ledgers | PK organization/account; organization and global human FKs | Current recipient management revision; missing row reads as zero |
| approval_grant_management_events | PK id; unique organization/account/revision; scoped composite grant FK | Restricted immutable-through-API ISSUE/REVOKE history with reason, actor snapshot and original timestamps |
| approval_grant_management_receipts | PK organization/actor/idempotency key; scoped composite event FK | Canonical request fingerprint and durable historical replay |

Membership removal does not cascade these records. Existing grants retain their original provenance limitations. API transactions write the safe generic audit without copying restricted reasons. No production seed or runtime execution table is introduced. See [accepted contract](docs/engineering/APPROVAL_GRANT_MANAGEMENT_CONTRACT.md).

V013 and scoped management transactions were verified against native PostgreSQL on candidate838b1d9. See [final QC](docs/qc/APPROVAL_GRANT_MANAGEMENT_QC_REPORT.md); production rollout remains separate.


## V014 member role management — verified development scope

| Table | Keys and relation | Purpose |
| --- | --- | --- |
| member_role_ledgers | PK organization/account; global human and organization FKs | Bounded retained role revision; missing row reads0 without insertion |
| member_role_events | PK id; unique org/account/revision and org/actor/id | Restricted immutable-through-API before/after role, actor snapshot, normalized reason and recorded time |
| member_role_receipts | PK org/actor/key; composite org/actor/event FK | Canonical UTF-8 command fingerprint and durable historical result |

No live membership FK/cascade or production seed. Supported future membership removal/rejoin writers must participate in the revision/incarnation protocol; privileged direct SQL bypass is outside ABA protection. A role change updates existing membership and commits ledger/event/receipt/safe audit atomically without changing grant tables, sessions or credentials. See [V014 runbook](backend/MEMBER_ROLE_MANAGEMENT_MIGRATION.md) and [contract](docs/engineering/MEMBER_ROLE_MANAGEMENT_CONTRACT.md).

V014 development runtime acceptance is bound to candidate81bc489 and its exact CI/tree evidence in [final QC](docs/qc/MEMBER_ROLE_MANAGEMENT_QC_REPORT.md); PR24 remains draft/unmerged.

## V015 member invitations — bounded development gate verified

| Table | Keys/relation | Purpose |
| --- | --- | --- |
| organization_invitations | Org/id scope; issuer account/version; unique nullable token digest | Fixed expiry, recipient, revision/generation, lifecycle and current capability digest |
| invitation_mail_jobs | Invitation/generation unique | Bounded retry queue with no raw token/body |
| invitation_events | Scoped invitation; unique invitation/revision and org/actor/id | Restricted actor snapshot and membership-join provenance |
| invitation_receipts | Org/actor/key; scoped event FK | Canonical management replay without repeating side effects |
| invitation_email_locks | Email digest primary key | Cross-org absent-account serialization before global human locks |

Actual membership insertion creates/advances retained V014 role revision without inventing a role-change event; rejoin never resets history. Existing-account credentials, sessions, explicit grants and other-org roles are preserved. Forward migration only; no production seed. See [V015 runbook](backend/MEMBER_INVITATIONS_MIGRATION.md) and [contract](docs/engineering/MEMBER_INVITATIONS_CONTRACT.md). Candidate31270e8 passed native PostgreSQL/SMTP,271BE,522browser and Docker/restart/logout; see [final QC](docs/qc/MEMBER_INVITATIONS_QC_REPORT.md).


## V016 Team settings — implementation in progress

| Table | Integrity | Purpose |
|---|---|---|
| team_settings_ledgers | Scoped Team key, nonnegative bounded revision | Retained settings concurrency ledger; lazy zero only in writer |
| team_settings_events | Team/revision and actor/event uniqueness; scoped Team FK | Immutable restricted before/after and original actor/time |
| team_settings_receipts | Org/actor/key uniqueness; scoped event FK | Canonical command replay and lost-response receipt lookup |

Assignment mutations preserve Agent identity/config/history and all historical references; detach is blocked for protected Mission/Task/Approval/reporting/AGENT audit attribution. Existing schemas/history are retained. [Contract](docs/engineering/TEAM_SETTINGS_CONTRACT.md), [runbook](backend/TEAM_SETTINGS_MIGRATION.md), [QC pending](docs/qc/TEAM_SETTINGS_QC_REPORT.md).
