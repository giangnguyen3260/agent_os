# AgentOS

Java21 / Spring Boot backend, Angular21 frontend and PostgreSQL. This is a local-development foundation, not a production release: persisted login/session, local-SMTP password recovery and membership-backed workspace selection are implemented. This branch adds a live organization overview and persisted task read model; the three-step Team Draft creation flow persists configuration and atomic agent assignments. The four-step Agent Draft flow saves identity, contract and requested capability configuration with optional team assignment and safe retries.

Agent Studio adds configuration editing with before/after review, persisted versions, optimistic concurrency and safe retries. See [Studio contract](docs/engineering/AGENT_STUDIO_CONTRACT.md), [Studio QC](docs/qc/AGENT_STUDIO_QC_REPORT.md) and [V007 rollout](backend/AGENT_STUDIO_MIGRATION.md).

Activation review now checks saved contract/model requests, structured limits and the assigned Team through a scoped ADMIN API. The responsive checklist distinguishes passed, blocked and unavailable prerequisites. Provider authorization, tool grants and an execution worker are absent, so activation remains disabled and direct activation requests fail without changing business data. See [review contract](docs/engineering/AGENT_ACTIVATION_REVIEW_CONTRACT.md), [QC evidence](docs/qc/AGENT_ACTIVATION_QC_REPORT.md) and [acceptance cases](docs/qc/AGENT_ACTIVATION_TEST_CASES.md). No schema migration is added by this slice.

The Agents directory now reads scoped persisted rows with literal name/role search, lifecycle filters and pagination. Desktop/tablet/mobile layouts link to creation, details, Studio and Draft activation review under current account permissions. Runtime/workload remain explicitly unavailable; configured concurrency is not allocated capacity. See [directory contract](docs/engineering/AGENT_DIRECTORY_CONTRACT.md), [QC evidence](docs/qc/AGENT_DIRECTORY_QC_REPORT.md) and [cases](docs/qc/AGENT_DIRECTORY_TEST_CASES.md).

The Teams directory adds persisted manager/Agent/task counts, literal name/purpose search, lifecycle filters and pagination. Running/Pending/Review links open the actual scoped task read, including Teams outside the dashboard page. Nullable legacy configuration is supported; health/runtime/cost remain unavailable. See [Team directory contract](docs/engineering/TEAM_DIRECTORY_CONTRACT.md), [QC](docs/qc/TEAM_DIRECTORY_QC_REPORT.md) and [cases](docs/qc/TEAM_DIRECTORY_TEST_CASES.md).

Team Workspace adds scoped overview lanes, paginated pending reasons/resolvers, assigned Agents and recorded mission/task activity metadata. Activity excludes foreign/mixed references and raw payloads/actor IDs. Existing Add Agent/Settings links work under current permissions; task creation and runtime metrics remain unavailable. Mission creation is implemented on the next stacked branch; see its delivery boundary below. See [Workspace contract](docs/engineering/TEAM_WORKSPACE_CONTRACT.md), [QC](docs/qc/TEAM_WORKSPACE_QC_REPORT.md) and [cases](docs/qc/TEAM_WORKSPACE_TEST_CASES.md).

The Mission creation slice adds a three-step Goal & context → Execution → Review flow from Team Workspace. It saves a CREATED Mission with real Team/manager scoping, required output, context and optional deadline/budget; safe retry receipts prevent duplicate creation. Planning and execution remain unavailable. See [contract](docs/engineering/MISSION_CREATE_CONTRACT.md), [QC](docs/qc/MISSION_CREATE_QC_REPORT.md) and [V008 rollout](backend/MISSION_CREATE_MIGRATION.md). Application91fac7e passed96backend tests,222real-backend browser instances and native PostgreSQL persistence/replay checks; Docker/restart/logout gates passed. Draft PR13 remains unmerged.

The Mission directory slice adds persisted scoped Missions, title/status/Team filters, pagination and recorded completed/pending task counts. A paginated Team chooser links to existing Mission creation; legacy unassigned rows remain readable without invalid detail links. See [contract](docs/engineering/MISSION_DIRECTORY_CONTRACT.md) and [QC](docs/qc/MISSION_DIRECTORY_QC_REPORT.md). Application6568c94 passed103backend tests,240real-backend browser instances and native PostgreSQL projection/no-write checks; Docker/restart/logout gates passed. Draft PR14 remains unmerged.

Mission Control adds a scoped read workspace for each assigned Mission: recorded task counts, status/pending pages and safe activity metadata. Unsupported planning, task/control mutations, output and cost remain unavailable. See [contract](docs/engineering/MISSION_WORKSPACE_CONTRACT.md) and [QC](docs/qc/MISSION_WORKSPACE_QC_REPORT.md). Candidate1488f16 passed109backend tests,258real-backend browser instances and native PostgreSQL read/scope/no-write checks; Docker and restart/logout gates passed. Draft PR15 remains unmerged.

Full roles, task execution/mutation, team/agent activation, effective grants, provider integration and runtime workflows remain incomplete. See [Agent contract](docs/engineering/AGENT_CREATE_CONTRACT.md), [Agent QC](docs/qc/AGENT_CREATE_QC_REPORT.md) and [V006 rollout](backend/AGENT_DRAFT_MIGRATION.md). See [Team delivery contract](docs/engineering/TEAM_CREATE_CONTRACT.md), [Team QC](docs/qc/TEAM_CREATE_QC_REPORT.md) and [V005 rollout](backend/TEAM_DRAFT_MIGRATION.md). See [dashboard delivery scope](docs/engineering/DASHBOARD_DELIVERY_CONTRACT.md), [workspace scope](docs/engineering/AUTH_WORKSPACE.md), [recovery setup and verification](docs/engineering/AUTH_RECOVERY.md) and [independent recovery QC](docs/qc/AUTH_RECOVERY_QC_REPORT.md).

## Build and run everything with Docker

Install Docker Engine or Docker Desktop with Compose v2 and BuildKit. On a Mac M3, Docker selects native ARM64 images; the files do not force x86 emulation. Java, Maven and Node are supplied by the build stages.

From the repository root:

```bash
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD to a random local password.
docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 180
```

Open http://localhost:8080. Override APP_PORT in .env if busy. The browser reaches `/api/` through Nginx; PostgreSQL, Redis and the backend have no published host ports in this stack. The default frontend binding is loopback-only. Spring sessions are stored in PostgreSQL; Redis is reserved for later runtime work. See [login setup](docs/engineering/AUTH_RUNTIME.md) for opt-in first-account provisioning. No default account or demo workspace is created.

- `backend/Dockerfile`: Maven + JDK21 build; runs Spotless, unit tests and package through `mvn verify`; non-root JRE runtime.
- `frontend/Dockerfile`: Node24 + `npm ci`, lint/format/tooling test/build; non-root Nginx runtime with SPA fallback and same-origin API proxy.
- `compose.yaml`: PostgreSQL health gate, backend DB readiness, frontend health check and persistent database volume.
- `docker.yml`: GitHub Actions builds the stack and smoke-tests frontend, SPA routes, API proxy and management-path isolation. No image is pushed by this workflow.

These are build definitions, not evidence that Docker has run in the assistant environment. Check [Docker QC](docs/qc/DOCKER_QC_REPORT.md) for verified and blocked checks. The backend uses its existing Boot3.3.5 baseline; the framework/security release review remains open. Containerization does not resolve missing application auth or production-security requirements.

## Useful commands

```bash
docker compose build
docker compose ps
docker compose logs --tail=100 backend
docker compose down
# Destructive: removes local database data. Run only for disposable data.
# docker compose down --volumes
```

Changing POSTGRES_PASSWORD after the database volume is initialized does not rotate the database user's password. Use PostgreSQL role-password rotation; do not delete a database volume to fix credentials when data matters. Keep .env out of Git and do not send credentials as Docker build arguments. Image tags identify maintained image families but are not immutable; pin audited image digests before release.

## Run locally without application containers

Requires a compatible Java21 JDK, Maven, Node24 and PostgreSQL. There is no Maven wrapper in this repository; use `mvn`, not `./mvnw`.

`docker/docker-compose.yml` is the older dependencies-only setup, distinct from the full root Compose stack. Updated helper scripts load the root .env and publish dependency ports only on loopback:

```bash
cp .env.example .env
# Set POSTGRES_PASSWORD, then:
bash scripts/dev-up.sh
cd backend
SPRING_DATASOURCE_PASSWORD='<your local password>' mvn spring-boot:run
```

In another terminal:

```bash
cd frontend
npm ci
npm start
```

Authenticated login, recovery, workspace and overview routes use API data. Older business previews remain blocked by navigation guards. The overview reports zero actual tasks on a fresh database and shows unavailable capacity/performance explicitly; it does not generate examples. V004 adds a tenant-constrained task table; read [migration rollout](backend/DASHBOARD_MIGRATION.md) before applying to existing data. The Docker Nginx API proxy is separate from the Angular dev-server setup. Stop development dependencies with `bash scripts/dev-down.sh` from repository root. Do not run both Compose setups against the same host ports without adjusting configuration.

## Quality and QC

```bash
python3 scripts/check-java-imports.py
mvn -f backend/pom.xml verify
cd frontend
npm ci
npm run format:check
npm run lint
npm run test:tooling
npm run build
```

Follow [Google coding standards](docs/engineering/CODING_STANDARDS.md). SonarQube scanning is temporarily paused. CI still enforces Java formatting, backend tests and coverage generation, frontend lint/format/tooling checks and build. Docker build and smoke checks run independently. The Sonar configuration is retained for later reactivation; a green CI run does not imply a Sonar quality gate has passed. Database/key-management/security, browser and load checks remain required before production.

Task Detail read delivery: [contract](docs/engineering/TASK_DETAIL_CONTRACT.md), [QC](docs/qc/TASK_DETAIL_QC_REPORT.md). Scoped status/pending/timestamps and safe activity; runtime/approval/output actions remain unavailable.

Create Task intake: [contract](docs/engineering/TASK_CREATE_CONTRACT.md), [QC](docs/qc/TASK_CREATE_QC_REPORT.md), [V009 rollout](backend/TASK_INTAKE_MIGRATION.md). Saves real DRAFT work to Intake with scoped owner/dependencies and safe retries; execution remains unavailable.

Tooling dependency patch: [QC](docs/qc/TOOLING_SECURITY_QC_REPORT.md) and [independent review](docs/qc/TOOLING_SECURITY_REVIEW.md). Full dev-inclusive audit and real CLI/dev-server compatibility checks are required by Quality/Docker.

Approval Center read delivery: [contract](docs/engineering/APPROVAL_CENTER_CONTRACT.md), [test cases](docs/qc/APPROVAL_CENTER_TEST_CASES.md), [QC](docs/qc/APPROVAL_CENTER_QC_REPORT.md). Real approval metadata, no pending-task substitution; decision/runtime flows remain unavailable.

Approval Center independent source review: [review](docs/qc/APPROVAL_CENTER_REVIEW.md); V010 metadata scope: [migration](backend/APPROVAL_METADATA_MIGRATION.md).

Approval Detail and bounded preview decisions: [contract](docs/engineering/APPROVAL_DETAIL_CONTRACT.md), [migration scope](backend/APPROVAL_DECISION_MIGRATION.md), [QC](docs/qc/APPROVAL_DETAIL_QC_REPORT.md), [independent review](docs/qc/APPROVAL_DETAIL_REVIEW.md). Explicit grants and immutable recorded decisions do not start execution.

Approval information exchange: [contract](docs/engineering/APPROVAL_INFORMATION_CONTRACT.md), [QC evidence](docs/qc/APPROVAL_INFORMATION_QC_REPORT.md), [independent review](docs/qc/APPROVAL_INFORMATION_REVIEW.md).
