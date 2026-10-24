# Member role management — prospective acceptance matrix

PROSPECTIVE / NOT RUN. Conditional on user choosing candidate A in MEMBER_ROLE_MANAGEMENT_PROPOSAL.md. These are planned oracles, not implemented tests or passed cases. Preserve the verified PR23 baseline176 backend/435 browser instances; never count that baseline as verification of role editing. Choice B means no writer is implemented.

Disposable data: two organizations; enabled actor Admins and Members, shared global accounts in both organizations, disabled/removed/foreign targets, no-history legacy membership, retained role events, matching explicit approval grant and valid sealed preview request. No production seed. Native/browser fixtures must be isolated from each other and existing suites.

| ID | Planned scenario | Required observable result | Evidence lane |
| --- | --- | --- | --- |
| MR01 | Enabled current ADMIN vs MEMBER/disabled/stale credential/absent membership reads | Only fresh ADMIN reads context/history/receipt; no target/profile/reason disclosure to others | BE, native, browser |
| MR02 | Missing/foreign organization, target and retained event; same UUID across scopes | Authorization before scope disclosure; scoped404 after ADMIN proof; never fetch foreign profile | BE, native |
| MR03 | Own role, with one or several enabled administrators | Both self-promotion/self-demotion blocked; context blockers coherent; no ledger/event/receipt/audit role write | BE, browser |
| MR04 | Disabled/removed current target, invalid role, no-op | Role write denied; disabled remains inspectable via current inventory; no enable/join/history fabrication | BE, native, browser |
| MR05 | MEMBER→ADMIN of another enabled current member | One persisted membership change, role revision+1, restricted event, minimal receipt, safe audit; no grant creation/runtime change | BE, native, browser |
| MR06 | ADMIN→MEMBER of another enabled current member | Administration denied on later fresh checks; explicit eligible approval grant remains usable; no session/credential/other-org mutation | BE, native, browser |
| MR07 | Last usable Admin and disabled Admins in count | No self change; enabled locked actor witnesses surviving Admin; disabled rows never satisfy usability | BE, native |
| MR08 | Stale expected role/revision, MEMBER→ADMIN→MEMBER ABA, exhaustion |409 with no partial row/revision creation; ABA cannot satisfy an old supported-writer command; no overflow | BE, native |
| MR09 | Canonical UTF-8 vectors, supplementary Unicode, Java strip, NBSP, combining forms and controls | Exact documented hashes;1..1000 code points; no NFC conversion; allowed TAB/LF/CR only among controls | Production validator, BE |
| MR10 | Duplicate/unknown/missing/null/coerced fields, invalid UUID/key/role/ack and missing CSRF | Exact safe400/403 behavior; no private input echoed; no mutation | MockMvc, native |
| MR11 | Exact historical retry after target role change/removal/disable | Fresh issuer ADMIN required; same original immutable result200 before mutable target checks, no second event/revision | BE, native |
| MR12 | Key reuse across target/body and wrong actor/org; grant-vs-role namespaces | Role-key collision409, scoped GET null where appropriate, no cross-target/event disclosure; namespace separation explicit | BE, native |
| MR13 | Issuer demoted/reset while waiting; restore current ADMIN later | Post-wait fresh denial; later authorized exact GET recovery only after private body erasure; no implicit replacement POST | BE, native, browser |
| MR14 | Current legacy member vs removed member with retained events vs foreign/missing account history | Empty provenance honestly unavailable; retained scoped history readable only by current ADMIN; otherwise404; stable page/count/order | BE, native, browser |
| MR15 | Two Admins simultaneously demote each other through this writer | Serialized winner commits; loser reauthorizes to403; at least one enabled Admin; no deadlock/partial change | Native observed barriers |
| MR16 | Reversed actor/target global accounts across organizations; global reset/login wait | Sorted deduplicated global lock order observed, no actor-first inversion; fresh credentials after waits | Native observed barriers |
| MR17 | Administrative writer already waiting behind demotion | Observe org/account wait before release; later authorization denies writer and business state remains unchanged | Native observed barriers |
| MR18 | Inject final audit failure and competing revision write | Membership/ledger/event/receipt/audit roll back together; whole relevant business snapshot unchanged; no false success | BE, native |
| MR19 | Actual edit→fresh review→ack→save→reload, before/after/grant warning | Blank new-role selection, exact transition, ack reset on edits/refresh, POST only final save, historical result separate from current inventory | Browser all3widths |
| MR20 | Dirty Back/cancel/unload, reused targets, obsolete route/workspace/history reply | Correct target/state after accepted navigation; cancellation preserves draft; no stale private result and no unintended POST | Browser all3widths |
| MR21 | Real commit with lost/malformed response; exact retry; null receipt with advanced role state | Unknown never inferred as failure/success; original key/body only, one event, strict result correlation; null oracle remains until replay observation | Browser all3widths |
| MR22 | Same-ID demotion, focus/visibility check, shared-session new actor, late read denial during dispatched POST | Private DOM gated, observed denial clears profile/reason/result/body; opaque same-actor GET recovery retained when needed; actor change drops all correlation | Browser all3widths |
| MR23 | Responsive keyboard/form/history/error/unknown/recorded states | Label/focus/action semantics, dark readable text, wrapping IDs, no horizontal overflow at1440/768/390; inspect synthetic screenshots | Browser + visual review |
| MR24 | Full suite, migration, restart/logout, exact candidate binding | Quality/Docker/native/all-browser gates; receipt/session durability where applicable; report exact commit/tree/merge/job/artifact digest | CI + independent QC |

Execution ledger: NOT RUN. New case counts depend on the approved implementation and are not inferred from these24 planned rows. Sonar paused. No production-scale, full accessibility, merge or Mac deployment acceptance is implied.
