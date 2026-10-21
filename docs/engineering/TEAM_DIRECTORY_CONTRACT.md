# Team directory delivery contract

Branch feature/team-directory stacks on Agent directory PR10. Forest S03 references: Figma5wUjDKXg8vtRaJcO9XHfml desktop43:1613, mobile60:3895, tablet60:10511, no-results47:3373. Existing Forest tokens, Inter font and native controls apply. Design sample business values are replaced by scoped persistence; health/runtime/cost/capacity are unavailable without authoritative integrations.

## User flow

/workspace/:id/teams lists Team name/purpose, manager, lifecycle, assigned Agent count and actual task counts. ADMIN can Create Team; ADMIN/MEMBER can open details and existing task reads. Running counts RUNNING rows; Pending counts WAITING/BLOCKED; Review counts IN_REVIEW; total includes every persisted task state. Multiple Agents and tasks must not multiply aggregates. Unassigned manager is explicit. Pending/Running/Review links open the corresponding scoped task drilldown; requested query parameters must actually drive the read, not merely change the URL.

Explicit search matches literal case-insensitive name or purpose, with optional lifecycle status. Fixed20-row pages preserve URL/back/reload state. New filters reset page0. Empty/no-results, loading, error/retry and lost-access states clear prior rows. Account/scope changes and late responses must not reintroduce old data. Compact navigation is a keyboard-accessible native dialog. Existing dashboard Teams summary behavior remains compatible.

## API

GET /api/v1/organizations/{organizationId}/teams/directory is a separate projection preserving the existing teams API. Enabled current account/credential/membership ADMIN/MEMBER required. Parameters: page0..100000 default0; query<=200 stripped literal contains; optional status DRAFT/ACTIVE/ARCHIVED. Invalid recognized parameters400; denied organization access403. Workspace-context lookup can return404 after membership revocation; directory API returns403.

Data {items,page,size,total}; each item has id,organizationId,name,purpose,status,managerAgentId,managerName,agentCount,totalTasks,runningTasks,pendingTasks,reviewTasks. Nullable legacy purpose/manager is supported. Sort created_at DESC,id. Count and rows use one read snapshot. Scoped manager/Agent/task joins and independent aggregates prevent cross-tenant leakage or join multiplication. Bind all inputs and escape search wildcard characters. Reads do not mutate business data or lock entire organizations.

## QC boundary

Backend integration cases plus six browser cases per1440/768/390 and a native PostgreSQL projection/count/no-write probe. Dedicated disposable org12121212-1212-4212-8212-121212121212 has25Teams and a3Agent/6task case; org13131313-1313-4313-8313-131313131313 is foreign. These IDs were checked against baseline fixtures before use. No migration or production seed. Actual final execution belongs in TEAM_DIRECTORY_QC_REPORT.md. Sonar remains paused; production security/scale, broad accessibility and runtime/activation remain outside this gate.
