# AgentOS Project Structure v0.1

## 1. Purpose

This document defines the recommended repository, backend package, frontend module, database migration, and test structure for AgentOS v0.1.

The goal is to make the first code skeleton consistent with:

- Modular monolith architecture
- Spring Boot 3 and Java 21
- Angular frontend
- PostgreSQL and Flyway
- Clear module boundaries
- Future extraction into services if needed

## 2. Repository Strategy

Use one monorepo for v0.1.

```text
agentos/
  backend/
  frontend/
  docs/
  docker/
  scripts/
  .github/
```

Reason:

- Backend and frontend evolve together in MVP.
- API contracts are easier to coordinate.
- Docker Compose can run the whole platform locally.
- Documentation stays close to implementation.

## 3. Root Structure

```text
agentos/
  README.md
  PRODUCT_SPEC.md
  DOMAIN_MODEL.md
  SYSTEM_ARCHITECTURE.md
  DATABASE_ERD.md
  MISSION_STATE_MACHINE.md
  API_BOUNDARY.md
  PROJECT_STRUCTURE.md

  backend/
    pom.xml
    src/

  frontend/
    package.json
    angular.json
    src/

  docker/
    docker-compose.yml
    postgres/
    redis/

  scripts/
    dev-up.sh
    dev-down.sh
    reset-db.sh

  docs/
    adr/
    api/
    diagrams/

  .github/
    workflows/
```

## 4. Backend Structure

Backend base package:

```text
com.agentos
```

Recommended module structure:

```text
backend/src/main/java/com/agentos/
  AgentOsApplication.java

  common/
    api/
    application/
    domain/
    infrastructure/

  organization/
    api/
    application/
    domain/
    infrastructure/

  agent/
    api/
    application/
    domain/
    infrastructure/

  skill/
    api/
    application/
    domain/
    infrastructure/

  model/
    api/
    application/
    domain/
    infrastructure/

  tool/
    api/
    application/
    domain/
    infrastructure/

  mission/
    api/
    application/
    domain/
    infrastructure/

  orchestration/
    api/
    application/
    domain/
    infrastructure/

  memory/
    api/
    application/
    domain/
    infrastructure/

  knowledge/
    api/
    application/
    domain/
    infrastructure/

  governance/
    api/
    application/
    domain/
    infrastructure/

  resource/
    api/
    application/
    domain/
    infrastructure/

  observability/
    api/
    application/
    domain/
    infrastructure/
```

## 5. Module Layer Meaning

| Layer | Purpose |
|---|---|
| api | REST controllers, request DTOs, response DTOs |
| application | Use cases, command handlers, query handlers, services |
| domain | Entities, value objects, domain services, domain events |
| infrastructure | JPA repositories, external adapters, persistence mapping |

## 6. Backend Common Package

```text
common/
  api/
    ApiResponse.java
    ApiErrorResponse.java
    GlobalExceptionHandler.java
    PageResponse.java

  application/
    Command.java
    Query.java
    UseCase.java
    CurrentActor.java

  domain/
    EntityId.java
    DomainEvent.java
    ActorType.java
    AuditMetadata.java

  infrastructure/
    clock/
      SystemClock.java
    persistence/
      BaseJpaEntity.java
    security/
      SecurityConfig.java
      ActorContext.java
```

## 7. Organization Module

```text
organization/
  api/
    OrganizationController.java
    TeamController.java
    dto/
      CreateOrganizationRequest.java
      UpdateOrganizationRequest.java
      OrganizationResponse.java
      CreateTeamRequest.java
      TeamResponse.java

  application/
    OrganizationService.java
    command/
      CreateOrganizationCommand.java
      UpdateOrganizationCommand.java
      CreateTeamCommand.java
      UpdateTeamCommand.java
    query/
      GetOrganizationQuery.java
      ListOrganizationsQuery.java

  domain/
    Organization.java
    OrganizationStatus.java
    Team.java
    TeamStatus.java
    event/
      OrganizationCreatedEvent.java
      TeamCreatedEvent.java

  infrastructure/
    OrganizationJpaEntity.java
    TeamJpaEntity.java
    OrganizationJpaRepository.java
    TeamJpaRepository.java
    OrganizationMapper.java
```

## 8. Agent Module

```text
agent/
  api/
    AgentController.java
    AgentContractController.java
    dto/

  application/
    AgentService.java
    AgentContractService.java
    command/
      CreateAgentCommand.java
      UpdateAgentCommand.java
      ChangeAgentStatusCommand.java
      UpsertAgentContractCommand.java
      ConfigureAgentModelCommand.java
      AssignAgentSkillsCommand.java
      AssignAgentToolsCommand.java
      ConfigureAgentPermissionsCommand.java

  domain/
    Agent.java
    AgentContract.java
    AgentStatus.java
    AgentLevel.java
    AutonomyLevel.java
    AgentPermission.java
    event/
      AgentCreatedEvent.java
      AgentStatusChangedEvent.java
      AgentContractVersionCreatedEvent.java

  infrastructure/
    AgentJpaEntity.java
    AgentContractJpaEntity.java
    AgentJpaRepository.java
    AgentContractJpaRepository.java
    AgentMapper.java
```

## 9. Skill Module

```text
skill/
  api/
    SkillController.java
    dto/

  application/
    SkillService.java
    command/
      CreateSkillCommand.java
      CreateSkillVersionCommand.java
      ActivateSkillVersionCommand.java
      DeprecateSkillCommand.java

  domain/
    Skill.java
    SkillStatus.java
    SkillVersion.java
    RequiredToolCapability.java

  infrastructure/
    SkillJpaEntity.java
    SkillJpaRepository.java
    SkillMapper.java
```

## 10. Model Module

```text
model/
  api/
    ModelProviderController.java
    dto/

  application/
    ModelGateway.java
    ModelProviderRegistry.java
    ModelUsageCalculator.java
    command/
      AddModelProviderCommand.java
      ConfigureAgentModelCommand.java
    dto/
      ModelRequest.java
      ModelResponse.java
      ModelChunk.java
      ProviderCapabilities.java

  domain/
    ModelProvider.java
    ModelProviderType.java
    AgentModelConfig.java
    ModelCall.java
    ModelCallStatus.java

  infrastructure/
    openai/
      OpenAiModelProviderAdapter.java
    anthropic/
      AnthropicModelProviderAdapter.java
    gemini/
      GeminiModelProviderAdapter.java
    persistence/
      ModelProviderJpaEntity.java
      ModelCallJpaEntity.java
      ModelProviderJpaRepository.java
      ModelCallJpaRepository.java
```

## 11. Tool Module

```text
tool/
  api/
    ToolController.java
    dto/

  application/
    ToolGateway.java
    ToolRegistry.java
    ToolPermissionService.java
    dto/
      ToolActionRequest.java
      ToolResult.java

  domain/
    Tool.java
    ToolType.java
    ToolCall.java
    ToolCallStatus.java
    ToolPermission.java
    ToolDecision.java

  infrastructure/
    adapters/
      HttpToolAdapter.java
      FileToolAdapter.java
      GitToolAdapter.java
      TerminalToolAdapter.java
    persistence/
      ToolJpaEntity.java
      ToolCallJpaEntity.java
      ToolJpaRepository.java
      ToolCallJpaRepository.java
```

## 12. Mission Module

```text
mission/
  api/
    MissionController.java
    TaskController.java
    dto/

  application/
    MissionService.java
    TaskService.java
    ArtifactService.java
    command/
      CreateMissionCommand.java
      StartMissionPlanningCommand.java
      StartMissionCommand.java
      PauseMissionCommand.java
      ResumeMissionCommand.java
      CancelMissionCommand.java
      AssignTaskCommand.java
      RetryTaskCommand.java
      SubmitTaskReviewCommand.java

  domain/
    Mission.java
    MissionStatus.java
    Task.java
    TaskStatus.java
    TaskType.java
    ExecutionRun.java
    ExecutionRunStatus.java
    Artifact.java
    ArtifactType.java
    event/
      MissionCreatedEvent.java
      TaskCreatedEvent.java
      TaskAssignedEvent.java
      TaskCompletedEvent.java

  infrastructure/
    MissionJpaEntity.java
    TaskJpaEntity.java
    ExecutionRunJpaEntity.java
    ArtifactJpaEntity.java
    MissionJpaRepository.java
    TaskJpaRepository.java
    ExecutionRunJpaRepository.java
    ArtifactJpaRepository.java
```

## 13. Orchestration Module

```text
orchestration/
  api/
    MissionEventStreamController.java

  application/
    OrchestratorService.java
    MissionPlanner.java
    TaskScheduler.java
    TaskExecutor.java
    ContextBuilder.java
    AgentRuntime.java
    RetryPolicyService.java

  domain/
    MissionPlan.java
    TaskGraph.java
    TaskDependency.java
    ContextPackage.java
    ContextBudget.java
    OrchestrationDecision.java

  infrastructure/
    outbox/
      OutboxEventDispatcher.java
    sse/
      MissionEventPublisher.java
```

## 14. Memory Module

```text
memory/
  api/
    MemoryController.java
    dto/

  application/
    MemoryService.java
    MemoryRetrievalService.java
    command/
      CreateMemoryEntryCommand.java
      PromoteMemoryCommand.java
      ArchiveMemoryCommand.java

  domain/
    MemoryEntry.java
    MemoryScope.java
    MemoryVisibility.java
    MemorySourceType.java

  infrastructure/
    MemoryEntryJpaEntity.java
    MemoryEntryJpaRepository.java
    MemoryMapper.java
```

## 15. Knowledge Module

```text
knowledge/
  api/
    KnowledgeSourceController.java

  application/
    KnowledgeSourceService.java
    KnowledgeRetrievalService.java

  domain/
    KnowledgeSource.java
    KnowledgeSourceType.java
    KnowledgeChunk.java

  infrastructure/
    KnowledgeSourceJpaEntity.java
    KnowledgeSourceJpaRepository.java
```

MVP can keep Knowledge Module minimal. Advanced retrieval and embeddings can come later.

## 16. Governance Module

```text
governance/
  api/
    ApprovalController.java
    PolicyController.java
    dto/

  application/
    GovernanceEngine.java
    ApprovalService.java
    PolicyService.java
    dto/
      GovernanceRequest.java
      GovernanceDecision.java

  domain/
    Policy.java
    PolicyRule.java
    PolicyDecision.java
    RiskLevel.java
    ApprovalRequest.java
    ApprovalStatus.java

  infrastructure/
    PolicyJpaEntity.java
    ApprovalRequestJpaEntity.java
    PolicyJpaRepository.java
    ApprovalRequestJpaRepository.java
```

## 17. Resource Module

```text
resource/
  api/
    UsageController.java
    BudgetController.java

  application/
    ResourceManager.java
    BudgetService.java
    RateLimitService.java
    TokenUsageService.java
    CostUsageService.java

  domain/
    Budget.java
    BudgetScope.java
    UsageRecord.java
    RateLimitRule.java
    ResourceDecision.java

  infrastructure/
    redis/
      RedisRateLimitStore.java
    persistence/
      BudgetJpaEntity.java
      UsageRecordJpaEntity.java
```

## 18. Observability Module

```text
observability/
  api/
    ObservabilityController.java
    AuditEventController.java
    TraceController.java

  application/
    AuditService.java
    TraceService.java
    MetricsService.java
    EvaluationService.java

  domain/
    AuditEvent.java
    AuditEventType.java
    TraceEntry.java
    EvaluationResult.java

  infrastructure/
    AuditEventJpaEntity.java
    AuditEventJpaRepository.java
    TraceQueryRepository.java
```

## 19. Backend Resources

```text
backend/src/main/resources/
  application.yml
  application-dev.yml
  application-test.yml
  application-prod.yml

  db/
    migration/
      V001__create_organization_and_team.sql
      V002__create_agent_and_contract.sql
      V003__create_skill_tool_and_permissions.sql
      V004__create_model_provider_and_model_call.sql
      V005__create_mission_task_execution.sql
      V006__create_governance_and_approval.sql
      V007__create_memory_artifact_audit.sql
      V008__create_outbox_events.sql
```

## 20. Backend Test Structure

```text
backend/src/test/java/com/agentos/
  organization/
    OrganizationServiceTest.java
    OrganizationControllerIT.java

  agent/
    AgentServiceTest.java
    AgentControllerIT.java

  mission/
    MissionStateMachineTest.java
    MissionControllerIT.java

  governance/
    GovernanceEngineTest.java
    ApprovalControllerIT.java

  model/
    ModelGatewayTest.java

  tool/
    ToolGatewayTest.java

  orchestration/
    OrchestratorServiceTest.java
```

Testing approach:

| Test Type | Purpose |
|---|---|
| Unit test | Domain rules, services, state transitions |
| Integration test | REST API and persistence |
| Contract test | Gateway adapter behavior |
| End-to-end test | MVP mission scenario |

## 21. Frontend Structure

Angular app structure:

```text
frontend/src/app/
  app.config.ts
  app.routes.ts

  core/
    auth/
    http/
    layout/
    guards/
    interceptors/
    models/

  shared/
    components/
    pipes/
    directives/
    utils/

  features/
    dashboard/
    organization/
    teams/
    agents/
    skills/
    tools/
    models/
    missions/
    approvals/
    memory/
    observability/
    settings/
```

## 22. Frontend Feature Structure

Each feature should follow:

```text
features/agents/
  agents.routes.ts
  pages/
    agent-list-page.component.ts
    agent-detail-page.component.ts
    agent-studio-page.component.ts
  components/
    agent-form.component.ts
    agent-contract-editor.component.ts
    agent-skill-list.component.ts
    agent-tool-permissions.component.ts
  services/
    agent-api.service.ts
  models/
    agent.model.ts
```

## 23. Frontend Core Services

```text
core/
  http/
    api-client.service.ts
    api-error.model.ts
    api-response.model.ts

  auth/
    auth.service.ts
    current-user.service.ts

  layout/
    shell-layout.component.ts
    sidebar.component.ts
    topbar.component.ts

  interceptors/
    auth.interceptor.ts
    error.interceptor.ts
    request-id.interceptor.ts
```

## 24. Frontend Routes

```text
/
  dashboard
  organization
  teams
  agents
  agents/:agentId
  skills
  tools
  models
  missions
  missions/:missionId
  missions/:missionId/tasks/:taskId
  approvals
  memory
  observability
  settings
```

## 25. Frontend Page Priority

MVP UI implementation order:

1. Shell layout
2. Dashboard
3. Organization and team pages
4. Agent list and Agent Studio
5. Skill list/editor
6. Tool registry and permissions
7. Model provider setup
8. Mission list and Mission Control
9. Approval Center
10. Trace Explorer
11. Memory Explorer

## 26. API Client Naming

| Feature | API Service |
|---|---|
| Organization | OrganizationApiService |
| Team | TeamApiService |
| Agent | AgentApiService |
| Skill | SkillApiService |
| Tool | ToolApiService |
| Model | ModelProviderApiService |
| Mission | MissionApiService |
| Task | TaskApiService |
| Approval | ApprovalApiService |
| Memory | MemoryApiService |
| Observability | ObservabilityApiService |

## 27. DTO Naming Rules

Backend:

```text
CreateAgentRequest
UpdateAgentRequest
AgentResponse
AgentDetailResponse
CreateAgentCommand
UpdateAgentCommand
```

Frontend:

```text
CreateAgentRequest
UpdateAgentRequest
Agent
AgentDetail
```

Avoid exposing JPA entities directly through REST.

## 28. Event and Outbox Structure

Backend outbox package:

```text
common/infrastructure/outbox/
  OutboxEvent.java
  OutboxEventRepository.java
  OutboxEventDispatcher.java
  OutboxEventStatus.java
```

Database table:

```text
outbox_events
  id
  event_type
  aggregate_type
  aggregate_id
  organization_id
  payload
  status
  created_at
  published_at
```

For v0.1, consumers can be in-process handlers.

## 29. Configuration Structure

```yaml
agentos:
  model:
    providers:
      openai:
        enabled: true
      anthropic:
        enabled: true
      gemini:
        enabled: true

  orchestration:
    max-task-retries: 3
    max-planning-retries: 2
    max-discussion-rounds: 5
    max-delegation-depth: 4

  resource:
    default-mission-cost-limit: 5.00
    default-agent-daily-cost-limit: 20.00

  governance:
    approval-timeout-hours: 24
```

## 30. Docker Compose Structure

```text
docker/
  docker-compose.yml
  postgres/
    init.sql
  redis/
```

Services:

```text
agentos-api
agentos-web
postgres
redis
```

## 31. Scripts

```text
scripts/
  dev-up.sh
  dev-down.sh
  reset-db.sh
  generate-openapi.sh
  run-backend-tests.sh
  run-frontend-tests.sh
```

## 32. Documentation Structure

```text
docs/
  adr/
    ADR-0001-modular-monolith.md
    ADR-0002-rest-api-first.md
    ADR-0003-transactional-outbox.md
    ADR-0004-agent-permission-enforced-outside-prompt.md

  api/
    openapi.yaml

  diagrams/
    architecture.md
    mission-flow.md
    database.md
```

## 33. Naming Conventions

### Java

| Type | Convention |
|---|---|
| Entity | `Agent`, `Mission`, `Task` |
| JPA entity | `AgentJpaEntity` |
| Repository | `AgentJpaRepository` |
| Mapper | `AgentMapper` |
| Service | `AgentService` |
| Command | `CreateAgentCommand` |
| Query | `GetAgentQuery` |
| Event | `AgentCreatedEvent` |
| Controller | `AgentController` |
| Request DTO | `CreateAgentRequest` |
| Response DTO | `AgentResponse` |

### TypeScript

| Type | Convention |
|---|---|
| Model | `Agent` |
| Detail model | `AgentDetail` |
| API service | `AgentApiService` |
| Page component | `AgentListPageComponent` |
| UI component | `AgentFormComponent` |

## 34. Dependency Rules

Allowed backend dependencies:

```text
api -> application
application -> domain
application -> other module application services
infrastructure -> domain
infrastructure -> application interfaces
domain -> no infrastructure dependency
```

Avoid:

- Controller calling repository directly
- One module using another module's repository
- Domain objects depending on Spring annotations
- JPA entities returned directly to API
- Agent runtime bypassing Model Gateway or Tool Gateway

## 35. First Skeleton Generation Order

Recommended implementation sequence:

1. Create backend Spring Boot project.
2. Add common response/error/security shell.
3. Add Flyway and PostgreSQL config.
4. Create organization module.
5. Create agent module.
6. Create skill/tool/model modules with stub gateways.
7. Create mission/task/execution tables and APIs.
8. Create governance/approval module.
9. Create observability/audit module.
10. Create orchestration state machine shell.
11. Create Angular shell layout.
12. Add feature pages in MVP priority order.

## 36. Definition of Done for Skeleton

Skeleton is ready when:

- Backend starts with Java 21.
- PostgreSQL migrations run successfully.
- Angular app starts.
- Organization CRUD works.
- Agent CRUD works.
- Mission can be created.
- Mission planning can be requested and emits an event.
- Audit event is written.
- Frontend can show Dashboard, Agents, Missions, Approvals placeholder pages.
- Docker Compose can start local dependencies.
