# Member organization role management — proposal

Status: PROPOSED, not an approved authorization policy and not implemented. Branch feature/member-role-management stacks on draft/unmerged PR23 at3da67e2fa03432ad63e28509e53d188ea8bd91a0. The verified application beneath it is838b1d9 (176 backend/435 browser passes). Those counts verify the earlier grant feature, not this proposal.

Review assets: [15 provisional Figma views](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=124-7180), [exact frame handoff](MEMBER_ROLE_MANAGEMENT_FIGMA.md), [independent proposal review](../qc/MEMBER_ROLE_MANAGEMENT_PROPOSAL_REVIEW.md), [24 prospective acceptance scenarios](../qc/MEMBER_ROLE_MANAGEMENT_TEST_CASES.md). All Figma views are marked ĐỀ XUẤT — CHƯA DUYỆT; no interactive save or implemented role writer is claimed.

## Why this is the next bounded slice

Members & permissions already reads actual organization memberships and approval-grant lifecycles. PR23 adds explicit grant issue/revoke. Organization ADMIN/MEMBER changes and invitations remain unavailable. Complete role editing before a separate invitation/onboarding delivery: it reuses current accounts/memberships and requires no email identity/token creation. The earlier approved Option A applies to approval grants only; it does not approve organization role administration. Current schema has ADMIN/MEMBER, not Owner or Team roles.

## Product decision to approve

| Choice | Authority | Consequence |
| --- | --- | --- |
| A — recommended conservative first delivery | Fresh enabled/version-matching HUMAN with current organization ADMIN may change another enabled current member between ADMIN and MEMBER. No self-change. | Another administrator may be promoted/demoted, with explicit review, reason and acknowledgement. No Owner, dual control or step-up verification is implied. An enabled acting Admin remains in the organization; the API still checks the enabled-Admin invariant under locks. |
| B — defer writes | Keep current read-only role inventory until an Owner/dual-control or step-up authority contract is designed and approved. | No role writer is delivered under current ADMIN alone; requires a separate complete provisioning/recovery design. |

A is a proposal, not inherited authorization from the approved grant issuer policy. User selection is pending. Invitation-only/self-signup, first administrator provisioning, account disable/enable, membership removal, Owner transfer and recovery after external removal of all enabled administrators remain separate open product decisions. This proposal does not claim to solve them.

## Proposed rules for A

- Actor is a real current human, enabled, matching current credential version and same-organization ADMIN. All reads and writes enforce fresh authority; UI links are not authorization.
- Target must be a current same-organization enabled human and different from actor. Disabled/removed/foreign/missing targets cannot be modified. Disabled current members remain inspectable in the existing inventory. No account is created or joined implicitly.
- New role must be exactly ADMIN or MEMBER, different from the current role; no no-op history. Default selection is blank. No bulk writes, free-form roles, self-promotion or self-demotion.
- Preserve at least one enabled organization ADMIN after the transaction. With self-change blocked and a fresh enabled acting Admin, an API write cannot remove the last enabled Admin. Keep an explicit invariant check for defense against stale/concurrent state; never count disabled administrators as usable access. External privileged SQL/account disable/recovery is outside this writer.
- ADMIN promotion grants current organization administrative capabilities (Team/Agent/Mission/Task configuration, member inspection and grant administration through their existing service checks). It does not create an approval grant or enable runtime execution. This is an authority expansion requiring the product decision above.
- MEMBER demotion removes organization administration on subsequent fresh authorization checks. It does not delete membership, log the human out of unrelated organizations, alter credentials, revoke every session, or erase history. Existing read snapshots/writes serialized before the change may finish first; do not promise retroactive cancellation or instantaneous cross-tab erasure.
- Existing approval grants remain unchanged for both promotion and demotion: ADMIN and MEMBER can use an explicit eligible grant under existing approval rules. UI review must say demoting ADMIN does not revoke separately granted approval rights and link to grant inspection. Revocation stays a separate explicit action; no automatic cascade or invented coupling.
- Required reason: Java String.strip semantics,1..1000 Unicode code points, TAB/LF/CR allowed, other C0/DEL rejected, no NFC conversion. Store only in restricted role history; generic audit carries operation/event/target identifiers, not reason or email. No new password/token storage.

## Proposed API and persistence boundary

Routes are proposed, not registered by this change. Base /api/v1/organizations/{org}/members/{account}/role-management.

| Method/suffix | Proposed purpose |
| --- | --- |
| GET /context | Fresh ADMIN-only current target role/enabled state, per-member role revision, checkedAt, allowed role transition and ordered blockers; no writes |
| POST collection | Explicit expected revision/current role, new role, normalized reason and acknowledgement, CSRF + UUID Idempotency-Key |
| GET /receipts/{key} | Minimal actor/org/key/account-scoped historical role-change result; requires current ADMIN authority |
| GET /history?page=0 | Restricted paginated provenance of actual role writes; legacy role state has no fabricated change history |

The conditional contract below freezes exact DTO fields, ordered blockers, fingerprint domain and precision for candidate A; any product changes must be reconciled before implementation. Proposed result binds operation CHANGE_ROLE, organization/account, original actor/key/event, beforeRole/afterRole, next role revision and recordedAt; excludes reason/email. Context cannot reserve authority. expectedRevision plus expectedCurrentRole protect against stale and ABA transitions. Grant-management revision remains independent; one role update must not rewrite grant ledgers.

Proposed additive V014 role ledger/events/receipts (no migration file yet), scoped organization/account revision; no destructive membership rewrite or cascade. A missing ledger reads0 without writes; success increments once; conflict/replay/denial/rollback/no-op never increments. Events/receipts survive future membership removal. Immutable-through-API provenance is not protection against a privileged DB owner.

Commands require strict exact fields, types and bounds; key reuse with different target/body is409. SHA256 canonical UTF-8 TreeMap fingerprint includes operation/domain/version, org/account, expected role revision/current role, new role, normalized reason and acknowledgement. Replay compares exact original fingerprint before mutable target checks, reauthorizing the issuer's current ADMIN status. Returns original result even if later target role changes, never claims current authority. Wrong-target receipt GET returns null; null never means failure.

## Locking and security review plan

Reuse organization mutex first so this writer serializes with grant, approval and information writers. Prove target membership scope before exposing target profile, then lock actor/target global account UUIDs in deterministic unsigned/lexical order, deduplicated; lock their scoped memberships in the same order, then role ledger. Do not acquire actor first and later add arbitrary target locks. Reauthorize actor and target after every required wait, compare expected revision/current role, recheck enabled-Admin invariant, then take one microsecond timestamp after waits. Role/membership update, ledger, restricted event, safe audit and durable receipt commit atomically. Audit failure must roll all back. Exact receipt-only replay uses actor-only locks after org mutex and must not fall through to the new-write path.

Demoting an Admin must serialize with an already-waiting administrative writer: a writer acquiring its authorization lock after role change commits must fail. Existing grant-dependent decisions by a demoted current MEMBER may remain allowed if the explicit grant and other snapshot/self/scope checks pass. Tests must distinguish removal of administration from grant revocation. Existing global account/reset/login lock ordering and cross-organization reversed actor/target safety remain mandatory. Same-org serialization is a bounded correctness tradeoff, not production scale acceptance.

## Proposed UI flow before coding

Entry: actual member card or its grant inventory → Sửa vai trò tổ chức for a different enabled current member, ADMIN-only fresh preflight. Self/disabled targets display an explanation rather than a working-looking save control. Existing member inventory remains the source of account identity.

| Screen/state | Content and transition |
| --- | --- |
| Edit | Current organization and target; current role readonly; new role initially blank; explicit ADMIN/MEMBER labels; required reason. Show administration vs separate approval grants. Xem lại thay đổi only after valid input. |
| Review | Fresh context/revision for exactly the selected target/transition; before→after role and capability consequences; reason; grant-preservation warning; checkedAt; unchecked explicit acknowledgement. Final Lưu vai trò is the only POST. Back invalidates acknowledgement. |
| Blocked/conflict | Self/disabled/removed target, lost ADMIN, same role or stale revision: clear invalid private state; no fake success. Refresh fresh context and re-review rather than silent replacement. Last enabled Admin remains protected. |
| Unknown | Lost/malformed response may be committed. Keep original key/frozen body for authorized identical manual retry; exact receipt GET. No new replacement. Null receipt or advanced role/revision is still unknown. |
| Denied/account change | After authority loss erase target/private reason/body and POST retry. Retain minimum original same-actor correlation for GET-only recovery if allowed later; account change removes all correlation. Gate private DOM during fresh identity checks. |
| Recorded | Immutable historical before/after result with actor/event/time, no reason; separate Làm mới thành viên action to inspect current role. Does not imply current role still equals saved role. |
| History | Restricted real events with pagination, reason and actor snapshot; legacy unavailable notice, not fabricated provenance. |

Dirty edits/review/unknown warn on Back, cancel, reused target route and unload. Accepted navigation clears old target/form/correlation; late reads/results cannot restore previous private state. Responsive widths1440/768/390, existing Forest green tokens, Inter, flat native accessible controls, readable dark text, focus and no horizontal overflow. Figma frames for this proposal must be explicitly labeled unapproved; existing verified grant frames must remain untouched.

## Delivery gates after product approval

1. Record choice and any changed self/target/session/grant rules; freeze exact protocol and update Figma flow/exception states before app implementation.
2. BE: additive persistence, strict scoped writer/history/receipt endpoints, fresh authority, canonical vectors and atomic rollback tests. FE: real DTO validators, fresh preflight/review, exact recovery, private state handling and inventory entry points; no hardcoded business fixtures.
3. QC: preserve176BE/435browser baseline; add meaningful policy/wire/expiry-independent role/ABA/replay/privacy tests. Native PostgreSQL proves simultaneous promotion/demotion, observed post-demotion admin writer denial, grant-preserving MEMBER decision authority, global account ordering and audit rollback/no runtime effects.
4. Real backend browser on all3widths: edit/review/save/readback/history, self/disabled/foreign/no-op/stale boundaries, lost/malformed committed reply, exact retry/null/denial/GET recovery, session/account/reused-route/late-history behavior and responsive screenshots. Collecting tests is not execution.
5. Full Quality/Docker/native/browser/restart evidence bound to exact candidate/tree/merge and screenshot artifact hash, independent source/evidence review. Sonar remains paused; PR remains draft/unmerged; no production or Mac deployment claim.

Decision record: authority A/B OPEN; recommended A. Self-change BLOCKED and separate approval grants PRESERVED are proposed together with A, not already approved product behavior. This branch changes proposal/design/review documentation only until the user selects authority policy.

## Conditional engineering contract for candidate A

The details below supersede earlier less-specific/deferred engineering descriptions and resolve routine protocol questions for review. They are contingent on choosing A and do not enable or approve any endpoint. If B is selected, these routes remain unavailable.

### Wire, precedence and revisions

All IDs use strict UUID strings. Context/history/receipt reads require fresh enabled/version-matching same-org ADMIN inside read-only REPEATABLE_READ transactions. New writes use READ_COMMITTED and post-wait authorization. Syntax errors400, absent issuer authority403, authorized missing/foreign target404; syntactically valid requests are authorized before target disclosure. CSRF stays required. Duplicate/unknown/missing/null fields, coercions and invalid enum cases are400 without echoing private input. Role revisions are integers0..2147483647; success returns1..2147483647; refuse a fresh write at the maximum with409 before creating any ledger. No-op newRole==expectedCurrentRole is400. Actual revision/current-role mismatch409 precedes mutable no-op/transition checks. Self/disabled transitions are400; invariant/competing-state conflict409. First POST201; exact authorized replay200. Common ApiResponse envelope and safe error mapping follow the current app.

Context GET accepts optional newRole=ADMIN|MEMBER. Exact context: {organizationId,accountId,checkedAt,revision,member,selectedRole,enabledAdminCount,canChange,blockers}. member exact {accountId,displayName,enabled,currentRole,isSelf}; no email/credential/session fields. selectedRole is null or exact enum. Ordered blockers: SELF_CHANGE, TARGET_DISABLED, NO_CHANGE, LAST_ENABLED_ADMIN. NO_CHANGE applies only to an explicitly selected current role; LAST_ENABLED_ADMIN applies to selected ADMIN→MEMBER when current enabled-Admin count<=1. Self and last-Admin blockers may coexist in this read preflight. canChange means selectedRole is nonnull and blockers is empty; a partial edit preflight never permits save. Context requires a current membership and returns404 before any foreign target profile lookup.

POST exact body: {expectedRevision,expectedCurrentRole,newRole,reason,acknowledgement}; acknowledgement exactly true; roles exact ADMIN|MEMBER; normalized reason follows the rules above. Namespace is role-management only: unique (organization_id,actor_id,idempotency_key) across all targets in the role receipt table, independent of the grant receipt table. No second operation exists in this namespace.

Exact POST/receipt result: {operation,organizationId,accountId,actorId,actorName,idempotencyKey,eventId,revision,recordedAt,beforeRole,afterRole}. operation is CHANGE_ROLE, roles differ, actorName is the locked event snapshot, revision is expected+1, recordedAt is UTC with microsecond precision. No current target name/email/reason is included. FE correlates actor/org/account/key/operation/expected roles/expected revision, not status alone. Receipt GET /receipts/{key} returns this result or null; wrong account or absent key returns null. Loss of issuer ADMIN denies403 even for a committed receipt. Later restored current ADMIN may recover the exact historical result. Current issuer role ABA is governed by fresh current authority, not permanent revocation. Historical retry precedes mutable target membership/enabled/current-role checks; it does not perform a new role write.

### Retention and history scope

Role ledger/event/receipt tables have organization and global human FKs, never a live-membership FK/cascade. Ledger is retained after membership removal and never reset on rejoin. Only this writer is proposed now; every future supported role or membership removal/rejoin writer must participate in the same revision/incarnation protocol before delivery. Rejoin/removal APIs are not enabled here. Privileged SQL changes that bypass the ledger are explicitly outside revision/ABA guarantees.

Proposed constraints: PK role ledger(org,account), nonnegative bounded revision; unique role event(org,account,revision) and unique event(org,actor,id); role receipt PK(org,actor,key) with composite FK(org,actor,event_id) to that event. Before/after roles must be valid and different. No deletion/update API for provenance. Generic audit links event and account identifiers only. A hash is for command integrity/idempotency, not a replacement for readable restricted history or deployment encryption-at-rest.

History exact {organizationId,accountId,checkedAt,items,page,size,total}, page0..100000, fixed size20, total nonnegative, order recordedAt DESC,id DESC. Item exact {id,actorId,actorName,beforeRole,afterRole,reason,recordedAt,revision}. Same-org current ADMIN proves target scope from retained same-org/account events, or a current same-org membership for empty legacy history. If neither exists,404 even when a global/foreign account exists. Removed targets with retained events remain inspectable through history without fetching their current global profile. Current legacy member with no role events gets empty history and unavailable-provenance explanation. Context still requires a current member; receipt lookup requires neither current target profile nor membership. Actor names/reasons are restricted recorded snapshots, never fabricated for legacy state.

### Canonical hash vectors

Fingerprint is SHA256 of lexicographically sorted compact JSON string encoded as literal UTF-8, domain MEMBER_ROLE_MANAGEMENT, version1, operation CHANGE_ROLE, organizationId/accountId lowercase UUIDs plus exact normalized command fields. Supplementary Unicode is literal UTF-8 rather than an escaped surrogate representation; preserve internal CR/LF and do not NFC-normalize. Integer/bool representation and stripped reason are fixed. New current role/time/profile are not added to a retry fingerprint. The canonical vector below is synthetic test data, not a seed:

```json
{"accountId":"80800000-0000-4000-8000-000000000002","acknowledgement":true,"domain":"MEMBER_ROLE_MANAGEMENT","expectedCurrentRole":"MEMBER","expectedRevision":0,"newRole":"ADMIN","operation":"CHANGE_ROLE","organizationId":"80808080-8080-4080-8080-808080808080","reason":"é\r\n😀","version":1}
```

SHA256: `a6ee4d1ac1fa261229dcb1aeed52ffb420e6cf3ecedf1ba3dafdc4d99d16fe2c`.
Using reason `e\u0301\r\n😀` instead gives `39e3e2f8e98a2eea93af07603c598aa1772d28163ed1e75c3988415758270955`; these normalization-equivalent strings remain distinct. Additional implementation vectors must cover Java edge stripping, nonbreaking whitespace, code-point bounds, controls, UUID case normalization and overflow.

### Observable UI freshness and recovery

Fresh route entry/reuse, every context/filter/history/receipt read and review use a current ADMIN workspace check plus the server endpoint's own authorization. Final POST independently reauthorizes under its locks. Window focus and document visibility returning to visible trigger bounded session+workspace revalidation; suppress control-focus events masquerading as window focus, deduplicate overlapping checks and gate private DOM until they finish. An unchanged human ID is insufficient: same-ID role demotion/version changes must be observed through current workspace/server checks. On route changes during a freshness check, discard obsolete workspace responses and load the actual current route. No promise of instantaneous remote-tab erasure or revocation of a prior read snapshot is made.

Callbacks bind human/org/account/request generation. On observed denial or failed freshness checks, erase target profile/history reasons/form/review/result/frozen body and POST retry. If any dispatched command may be unresolved, preserve only in-memory correlation {actor,org,account,key,operation,expectedRevision,beforeRole,afterRole} as unknown for same-actor receipt GET recovery; this also applies when a late history failure arrives during the first POST. Account change erases all correlation. Preserve these bindings before cancellation: unsubscribe cannot undo a server commit. A null receipt with only opaque correlation returns immediately with unknown state, without private target reads or a replacement command.

An authorized identical manual retry is permitted only while the original frozen body/key remain and fresh issuer ADMIN is confirmed; current target role changes do not mutate that command. No auto-POST on refresh/focus/visibility. Current context/list role or a higher revision never proves old success/failure. Review edits/target/transition changes invalidate acknowledgement; refresh/review must bind the exact selected transition. Unknown navigation/reload warns that in-memory recovery may be lost. Do not store reason/body/correlation in browser storage, address-bar parameters, analytics or generic logs. Scope IDs alone remain normal route parameters.

The review warning/link describes separately granted approval authority, not continued access to ADMIN-only grant inventory after demotion. A newly demoted MEMBER may use a still-eligible explicit grant through existing approval routes but cannot inspect the ADMIN-only grant inventory merely because it holds a grant. Recorded result is historical and separate from refreshed current role. These are proposed UI/protocol rules, not implemented states or evidence of product approval.
