# V006 Agent Draft rollout

V006 adds `agent_draft_config`, durable `agent_create_receipts`, and an organization/name/id index for bounded agent reads. Team candidate reads reuse the V004 team index. Existing agents remain unchanged and expose null optional Draft configuration on detail reads. No sample business data, grants, provider credentials or activation are introduced.

Apply through Flyway after V005. Back up existing databases and use the environment's schema-change window: ordinary index creation can block concurrent writes. Review dataset size before production rollout. Fresh disposable PostgreSQL CI coverage does not establish a production change window or throughput target.

Draft configuration is editable input for later review. Model, skills, tools and limits are requested text, not approved providers, effective permissions or enforced runtime budgets. Do not store provider credentials in these fields. Agent level MID, autonomy SUGGEST and status DRAFT are controlled by the creation service.

Receipts retain the original response for each organization/account/key. Do not prune them or change response deserialization without preserving retry compatibility. Current authorization is checked before replay. A selected same-organization DRAFT/ACTIVE team gets a new agent without changing its manager or activating it. Legacy entity-service helpers remain internal; the web controller uses the authenticated Draft service.

The agents collection response is now paginated (`data.items`, `page`, `size`, `total`) instead of the earlier preview array; consumers must use this contract. Legacy preview routes remain blocked by their preview guard.
