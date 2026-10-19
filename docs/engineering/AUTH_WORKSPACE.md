# Workspace selection delivery contract

Scope: AUTH workspace and no-workspace frames, followed by a minimal live workspace context. This does not complete the business dashboard or team-role model.

Figma references: desktop 71:6584 / 71:6608; tablet 71:7388 / 71:7412; mobile 71:8192 / 71:8216 in file 5wUjDKXg8vtRaJcO9XHfml. Reuse Forest tokens and self-hosted Inter. Example names, email and team-manager grants in Figma must not become runtime business data.

The selector lists only the signed-in account's current PostgreSQL memberships. Opening /workspace/:id resolves GET /api/v1/auth/workspaces/{id}; reloading resolves it again. An explicit URL identifies the context; no browser-stored role or implicit global selected tenant grants authority. Existing organization resource authorization continues to validate every request. Nonexistent and foreign workspaces return the same 404. The current schema has organization ADMIN/MEMBER only; these are labelled as organization roles, never team roles.

No memberships: explain the state, allow a fresh permission check and real logout/account change. No public organization creation or invitation delivery is added. Loading, API failures, lost access and retry are first-class states. No fabricated dashboard statistics or sample teams are reachable through selection.

CI provisions disposable test-only accounts and organizations through PostgreSQL; this is not production seed data. Production membership provisioning remains an operator responsibility pending the administration flow. Existing login/recovery suites remain required, alongside workspace membership isolation, reload, empty-state and viewport coverage. Sonar remains paused per user decision.

Validation: application revision 4f3e795 passed quality and Docker jobs with 33 backend tests and 33 browser instances across three viewports. Independent QC found no remaining development-scope blocker. See docs/qc/AUTH_WORKSPACE_QC_REPORT.md for exact runs and limits. The context page is a bounded hand-off, not the completed dashboard.
