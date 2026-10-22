# Approval Detail / decision independent QC cases

Prepared 2026-10-22 from `docs/engineering/APPROVAL_DETAIL_CONTRACT.md` and current schema, SecurityConfig, authentication, Approval Center service/controller/validator, and prior Center review. This is a prospective acceptance inventory: **none of these cases has been executed by this reviewer**. Final source findings and actual execution provenance belong in `APPROVAL_DETAIL_REVIEW.md` once implementation exists.

## Immediate implementation blockers / handoff

1. **P0 transport boundary:** current SecurityConfig permits MEMBER reads but requires ADMIN for every organization write. Permit MEMBER only for POST `/api/v1/organizations/{org}/approvals/{id}/approve` and `/reject`, with exact path segmentation and live membership. Do not relax sibling writes, extra suffixes, other methods, or introduce title-based service permission. ADMIN without a matching explicit grant must fail too.
2. **P0 authority model:** V010 is metadata only. V011 must establish explicit scoped grants, sealed snapshots, scoped current heads, human requester identity, immutable decisions and actor/org receipts. No production seed or grant-management endpoint. Preserve existing migration bytes. Agent identity is not sufficient to establish a different requesting human.
3. **P0 transaction/replay:** a cached receipt cannot bypass fresh account/version, membership or grant checks. Lock account/membership, head, request and matching grant consistently; check wall-clock expiry after waits. Replay must return its original immutable result after fresh permission checks even if the head later expires. Do not reuse all first-decision blockers blindly on replay.
4. **P0 integrity/races:** stored hash equality alone is not sealing. Recompute canonical bytes from all authorization-relevant snapshot data; compare current scoped head and request binding. Serialize opposite decisions and revocation with real SQL locks. An atomic transaction must include status, decision, safe event and receipt, with zero Task/Mission/runtime effects.
5. **P1 contract precision to freeze during review:** enumerate effective blockers and inactive states; define deterministic canonical serialization including nulls, timestamps, Unicode and numeric/cost representation; retain exact HTTP/error assertions for malformed versus stale snapshots. Contract explicitly fixes malformed=400, missing permission=403, stale/conflict=409 and first/replay=201/200. Do not infer unspecified individual blocker status mappings as executed failures.

These are required implementation gates, not claims that the existing read-only Center violated its previous scope. No final candidate is reviewed yet.

## Fixture and universal oracles

Use isolated CI/test fixtures, never production seed. Two real organizations O1/O2; two Teams T1/T2 in O1 and T3 in O2; real Mission/Task/Agent references in each; ADMIN A, MEMBER M, requester human R, foreign human F, and an explicitly disabled/version-stale account. A and M differ from R. Create an eligible PENDING request P with safe sealed snapshot S1, matching current head H1 and exact unrevoked/unexpired grant G for M. Vary one field per negative case; preserve an eligible control. Use fresh request/key for independent first-decision cases. Include every risk level.

Before/after compare exact rows, IDs and counts for approval requests, snapshots, heads, decisions, receipts and decision audit events, plus complete affected Task/Mission/Agent/runtime/outbox tables. Session/authentication infrastructure may change normally and is not a business decision effect. GET never changes approval status. Denials and failed transactions create no decision/event/receipt and do not change business state. Success changes only the selected approval status/timestamp and inserts exactly one decision, safe event and receipt. Exact retries insert nothing and preserve original decision fields. No provider/tool/executor calls or execution token.

## API / authorization cases

| ID | Setup and operation | Required result |
|---|---|---|
| AD01 | GET P as live A then M | 200; explicit nullable keys; safe request/snapshot/eligibility/decision shape; executionAvailable=false; no writes. |
| AD02 | GET metadata-only legacy P; legacy APPROVED with no decision | Readable; snapshot/decision null as applicable; no decision eligibility or execution authority. |
| AD03 | Authorized O1 reader GET missing ID then actual O2 approval under O1; F accesses O1 | First two 404 without foreign data; foreign organization access 403 after authentication. |
| AD04 | Missing session, disabled account, rotated credential version, deleted membership, unsupported role | No disclosure/decision; fresh identity checks, not session role cache. Unauthenticated 401; authenticated missing permission 403. |
| AD05 | M with exact G approves P with ack=true and rejects fresh P with nonblank reason | Both traverse transport; 201 immutable result; no ADMIN dependency. |
| AD06 | A without G; M without G; grants differing only in org, Team, action type, risk or grantee; expired/revoked grants | All decisions denied 403; title does not supply a grant; no writes. |
| AD07 | M POST sibling Team/Agent/Task write, approvals collection, extra path suffix, trailing/encoded segment variants; PUT/PATCH/DELETE decision paths | No widening of MEMBER write authority; no decision or unrelated mutation. Valid exact routes remain usable. |
| AD08 | Valid session/body/key with absent, wrong or another-session CSRF; omit/malformed/non-UUID idempotency key | CSRF 403; malformed/missing required key 400; no writes. |
| AD09 | R has exact grant and attempts approve/reject at LOW/MEDIUM/HIGH/CRITICAL; omit human requester | Deny every self-decision and unknown requester; Agent ID/name never substitutes for human identity. |
| AD10 | PENDING vs NEEDS_INFORMATION and every terminal status, with/without legacy decision | Only eligible PENDING first decisions; no fabricated transition from GET or legacy approved status. |

## Snapshot / validation cases

| ID | Setup and operation | Required result |
|---|---|---|
| AD11 | Missing snapshot, unsealed/invalid/unbound snapshot; absent or stale head | GET remains safe and blocked; first decision denied with no writes. |
| AD12 | Submit previous version/hash; same version wrong digest; different request's valid digest; same action UUID in foreign scope | Stale/conflict 409 where applicable; cannot authorize another request/action/scope. |
| AD13 | Mutate each stored canonical field separately while retaining digest: requester human, org/Team/Mission/Task/Agent binding, action ID/type/risk, head revision/digest, target/scope/effect, cost/currency, expiry | Recomputed hash or binding check fails; GET cannot present eligible authority; decision denied. Cover every field actually introduced by schema. |
| AD14 | Advance head revision/digest without changing old snapshot; rebind request Team/action/risk; move requester Agent to T2 | Old request blocked even if client body and stored digest still match. |
| AD15 | Expire snapshot/head/grant at boundary and while POST waits on a held lock | Check real current time after lock; equality to expiry is expired. Transaction-start `current_timestamp` must not extend validity across waits. |
| AD16 | Archive/inactivate org, Team, Mission, Task, Agent or action as applicable | First decision blocked consistently with declared lifecycle rules; no task resurrection. Exercise null optional parents separately. |
| AD17 | version 0/negative/fraction/string/null/overflow; hash uppercase/wrong length/nonhex/null; ack false/missing/string/null; reject null/empty/whitespace reason; reason 2000/2001 | Strict typed request; malformed 400. Approve requires true; reject requires nonblank; length boundary enforced without coercion. Reject acknowledgement follows explicit contract rather than inventing approve-only rules. |
| AD18 | Strings with quotes, newlines, backslashes, Unicode, delimiter-like text; reordered JSON keys; optional nulls and stripped reason | Deterministic canonical snapshot/fingerprint; distinct meaningful bodies never collide through concatenation; equivalent stripped reasons replay. Capture canonical test vectors. |
| AD19 | Safe display fields contain HTML/script markup; malicious payload/raw provider/secret fields submitted to public routes | Text escaped in FE; no public snapshot/grant writer or raw-secret acceptance. Responses/errors/audit never echo test secret markers. Hashing is not treated as encryption. |

## Idempotency / atomicity / PostgreSQL races

Run race cases against native PostgreSQL with independent connections and observable lock barriers. Do not use only H2 or timing sleeps. Record SQL lock holder/waiter, commit order, HTTP outcomes and exact persisted rows.

| ID | Setup and operation | Required result |
|---|---|---|
| AD20 | Same actor/org/key/body replay after first success, process restart and session renewal with live authority | 201 then 200; same immutable ID/status/actor/reason/version/hash/time; exactly one decision/event/receipt. |
| AD21 | Reuse key changing one of approval ID, operation, version, digest, acknowledgement or stripped reason | 409 for conflicting canonical fingerprint; no second effect. Include approve vs reject and cross-request same-key cases. |
| AD22 | Different actor uses same UUID key with own grant and different request; same actor/key in another authorized org | Namespaces independent; never return another actor/org's receipt or decision. |
| AD23 | After success revoke G, expire G, disable account, rotate credentials or remove membership, then identical replay | Fresh authorization denies replay; no immutable result leaked via receipt shortcut. No new writes. Test each revocation independently. |
| AD24 | After success let snapshot/head expire or advance head; keep fresh identity/membership/exact grant | Identical authorized replay 200 original result; no new evaluation/decision or execution. Different-body request remains conflict. |
| AD25 | Two concurrent approves with same key/body; then with different keys | Same-key first/replay converge; different-key loser 409. One decision/event/receipt for the accepted operation, never duplicate winner rows. |
| AD26 | Concurrent approve vs reject with different keys, both directions winning under controlled barriers | One 201 and one conflict; status agrees with sole decision; no rejected loser receipt/event. No partial record or deadlock leak. |
| AD27 | Revoker locks grant/account/membership, updates then commits while POST waits; repeat with rollback | Committed revocation wins and denies waiting POST; rollback permits only if otherwise eligible and unexpired. Prove POST actually waited. |
| AD28 | POST locks authority first, concurrent revoker waits; release decision transaction then revoker | Serial order demonstrable; decision commits before revocation; subsequent identical replay denies. No claim that later revocation retroactively cancels recorded decision. |
| AD29 | Head replacement/action invalidation races with decision, both lock orders; requester movement/parent invalidation race where supported | First decision never validates a mixed head/scope view; document all locked dependency rows and writer lock compatibility. Check consistent lock order with auth/recovery and both decision operations. |
| AD30 | Inject database failure after status write, decision insert, event insert, receipt insert, and force uniqueness failure | Transaction rollback restores all business rows; no partial receipt/status/event. Retry can subsequently create exactly one complete result. |
| AD31 | Try altering/deleting snapshots/decisions through all public routes; re-decide after success; inspect DB constraints and application SQL | No mutation API; immutable records preserved. Direct SQL mutation tests distinguish any actual DB enforcement from API-only immutability. Do not claim trigger enforcement unless present. |
| AD32 | Successful approve/reject, failed decision and replay with linked real Task/Mission/runtime rows | Whole-row before/after confirms zero execution/task/mission effects; audit contains safe IDs/status only, no reason or snapshot payload. |

## Frontend / browser cases

Run real browser at 1440, 768 and 390 widths. Intercepted errors validate UI behavior only; pair permission/race assertions with real backend evidence.

| ID | Steps | Required result |
|---|---|---|
| AD33 | Open detail from actual Center row; direct URL; back to Center | Correct org/approval route and real data; no hardcoded record or simulated grant. |
| AD34 | Verify target/scope; cancel confirmation; refresh detail | Verification performs no write; acknowledge resets on refresh/version change; approve requires loaded exact snapshot. |
| AD35 | Open reject, cancel/reopen, blank/whitespace/2001-char reason, valid reject | Blank initial reason; required validation; no stale prior reason; immutable success read-only. |
| AD36 | Double click submit; delay/drop response after backend commit; retry | One frozen body/key; retry identical method/path/body/key; no automatic replacement POST, edits or operation switching while uncertain. Reconcile via detail refresh safely. |
| AD37 | Navigate/back/close dialog/change scope during in-flight or uncertain outcome | Ordinary navigation warns before discarding ambiguity; no silent replacement key or re-submission. Inspect account/logout handling separately for prompt clearing of private data. |
| AD38 | Delay old GET, change route/account/org, resolve newer GET then old; fail auth/workspace/detail or stall each stage | Independent epochs and bounded waits; old content cannot repopulate; clear private fields/decision/reason on failure or context change; useful recovery. |
| AD39 | Return foreign approval ID/org, mismatched decision approval/version/hash/status, missing nullable keys, extra keys, wrong types, malformed expiry or canApprove/blockers contradiction | Strict validators fail closed; no active decision controls or foreign/private stale display. Test POST result validation too. |
| AD40 | Revoke grant after GET; expire snapshot while page open; head advances before POST | Backend denial safely shown; refresh clears acknowledgement; eligibility UI never acts as authority. No fake task resumed message. |
| AD41 | Completed/legacy-approved/blocked/metadata-only details and replay reconciliation | Read-only immutable decision where present; reasons treated as private decision detail; executionAvailable=false throughout; unavailable info/reply/grant/execute flows never send writes. |
| AD42 | Keyboard menu Escape/Tab/focus return, dialog errors, long target/scope/reason, all three widths | No overflow; native usable controls; readable dark text; visible pending/errors; screenshots include confirmation, rejection and completed states. No localStorage/cached authorization. |

## Evidence required before final disposition

- Candidate commit plus reviewed working-tree file hashes/diff; schema/security/backend/FE/probe source coverage and precise remaining defects.
- Actual Quality and full Docker regression run links and candidate identity; PostgreSQL race/revocation/rollback transcripts and exact before/after oracles; browser result counts and three-width screenshots. Existing Center passes do not establish this slice.
- Label each result as reviewer-executed, independently inspected execution artifact, coordinator-reported, source-only or pending. No tests, probes, builds, browser checks, installs, commits or publishing were performed in preparing this inventory. Sonar remains paused, not passed.

Only this QC case file is owned/modified in this initial pass. Backend and FE implementation remain with their assigned owners. Final actual-code review remains pending delivery of the candidate; no approval of unwritten implementation is implied.
