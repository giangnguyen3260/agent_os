# Member invitations independent QC cases

Source: full `docs/engineering/MEMBER_INVITATIONS_CONTRACT.md`, including shared labels/hooks and retained approval rejoin warning. Base supplied by main: `778ed08911f761eaa3a5397291414a560679248e` (prior 192 backend / 471 browser results are supplied historical evidence, not independently rerun here).

The scripts require disposable PostgreSQL, an enabled invitation worker, HTTP frontend/backend, and isolated Mailpit SMTP. No external delivery. Fixture applies once after V015, copies only the disposable bootstrap password hash, and creates no invitation capability directly. Native emails are `invitation-native-*` and native existing humans 901–930; browser emails are `invitation-browser-{project}-*` and existing humans 101–130 / 201–230 / 301–330. Browser actors 3/4/5; native actor 1 and 901/902. Foreign actor 99. Organizations are exactly 90909090-9090-4090-8090-909090909090 and 91919191-9191-4191-8191-919191919191. No previous-feature account is modified. The fixture intentionally fails on reapplication; rebuild the disposable database before repeating.

| Native gate | Authored oracle |
| --- | --- |
| N01 transport / scope / wire | Actual anonymous 401, MEMBER 403, missing CSRF 403, foreign target 404, unknown/duplicate query, duplicate/extra body fields, malformed email and false acknowledgement 400; public CSRF; name/password code-point and UTF8 limits; disabled recipient generic denial regardless of caller |
| N02 create / replay | Exact 12-field receipt, actor-scoped receipt GET, same key/body 200, live org/email conflict 409 |
| N03 real mail / digest | Mailpit recipient search, 43-char fragment capability, SHA256 matches committed PostgreSQL row, no raw token in invitation/job/event/receipt/email-lock/audit tables |
| N04 new human | Masked NEW_ACCOUNT inspection, explicit accept, one human, membership MEMBER, ledger revision 1, consumed capability 400 |
| N05 existing / mismatch | SIGN_IN anonymous, MISMATCH other human, 401 unauthenticated accept, 403 wrong account, actual authenticated accept, exact existing credentials retained |
| N06 retained role ledger | Existing human without membership advances retained 7 to 8; maximum revision conflicts and leaves business state unchanged; existing ADMIN preserved without ledger increment |
| N07 org concurrency | Dedicated PostgreSQL holder and observed two HTTP waiters; duplicate creates produce 201/409 |
| N08 revoke versus accept | Observed shared org waiters; outcomes only accept 200/revoke 409 or accept 400/revoke 201 |
| N09 global email | Observed digest mutex waiters across two orgs; one anonymous new-account accept 200, second requires authentication 401; globally one human |
| N10 global human ordering | Reverse issuer 902 / recipient 901; lower UUID observed first, higher has no waiter until lower releases, then higher observed |
| N11 audit rollback | Scoped NOT VALID audit constraint forces real DB failure; no account/join/event/receipt/capability consumption; cleanup in finally; valid retry succeeds |
| N13 candidate issuer refresh | Real B resend holds org while accept waits with old candidate; final ledger barrier; NOWAIT proves latest issuer B is held and old issuer A free; retained ledger advances 11→12; recordedAt is no earlier than DB clock immediately before final ledger-holder COMMIT |
| N14 final ledger expiry | Set short expiry before accept; observe ledger wait; let DB clock naturally pass expiry; release; generic INVALID_INVITATION 400, retained ledger/account/membership unchanged, no acceptance event |
| N15 invitation revision MAX | Actual valid SMTP capability at invitation revision 2147483647; accept 409, no account/join/event, capability remains valid |
| N17 management final wait | Hold scoped invitation row, observe revoke waiting; natural expiry after request starts; release then conflict 409 without revoke event |
| N16 canonical fingerprint / grammar | Three published literal UTF8 SHA256 vectors; DB receipt fingerprint equals independent nine-key canonical JSON including nulls, domain/version; strict dot-atom/domain limits and recipient-list parsing rejection |
| N18 real SMTP outage / recovery | Exclusive stop/start argv; actual failed transport, observe positive queued retry then FAILED at five attempts; no counter/state scheduling edits; old delivered digest/inspection and exact fixed expiry survive; restart cannot retry FAILED job; explicit resend creates delivered replacement |
| N12 resend / issuer loss | Queued replacement preserves previous capability; real SMTP ACK replaces digest; fixed expiry; demoted issuer invalidates inspect/accept; another ADMIN explicitly resends; revoke invalidates; historical receipts remain immutable |

The probe has a 295-second cleanup alarm and a 299-second forced process exit, HTTP timeouts, bounded lock barriers, and a final elapsed assertion. A lock barrier requires an actual PostgreSQL waiter; submitting concurrent futures alone does not count as evidence. It never prints token/mail/password values. The ledger timestamp oracle is a necessary pre-COMMIT database lower bound on release, not a claim to measure the exact lock-release instant. Native full run is separate from syntax validation. SMTP failure/retry exhaustion is authored as N18 but requires the exclusive lifecycle configuration below. Dispatch issuer loss, feature-disabled transport, log scanning and restart evidence remain explicit additional gates until demonstrated by main; a successful probe must not be represented as proving those gates.

| Browser ID (each at 1440 / 768 / 390) | Authored scenario |
| --- | --- |
| INV-B01 | Actual member inventory entry; list/edit/review; no early POST; explicit unchecked acknowledgement; fixed MEMBER and retained approval warning; exact create body/key/receipt; actual Mailpit delivery; visible Đang chờ chấp nhận plus scoped context PENDING; screenshot states |
| INV-B02 | Invalid email disabled; edit/review resets acknowledgement; no mutation |
| INV-B03 | Actual scoped detail; resend/revoke review; exact revision; fixed expiry; visible Đã thu hồi plus scoped context REVOKED terminal readback |
| INV-B04 | Lost reply after actual 201 backend commit; UNKNOWN; manually resend identical body/key and receive immutable 200 replay |
| INV-B05 | Malformed committed reply; advance state with actual revoke; receipt GET response null injection after fetching actual receipt; UNKNOWN remains; historical replay does not imply current PENDING |
| INV-B06 | Real database demotion; focus workspace response observes MEMBER; wait for private DOM removal before later input; draft/list/review disappear |
| INV-B07 | Dispatched UNKNOWN followed by observed denial; email/body erased; restore ADMIN; receipt GET recovery only, no second POST |
| INV-B08 | Dirty Back and browser unload dialogs dismissed preserve draft; explicit confirmed exit succeeds; zero mutation |
| INV-B09 | Actual committed response held; sibling switches real session account; focus observes new actor; late response cannot restore receipt/private email or recovery |
| INV-B10 | Real SMTP fragment erased; anonymous NEW_ACCOUNT fields and review warning; explicit acceptance only; no auto-login or secret storage |
| INV-B11 | Existing account inline actual login; token remains component memory; no autoaccept after login; explicit null/null acceptance and actual workspace link |
| INV-B12 | Wrong signed-in account; registration absent; explicit logout restores new-account flow; zero accept |
| INV-B13 | Actual backend 200 inspections with initial/pre-accept 503 and pre-accept malformed reply faults show unavailable, never expiry; real retry restores blank unchecked review, no accept/storage; real expired DB capability; HTTP 400 INVALID_INVITATION and generic invalid; erased fragment reload cannot recover token; zero accept |
| INV-B14 | Actual acceptance 200 commit with malformed response injection; UNKNOWN remains on focus despite consumed capability; password removed; no auto-replay |
| INV-B15 | Public name/password draft; sibling real account switch; observed focus clears inputs; explicit logout returns empty fields; no autoaccept |
| INV-B17 | Real same-document hash replacement: reject dirty confirmation keeps draft, accept clears old secrets and hash; held real old inspection cannot restore SIGN_IN over new empty NEW_ACCOUNT; same document identity, unchecked acknowledgement, zero acceptance POST and no stored secrets |
| INV-B16 | Real list 200 containing a fresh private row is reply-faulted to 503; availability alert, all private DOM removed, zero UI POST; real retry restores list; foreign actor creates private target; current org detail 404; no target private DOM; visibility refresh cannot issue mutation |

Only real HTTP responses are used. Fault injection first calls `route.fetch()` and asserts the real success before aborting or altering the response. Fixtures and SQL authority/expiry controls are restricted to assigned disposable targets. No request fulfillment fabricates a successful backend result. Receipt-null injection is deliberately a client protocol fault after actual committed receipt fetch.

Run from repository root (main owns compose/workflows):

```sh
E2E_BASE_URL=http://127.0.0.1:18080 E2E_MAILPIT_URL=http://127.0.0.1:8025 python scripts/member-invitations-probe.py
cd frontend
npx playwright test tests/e2e/member-invitations.spec.mjs --workers=1 --retries=0 --max-failures=1
```

`E2E_PASSWORD` must be supplied through the existing secret mechanism. `INV_PSQL_COMMAND` may override psql as a JSON argument array. Native runs before browser. Existing Playwright projects/configuration remain unchanged: 17 × 3 = 51 additional cases; baseline 471 remains unchanged, combined expected 522. Seventeen screenshot states per project yield 51 expected images on a complete pass (none claimed generated locally). Actual admin/public templates and inventory link were inspected before freezing the exact selectors. Local production FE validator script, independent Python vectors, syntax/AST and 51-case discovery passed; no browser/native runtime is claimed. Screenshot paths: `frontend/qa-visuals/member-invitations-*.png`, artifact name `member-invitations-visuals`. Screenshots are synthetic but capability/credential diagnostics must remain suppressed. Main owns full baseline regression, screenshots review, restart, exact candidate binding and final QC_REPORT.


N18 CI setup (main owns workflow/Compose): configure backend `AGENTOS_INVITATIONS_RETRY_DELAY_SECONDS=1` and dispatch delay at most 1000ms. Export explicit JSON argv `INV_SMTP_STOP_COMMAND='["docker","compose","stop","-t","1","mailpit"]'` and `INV_SMTP_START_COMMAND='["docker","compose","start","mailpit"]'` with the actual isolated service name. Missing lifecycle controls fail before mutation. Stop/start each has a ten-second timeout; actual retry observation has a 45-second bound within the existing 295/299-second global deadline. Native must have exclusive mail-service ownership; run it before browser and add unconditional workflow recovery for hard termination. The test does not edit attempts/state/available_at, seed failures or introduce production-only test hooks. No real outage was run in this authoring environment (Docker/Maven unavailable).


Candidate 4 outage controls supersede the historical stop/start setup above: use `INV_SMTP_STOP_COMMAND='["docker","compose","pause","mailpit"]'` and `INV_SMTP_START_COMMAND='["docker","compose","unpause","mailpit"]'` with exclusive service ownership and unconditional CI unpause recovery. Existing 5000ms SMTP read timeout, CI 1s retry and five actual failures remain required within 45s. After recovery the probe requires actual HTTP search readiness within 8s before explicit resend; this does not replace the unchanged 18s real-delivery oracle. Replacement failure emits only whitelisted generation/state/attempts, never recipient or capability data. CI3 reportedly passed exhaustion/old-token/fixed-expiry assertions but failed replacement delivery; full N18 remains unpassed until a complete real run succeeds. Counts stay 51 new / 522 expected total / 51 planned screenshots.
