# AgentOS System Architecture v0.1

## 1. Architecture Style

AgentOS v0.1 should start as a modular monolith.

Reason:

- Faster MVP development
- Easier transaction boundaries
- Easier debugging
- Avoid premature distributed complexity
- Modules can be split into services later

Candidate future service boundaries:

- Agent Runtime
- Model Gateway
- Tool Gateway
- Orchestrator
- Memory/Knowledge Retrieval
- Observability pipeline

## 2. Recommended Stack

### Backend

| Technology | Purpose |
|---|---|
| Java 21 | Runtime |
| Spring Boot 3 | Backend framework |
| Spring Security | Authentication and authorization |
| Spring Data JPA | Persistence |
| PostgreSQL | Primary database |
| Flyway | Database migration |
| Redis | Runtime cache, locks, rate counters |
| WebSocket or SSE | Realtime mission updates |

### Frontend

| Technology | Purpose |
|---|---|
| Angular | Web application |
| PrimeNG | UI component set |
| Tailwind optional | Utility styling |

### AI Integrations

| Provider | Role |
|---|---|
| OpenAI | Model provider |
| Anthropic | Model provider |
| Gemini | Model provider |

## 3. High-Level Components

```text
Angular Web App
      |
Spring Boot API
      |
      +-- Organization Module
      +-- Agent Module
      +-- Skill Module
      +-- Model Module
      +-- Tool Module
      +-- Mission Module
      +-- Orchestration Module
      +-- Memory Module
      +-- Knowledge Module
      +-- Governance Module
      +-- Resource Module
      +-- Observability Module
      |
PostgreSQL + Redis
      |
External AI Providers / Tools
```

## 4. Backend Module Structure

```text
agentos-backend
  src/main/java/com/agentos
    organization
    agent
    skill
    model
    tool
    mission
    orchestration
    memory
    knowledge
    governance
    resource
    observability
    common
```

Each module should own:

- Domain entities
- Repository interfaces
- Application services
- API DTOs
- Controllers where relevant
- Domain events where relevant

Modules should communicate through application services and domain events, not direct repository access across module boundaries.

## 5. Request Flow: Mission Execution

```text
Human creates mission
  -> Mission API
  -> Mission Service
  -> Audit Event: MISSION_CREATED
  -> Orchestrator schedules planning
  -> Manager Agent execution run
  -> Context Builder
  -> Model Gateway
  -> Task graph generated
  -> Task records persisted
  -> Audit Event: MISSION_PLANNED
```

## 6. Request Flow: Tool Action

```text
Agent proposes tool action
  -> Tool Gateway
  -> Governance Engine
      -> ALLOW: execute tool
      -> APPROVAL: create approval request and pause execution
      -> DENY: reject action
  -> Audit Event
  -> Continue, wait, or fail execution run
```

Important rule:

> Agent never calls tools directly.

## 7. Model Gateway

All LLM calls must pass through Model Gateway.

Responsibilities:

- Provider selection
- Request normalization
- Streaming support
- Token counting
- Cost calculation
- Rate limiting
- Retry
- Fallback
- Audit
- Error handling

Interface sketch:

```java
public interface ModelProvider {
    ModelResponse execute(ModelRequest request);
    Stream<ModelChunk> stream(ModelRequest request);
    ProviderCapabilities capabilities();
}
```

Provider implementations:

- OpenAIModelProvider
- AnthropicModelProvider
- GeminiModelProvider

## 8. Context Engine

Context Engine builds the prompt package for a task.

Inputs:

- Agent contract
- Mission goal
- Task description
- Relevant skills
- Relevant memory
- Relevant artifacts
- Recent messages
- Governance constraints
- Tool permissions

Output:

- ModelRequest
- Context budget report

Context budget example:

| Segment | Token Budget |
|---|---:|
| Agent contract | 2,000 |
| Skills | 3,000 |
| Mission/task | 2,000 |
| Memory | 5,000 |
| Artifacts | 20,000 |
| Recent messages | 5,000 |
| Reserved output | 8,000 |

## 9. Tool Gateway

Tool Gateway owns tool execution and permission enforcement.

Responsibilities:

- Capability lookup
- Permission check
- Policy check
- Approval creation
- Tool execution
- Tool result normalization
- Tool call logging
- Error mapping

MVP tools:

| Tool | MVP Capability |
|---|---|
| HTTP | Call allowed endpoints |
| File | Read/write controlled workspace files |
| Git | Read repository state, create branches, create commits |
| Terminal | Execute allowlisted commands |

Future tools:

- GitHub
- GitLab
- Jira
- Linear
- Slack
- Gmail
- Google Drive
- Database
- Browser
- MCP servers

## 10. Governance Engine

Governance Engine decides if an action is allowed.

Decision values:

| Decision | Meaning |
|---|---|
| ALLOW | Execute immediately |
| APPROVAL_REQUIRED | Pause and ask human |
| DENY | Block action |

Policy examples:

| Action | Decision |
|---|---|
| READ_REPOSITORY | ALLOW |
| RUN_TESTS | ALLOW |
| CREATE_PULL_REQUEST | ALLOW |
| MERGE_PULL_REQUEST | APPROVAL_REQUIRED |
| DATABASE_SCHEMA_CHANGE | APPROVAL_REQUIRED |
| DELETE_DATA | APPROVAL_REQUIRED |
| DROP_TABLE | DENY |
| DEPLOY_PRODUCTION | APPROVAL_REQUIRED |

## 11. Event-Driven Execution

Use domain events internally.

Initial implementation can use:

- Database transaction
- Transactional outbox table
- Scheduled outbox dispatcher

No Kafka required for v0.1.

Important events:

```text
MISSION_CREATED
MISSION_PLANNING_STARTED
MISSION_PLANNED
TASK_CREATED
TASK_READY
TASK_ASSIGNED
EXECUTION_STARTED
MODEL_CALL_STARTED
MODEL_CALL_COMPLETED
TOOL_ACTION_REQUESTED
POLICY_CHECKED
APPROVAL_REQUESTED
APPROVAL_GRANTED
APPROVAL_REJECTED
TASK_COMPLETED
TASK_REJECTED
MISSION_COMPLETED
MISSION_FAILED
```

## 12. Resource Manager

Resource Manager enforces:

- Cost budget
- Token budget
- Request rate
- Token rate
- Concurrent calls
- Max retries
- Max agent hops
- Max discussion rounds

Hierarchy:

```text
Organization Budget
  -> Team Budget
  -> Agent Budget
  -> Mission Budget
```

## 13. Observability

Every mission should provide:

- Timeline
- Task graph
- Agent assignments
- Execution runs
- Model calls
- Tool calls
- Approval decisions
- Artifacts
- Token usage
- Cost usage
- Errors
- Reviewer feedback

## 14. Security Principles

- Secrets are never stored inside agent/tool JSON config.
- Tool permission is enforced in backend services, not only prompt text.
- Agent cannot modify its own authority.
- Agent cannot install tools.
- Agent cannot change governance policies.
- Destructive actions require approval or are blocked.
- Audit logs should be append-only.

## 15. Deployment Shape for MVP

```text
agentos-web
agentos-api
postgres
redis
```

Docker Compose is enough for local MVP.

Production later:

- API container
- Web container
- Managed PostgreSQL
- Managed Redis
- Secret manager
- Object storage
- Queue/event bus
