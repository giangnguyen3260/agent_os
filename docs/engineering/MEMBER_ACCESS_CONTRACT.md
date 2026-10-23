# Member access inventory

Stacks on draft/unmerged Approval Information PR21 (6b639c7). This bounded slice implements the persisted Members & permissions inventory and a member's approval-grant inspection. It does not issue/revoke grants, change roles, invite members, or enable execution. Those writes require a separate issuer/expiry/revocation contract; the Figma grant-edit footer explicitly leaves them open.

## Design

Figma file 5wUjDKXg8vtRaJcO9XHfml, Forest AUTH members desktop71:6673/tablet71:7477/mobile71:8281; high-fidelity contexts and screenshots reviewed. Grant-edit71:7033 and review71:7063 reviewed for scope distinctions only. Reuse Forest Inter/tokens and native Angular buttons: the mapped React Simple Design System Button has no Angular implementation/dependency. No visible static image/SVG assets. Responsive centered content max1000px, desktop40px vertical, tablet24px/mobile16px horizontal, white bordered8px panel,20px gaps, canvas member cards with12px padding, readable ink. Existing authenticated workspace route/link reused; no illustrative person/team/role data is copied. Owner and team roles do not exist in the current schema and must be labelled unavailable, never inferred from organization ADMIN/MEMBER.

## Read API

Both endpoints require a freshly enabled credential-version-matching HUMAN with current organization ADMIN membership. MEMBER receives403, absent/foreign org403, missing/foreign member404 after org authorization. Organization ADMIN permits inventory inspection only, never approval authority. Password hashes, credential versions, session IDs and audit internals never returned. Read-only REPEATABLE_READ transaction: authorization, rows/counts and generatedAt classification share one snapshot, no write locks or business writes. Bound SQL, fixed20-row pages, page integer0..100000; no per-row queries.

GET /api/v1/organizations/{org}/members?page=0&query=&role=
- query optional Java String.strip edge whitespace normalized <=200 Unicode code points, literal case-insensitive contains over display name/email; escape SQL wildcard/backslash. role optional ADMIN|MEMBER; blank means all, invalid400.
- response data {organizationId,generatedAt,items,page,size:20,total}.
- items {accountId,displayName,email,role,enabled,isSelf,grantCounts:{unexpired,expired,revoked}}. Current membership rows include disabled accounts. Order lower(display_name),account_id. isSelf matches authenticated actor ID. Counts scope org AND account. Revoked takes precedence; expired means revoked_at IS NULL and expires_at<=generatedAt; unexpired means revoked_at IS NULL and expires_at>generatedAt. These are recorded grant lifecycle counts, NOT effective permission; disabled accounts/archived parents/unsupported actions/self-review and other decision checks can still block.

GET /api/v1/organizations/{org}/members/{accountId}/approval-grants?page=0&status=ALL
- status ALL|UNEXPIRED|EXPIRED|REVOKED, invalid400. Member must have a current membership in this organization, including disabled accounts.
- response data {organizationId,generatedAt,member:{accountId,displayName,email,role,enabled,isSelf},items,page,size:20,total}.
- items {id,organizationId,accountId,teamId,teamName,actionType,riskLevel,expiresAt,revokedAt,status}; revokedAt nullable, explicit key. status derived using the exact generatedAt above. Order expires_at desc,id. Counts/items use the same filter and snapshot. Safe same-org Team name only. Any stored actionType is displayed as text, not permission to execute it; supported decisions remain limited to PUBLISH_PREVIEW. No mutation or schema migration/production seed.

## Frontend

Authenticated routes /workspace/:id/members and /workspace/:id/members/:accountId/approval-grants. ADMIN-only from fresh workspace context on every load/filter/page/refresh and reused-route transition; guard plus server enforcement. Members search/role/page and grant status/page are URL-backed, reload/back supported. Native forms, labelled filters, Previous/Next, explicit loading/empty/error/Try again, Back to members/workspace. Each member card shows actual account state and org role, self marker and3 grant counts; View approval grants opens the real scoped inspection. Details show actual Team/action/risk/expiry/revoked time and lifecycle. Do not say unexpired grants guarantee approval eligibility. No imaginary team roles, Owner/invitations/edit/grant-success flow. Explain unavailable actions without functional-looking fake buttons.

Strict runtime shape, context IDs, enums, pagination/count consistency, self identity, duplicate IDs and lifecycle-as-of validation before rendering. Escape all text through Angular binding; no HTML/evidence links. Clear all private inventory on refresh/errors/authorization changes/account changes/route changes; ignore late responses with captured account/context epoch. Finite request timeouts; recovery GET only; no writes/session storage of inventory. Error state must not retain email/member/grant details. Native keyboard navigation and no horizontal overflow at1440/768/390.

## Verification

Backend meaningful real-DB tests: fresh admin/member/account/version/org boundaries, disabled target, missing/foreign existing member, tenant grant counts, literal filters/roles/pages, revoked-over-expired precedence and exact as-of semantics, stable pagination, no secret fields and no effects. Disposable CI fixture only. Native PostgreSQL probe checks actual HTTP rows, freshness/revocation and no writes. Browser real backend across3 widths: search/filter/page/reload, grant statuses, disabled account, empty states, malformed/failed read cleanup, access revocation and late responses, keyboard navigation and synthetic screenshot artifacts. Full Quality/Docker regressions plus independent source review. Report exact tested candidate; never treat prospective cases as executed evidence. Sonar remains paused; no Mac deployment/production scale acceptance.

Normalization uses Java Character.isWhitespace at edges (NBSP/U+2007/U+202F/U+FEFF excluded), mirrored explicitly in FE. Grant lifecycle comparison preserves serialized Instant fractional nanoseconds in FE; Date millisecond truncation must not change classification.

Delivery verification: candidate9fdc56f passed168 backend tests,402 browser instances, native PostgreSQL read/freshness/no-effects and restart/logout. Coordinator reviewed12synthetic captures. See [QC report](../qc/MEMBER_ACCESS_QC_REPORT.md) for exact runs, historical failure/correction and limits; no grant writer is implied.
