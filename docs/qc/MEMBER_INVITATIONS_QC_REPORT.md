# Member invitations — bounded development gate verified

[PR25](https://github.com/giangnguyen3260/agent_os/pull/25) remains draft/unmerged on `feature/member-invitations`, stacked on PR24. This report covers the invitation slice only. No production or Mac deployment acceptance.

## Exact candidate

| Binding | Value |
|---|---|
| Application/test candidate | `31270e8388d7bd02430786830cd3492bff51ccaa` |
| Application/test tree | `60c1622fddcf1aa1b8d2be0144c45b63d534263d` |
| Tested PR merge | `06969a2008f91c84ae2d7c08de0abc2cd8680f8f` |
| Base | `778ed08911f761eaa3a5397291414a560679248e` |
| Quality | [40186580919](https://github.com/giangnguyen3260/agent_os/actions/runs/40186580919), job121148529888 SUCCESS:271BE,0fail/errors/skips, audit0 and all quality gates |
| Docker | [40186580915](https://github.com/giangnguyen3260/agent_os/actions/runs/40186580915), job121148529822 SUCCESS: native PostgreSQL/SMTP,51+471browser and restart/logout |

Executed browser gate: **522 passed**, zero failures/skips/retries:51 invitation instances (17 cases ×3 widths,2.0minutes), then471 baseline (14.6minutes). Backend **271 passed**,0failures/errors/skips:192 baseline +79 additions (59 @Test methods and20 parameterized inputs). B13 URL erasure, B17 same-document replacement, B08 dirty Back/reload and baseline AD-B10 all passed at1440/768/390. Native PostgreSQL/SMTP and backend restart/session/logout passed. This report binds the tested application/test candidate above; a later documentation-only closure does not change that tested source.

## Delivered boundary

Organization Admin invites organization MEMBER only. Reviewed create/resend/revoke, durable actor-scoped receipts and explicit lost-result recovery use real persistence. Public invitation review supports new accounts, inline sign-in for existing accounts, mismatch/logout, expired/invalid, unavailable, success and unknown acceptance. There is no automatic acceptance or new-account login. Existing account credentials/profile/session/other memberships are preserved; existing membership retains its role. A real new join advances the retained role revision. Reviews warn that retained eligible approval grants may become usable again on rejoin; invitations issue no grants.

Fixed72h deadline,60s resend cooldown and alternate current-Admin resend reauthorization are documented engineering defaults, not invented historical business approval. Another Admin resend explicitly takes issuer responsibility; the old capability remains usable until SMTP-acknowledged replacement commits. Tokens are32 random bytes generated in sender memory, SHA256 only in storage, with metadata-only durable jobs and five bounded attempts. SMTP ACK is not exactly-once delivery or proof of human receipt.

V015 is additive, without production seed. Organization-first, global email-digest and sorted-human locks, post-wait issuer/authority/expiry checks, retained-ledger overflow and transactional provenance protect enrollment. Public POSTs still require CSRF; only the two exact inspect/accept endpoints are added to anonymous transport.

## Executed gates

- Quality: Java21 compilation, Google Java formatting/imports, JUnit, FE lint/format/tooling/actual production DTO validators and canonical SHA256 vectors, audit and Angular production build.
- Native PostgreSQL/HTTP/SMTP: strict wire/auth/replay, digest-only real delivered links, one-time new/existing acceptance, no account overwrite, rejoin revision/overflow, disabled-recipient privacy, organization/global-email/sorted-human lock barriers, expiry after waits, latest issuer rebinding, audit rollback, fixed-expiry resend/revoke and immutable historical receipts.
- Real exclusive Mailpit pause/unpause: observed SMTP attempts and five-attempt exhaustion, FAILED job does not restart by itself, old digest/deadline preserved, explicit new resend acknowledged and old capability invalidated. CI retry1second only; production60seconds and SMTP timeout bounds unchanged.
- Browser: all522 instances, no retries, one worker, real responses before response faults. Includes dirty Back/reload/accepted exit, stale responses, focus/account/role loss, private-DOM cleanup, availability versus definite invalidity, unknown result recovery, no early writes, fragment erasure and same-document replacement.
- Visual: download/hash verification and coordinator inspection of all51 invitation captures at1440/768/390, against the48 editable Figma handoff views (16 states ×3). Seventeen captured states include a separate denied state.
- Docker healthy stack, actual migrations/persistence, backend restart/session/logout checks and independent QC evidence review.

## Local verification

FE lint, Prettier, actual production invitation validators and three canonical SHA256 vectors, Angular production build, Java formatter/imports, workflow/Compose YAML parse, Python AST, browser Node syntax and disjoint51/471 collection passed. Local Java17 cannot compile this Java21 project; local Maven/Docker are unavailable. CI supplies the actual Java21/PostgreSQL/SMTP/browser evidence. Main fetched high-fidelity Figma contexts/screenshots for mobile new account, desktop review and tablet inline sign-in; design-only review is not runtime acceptance.

## Historical failures and corrections

All entries below are historical, not successful completion of the current candidate. Base remains778ed089.

| Candidate | Quality / Docker run | Actual outcome and correction |
|---|---|---|
| `662c1b274fa2f5843c53c84ab9619abd0feeaebb` tree`610b3013152563ef3ae901e76ec34d470cf01a03`, merge`972e72f800feb0952dede567a08ce4f198ec77a9` | 40105133334 / 40105133250 |269BE:268pass/1fail. MockMvc csrf() added a query parameter rejected by the strict guard. Tests now use csrf().asHeader() and add positive header/malformed-wire regressions; production guards unchanged. No native/browser/restart. |
| `13d5bd7466bb79e158f72cb3a370aaa836755bcc` tree`83d930abced9e35aa563c8e442913fba94adfffa`, merge`91be3ef80dfc9dbf80069549a461365dfc9c4f96` | 40105656998 / 40105656969 | Quality271pass/audit0. Native14 earlier groups passed;45s SMTP exhaustion failed because Compose did not forward retry1s (container retained default60s). Added pass-through with unchanged production defaults/deadlines. No browser/restart. |
| `aeb87c31b38b759a406775e597baef5e64e5f689` tree`f2c39a4bb8912f41f84a165e36c49a120789f3db`, merge`bf7f2ccf178604304a384a231561dcd1d78c84aa` | 40106187787 / 40106187774 | Quality271pass. Five actual SMTP attempts exhausted correctly; replacement after container stop/start missed18s. DNS/readiness was a hypothesis, not proven. Isolated controls changed to pause/unpause, bounded HTTP readiness and safe state/attempt diagnostics. No deadlines/assertions relaxed; no browser/restart. |
| `1544d071cabb5b71d2dab43e7108989537af0a61` tree`d788c22cd8633918237045c4ae711f8b85971617`, merge`920f797d23c9bf5b1fa14f43fda68737c22d1539` | 40107025146 / 40107025144 | Quality271pass; all native including SMTP passed. Browser83pass/1fail/438unrun: native expiry rows polluted the browser org and violated strict72h DTO coherence. Isolated native9292/9393 versus browser9090/9191, preserved memberships/golden vectors, coherent expiry barriers and explicit clean handoff. Production validator unchanged. |
| `56ed47134b0d47fa3aa7cb67e8f6f2ed50aa4699` tree`a25a5729a080ee8573f03c47f8c770320162cb6d`, merge`5cd4d42a9e625b28f0335c1260f4e99cddad4368` | 40108005028 / 40108005024 | Quality271pass/native passed. Browser90pass/1fail/431unrun. B08 entered with page.goto, making Back cross-document beforeunload with browser-controlled empty text. Test now enters through actual routerLink and explicitly checks confirm type/message. |
| `81b2bbf52788290128b8c6b555e1e6e6ec0c8f45` tree`7d89dc8a8fe9924ae85a03334d74e091b2ac275c`, merge`58f82e0a307fb3a94d8a9d4b61b2da2db3ea3ee1` | 40108825226 / 40108825247 | Quality271pass/native passed. Invitation7pass/1fail/43unrun; baseline471/restart unrun. B08 rejected Back and reload correctly, but accepted Back skipped the list. Installed Angular defaults history cancellation to replace. Current candidate sets computed restoration; test additionally awaits restored form URL, preserving destination/draft/zero-write oracles. |

Additional FE corrections made between earlier candidates separate public inspect400 INVALID_INVITATION from transport/malformed/unavailable errors, and management availability from definite authorization denial, retaining private cleanup and unknown-outcome behavior. Same-document token replacement is scrubbed before dirty confirmation; obsolete asynchronous responses are fenced. Full browser gates must verify these implementations.

## Partial visual evidence

CI4 artifact12565013326:3desktop edit/review/recorded PNGs, ZIP SHA256`311fe5ee2bf8eae79bca87455072cf3302a697c754d1730249e9a8b71290e13a`.

CI5 artifact12564634780:9desktop edit/review/recorded/list/detail/resend-review/revoke-review/unknown/denied PNGs, ZIP SHA256`496ff7ec27c9337f287511226ff9aed343bf84006245c3637185adae3638c845`.

Coordinator downloaded, verified and inspected all images in each partial set: readable dark text, clear primary/secondary actions and no observed overlap. Neither set substitutes for the final51 responsive captures.

## Remaining limits

Sonar remains paused. No first-Admin bootstrap, Team Settings/Organization Map, provider/tool authorization, Agent activation or Mission/Task execution is delivered by this slice. No production external-SMTP delivery, abuse/retention/KMS/load/failover or complete accessibility acceptance. Engineering defaults and draft PR state remain explicit. Completing invitation gates does not complete the remaining project screens.

## Token URL correction after candidate7

B08 dirty history restoration passed in CI7. B13 reopened an expired invitation on the same document: the real inspect returned400 INVALID_INVITATION, but Angular committed its captured fragment back after the native hashchange handler scrubbed it. The next source subscribes to completed/skipped router navigation and scrubs the URL again without consuming a capability, resetting review or issuing another request. Hashchange consumes event.newURL so an earlier router scrub cannot erase the incoming capability before its handler reads it. Subscription ends with component destruction. Existing B13 URL/invalid/no-write and B17 dirty-cancel/replacement/late-response oracles remain unchanged. Runtime acceptance remains pending the new candidate.

Independent source reviewer Turing inspected the token URL correction against the contract and B13/B17: no actionable critical race/security issue found. This is source review only; runtime approval remains pending. No assertions, case counts or retry settings changed. Local FE lint/format/production validators/build passed on this source.

## Candidate8 browser outcome

All51 invitation browser instances passed, including B13 and B17 at all3 widths. The baseline stopped at AD-B10: synthetic pushState({},...) discarded Angular history page metadata, so computed cancellation could not determine the previous entry position. Its prompt, old-content and single-POST contract remains required. A bounded test-fixture correction is under independent QC review; no production guard is being bypassed and no assertion removed. All471 baseline instances and restart must complete on the final candidate.

## Complete invitation visual review

Candidate8 `38c67622276d5502f686a699c01df4a8b8de70ba`, tree`2bf719b35f03d7485088f62f82534d75d48898cd`, merge`7cfb2633e122fb2e2263953ed5b4bab0680475fe`: Quality40185649031/job121145756173271pass; Docker40185649053/job12114575618051invitation pass,43baselinepass/1fail/427unrun, restart unrun. All51 invitation PNGs in artifact12596203147 were downloaded and ZIP SHA256 matched metadata: `9353beed2c5fb7a5a9bc423541ad1a498d08f00685f8d964364845f89feaf79e`. Coordinator inspected all17states across1440/768/390, plus full-resolution vertical segments for long tablet/mobile lists: readable ink text, clear action hierarchy, wrapped recipient/receipt values and no observed overlap or horizontal overflow. Mobile long forms/lists scroll vertically as intended. This is bounded synthetic visual review, not full accessibility certification.

Candidate9 changes only four baseline fixture call sites, one shared test helper and this report. Application sources are byte-identical to candidate8. The helper retains Angular history state, advances its page index for the pushed entry and dispatches that stored state; it fails explicitly without a valid router page index. It models managed history for synthetic parameter-reuse tests; actual native Back/reload remain covered by B08. All original prompt/URL/DOM/single-write oracles,522-case collection and zero retries remain. At that handoff, final baseline/restart evidence was pending; execution is closed below.

## Final executed evidence

Quality40186580919/job121148529888 completed SUCCESS. Docker40186580915/job121148529822 completed SUCCESS. Native invitation output at2026-10-25T10:58:46Z confirms wire/auth/receipts, real SMTP new/existing enrollment and digest-only one-time joins, organization/global-email/sorted-human lock barriers, post-wait issuer/expiry/ledger checks, audit rollback, overflow, fixed deadline, queued replacement, issuer loss/explicit reauthorization, historical receipts and real transport outage/retry/five-attempt exhaustion/recovery. Native/browser isolation and coherent72h fixture handoff passed.

Invitation browser51 passed at11:01:19Z; baseline471 passed at11:15:57Z. AD-B10 passed desktop/tablet/mobile with the corrected managed-history fixture and original assertions. Post-browser native gates passed. At11:16:46Z the exact session survived backend restart and logout rejected replay. Dates/times here are log UTC evidence, not local deployment times.

Final run artifact12596661547 contains51 PNGs,17 per viewport. Coordinator downloaded ZIP and SHA256 matched GitHub metadata: `92065d54a855e31cf670e5409c27f605f22a91afe14e1c10b0159957c6eeb61b`. Full51 visual review described above uses candidate8's byte-identical application source; final candidate9 changes only test fixtures/report and reproduces all51 responsive executions. No application UI change requires repeated visual certification.

Earlier chronological pending/failed statements document those candidates only; they do not override this final result. No known blocking finding remains for this bounded invitation implementation. PR25 remains draft/unmerged, stacked on PR24. Production, Mac deployment and the other remaining screens are not accepted by these development results.

Independent reviewer Turing separately retrieved completed Quality/Docker logs and GitHub bindings, verified head/tree/tested-merge/base parents,271 and51+471 counts, B13/B17/B08 and affected baseline navigation oracles at all3widths, native PostgreSQL/SMTP steps, restart/logout and successful teardown. No blocking issue found in that bounded review. Artifact download/hash/count verification and visual inspection are coordinator evidence, not independently repeated by the reviewer.
