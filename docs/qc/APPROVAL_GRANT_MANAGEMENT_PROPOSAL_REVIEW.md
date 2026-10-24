# Independent SA / BA / QC proposal review

Disposition: **ready for user policy decision; issuer policy not selected; not runtime acceptance**. Reviewed branch `feature/approval-grant-management`, baseline `5f40a6be7a6d05877adef12c9ec1bc1473570dc9`, and the working-tree `docs/engineering/APPROVAL_GRANT_MANAGEMENT_PROPOSAL.md`. This review owns only this file. No application, migration, test, or proposal changes were made; no tests were run for this Markdown-only review.

## Product recommendation for the user

Choose **A** for the bounded first delivery if organization administrators are trusted to grant approval rights to other members. It uses the existing ADMIN/MEMBER model and is simpler to operate. It prevents direct self-issuance, but two administrators can grant rights to each other; it is not dual control.

Choose **B** if approval issuers must have separately assigned, scoped authority. It gives stronger separation, but requires an explicit process for assigning the first issuer, recovery, expiry and revocation before delivery. ADMIN alone would not grant writer access.

With either choice, explicitly accept or edit the proposed maximum **7 days** and maximum new risk **HIGH**, with exact Team/action/risk scope and no self-issuance. These are new product choices, not previously accepted policy. B additionally needs a decision on grant validity when issuer entitlement expires or is revoked. The user has already authorized implementation work; the pending decision selects new business issuer/lifetime policy, rather than granting permission for routine coding. Neither alternative becomes selected policy merely by appearing in this review. The Figma footer's unresolved-policy status is reported by the proposal and corroborated by the local Member access contract; this review did not independently retrieve Figma or personal history.

## Contract corrections for the main author

### 1. High — the proposed account lock order can deadlock across organizations

The persistence section specifies organization → actor account/membership → recipient account/membership, then postpones sorted account acquisition to a later flow. This flow already locks two humans. `human_accounts` is global, while memberships and the organization mutex are organization-scoped (V002). With A and B belonging to two organizations, one command can hold org X/account A and wait for recipient B while another holds org Y/account B and waits for recipient A. Different organization locks do not serialize these commands. PostgreSQL can abort a participant; this is an availability/protocol flaw, not evidence of an authorization bypass.

Correction: specify an acquisition strategy for this flow now. After organization scope authorization, lock the distinct actor/recipient account rows in one deterministic UUID order, then revalidate enabled/version/membership/role under the locks. Define the self-revoke single-account case. Do not call an actor-first locking helper before sorting. Check compatibility with all other account lockers; retain organization-first ordering. Alternatively design recipient freshness without a second account lock and prove its concurrency semantics. The existing Approval Information contract explicitly avoids a second human-account lock; it is not a sufficient template for copying actor-first order into this writer.

### 2. High — receipt response fields do not satisfy the required exact recovery binding

The recovery paragraph requires captured actor/org/account/key/operation binding. The listed immutable result includes operation/org/account/grant/event/revision/time but omits actor and key. The key namespace also spans both operations, while GET has no expected operation. A request URL can supply some binding, but the current text does not define whether that is authoritative transport correlation or a required response field.

Correction: freeze one explicit receipt DTO with `actorId`, `idempotencyKey`, operation, organizationId, accountId, grantId, eventId, revision and operation-specific timestamps (explicit nulls), or expressly define equivalent verified request/response binding. Specify POST and GET use the same immutable result. Lookup actor/org/key first under current writer authority, verify stored account/operation binding, and return the stored result without target-current-membership, Team-state, current grant expiry/revocation or revision checks. For POST replay, compare the normalized immutable fingerprint before any mutable issuance/revocation precondition; stale expectedRevision is part of the original command, not grounds to reject an exact replay. A fresh new-key repeat revoke remains409. Define wrong-account GET behavior without disclosing the other account's receipt.

For B, “current scoped writer authority” must mean independently current entitlement covering the original stored scope, not a current recipient membership or a still-live target grant. Decide whether a separately scoped revoker is needed for legacy/CRITICAL rows that cannot be issued. Do not quietly let a narrow LOW issuer inspect or revoke all HIGH/CRITICAL grants.

### 3. Medium — single-row revocation must not promise removal of all approval rights

V011 permits multiple exact-scope grants; `ApprovalDetailService.granted()` accepts **any** matching unrevoked row with `expires_at > now`. The proposal correctly retains legacy duplicates and revokes one ID. Revoking one duplicate can therefore leave the recipient eligible through another row. The new writer's duplicate check cannot eliminate this historical condition.

Correction: state that revoke removes this recorded grant only. Refresh same-scope inventory and show whether other unexpired recorded grants remain, without equating that count with effective authorization. Preserve the existing decision behavior. A bulk “remove all approval rights” operation would require a separately specified scope and command, not an inferred side effect. Include legacy overlapping rows in future revocation verification.

### 4. Medium — freeze revision, timestamp, normalization and fingerprint details before coding

The proposal explicitly defers final DTOs, which is appropriate for a decision document, but leaves implementation-critical details open. Specify initial ledger revision for existing recipients, atomic first-row creation, monotonic increment on successful ISSUE/REVOKE only, replay/no-op non-increment, and numeric bounds consistent with FE exact integers (or an explicit string representation). Preserve history for former members. Live duplicate detection must include legacy rows and run under the shared organization lock at post-wait wall time; a time-dependent partial uniqueness predicate is not a substitute.

Specify one persisted timestamp precision and derive expiry from the final post-lock operation instant; classify expired at equality. Define canonical command and grant fingerprint fields/encoding, Java edge-strip behavior, Unicode code-point counting, treatment of control characters and unknown/duplicate/null JSON fields. Member inventory uses Java strip; Approval Information uses a broader documented whitespace union. “Follows existing patterns” must not select contradictory normalizers. Confirm strict integer duration1..168, acknowledgement exactly true, bounded revision, and digest format.

### 5. Medium — historical privacy and unknown-outcome navigation need explicit boundaries

Revocation context for a removed member is a deliberate extension of the existing inventory API, which requires current membership. Keep inventory unchanged. Authorize organization and bind grant/org/account before loading historical names or email; return only the minimum historical display fields actually needed. V011 grants have no issuer/name/reason provenance: label legacy provenance unavailable rather than deriving it from today's actor or current display name. Define who can read the restricted reason history and through which future endpoint; reason-free generic audit and receipt are correct.

Define that account changes erase even in-memory correlation from the prior account. Within an unresolved operation's captured account, keep only opaque correlation and an uncertainty warning after denial; erase reason/body/private DTOs, disable retry until fresh authorization, and never interpret denied retry or GET null as proof of failure. Since refresh destroys memory and browser storage is forbidden, promise recovery only when the exact key remains available; explicitly state the reload/abandonment limitation and warning. There is no contradiction in allowing authorized exact receipt recovery after recipient removal while refusing new issuance.

## Product choices versus routine implementation

Product decisions remain A/B,7-day/HIGH bounds, acceptance of reciprocal administrator grants under A, and B's provisioning/recovery/entitlement-containment/revoker rules. Membership removal or account disablement currently blocks approval through fresh checks; rejoining/re-enabling before expiry can make an unrevoked old grant usable again because grants do not bind a membership generation. State this existing lifecycle consequence in the decision record. Permanent cancellation on removal would be an additional product rule and implementation scope, not an assumed property of this proposal.

Routine engineering decisions are deterministic account locking, atomic ledger creation/increment, DTO allowlists, normalized fingerprints, receipt check order, scoped SQL, privacy cleanup and precision. Resolve these without reopening issuer policy or adding a hidden issuer fallback. They must be frozen before writer implementation, but do not prevent presenting the two product options now.

## Verified baseline alignment and future QC scope

- `docs/engineering/MEMBER_ACCESS_CONTRACT.md` and `MemberAccessService`: fresh enabled/version-matching ADMIN inventory only; REPEATABLE_READ reads, current target membership and no grant writes. ADMIN is not existing approval authority.
- `ApprovalDetailService.authorize/grants/granted/decide`: organization-first mutation serialization, exact Team/action/risk grants, self-decision blockers, post-lock wall-clock checks and fresh live-grant authorization on decision replay. Management receipt recovery must not change that decision replay policy.
- `ApprovalInformationService` and its contract: shared organization mutex and immutable operation receipts; requester ANSWER authority is distinct from approver QUESTION grant authority. Revocation must block subsequent grant-dependent decisions/questions, not indiscriminately disable valid requester answers.
- V002/V011/V012: ADMIN/MEMBER only; global accounts; legacy grants lack provenance/uniqueness; immutable-history additive migration approach is appropriate. No executor, provider or Task/Mission mutation is authorized by this feature.

Future tests should target the cross-organization reversed-account lock case, revoked grant lock waiter, expiry after waits, duplicate legacy grants, exact historical replay after mutable target changes, actor/account/op/key collisions, scoped B recovery if selected, rollback and denied-response privacy. These are prospective checks, not executed results. Baseline168/402 counts belong to prior delivery evidence and confer no acceptance on a future writer.

**Handoff:** present A/B and lifetime/risk bounds for explicit user decision now; incorporate the contract corrections before implementation. No runtime acceptance, O03 closure, production readiness, merge, writer activation or tests passing is claimed by this review.

## Follow-up dispositions — latest candidate A proposal

Re-read the corrected persistence/locking section and the appended **Routine protocol details resolved for candidate A**, including its explicit precedence over earlier less-specific descriptions. The findings above record the initial review; the dispositions below supersede their open status for this proposal review.

| Finding | Disposition | Verified correction |
| --- | --- | --- |
| 1 — global account lock order | Closed for reviewable A proposal | Organization lock and scope proof precede deduplicated UUID-sorted actor/recipient account locks, followed by deterministic memberships and post-wait freshness checks. Deduplication covers self-revoke. An actor-only receipt path must not transition into a new two-account write path. Actual SQL acquisition order remains an implementation verification gate. |
| 2 — exact receipt binding and replay | Closed for reviewable A proposal | Shared immutable POST/GET allowlist now includes actorId, actorName snapshot and idempotencyKey. GET requires operation and returns null for wrong account/operation. Exact normalized POST replay precedes mutable target/revision checks, while current actor authority remains mandatory. B is explicitly excluded pending its own complete issuer/revoker contract. |
| 3 — duplicate legacy grant revocation | Closed for reviewable A proposal | Explicit grant-ID-only effect, refreshed same-scope recorded counts, no promise to remove all approval rights, and preservation of any-qualifying-grant decision semantics. Requester ANSWER remains independently authorized. |
| 4 — revision/time/canonicalization/wire | Closed for reviewable A proposal | Revision0 read semantics, atomic initial ledger creation, bounded increment/overflow, post-lock microsecond time, equality expiry, legacy duplicate detection, canonical command/grant fingerprints, explicit Java strip/code-point/control rules and strict JSON types/allowlists are now specified. BE/FE conformance oracles and final schema/DTO review remain implementation gates. |
| 5 — privacy/provenance/uncertainty | Closed for reviewable A proposal | Historical scope proof precedes recipient lookup; email is unnecessary. Restricted ADMIN-only scoped history has a defined allowlist; legacy provenance remains unavailable. Frozen retry material is erased on denial, prior-account correlation is erased on account change, and reload/key-loss limits plus null-receipt uncertainty are explicit. Existing membership re-enable behavior is disclosed. |

**Current disposition: conditionally ready for the A/B policy decision and a documentation-only draft PR. No remaining concrete blocker to presenting candidate A as a reviewable proposal was found in this targeted reread.** Final context DTOs, additive schema, canonicalization conformance and concurrency evidence remain implementation gates, not claims of delivered behavior. For B, provisioning/recovery, issuer expiry containment and scoped revocation/recovery authority still require a complete contract before implementation.

Authority A/B, maximum7-day lifetime and HIGH new-risk ceiling remain **OPEN / PROPOSED** for explicit user acceptance or revision. This closure does not select issuer policy or accept runtime behavior; it does not reopen the user’s existing authorization for implementation work. No app code or tests were changed or run; no runtime acceptance is granted. Publication/PR management remains with the main author; this reviewer changed only this review file.


## Final terminology delta and review source freeze

Targeted terminology review complete. All five findings remain closed for the reviewable candidate A proposal; the two-Markdown-file proposal package is source-review ready for the main author’s draft PR. A/B issuer selection and proposed7-day/HIGH bounds are still business decisions. B remains an open alternative whose separate provisioning/lifecycle contract is not yet fleshed out; that does not block presenting candidate A.

The user has authorized implementation work. Pending policy selection must not be described as missing task permission or as requiring renewed approval for routine engineering. Final schema/DTO conformance and runtime verification remain engineering delivery gates. This review records **issuer policy not selected; not runtime acceptance**.

Reviewer source freeze requested after this delta: no further reviewer edits are planned absent a material proposal change or a specific review request. Main retains ownership of proposal/PR publication. Only this review file was edited; no application changes or tests.
