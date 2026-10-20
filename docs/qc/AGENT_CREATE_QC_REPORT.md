# Agent Draft QC — execution pending

Scope: four-step Identity → Contract → Capabilities → Review & test, persisted DRAFT configuration, optional scoped team assignment, safe retries. Branch feature/agent-create stacks on draft/unmerged Team PR6. No activation, effective grants, provider execution or sandbox-test result is claimed. Sonar remains temporarily paused by request.

Local evidence: frontend production compilation, 93 browser-instance discovery, Node/Python syntax checks, and mocked-HTTP review of 18 rendered states across1440/768/390 plus three malformed-success retry flows. Mocked checks do not establish persistence or server authorization. Backend execution and real PostgreSQL/browser CI remain pending; final report must record exact revisions and runs before a development PASS.

Coverage planned: eight new backend integration tests (tenant/admin/CSRF, typed validation, durable replay/conflict, legacy detail, pagination, rollback and concurrent retry), 21 new browser instances alongside72 baseline, and a separate native PostgreSQL concurrent HTTP creation/replay probe. Rollback/concurrency backend tests use H2; native database evidence will be identified separately.

See docs/engineering/AGENT_CREATE_CONTRACT.md and backend/AGENT_DRAFT_MIGRATION.md for contract, collection response compatibility and rollout limits.
