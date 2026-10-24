# Member invitations — QC evidence in progress

Runtime acceptance pending. [PR25](https://github.com/giangnguyen3260/agent_os/pull/25) remains draft/unmerged on feature/member-invitations, stacked on PR24. No production or Mac deployment acceptance.

## Current candidate

- Application/test candidate: `81b2bbf52788290128b8c6b555e1e6e6ec0c8f45`.
- Tree: `7d89dc8a8fe9924ae85a03334d74e091b2ac275c`.
- Tested PR merge: `58f82e0a307fb3a94d8a9d4b61b2da2db3ea3ee1`; base `778ed08911f761eaa3a5397291414a560679248e`.
- [Quality40108825226](https://github.com/giangnguyen3260/agent_os/actions/runs/40108825226), job120916048510: SUCCESS271 tests, audit0 and all quality gates.
- [Docker40108825247](https://github.com/giangnguyen3260/agent_os/actions/runs/40108825247), job120916048657: native passed; browser7passed/1failed/43unrun, baseline471 and restart unrun.

Browser gate522:51 invitation instances (17 cases ×3 widths) first, then471 baseline, with51 invitation captures planned. Disjoint collection counts were observed locally; these are not executed passes. Prior candidate5 Quality271/native passed; browser90pass/1fail/431unrun. Full acceptance requires this replacement candidate.

## Executed local checks

FE Google lint, Prettier, actual production invitation validators (strict fields/scope/lifecycle/cooldown/replay/Unicode/password and three canonical SHA256 vectors), Angular production build; Java Google formatter/imports; workflow/Compose YAML parse; Python probe AST; browser Node syntax/collection. Local Java17 cannot compile projectJava21; Maven/Docker/real backend browser are unavailable locally. Figma48 editable views were updated, and coordinator inspected high-fidelity contexts/screenshots for mobile new account, desktop review and tablet inline sign-in. Final browser screenshots remain pending.

## Historical first CI failure

Candidate662c1b274fa2f5843c53c84ab9619abd0feeaebb/tree610b3013152563ef3ae901e76ec34d470cf01a03 was built as merge972e72f800feb0952dede567a08ce4f198ec77a9. Quality40105133334/job120905143386 and Docker40105133250/job120905143015 both compiled and ran269 backend tests:268 passed,1 failed,0 errors/skips. The invalid-capability test expected INVALID_INVITATION but saw BAD_REQUEST. MockMvc csrf() injected a request parameter rejected by the strict query guard before token validation. Corrected tests use csrf().asHeader(), matching the FE; added valid public-header inspection and malformed-wire regressions. Production query guards and authorization remain unchanged. Native/SMTP/browser/restart did not execute because backend verification failed during image build.

Candidate2 also makes both initial inspection and pre-accept inspection failures distinguish a definite400 INVALID_INVITATION from unavailable/malformed/transport responses. Post-dispatch unknown acceptance stays unknown, clears passwords and never auto-retries. Extended browser caseB13 observes actual backend200 before faulting the reply, then real retry to blank unchecked review with no acceptance POST. No privacy or no-early-write assertion was removed.

## Bounded delivered design

Organization Admin invites MEMBER only, no Team/Agent/grant assignment. Fixed72h deadline,60s resend cooldown; another current Admin resend explicitly reauthorizes outstanding capability. Tokens are32 random bytes in memory and SHA256 digests in storage; mail jobs contain metadata only. Real SMTP outage/retry/exhaustion and prior-token preservation are authored native gates; CI Mailpit is exclusive/disposable and retry delay1s is test-only, while production default60s remains.

Public new accounts require invitation capability and explicit acceptance with valid name/password; no auto-login. Existing accounts must authenticate exact recipient and cannot have profile/password/session overwritten. Membership insertion advances retained role revision; existing membership/current role/grants remain unchanged, with retained-grant rejoin warning. Organization/email/sorted-human locking and post-wait authority/expiry checks protect lifecycle transitions. One-time acceptance, safe-audit rollback, retained-ledger overflow and cross-org creation races require actual PostgreSQL evidence.

Engineering defaults remain explicit draft implementation choices, not invented historical business approval. Sonar paused. Invitations do not implement first-Admin bootstrap, Team Settings/Organization Map, provider/tool authorization, Agent activation, Mission/Task runtime, production abuse/retention/KMS/load/failover or full accessibility acceptance.

## Replacement configuration candidate

Candidateaeb87c31b38b759a406775e597baef5e64e5f689, treef2c39a4bb8912f41f84a165e36c49a120789f3db changes only two Compose environment pass-through lines from the prior tested application/test tree. Quality40106187787/job120908245546 and Docker40106187774/job120908245661 are pending. Assertions,45s outage deadline and application/test sources are unchanged. No browser/visual/restart acceptance is yet claimed.

## CI3 partial outcome and following corrections

Quality40106187787/job120908245546 passed again. Docker40106187774/job120908245661 passed the14 earlier invitation native groups and proceeded through observed five-attempt SMTP failure exhaustion with the old digest/deadline preserved, but the replacement message after Mailpit stop/start did not arrive within18seconds. DNS/IP/readiness is a hypothesis, not an established root cause from these logs. Browser/restart remain unrun.

Following configuration uses exclusive disposable Mailpit pause/unpause, preserving its network identity while inducing real SMTP read timeouts. Original45s exhaustion and18s delivery deadlines, five attempts, old-digest/fixed-expiry and replacement-token assertions remain. A bounded HTTP readiness check precedes resend; timeout diagnostics include only generation/state/attempts (no recipient/token/body/credentials). This tests transport outage recovery without introducing an SMTP container/DNS restart scenario. Production retry60s and SMTP timeout bounds are unchanged.

A separate FE source correction makes Admin transport/malformed-data failures show availability copy rather than falsely asserting missing permission. Actual401/403/404/authority denial copy and all private-data cleanup remain; retained opaque recovery also shows an explicit unknown-result warning. Extended existingB16 faults an actual list200 into503, checks private-DOM cleanup/no POST, and retries against real200 before its retained foreign404 assertions. Planned522/51 counts are unchanged; runtime verification must use the replacement candidate.

## Current CI4 automated gates

Candidate1544d071cabb5b71d2dab43e7108989537af0a61 / treed788c22cd8633918237045c4ae711f8b85971617 / testedmerge920f797d23c9bf5b1fa14f43fda68737c22d1539, base778ed089. Quality40107025146/job120910722551 SUCCESS:271tests0 failures/errors/skips, audit0 and all quality gates. Docker40107025144/job120910722572 has completed its PostgreSQL/SMTP invitation step successfully and is running browser regression. Browser522,51captures and restart remain pending until completed logs and visual inspection. No final acceptance yet.

## CI4 browser failure and candidate5 isolation

CI4 native PostgreSQL/SMTP completed, including real transport outage, five-attempt exhaustion, preservation of the old capability/deadline and explicit acknowledged replacement. Browser stopped at INV-B01 after83 baseline passes (438 unrun): native expiry fixtures shared organization9090 with browser, and two deliberately shortened expiries violated the strict createdAt+72h DTO invariant. The list correctly failed closed. Three desktop edit/review/recorded captures were downloaded, SHA256 verified (`311fe5ee2bf8eae79bca87455072cf3302a697c754d1730249e9a8b71290e13a`) and inspected by coordinator; they are partial evidence, not the final51-image gate.

Candidate5 isolates native organizations9292/9393 from browser9090/9191, preserves all original memberships, keeps canonical hash vectors pinned to their published organization, and makes both expiry barriers preserve the72h relationship. Native handoff checks coherent expiry and untouched browser collections. No application DTO validator, lifecycle assertion, timeout or browser oracle was relaxed. The full replacement candidate must pass before final acceptance.

## CI5 dirty-navigation test correction

CI5 native gates and Quality passed. Browser B01–B07 passed after83 baseline instances. B08 used page.goto to enter the form, so history.back was cross-document beforeunload (empty browser-controlled text), not Angular canDeactivate. The next candidate enters through the real Mời thành viên routerLink and requires confirm type plus the original Vietnamese message, then independently checks reload beforeunload and explicit accepted Back with zero writes. No application guard changed. Workflow ordering runs invitation51 first and baseline471 second, disjoint collection verified locally; all522 are still required, one worker, retries zero and first failure stops the gate.

CI5 partial artifact12564634780 contains9 desktop invitation states, SHA256496ff7ec27c9337f287511226ff9aed343bf84006245c3637185adae3638c845. Coordinator downloaded, verified and inspected all9 (edit/review/recorded/list/detail/resend-review/revoke-review/unknown/denied); readable dark text, clear primary/secondary actions and no observed overlap. These do not substitute for the final51-image responsive gate.

## CI6 history-restoration source finding

B08 reached and passed both the real confirm type/message on rejected Back and beforeunload cancellation, preserving the draft. A subsequent accepted Back went to /workspace instead of the preceding invitation list. Installed Angular StateManager defaults canceledNavigationResolution to replace, replacing the preceding history entry when a popstate navigation is rejected. Main changes router configuration to computed so rejected traversal restores the original history position. Expected destination and zero-write oracle remain unchanged; all baseline navigation tests must rerun because this is global router configuration. Local lint/format/production build passed on the corrected source; runtime remains pending replacement candidate.
