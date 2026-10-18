# AgentOS Product Specification v0.1

## 1. Product Vision

AgentOS is a platform where humans build, govern, and operate AI organizations.

Instead of configuring one chatbot, users create an AI organization made of teams, agents, skills, tools, memory, policies, missions, and observable execution.

Core positioning:

> Build and manage your own AI organization.

## 2. Product Goal

AgentOS v0.1 must prove one complete operating loop:

1. Human creates an organization.
2. Human creates a team of AI agents.
3. Human assigns models, skills, tools, permissions, and budgets.
4. Human creates a mission.
5. Manager Agent plans tasks.
6. Agents execute assigned tasks.
7. Critical actions pause for approval.
8. Human approves or rejects.
9. QA/reviewer validates the result.
10. Human reviews trace, artifacts, token usage, and cost.

## 3. Target User

Primary MVP user:

- Technical founder
- Engineering manager
- Tech lead
- Solo developer building software with AI agents

Initial domain:

- Software development organization
- Spring Boot / Angular / PostgreSQL style project work
- Requirements, architecture, implementation, review, QA, and delivery

## 4. Core Principles

1. Agent is not model.
2. Skill is not tool.
3. Memory is not knowledge.
4. Permission is enforced outside prompts.
5. Human controls authority.
6. Important actions are traceable.
7. Agents may request more authority, but cannot grant it to themselves.
8. LLM decides what to propose; orchestrator decides what is allowed to happen.

## 5. MVP Scope

### Included

- Organization management
- Team management
- Agent Studio
- Agent contract
- Model provider configuration
- Basic model gateway
- Skill definition and assignment
- Tool registry and tool permission model
- Mission creation
- Manager-generated task graph
- Task assignment
- Execution run tracking
- Human approval workflow
- Basic memory scopes
- Context builder
- Token and cost tracking
- Audit log
- Mission trace view

### Excluded From v0.1

- Marketplace
- Automatic agent hiring
- Agent promotion
- Advanced memory conflict resolution
- Vector search
- Custom MCP marketplace
- Multi-organization billing
- Complex model routing
- Local model hosting
- Full plugin marketplace
- Production deployment automation

## 6. MVP Demo Scenario

Human creates an Engineering Team:

| Agent | Role | Model Provider | Main Responsibility |
|---|---|---|---|
| Maya | Engineering Manager | OpenAI | Plan mission and assign tasks |
| Emma | Solution Architect | Anthropic | Architecture and design review |
| Alex | Backend Developer | Anthropic | Implement backend changes |
| Quinn | QA Engineer | Gemini | Validate output |

Mission:

> Build a customer registration API.

Expected flow:

1. Human creates the mission.
2. Manager Agent breaks it into tasks.
3. Architect designs the API and database.
4. Backend Agent proposes implementation.
5. Backend Agent requests a database schema change.
6. Governance Engine requires human approval.
7. Human approves.
8. Backend Agent continues.
9. QA rejects once due to missing validation.
10. Backend Agent fixes.
11. QA passes.
12. Mission completes.
13. Human can inspect trace, artifacts, token usage, cost, prompts, and tool calls.

## 7. Product Modules

| Module | Purpose |
|---|---|
| Organization | Workspace, teams, members, constitution |
| Agent | Identity, role, contract, authority, budget |
| Skill | Instructions and reusable capabilities |
| Model | Provider abstraction and LLM calls |
| Tool | Registry, permissions, gateway enforcement |
| Mission | Business/user goal |
| Task | Unit of planned work |
| Orchestration | Planning, assignment, state transitions |
| Memory | Agent/team/org/mission memory |
| Knowledge | Documents, repositories, retrieved references |
| Governance | Policy, approval, risk decisions |
| Resource | Token, cost, rate, concurrency limits |
| Observability | Audit, trace, metrics, evaluation |

## 8. User Experience

### Main Navigation

- Dashboard
- Organization
- Teams
- Agents
- Skills
- Tools
- Models
- Missions
- Approvals
- Memory
- Observability
- Settings

### Signature Screens

1. Organization Map
   - Shows human, manager agents, teams, reporting structure.

2. Agent Studio
   - Configure role, model, skills, tools, memory, permissions, autonomy, budget.

3. Mission Control
   - Shows task graph, status, agent activity, approvals, artifacts.

4. Approval Center
   - Shows blocked actions requiring human decision.

5. Trace Explorer
   - Shows mission timeline, model calls, tool calls, costs, errors, decisions.

## 9. Agent Lifecycle

```text
DRAFT
  -> ACTIVE
  -> DEGRADED
  -> SUSPENDED
  -> ARCHIVED
```

Lifecycle meaning:

| State | Meaning |
|---|---|
| DRAFT | Agent is being configured and cannot execute tasks |
| ACTIVE | Agent can receive tasks |
| DEGRADED | Agent has repeated failures but may continue with limits |
| SUSPENDED | Agent cannot execute tasks |
| ARCHIVED | Agent is retired but history remains readable |

## 10. Authority Levels

| Level | Meaning |
|---|---|
| Observe | Can read assigned context only |
| Suggest | Can propose actions but cannot execute |
| Execute | Can execute allowed low-risk actions |
| Conditional | Can execute some actions and request approval for risky ones |
| Managed | Can delegate within defined team boundary |

No level allows agents to grant themselves permissions, install tools, edit governance, or bypass human approval.

## 11. Success Criteria

AgentOS v0.1 is successful when:

- A user can create an organization and agents.
- A mission can be planned into tasks.
- Tasks can be assigned and executed by agents.
- A policy can block a risky action.
- Human approval can resume the mission.
- Execution is traceable end to end.
- Token and cost usage are visible.
- Artifacts are attached to tasks and missions.
