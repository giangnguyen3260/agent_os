# Member role management — acceptance matrix

VERIFIED FOR BOUNDED DEVELOPMENT SCOPE — candidate81bc489 passed192BE/471browser, native PostgreSQL, Docker/restart and21-capture visual review. See [final QC](MEMBER_ROLE_MANAGEMENT_QC_REPORT.md). Candidate A is active under docs/engineering/MEMBER_ROLE_MANAGEMENT_CONTRACT.md following the authorized continuation. The24 rows below map acceptance oracles to their evidence lanes; counts are test instances, not24 independently executed cases. Twelve real-backend browser cases passed at three widths. Preserve the verified PR23 baseline176 backend/435 browser instances; never count that baseline as verification of role editing.

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
| MR09 | Canonical UTF-8 vectors, supplementary Unicode, Java strip, NBSP, combining forms and controls | Exact documented hashes;1..1000 code points; reject unpaired UTF-16 surrogates before hashing; valid paired emoji and combining forms unchanged; no NFC conversion; allowed TAB/LF/CR only among controls | Production validator, BE |
| MR10 | Duplicate/unknown/missing/null/coerced fields, invalid UUID/key/role/ack and missing CSRF | Controller syntax400 after transport authorization; malformed organization denied403 by outer guard; anonymous401 / CSRF403 precede controller; no private input echoed or mutation | MockMvc, native |
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

Execution ledger:471 browser instances passed, including36 role instances;192 backend tests and native/restart gates passed. New case counts are measured independently from these24 acceptance rows. Sonar paused. No production-scale, full accessibility, merge or Mac deployment acceptance is implied.


## Implemented browser mapping — 24 October 2026

Source: `frontend/tests/e2e/member-role-management.spec.mjs`. `node --check` succeeds; Playwright collection reports 36 role instances and 471 total instances in 22 files (435 existing + 36 new). Collection does not execute the app. At initial authoring, local Chromium and E2E_PASSWORD were absent. Final execution used the isolated CI stack and is recorded in the final QC report.

| Browser case | Matrix rows | Concrete oracle |
| --- | --- | --- |
| MR-B01 | MR05, MR14, MR19, MR23 | Blank edit, no early POST, fresh selected transition, unchecked acknowledgement, exact 11-field persisted receipt, normalized restricted history, current readback |
| MR-B02 | MR04, MR09, MR19 | No-op/invalid reason disables review; supplementary code-point boundary and NBSP reach review; edit resets acknowledgement without POST |
| MR-B03 | MR01–04, MR23 | Real context response corrupted with scope/type/private-field/member/blocker faults is rejected; self/disabled read-only; foreign404 and no profile disclosure |
| MR-B04 | MR06, MR19 | Real promotion then UI demotion of *02; fresh MEMBER readback and exact pre/post explicit-grant inventory equality |
| MR-B05 | MR20 | Actual inventory RouterLink; canceled history Back and native beforeunload reload preserve draft; accepted dirty exit makes no POST |
| MR-B06 | MR11, MR21 | Backend201 truly committed then reply aborted; retry returns200 with byte-identical body/key; one history event; no key/reason in browser storage |
| MR-B07 | MR08, MR11, MR21 | Real201 corrupted after commit; independent role reversal advances revision; real receipt verified then replaced with null until original POST replay is observed; historical result only |
| MR-B08 | MR13, MR22 | Real loss of issuer ADMIN observed via workspace response; private draft/body erased, retry removed; after restoration exact historical GET recovery without another POST |
| MR-B09 | MR01, MR22 | Same human ID demoted in disposable DB; window focus observes current MEMBER workspace, clears private draft; no correlation for an undispatched draft |
| MR-B10 | MR20 | Canceled dirty reused-route navigation; accepted target change; actual context installed before Back/Forward; bounded delayed real forward response cannot overwrite restored target |
| MR-B11 | MR22 | Real committed POST held; sibling shared-session logout/login changes actor; session response proves changed ID; released old reply restores neither private state nor correlation |
| MR-B12 | MR13, MR22 | Real history request held until issuer demotion while first POST is committed but unresolved; actual403 erases private state; restored same actor recovers only exact receipt GET; late original reply harmless |

Fixture: role organization80808080-8080-4080-8080-808080808080; accounts8080; browser ranges101..120/201..220/301..320. Browser ADMIN actors3/4/5 are restored in finally after every SQL demotion. Native targets901..930 and cross actors91/92 are not browser mutations. B02 does not save; B04 alone mutates the *02 grant-bearing target. Enabled-Admin count is not assumed fixed. SQL role changes model out-of-band authority loss only and do not claim supported-writer revision guarantees.

Seven screenshot states per project: edit, review, recorded, history, error, unknown, denied. Names are `member-role-management-{desktop|tablet|mobile}-{state}.png`; all content comes from disposable synthetic fixture data. The screenshot helper also checks horizontal overflow. All21 final CI images were downloaded, digest-verified and inspected by the coordinator; bounded keyboard/overflow assertions passed in the browser suite.

The completed Native/BE gates cover retained removed-member history, strict wire/CSRF, canonical digest vectors, audit rollback, overflow, observed lock races, post-demotion administrative denial and actual approval by a demoted explicit grantee. B04 proves grant preservation only; it does not substitute for the native eligible-approval probe. Final gate publication and exact candidate binding belong to the main worker.


Transport precedence clarification (authorized main-worker direction): retain existing SecurityConfig. An organization identifier rejected by the outer organization guard yields403 for an authenticated request; it cannot establish organization authority. With a valid organization and authorized ADMIN, malformed target/receipt key/idempotency key reaching the role controller yields400. Anonymous authentication401 and missing/invalid CSRF403 are earlier transport decisions. The broad syntax400 oracle applies after those guards. This is a scoped contract clarification, not permission to widen shared security behavior. Any new malformed-organization transport test must expect the existing outer403; no such browser assertion is currently present.
