# Approval grant management — prospective cases

Authority: accepted option A contract, not historical proposal. **All rows below are prospective until backed by an execution record.** Target: 10 independent browser cases × desktop/tablet/mobile = 30 cases, without removing or weakening the inherited 402 browser / 168 backend baseline.

## Browser matrix

| ID | Scenario and independent oracle |
| --- | --- |
| AGM-B01 | Blank native Team/risk/duration/reason; no mutation before final save. Search actual paginated Team directory; archived disabled. Select LOW and 1 hour; selected-scope review GET supplies current revision. Acknowledge, POST actual backend, compare all persisted result IDs/time/actor/key/revision to scoped inventory and history. Reload; revoke same ID with fresh fingerprint/revision/reason/ack; verify exact revokedAt and both history entries. Capture edit/review/issued/revoke/revoked. |
| AGM-B02 | HIGH and 168 hours accepted; check server recordedAt-to-expiry delta. Blank/0/169/fractional duration, invalid reason controls and >1000 code points cannot save; internal tab/LF/CR and Java-strip boundary vectors preserve exact accepted reason. Native labels and keyboard focus; no overflow. |
| AGM-B03 | Legacy overlapping exact-scope grants: preflight counts OTHER live rows, legacy history has no invented actor/reason; revoke only chosen grant, remaining row still unrevoked. Disabled/removed/archived/unsupported legacy contexts stay scoped and revocable. |
| AGM-B04 | Real second operation advances revision after review; original new-key command returns 409 without new event. Real live duplicate blocks selected scope. Foreign recipient/Team/grant returns scoped denial without private rendering; self and disabled issue blocked. Re-review invalidates acknowledgement. |
| AGM-B05 | Dirty form before Back, SPA route reuse, route recipient change and unload: register dialog handlers BEFORE action, dismiss retains draft; accepting navigation clears old reason/body/private DTO. Return/reused route is blank. No unintended POST. |
| AGM-B06 | Backend commits ISSUE, original browser response lost. Assert unknown, exact receipt key/operation, no replacement command. Manual retry sends identical serialized body and Idempotency-Key; real replay 200 yields same event/grant/revision, exactly one history event and one matching grant. Capture unknown. |
| AGM-B07 | Backend commits REVOKE, reply malformed/lost. Force receipt transport error/null only after real POST has committed; independently advance state using a real write. Null receipt plus later inventory/revision remains unknown; retry exact original POST recovers historical revoke, never infers from current state. |
| AGM-B08 | Commit then lose reply; revoke issuer role in disposable database. Recovery/identical retry denied: erase reason/private snapshots/body, retain only opaque same-account correlation and uncertainty warning. Restore ADMIN; GET exact receipt recovers without POST; verify denial never claimed original failed. Capture denied. |
| AGM-B09 | Hold a real backend GET/POST response; switch authenticated account using shared-session logout/login. Release response and assert old recipient/reason/result/correlation cannot render or recover. Independent context retains inherited viewport and does not share session. No browser-storage recovery material. |
| AGM-B10 | Wrong-scope/extra-field/malformed result/receipt/history DTO rejected before private render. Exercise late response across reused route and Back, actual backend read after removing injection, explicit loading/error/retry/empty states and acknowledgement reset. No mocked business success. |

## Test harness requirements

Dedicated disposable fixture owned by BE worker Hegel `01a16922-81c8-7475-9641-3a91031740b3`; do not mutate baseline fixture organizations. Await actual IDs/accounts and per-width allocation rather than inventing provenance or silently skipping. Reset command must be recorded with execution. UI labels will follow actual normative Vietnamese native labels; select by explicit aria-label. Every independent browser context explicitly inherits the project viewport. Install native dialog handlers before navigation/action.

Synthetic screenshots: `qa-visuals/approval-grant-management-{desktop|tablet|mobile}-{edit|review|issued|revoke|revoked|unknown|denied}.png`. No screenshots/traces/auth storage from real accounts. Error injection may corrupt reads or deny transport; ambiguous write injection MUST call original `route.fetch()` and verify backend success before dropping/mangling the reply. No success fulfill for an unexecuted command.

## Backend/native review matrix (BE-owned execution)

Strict wire/authorization/CSRF; no self issue; DRAFT/ACTIVE/HIGH/168; disabled/removed/archived/foreign contexts; legacy duplicates; reason/code-point/control/normalization vectors; zero-ledger read and invalid-write noncreation; expiry equality/post-lock time/microsecond hash vectors; revision overflow; cross-target/operation key collisions; replay after removal/expiry/revoke; scoped paginated history; generic audit privacy; transaction rollback including audit failure; concurrent exact-scope issue; cross-org reversed actor/recipient lock ordering; observed post-revoke waiter blocks grant-dependent question/decision while requester answer remains separate; native restart receipt persistence; no Task/Mission/provider/worker effects.

## Execution ledger

Pending source/fixture. No executed passes, screenshots, CI results or final acceptance yet. Record command, UTC time, candidate hash, fixture version/reset, environment, per-project counts, failures and artifact/log locations here after execution. Inherited counts must never be presented as verification of these new cases.

### AGM-B11 — dispatched revoke plus late history denial (all three widths)

Use isolated viewport target+11. Create a real grant, hold the real committed201 revoke reply, then demote the issuer and release the in-flight history request to obtain a real403. Require private-data erasure, unknown state and no POST retry. Restore ADMIN and recover only the exact operation/key receipt by GET; late original POST response must not change the recovered result. Role and barrier cleanup are mandatory. Suite now collects435 instances, including33 management cases. Collection is not execution.

## Final execution

All11 cases passed on desktop1440×1000,tablet768×1024,mobile390×844 (33 management instances); entire suite435 passed with no failures/skips on candidate838b1d9e091cd985e6937280e03e8be3cd5ef767. Isolated CI fixtures were freshly provisioned before native/browser execution. Docker run40007019847/job120597403681 completed browser execution2026-10-23T19:12:43Z and restart/logout2026-10-23T19:13:23Z. Quality run40007020133/job120597404879 passed176 backend tests and FE checks. See APPROVAL_GRANT_MANAGEMENT_QC_REPORT.md for source/merge/artifact binding, review and limitations. Earlier pending notes are historical.
