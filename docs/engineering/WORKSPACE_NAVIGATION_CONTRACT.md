# Workspace navigation completion contract

Branch `feature/workspace-navigation-completion`, based on accepted PR35 closure `06eb8b500dc80f5e72e6051b4a968f4a208cd9a9`. This is an explicitly selected integration slice under continuing screen-by-screen authorization, not a new execution capability or invented Figma approval. See [BA/SA review](POST_PR35_DELIVERY_REVIEW.md).

## User outcome and exact scope

A user can discover already-delivered workspace destinations from the24 existing Forest shells listed below. Desktop sidebar and compact menu show the same ordered sections: Overview, Organization map, Teams, Agents, Missions, Approvals, Trace. Current section can retain its selected span/non-action. Existing Change workspace/Đổi workspace remains last wherever present. Native Angular links bind the current route organization ID to existing `/workspace/:id/{dashboard,organization-map,teams,agents,missions,approvals,trace}` routes; do not add task/mission filters, inferred approval IDs or foreign target IDs.

Seventeen disabled Approvals placeholders become actual links. Seven more Forest templates gain their missing existing destinations. Dashboard and Trace navigation already contain all seven and need no changes. Member/grant/invitation pages use separate local administration navigation and do not acquire a new shell in this slice. Authentication, password recovery, public invitation acceptance and workspace chooser stay outside organization navigation.

Task Detail's disabled View approval action becomes **Organization approvals**, linking to the existing organization-wide center with the explicit caption: “Shows approvals across this organization; this task is not used as a filter.” This is not task-specific discovery or an approval decision. Runtime task actions and execution remain unavailable. Mission Control's stale “Task editing ... unavailable” copy is replaced with truthful DRAFT input/owner configuration guidance; planning, outputs, execution and stored-state meaning remain explicit. Intake pause still controls acceptance of new tasks, not dispatch.

## Safety and interaction invariants

Use existing RouterLink behavior and all destination auth/role/grant checks. ADMIN and MEMBER may browse organization-wide approval metadata under existing policy; opening a page confers no decision grant. Wrong organization/account access remains denied by actual server authorization. Do not change backend endpoints, DTOs, database schema/data, state transitions, existing guards or command services. Link clicks never save, decide, dispatch or auto-retry a command. Existing dirty, pending and UNKNOWN navigation guards remain authoritative; cancellation preserves current draft and route, acceptance discards only local unsaved state. Preserve existing link handlers and native dialog open/close/cancel controls, compact breakpoints, desktop selected sections and readable Forest styles.

## Affected templates

- `frontend/src/app/features/task-edit/task-draft-edit.component.html`
- `frontend/src/app/features/mission-intake-history/mission-intake-history.component.html`
- `frontend/src/app/features/task-owner/task-owner.component.html`
- `frontend/src/app/features/task-history/task-history.component.html`
- `frontend/src/app/features/agents/agent-page.component.html`
- `frontend/src/app/features/agents/agent-studio-page.component.html`
- `frontend/src/app/features/agents/agent-list-page.component.html`
- `frontend/src/app/features/agents/activation-review-page.component.html`
- `frontend/src/app/features/task-owner-history/task-owner-history.component.html`
- `frontend/src/app/features/missions/task-detail.component.html`
- `frontend/src/app/features/missions/new-task-create.component.html`
- `frontend/src/app/features/missions/mission-workspace.component.html`
- `frontend/src/app/features/teams/team-settings.component.html`
- `frontend/src/app/features/teams/team-page.component.html`
- `frontend/src/app/features/teams/team-directory.component.html`
- `frontend/src/app/features/teams/team-workspace.component.html`
- `frontend/src/app/features/mission-intake/mission-intake.component.html`
- `frontend/src/app/features/approvals/approval-detail.component.html`
- `frontend/src/app/features/approvals/approval-information.component.html`
- `frontend/src/app/features/approvals/approval-list-page.component.html`
- `frontend/src/app/features/agents/agent-permissions.component.html`
- `frontend/src/app/features/organization/organization-map.component.html`
- `frontend/src/app/features/missions/mission-list-page.component.html`
- `frontend/src/app/features/missions/mission-page.component.html`

## Acceptance evidence

Compile/lint/full source formatting must pass. Independent browser QC uses actual authenticated backend sessions and isolated existing fixtures, verifies scoped hrefs and actual organization Approvals/Organization Map GET arrivals, ADMIN/MEMBER/wrong-org behavior, no organization API writes, and dirty navigation cancel/accept. Runtime coverage spans all24 modified shells at1440/768/390; additional scenarios protect Task Detail’s organization-wide action and native keyboard navigation. Existing903 browser invocations plus3 focused repeats remain selected and required; new navigation cases run separately and are excluded only from the retained inverse selection, never dropped. No retries or existing test timeouts are relaxed.

Capture desktop/sidebar and tablet/mobile open-menu views with actual route, width, SHA256 and timestamp in JSONL; review captured readability/wrapping. These captures establish navigation integration only, not all product/Figma fidelity or formal accessibility certification. Local Java17 lacks Maven/Java21/Docker; CI supplies actual Java/PostgreSQL/browser evidence. Sonar remains paused. No merge/deploy.
