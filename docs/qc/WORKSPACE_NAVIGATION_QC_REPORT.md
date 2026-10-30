# Workspace navigation completion QC

Status: implementation prepared on `feature/workspace-navigation-completion`, based on accepted PR35 closure `06eb8b500dc80f5e72e6051b4a968f4a208cd9a9`. Runtime/browser/visual acceptance remains pending. No merge/deploy; Sonar remains paused.

Scope is integration of existing destinations across24 Forest templates, including17 disabled Approvals placeholders and compact-menu parity. Task Detail now names its organization-wide approvals destination explicitly; no task filter is inferred. Mission Control copy reflects actual DRAFT input/owner configuration while retaining unavailable planning/outputs/execution. See [contract](../engineering/WORKSPACE_NAVIGATION_CONTRACT.md) and [BA/SA review](../engineering/POST_PR35_DELIVERY_REVIEW.md).

Frontend lint, full source formatting and Angular production build passed locally after the template changes. No backend/security/database/DTO/guard/command implementation changes. New real-backend navigation acceptance and retained903 browser invocations plus3 focused repeats are required. All retained native PostgreSQL, backend quality and Docker restart/logout checks remain selected. Existing fixtures are used only in disposable CI; no production hardcoding or fabricated API success. Local Java17 has no Maven/Java21/Docker, so runtime evidence must come from actual CI.

Independent cases and source review will record navigation scope, actual GET arrivals, role/foreign-org boundaries, unsaved-draft navigation cancellation/acceptance and absence of organization API writes. Desktop/tablet/mobile captures plus actual hash/route/width JSONL remain unaccepted until generated and inspected. No Figma frame is edited or claimed to match exactly.

Independent browser collection:90 new invocations (24 affected-source routes +6 focused cases ×3 viewports), with30-second default timeout and zero retries. Combined with903 retained invocations, the disjoint target is993, plus3 existing focused repeats. Syntax, scoped Prettier and collection pass; these are not runtime passes. Whole-business-table snapshots compare compact sorted-value/xmin/ctid digests, avoiding raw row output.
