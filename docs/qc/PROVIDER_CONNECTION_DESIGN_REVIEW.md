# Independent BA-03 provider connection design review

Date: 31 October 2026. Disposition: **changes required before design freeze** for the specific contract issues below. This is an API/security/business and UI-contract review only. Missing runtime implementation is not a rejection criterion. No runtime, live-provider, browser or Figma verification was performed; no tests are reported passed. No other agents were used. Only this review file was written.

## Reviewed baseline and limits

Reviewed the working-tree `docs/engineering/PROVIDER_CONNECTION_API_CONTRACT.md` (API), `PROVIDER_CONNECTION_UI_FLOW.md` (UI), `PROVIDER_CONNECTION_REQUIREMENTS.md` (requirements brief), `RUNTIME_LIFECYCLE_AUTHORITY_CONTRACT.md` (lifecycle), and `docs/qc/PROVIDER_CONNECTION_TEST_CASES.md` (cases). Repository HEAD at review: `0d274c16f574c5f7303867ddfbdf851d553d0a41`; proposed API/UI/cases were untracked, so that hash alone does not identify their contents. References below use document sections and case IDs to identify the reviewed statements. Existing `AUTH_RUNTIME.md`, `TASK_OWNER_CONTRACT.md`, and common API envelope declarations were read for compatibility, not execution evidence.

The explicit all-three choice is settled: OpenAI, Anthropic/Claude and Google Gemini, installer-selected nonempty enabled subset, no implicit default. Requirements supersede lifecycle §8's earlier unresolved provider-selection sentence; that historical sentence is not a product blocker. Broader requirements for Agent binding/model selection and cost-bearing model tests remain separate gated packages, not missing endpoints to add to this management slice.

The reported ongoing Figma work (54 frames, 18 states, three widths) is context only. This review neither certifies nor rejects those counts or visual parity. UI findings concern the written API/UI contract and can be resolved while the design continues.

## Blocking contract findings and exact recommended fixes

### B1 — Access check and discovery are different operations, but the UI promises discovery from an access check

**Evidence:** UI view table, “Access check,” says “Explicitly list available model metadata” and includes “cancelled.” API §§4,7 and PC-C02 specify that ACCESS_CHECK discards the catalogue, returns `catalogueState=NOT_REQUESTED`, and cannot serve models. Only MODEL_DISCOVERY retains models. Neither a cancellation route nor a CANCELLED check state/outcome exists. UI's populated-list row also requires latest access-check revision/time, while API §4 `List.items` contains only `Connection`, which has no check summary. “Empty-filter” has no server filter query contract.

**Impact:** A literal UI implementation must invent data, invoke an undisclosed second operation, add unsupported cancellation, or fetch separate detail records to populate the list.

**Fix:** Define two explicit actions: “Check access” starts `/access-checks`, shows observation only, and never lists models; “Discover models” starts `/model-discoveries`, shows its own queued/result/history and COMPLETE/TRUNCATED state, and pages only its frozen check snapshot. Remove server cancellation from this slice; local navigation/stop-polling must explicitly leave accepted work running. Prefer removing check revision/time from list rows and showing them on Detail; otherwise explicitly contract separately authorized bounded Detail fetches and their loading/error behavior, without claiming the fields arrive in `List`. Remove filtering or label it as filtering only the loaded page. Update PC-U01/U02 and the handoff action/state inventory accordingly. Preserve discovery's rule that it does not overwrite `currentObservation` from access checks.

### B2 — Deferred work lacks an explicit persisted actor credential version and enforceable lease/deadline predicate

**Evidence:** API §7 requires current “credential-matching” actor authority, one dispatch token, fresh eligibility each page, CAS publication, and a sweeper at deadline+5s. It does not specify the persisted initiating account credential version, durable deadline origin, or expiry predicate in authorization/publication. Public Check appropriately has no actor credential version, but §10's internal persistence requirements do not define it either. PC-C04/C07–C10 assert intended outcomes without defining these internal inputs. Lifecycle §§2,6 require bounded fencing and credential-version checks.

**Impact:** An implementation could compare an account with its newly loaded version rather than the accepting version, allowing work queued before password/credential invalidation. A paused worker could authorize another page or publish success after its operation deadline but before the sweeper terminalizes it. Unique token identity alone does not expire authority.

**Fix:** Specify internal immutable `actorId` and `acceptedActorCredentialVersion`, captured from the authenticated command, plus `queueExpiresAt=acceptedAt+60s`, worker owner/token, `startedAt`, and `dispatchDeadline=startedAt+30s`. Under org-first locks, dispatch/page authorization must require exact captured actor credential version, enabled account, current ADMIN, current connection/credential/policy revisions, correct live owner/token/state and unexpired deadline. Persist one page authorization identity per `(checkId,pageOrdinal)` before sending; duplicate delivery cannot reacquire permission to send that page. Publication must use the same locks, re-read authority/revisions, check token and deadline, and atomically publish terminal Check plus snapshot. After expiry, a late result cannot become ACCESSIBLE even before the sweeper runs; uncertain outstanding sends become UNKNOWN without resend. Freeze the expiry boundary and add barrier cases for credential change, pause past deadline before next-page authorization, and publication after deadline but before sweeping. No new public secret-bearing fields are needed.

### B3 — Deployment-policy revision is a fence without a defined shared serialization mechanism

**Evidence:** API §§1,5,7 promise installer policy revisions, off/on ABA invalidation, and final locked authorization/publication, but give no policy storage/update participant or lock order. An organization mutex alone cannot serialize a process-local configuration reload or independently applied deployment policy across workers.

**Impact:** Workers could authorize against different enabled sets or publish a result against a stale policy even though both claim to have checked a revision. This affects the promised disable boundary, not just deployment convenience.

**Fix:** Freeze one authoritative durable policy revision and enabled set shared by all API/worker instances. One compatible protocol is a singleton policy row: policy writers lock only that row; organization operations lock organization first, then policy row, then existing account/membership and scoped rows. Policy writers must never acquire organization locks afterward. Increment monotonically on every policy mutation, reject overflow, atomically read enabled flags and revision, and require a current locked match at each page authorization and publication. Deployment readiness must reject instances unable to use that authority. Policy changes need not cancel already authorized network work, but must fence subsequent authorizations/publication. Extend PC-M01/C03/C06 to two workers across policy off/on changes and both lock orderings.

### B4 — Credential health has no defined read projection, and immutable snapshots need a precise health timestamp meaning

**Evidence:** API §4 says PRESENT means an authenticated envelope with a resolvable key reference and UNAVAILABLE covers corrupt ciphertext and unavailable key service. §§5–6 require safe reads/rename/disable during KMS outage and prohibit remote KMS calls inside transactions. There is no stated mechanism to authenticate ciphertext or determine current KMS availability for List/Detail without decrypting/probing. Receipts embed immutable `Connection` snapshots, whose health can later change without revision. PC-M09/E02 expect corruption/outage to yield safe unavailable projections.

**Impact:** Implementers can disagree between optimistic PRESENT, decrypt-on-every-read, or failing the whole metadata read. Receipts could also be mistakenly refreshed with current health, violating replay identity. This is an unresolved design rule, not a claim that every read must call KMS or a provider.

**Fix:** Define a side-effect-free health projection and its evidence limits. A concrete conservative choice: PRESENT means structurally present authenticated-at-write storage with no known integrity failure and locally known usable key-service/key-version health; unknown/unavailable health yields UNAVAILABLE. Explicitly state that this is not per-read cryptographic verification, remote credential validity or a guarantee the next unwrap succeeds. GET must not decrypt secrets, call a provider, refresh health or enqueue work; dispatch detects latent corruption and fails closed. Specify how independently maintained key-health/integrity observations become visible without making reads mutate business state. Keep safe metadata/rename/disable available without KMS; receipt snapshots retain health as observed at commit forever. Adjust PC-M09/E02 so cryptographic tampering is detected on a defined unwrap path, not magically detected by GET. If stronger per-read authentication is required instead, explicitly design its bounded out-of-transaction mechanism and outage behavior before freeze.

### B5 — Pagination cutoff outcomes are not exhaustive at later-page byte limits or the OpenAI item cap

**Evidence:** API §7 allows retained complete pages with ACCESSIBLE/TRUNCATED at page/model/aggregate bounds, classifies first-page excess as RESPONSE_LIMIT, and classifies later schema/transport/provider errors as failures. It does not explicitly classify crossing the remaining 4MiB budget during page N after earlier valid pages, crossing 1MiB on a later page, or an OpenAI single response containing more than 1000 valid models with no cursor. “Cap reached with no remaining cursor => COMPLETE” cannot prove completeness if items were dropped. PC-P08 repeats the broad rule without these discriminating oracles.

**Impact:** Two implementations can publish different outcomes for identical bounded input, or mark a cut-off single catalogue COMPLETE.

**Fix:** Add a precedence table: a local byte/item cutoff during any response aborts that response and never retains part of that page; with zero earlier complete pages it yields FAILED/RESPONSE_LIMIT, otherwise ACCESSIBLE/TRUNCATED with only earlier complete pages. A definitive provider/schema/cursor error actually observed within allowed parsing bounds discards all staged pages and yields its defined failure; do not continue reading beyond the bound merely to determine whether an error exists. OpenAI >1000 items on its sole page must be FAILED/RESPONSE_LIMIT under the whole-page rule, never COMPLETE. COMPLETE requires a fully validated final page, no retained-item loss and confirmed end-of-catalogue. Specify whether exactly-equal byte ceilings accept a fully consumed valid document. Add later-page per-page/aggregate ceiling, exact-boundary, and OpenAI 1000/1001 cases. Retain transient cursor max2048 UTF-8 bytes, encoded-value-only handling, repeat detection and no public cursor.

### B6 — Out-of-transaction key preparation needs an explicit replay-first protocol

**Evidence:** API §5 requires receipt replay before mutable gates and pre-encryption outside transactions; §6 requires historical HMAC-version verification and no remote KMS calls inside transactions. It does not prescribe the two-phase sequence. PC-I04/I09 and PC-M11 require replay despite later credential/provider/key outages, subject to historical HMAC availability.

**Impact:** Unconditionally pre-encrypting CREATE/ROTATE before looking up a receipt can reject a valid replay when encryption is unavailable. Looking up only under the final lock and then calling remote HMAC/KMS there violates the no-network transaction rule. A concurrent receipt insertion can change which historical HMAC key version is needed.

**Fix:** Specify: authorize and strictly parse; read own scoped receipt/key-version outside the write transaction; obtain/compute its historical HMAC outside DB locks and return a verified historical replay after fresh authority/scope checks. For a fresh candidate, prepare current digest and (only for fresh create/rotate) encryption outside locks. Then lock org-first, reauthorize, re-read own receipt and check it before mutable gates. If a raced receipt requires an unprepared historical key/version, release/roll back, obtain it outside locks and repeat with a bounded attempt policy; never mutate or fall back to a new command. An exact replay must not require working encryption when its historical HMAC key remains available. GET receipt remains independent of both key services. Add a replay-with-encryption-down/HMAC-up case and concurrent HMAC rotation/receipt-insertion case. Preserve secret-free UNKNOWN recovery and no frozen secret-body resubmit.

## Boundaries that are consistent in the proposed design

These are findings about written rules, not security verification or passed tests.

| Area | Review result |
| --- | --- |
| Exact envelopes | API §3 matches existing `ApiResponse<T>(data,meta)`, `ApiMeta(requestId)` and `ApiErrorResponse(error,meta)` with error `{code,message,details}`. New slice codes do not replace existing endpoints globally. Nulls and field sets are explicit. |
| Secret-bearing UNKNOWN | API §8 and PC-I10–I15 separate metadata commitment from provider outcome; clear secret and frozen body; own receipt read cannot execute a command; null is not proof of failure; deliberate recreation can duplicate a named connection. |
| Idempotency and key rotation | Scoped actor/org/key namespace, canonical typed HMAC, historical HMAC keys, separate KEK/HMAC authority, no weak-secret offline verifier, authenticated scoped envelope and CAS rewrap are specified. B6 addresses preparation order, not a demand to replace this scheme. |
| Disable and revision semantics | Rotation increments credential and configuration revisions; disable blocks later authorizations, preserves history and does not promise remote revocation/cancellation. Rename conservatively invalidates checks. B2/B3 require making the worker/policy fence executable as a contract. |
| Tenant and authority | Current human ADMIN only, same-org scoping, own-actor receipts, post-wait authority checks and organization-first ordering are explicit; MEMBER/Agent reads are deliberately excluded pending a separate contract. |
| Outbound containment | Fixed hosts/paths/auth profiles, HTTPS443, connect-time public-address enforcement including IPv6/rebinding, no redirects/proxies/background retries, bounded decompression and untrusted opaque cursors are specified. No arbitrary endpoint input. |
| Privacy and safe errors | Constant mask, no prefix/suffix, no raw command/provider body/header logging, no persisted browser secret, safe fixed error enums and historical result labels are explicit. Redaction must cover failure/APM/crash paths as the cases require. |
| Business guarantees | Models-list success proves only that observation. No billing/quota/generation authorization guarantee, zero-cost fabrication, automatic model selection/failover, implicit Agent binding or activation bypass is promised. |

Official reference spot-checks on the review date support the selected wire profiles rather than a blanket objection to Claude Bearer/version selection: [Claude overview](https://platform.claude.com/docs/en/api/overview) documents Bearer API keys, workspace selection and the `2026-10-21` version example; [Claude models](https://platform.claude.com/docs/en/api/models/list), [Gemini models](https://ai.google.dev/api/models) and [OpenAI models](https://developers.openai.com/api/reference/resources/models/methods/list) describe the respective catalogue shapes. This was documentation retrieval only, not a successful adapter or credential test. Reverification and pinned adapter fixtures remain implementation prerequisites.

## Design prerequisites versus implementation acceptance

Before freezing the affected design, resolve B1–B6 in the API/UI/case contracts and reconcile the final Figma actions, field provenance and states against those exact DTOs. An evolving UI flow is not itself a rejection; freeze only after the explicit operation and data mismatches are resolved. Do not add cancellation, generation, binding or activation merely to satisfy a specimen frame.

Before deployment/implementation acceptance, separately supply the KMS identity/provisioning/recovery and HMAC-retention runbook, durable policy authority, SQL constraints, worker/sweeper implementation, redaction controls, executed PostgreSQL race/restart evidence and browser evidence. Authored case counts are not passed results. Optional authorized live models-only smoke evidence must be identified separately from mocks and makes no nonbillable guarantee. None of these absent runtime artifacts is a reason to reject the current documents as unimplemented; the blockers above are missing or contradictory design rules.

This review authorizes no production credential use, provider request, code or Figma change. Separate Agent binding, grants, activation, generation budget/reservations and actual usage/UNKNOWN reconciliation remain gated even after this management design is corrected.


## Retest — revised contracts and six original screenshots

Date: 31 October 2026. **Retest disposition: B1–B6 addressed at design-contract level; no remaining blocking API/security/business contradiction or UI-contract parity defect identified in the reviewed scope.** The original review above is preserved as the historical assessment of the earlier proposals. This retest supersedes its open-blocker disposition for the revised content identified below. It does not establish implementation acceptance or complete Figma handoff acceptance.

### Retest baseline and method

Re-read the actual revised API contract, UI flow and test-case file, including API §§1,4,5.1,7,9,10 and the corresponding new/changed case oracles. Independently counted **109 case rows, 109 unique case IDs, zero duplicate IDs**. These are authored cases; none was executed in this review. No runtime/backend/browser/live-provider testing, code changes, Figma inspection/editing or other-agent work occurred.

Working-tree SHA-256 identifiers at retest:

| Document | SHA-256 |
| --- | --- |
| `docs/engineering/PROVIDER_CONNECTION_API_CONTRACT.md` | `a24c70bd11f629b905f1d3ad049639266fcd0ab8a1f04dcea66820889be246d5` |
| `docs/engineering/PROVIDER_CONNECTION_UI_FLOW.md` | `dab197de695c461f79a05ee9b51550471ed3a909e81400fdf2f13aee5123f56f` |
| `docs/qc/PROVIDER_CONNECTION_TEST_CASES.md` | `2624ca30cde2733a3b5438822091f0f4c375177bc6fe769538dd07a15502aba7` |

### Prior finding dispositions

| Finding | Retest result and evidence |
| --- | --- |
| B1 — operation/data parity | **Addressed.** UI table and appended parity correction explicitly separate `/access-checks` observation from `/model-discoveries` frozen catalogue/paging, forbid hidden chaining and server cancellation, explain leaving does not cancel accepted work, and keep List metadata-only with no search/filter. Check revision/time belongs to separately loaded Detail. PC-U01/U02 align. |
| B2 — actor version, lease/deadline and publication | **Addressed.** API §7 persists initiating actor/version, queue expiry, owner/token, dispatch deadline and unique page ordinal authorization. Each page rechecks captured actor version and current authority/revisions under locks; publication samples database time after waits and atomically fences Check/snapshot. Deadline equality is expired, UNKNOWN wins for nonterminal RUNNING at expiry, and token/page authorization cannot be reacquired for resend. PC-C04/C07–C09/C16–C20 and PC-P13 cover the relevant races as authored oracles. |
| B3 — policy serialization | **Addressed.** API §§1,5 specify the shared durable singleton, atomic enabled-set/revision, nonempty set, overflow rejection, org→policy→account/scoped lock order and policy-only writers that never acquire org locks afterward. Unready instances cannot authorize from a stale cache. PC-M01/M15 and PC-C03/C06/C20 cover multi-instance/ABA ordering. No public installer-management API is unnecessarily added. |
| B4 — health and historical snapshots | **Addressed.** API §4 defines authenticated-at-write evidence, independent safe health observations with maximum60s validity, envelope-version-bound integrity failure, unknown/expiry→UNAVAILABLE and no per-read decryption/remote validity claim. GET performs no KMS/provider call, refresh or enqueue. Receipt health/mask remains immutable; metadata/rename/disable remain available subject to separate HMAC availability. PC-M09/M11/M14 and PC-E02/E10 distinguish latent tamper from detected failure and stale health. |
| B5 — cutoff precedence | **Addressed.** API §7 now has an explicit whole-page outcome table, exact-ceiling acceptance only for fully consumed valid documents, later-page cutoff retaining only earlier whole pages, definitive-error precedence within allowed parsing bounds, OpenAI1001→RESPONSE_LIMIT, byte-based cursor limit and immutable UNKNOWN at deadline. PC-P08/P13/P18–P22 provide discriminating authored cases. The clarified deadline rule consistently chooses UNKNOWN even between pages once the dispatch deadline is reached; prior suggested between-page deadline truncation is no longer the target. |
| B6 — replay-first preparation | **Addressed.** API §5.1 explicitly probes own receipt before encryption, prepares historical/current HMAC outside transactions, rechecks receipt first under locks, releases locks for unprepared historical versions and re-probes on encryption-preparation failure. Three-pass/10s bounds and capped external-call deadlines avoid unlimited preparation loops. PC-I16–I19 cover encryption-down replay, raced insertion/HMAC rotation, failed encryption preparation and bounded exhaustion. GET receipt remains key-service independent. |

All-three-provider choice, exact existing response envelopes, fixed masks, anti-secret logging, scoped authority, allowlisted/SSRF-constrained outbound requests, safe errors, metadata-secret UNKNOWN recovery and separate generation/activation/billing boundaries remain intact. No additional provider mode, generation, binding, cancellation or runtime feature is required to close these findings.

### Exact visual inspection extent

Independently opened and visually inspected **all six supplied original PNG files**, without creating crops, composites or replacement screenshots. All were available in `/workspace/scratch/24b4696504b1/provider-design-review/`. The dimensions below were independently read from the files. Observations describe only visible static pixels and labels.

| Original file | Dimensions | Directly inspected content / limits |
| --- | --- | --- |
| `desktop-detail.png` | 1440×1000 | Desktop navigation and specimen-labelled OpenAI detail; distinct “Kiểm tra truy cập” and “Khám phá model” buttons; rename/rotate/disable, revision placeholders and explicit statement that model testing needs separate runtime/cost work and is unavailable. No catalogue is falsely shown as an access-check result. |
| `tablet-list.png` | 768×1024 | Compact header, three named provider cards and enabled/disabled specimen states; no check time, observation badge or filter fabricated from List. Create and previous/next controls are visible. The only explicit per-record open button shown is for OpenAI; static pixels do not establish card hit targets for Claude/Gemini or pagination enablement. |
| `mobile-create-claude.png` | 390×1070 | Full-height mobile Claude create specimen, all three provider choices with Claude visibly selected, key-input placeholder without an actual key, optional workspace field, review/cancel/back actions and explanation that save does not test or invoke a model. Input masking behavior is not testable from this placeholder screenshot. |
| `mobile-provider-select.png` | 390×844 | Explicit three-provider choice with text stating no default, specimen label, no selected provider, and no automatic check/model call. Availability/disabled variants are not demonstrated by this one frame. |
| `mobile-command-unknown.png` | 390×844 | Save UNKNOWN is distinguished from provider result: command may have committed, only recovery locator retained, no API-key retention/automatic resubmit, absence of receipt does not prove failure, and explicit result lookup. Two list/back actions are visible. |
| `mobile-check-unknown.png` | 390×844 | Check UNKNOWN explains possible provider delivery, no automatic resend, deliberate new observation, credential-version provenance and no inference of runtime permission or billing status from history. Distinct detail/back-list actions are visible. |

No obvious clipped content or horizontal overflow was visible in these six originals. This observation does not establish responsive behavior between widths, contrast measurements, keyboard/focus order, screen-reader semantics, form validation, masked entry, network behavior, route wiring or clickable prototype behavior. In particular, the 390×1070 create image is a taller static export, not proof of scrolling in an844px browser viewport.

The reported **Figma page165:2, 72 editable frames, 24 states×3 widths** remains author-reported. I did not open the Figma file, inspect node/layer editability, count frames, inspect the other66 frames, or exercise prototype links. None of the six supplied originals is a discovery-result or running/failed-check frame; the revised written contracts cover those states, but this visual sample does not verify them. Main's precise handoff inventory remains the appropriate place to record node IDs, state/provider/width mappings and static-frame limitations. Static frames need not become a clickable prototype to satisfy this bounded review.

### Nonblocking refinements and handoff notes

- **Duplicate navigation:** Mobile create/provider-select show cancel plus back-to-list; command UNKNOWN shows two similar list actions. Consolidate equivalent destinations or clarify distinct dirty/UNKNOWN guard behavior. This is a UX refinement, not a security blocker or request for server cancellation.
- **Action hierarchy:** Desktop detail gives five actions similar full-width visual weight. Distinguish observation/discovery from rename/rotation/disable, especially the destructive policy change, without changing the contracted review/command flow.
- **List affordances:** In handoff, make opening every connection unambiguous and annotate previous/next disabled states from actual page/total. The tablet specimen alone cannot prove the Claude/Gemini cards are actionable; it also cannot prove they are inaccessible. No new API field or route is needed.
- **Error-table shorthand:** API §9 still summarizes authority loss as “before send”/“after send,” while §7 correctly uses the durable page-authorization boundary. Replace those labels with “before any page authorization”/“after any page authorization,” referencing §7's deadline precedence, to remove shorthand ambiguity. The specific §7 rules and PC-C08/C09/C16–C20 already resolve the intended behavior; this is editorial consistency, not a reopened B2 blocker.

Only this review file was appended. The six prior findings remain visible, now with explicit retest dispositions. Runtime implementation, deployment runbooks, executed tests and optional authorized live smoke evidence remain separate acceptance work; their absence does not reverse this design-level result.


## Handoff consistency addendum — final shared-style captures

Date: 31 October 2026. **No additional design blocker identified.** Read the actual `PROVIDER_CONNECTION_FIGMA_HANDOFF.md` and `PROVIDER_CONNECTION_FIGMA_LEDGER.json`, and re-opened all six current original PNGs after the reported final Forest shared-style pass. This adds handoff consistency evidence to the retest above without removing its historical findings or changing B1–B6's addressed disposition.

### Independently checked document/ledger consistency

The local ledger contains72 distinct root-frame IDs,24 named states and exactly24 entries at each of1440/768/390 widths. All72 state/width/node-ID mappings in the handoff table match ledger names and widths; no missing or mismatched mapping was found. Independently recomputed pairwise root-rectangle intersections from the ledger's recorded coordinates: zero overlapping root frames. This verifies consistency of the supplied snapshot, not the current remote Figma document.

The handoff's195 descendant frames,1029 texts,267 instances,5 text-style IDs and22 bound-variable IDs agree with the ledger's recorded counts/ID arrays. Ledger arrays for bad fonts, image fills, short multiline blocks, horizontal overflow and frame overlaps are empty. These descendant/font/style/binding/image/layout results remain coordinator-produced structural evidence: I read their recorded results but did not independently traverse Figma nodes or rerun those inspections. The ledger's style IDs alone do not prove every node's font or token binding. In particular, root-rectangle non-overlap does not establish absence of overlapping child controls or text.

Handoff/API/UI descriptions agree on explicit provider selection, metadata-only List, separate access-check/discovery, frozen discovery history, no server cancellation, separate command UNKNOWN versus remote-check UNKNOWN, and no automatic test/generation on save/rotate/enable. The explicit generic-review rule correctly requires preserving the actual chosen provider/name/options through review and receipt; the OpenAI detail specimen cannot replace a selected Claude/Gemini connection. The handoff clearly labels these as static editable specimens with no executable prototype reactions. No clickable-prototype requirement or claim is introduced.

The handoff also correctly limits its own visual claim to the six final compositions, distinguishes an earlier discovery-result inspection from a final shared-style visual pass, and does not claim all72 screens were individually screenshot-reviewed. This is consistent with the independent extent below. The prior retest's author-reported inventory statement is now supplemented by the local table/ledger reconciliation; actual remote editability remains unverified by me.

### Current six-original visual extent

Viewed these six files directly, again, in full as supplied under `/workspace/scratch/24b4696504b1/provider-design-review/`. No Figma file was opened and no additional composition was visually inspected in this addendum.

| Current original | Size | SHA-256 |
| --- | --- | --- |
| `desktop-detail.png` | 1440×1000 | `6a5238a54483386dcb9a6ea56b9d9486ef5111d5408189e5fc33cb3be9d7ba34` |
| `tablet-list.png` | 768×1024 | `6f0ed7501d3a026b2258accf40ff68da047495fd9426ee30d536b1d778facc05` |
| `mobile-create-claude.png` | 390×1070 | `de3cedd7ae7e6cc61b5fb1d12846cd4b72c2e58d526358e72b6d71a4c9a4b183` |
| `mobile-provider-select.png` | 390×844 | `7bae2e07eff1a3d49d7f6494b051d8ea3cb9207c6e06573f6865dd2139150107` |
| `mobile-command-unknown.png` | 390×844 | `e54f62c15910df9901b0f66686d7b4638fe64a1d4b9560696c205553e67fd752` |
| `mobile-check-unknown.png` | 390×844 | `4890d8cd4e8f28927affa5606ba85f357ce21b2d2b2f67cbd40c837a0f366c4f` |

The prior six-image content observations remain applicable to these current files: detail separates check/discovery; List does not fabricate observations; Claude create preserves selected-provider/workspace context; selection states no default; command UNKNOWN preserves receipt uncertainty without retaining a key; check UNKNOWN does not imply denial, runtime permission or billing validity. The final images show readable dark text on pale Forest surfaces with no obvious clipped text or horizontal overflow in this sample. No contrast measurement, font-node inspection, typed-key masking or responsive scrolling test was performed. Duplicate back/list actions and equal-weight detail actions remain nonblocking UX refinements already recorded above.

These are six specific compositions, not a visual review of the other66 ledger frames, all provider/state combinations, or discovery/running/failed states. The1070px-tall mobile create export remains full static content, not evidence of browser viewport behavior. No browser, keyboard, screen-reader, KMS, PostgreSQL, live-provider or runtime test is claimed.

Reviewed handoff SHA-256: `8ed4359c79f5069da5eb07d79da7e61912e2f434d35efe112764e7b8ecadaaca`. Reviewed ledger SHA-256: `6bd91a66f88106aa66f9e1e91cb1123fdb990a6cb7ec9576cde6ccad49610bd3`.

Only this QC review was appended. Handoff consistency and the bounded six-original inspection add no new blocker and do not expand the implementation scope or establish runtime acceptance.


## Final editorial clarification — bounded document check

31 October2026: checked the revised API §9 authorization-boundary wording and UI flow's appended implementation refinements. The error table now distinguishes before/after any page authorization, matching §7. Native scoped card links, actual page/total-derived disabled navigation, action hierarchy and consolidated back/cancel guards are explicitly future implementation requirements, not claimed Figma/runtime changes. README, IMPLEMENTATION_STATUS and POST_PR38_BACKLOG checkpoint statements explicitly separate design evidence from implemented behavior and executed tests.

**B1–B6 remain addressed; no new blocker identified by this editorial check.** No repeat visual review, remote Figma inspection, runtime/live testing or PR publication was performed. The prior six-current-original inspection extent and handoff qualifications remain unchanged. Only this review was appended.

Current API SHA-256: `4c120cd95bfc631dc27340a19a5d412ccc28031569f3d786c9136960ca4be0f0`. Current UI flow SHA-256: `c496ef2f77ac5051b9ee9aeb6433801adbd1dbf22661e04fbdad577c95b556e5`. Test cases unchanged from the retest: `2624ca30cde2733a3b5438822091f0f4c375177bc6fe769538dd07a15502aba7`.
