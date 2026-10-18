# AgentOS

Java21 / Spring Boot backend, Angular21 frontend and PostgreSQL. This is a local-development foundation, not a production release: the login feature adds persisted human accounts and server sessions, while full roles, recovery and runtime workflows remain incomplete. See [implementation status](IMPLEMENTATION_STATUS.md) and [QC evidence](docs/qc/FOUNDATION_QC_REPORT.md).

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

This source-preview frontend currently uses illustrative data. The Docker Nginx API proxy is separate from the Angular dev-server setup. Stop development dependencies with `bash scripts/dev-down.sh` from repository root. Do not run both Compose setups against the same host ports without adjusting configuration.

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
