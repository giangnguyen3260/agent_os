# Approval information exchange — prospective independent QC inventory

2026-10-23; contract: `docs/engineering/APPROVAL_INFORMATION_CONTRACT.md`.

**All cases below are prospective / NOT RUN by this reviewer.** This is a review inventory, not an executable suite or a pass count. Source review findings are in `APPROVAL_INFORMATION_REVIEW.md`. Coordinator/worker evidence must separately name the tested commit, test/probe IDs, CI job and exact result. Inherited PR20 results do not verify these changes.

Use disposable sealed supported PUBLISH_PREVIEW fixtures: enabled ADMIN and MEMBER approvers with explicit matching grants; exact human requester MEMBER without approver grant; unrelated same-org human/Agent; second org; legacy metadata-only rows. Persisted assertions compare normalized command, immutable thread, status/revision, receipts, audit and all unrelated business rows. No real private data in fixtures/screenshots.

| Case | Scenario | Required oracle |
| --- | --- | --- |
| AI-01 | ADMIN with scoped grant asks from PENDING revision0 | 201, NEEDS_INFORMATION revision1, one QUESTION/event/receipt, exact actor, blank-start UI; sealed payload unchanged |
| AI-02 | MEMBER with scoped grant asks | Same actual native API behavior as ADMIN; no role-derived decision privilege |
| AI-03 | Exact human requester without grant answers question | 201, PENDING revision2, one ANSWER with optional evidence; grant not required |
| AI-04 | Multiple sequential ask/reply rounds and reload | Immutable ordered complete thread, captured actor names, latestQuestionId semantics, monotonic revision |
| AI-05 | Wrong ADMIN / granted approver / same Agent but wrong human replies | 403, zero thread/status/revision/receipt/event changes |
| AI-06 | Requester tries own ask or approve, including owning a grant | Denied; reply permission never confers selfdecision |
| AI-07 | Ask with missing/wrong Team/action/risk grant | 403/no effects; ADMIN title cannot substitute |
| AI-08 | Grant revoked or expired before ask and before exact ask replay | Denied fresh authority; existing immutable history remains intact |
| AI-09 | Revoke grant while ask blocks on its locked grant row | Observe PostgreSQL blocking relationship, commit revocation, request403/no effects |
| AI-10 | Requester removed membership, disabled account or stale credential version | Reply and exact replay denied; no second account lock required |
| AI-11 | Revoke membership while reply is blocked | Observable PostgreSQL lock wait; fresh authority rechecked, no unauthorized commit |
| AI-12 | Foreign org and foreign approval under own org | Org authorization403; authorized own-org foreign ID404; no private thread leakage |
| AI-13 | Missing requester / foreign-only human requester | Reply denied; no Agent/role fallback; ask fails closed consistent contract |
| AI-14 | Legacy NEEDS_INFORMATION with no persisted question | GET canReply false; arbitrary questionId/revision cannot manufacture answer |
| AI-15 | Expired/corrupt/invalidated/unsupported snapshot, changed/inactive head | New ask/reply denied; no sealed-payload changes |
| AI-16 | Inactive parent / requester Agent moved Team | New operations fail closed; no Task/Mission/Agent mutation |
| AI-17 | Identical ask replay after transition and later reply | 200 exact immutable original result despite new state/revision; no duplicate audit/entry |
| AI-18 | Identical reply replay after subsequent ask | 200 original result while exact requester still authorized; no new answer |
| AI-19 | Same actor/org/key reused across ask/reply, approval or changed body/revision/question | Information-operation collisions409; decision-key namespace explicitly follows documented boundary; no accidental second write |
| AI-20 | Different actor or org uses same UUID key | Proper independent namespace plus own authority; never receives another actor receipt |
| AI-21 | Simultaneous duplicate ask/reply same key | One201/one200 identical data, one effect; native PostgreSQL barrier |
| AI-22 | Simultaneous different-key replies to same question | One201/one409; one ANSWER/event/revision transition |
| AI-23 | Simultaneous ask and approve from same revision | One serialized winner; loser409, never NEEDS_INFORMATION plus committed decision |
| AI-24 | Simultaneous reply and decision while NEEDS_INFORMATION | No decision before valid reply; old revision cannot decide after reply; explicit locked-state oracle |
| AI-25 | Old approval AND rejection command after ask/reply | Revision0 versus current2 ->409 even unchanged snapshot; fresh ack/revision2 required |
| AI-26 | Legacy omission of informationRevision | Allowed only current0; null/string/fraction/negative/unsafe integer400; omission cannot bypass current2 |
| AI-27 | Previously committed decision replay after context change | Fresh grant required; exact old fingerprint replay200 before mutable revision checks; incompatible key/body409 |
| AI-28 | Blank/oversize question, answer, evidence; invalid UUID/key/CSRF | Strict400/403 as specified, no coercion, duplicate/unknown/null-type cases; no effects |
| AI-29 | Normalization boundaries / Unicode whitespace / malicious HTML and URL text | Consistent documented normalization/fingerprint, literal safe rendering, no fetch/execution, no raw text in audit/error output |
| AI-30 |20th round and concurrent cap |38 entries ask ->39, outstanding reply ->40;21st ask denied; never stranded final question or truncated history |
| AI-31 | Audit insertion failure during ask and reply | Transaction rolls back entry/status/revision/receipt; business rows unchanged |
| AI-32 | Thread GET / repeated GET / no grant reader | Fresh same-org read; no writes, bounded complete history; safe non-actionable eligibility |
| AI-33 | MEMBER exact routes versus sibling writes | request-information/reply transport allowed only for exact routes; service authority/CSRF retained, sibling writes denied |
| AI-34 | FE question cancel/reopen and requester answer controls | Blank forms, exact question visible, only authoritative requester can submit answer; approver sees waiting thread |
| AI-35 | FE stale/mismatched detail and information responses | Disable mutations and reset ack until revisions match; malformed/wrong-ID/extra-field/invalid-order thread fails closed |
| AI-36 | Reply refresh after prior scope acknowledgement | Ack reset; fresh scope review required; old frozen decision cannot be rewritten/retried with new revision |
| AI-37 | Accepted ask/reply then lost/malformed response | Unknown warning; frozen exact operation/key/body, no automatic retry or replacement command; actual POST must commit before fault injection |
| AI-38 | Exact unknown-outcome retry | Same org/approval/account/op/body/key; native200 receipt, single effect; repeated-round text cannot falsely reconcile another entry |
| AI-39 | Unknown outcome then POST403/409 or workspace/detail/thread GET denial | Warning preserved; denied private question/answer/evidence cleared, retry suppressed; restored access reconciles immutable history without false exact-success claim |
| AI-40 | Dirty/in-flight navigation, reused route, logout/account switch, delayed callbacks | Guard cancel preserves context; accepted navigation invalidates late responses; no cross-account/route rendering or submission, beforeunload behavior |
| AI-41 |390/768/1440 real browser lifecycle and long thread | Readable dark text, wrapped long evidence/hash,44px controls, no horizontal overflow; captures question/waiting/reply/history/error states |
| AI-42 | Docker persistence/restart plus complete regression | Native PostgreSQL migrations/probes; real ask/reply survives restart; logout enforced; existing142BE/354browser regression retained, Google style/npm audit0; Sonar paused |

## Evidence ledger

No execution evidence for this branch has been reviewed. Populate only after inspecting actual worker/coordinator logs. Keep unit/H2, native PostgreSQL, browser, visual and source-review evidence distinct. Any case lacking an explicit matching assertion remains unverified even if aggregate CI is green.

## Receipt-recovery amendment (prospective, NOT RUN)

AI-38/39 additionally require GET `/information/receipts/{key}` checks: same exact actor/org/approval path only; unknown key ->null; wrong actor/key/approval never returns another receipt; QUESTION receipt requires original scoped grant and ANSWER receipt exact requester plus fresh membership/account. A null receipt after another actor completes a later round must retain unknown and prevent a replacement submission. Reject malformed receipt, wrong revision/entry/actor and thread-detail mismatch without dropping correlation. Receipt403/401/404 clears private input and preserves unknown; errors/timeouts retain warning. Only exact receipt plus matching immutable thread entry resolves. Include legacy decision revision0 receipt replay and omitted revision after round2 ->409. These amend existing inventory entries; no increase to execution/pass counts is implied.

## Browser source mapping (unexecuted)

New `approval-information.spec.mjs` AI-B01..B08 provides intended source coverage for persisted ask/reply/decision (01/03/04/34/36), MEMBER/exact requester (02/05), exact POST replay (21/37/38), null receipt versus revision growth (38), workspace denial cleanup/recovery (39), malformed GET (35), dirty reused-route/menu (40), and stale acknowledgement409 (25). All remain NOT RUN by this reviewer. Responsive requester captures need an explicitly inherited viewport before AI-41 acceptance; current browser helper creates a separate default context. Direct-detail revision/conversation affordance assertions are also pending. See AI-BR01/02 in the review.

Source correction confirmed before CI: requester context now inherits explicit viewport with innerWidth assertion and populated reply overflow check; Detail supplemental revision/text/link assertions added to I01/I08. BR01/BR02 source gaps are closed. These test definitions still have no execution evidence reviewed here.
