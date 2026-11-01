# Independent requirements review: provider options and deployment policy

Reviewed: 2026-11-02. Source: PROVIDER_OPTIONS_POLICY_CONTRACT.md, against PROVIDER_CONNECTION_API_CONTRACT.md §§1, 3 and 4 and the existing TaskOwner controller/security, SecurityConfig, RecoveryService and common API envelope patterns. Review is requirements/design only; no implementation, tests, database execution, deployment or acceptance evidence is claimed.

Disposition: suitable for bounded BE delegation, with the clarifications below incorporated into implementation acceptance. No scope blocker found. Runtime/session compatibility and native PostgreSQL evidence remain OPEN.

## Scope and precedence accepted

The slice exposes only public options. Exact Options/ProviderOption fields, fixed provider order/mode/schema mappings, positive decimal-string policy revision and actual persisted enabled flags match the parent contract. No connection/receipt/check/model read or mutation route, credential-health projection, provider adapter or runtime authority is authorized. Existing internal receipt/completion primitives remain unchanged and are not newly policy gated.

V024 creates two empty tables. Trusted explicit provisioning establishes revision 1; there are no startup defaults. Policy replacement serializes only on the singleton, compares expected revision before no-op, rejects overflow and preserves prior state on rejection. The CLI is a trusted deployment control, not an organization ADMIN permission or HTTP writer.

Shared quota is retained, not deferred. The final precedence supersedes earlier review proposals: fresh transport authority → GET method check → security admission → strict request shape → fresh RR projection. Thus malformed admitted GETs consume quota and exhaustion returns 429 before shape errors. Unsupported methods and transport-denied callers consume none. Later shape/read/authority failure does not refund a committed slot. Security admission and session handling are explicit security writes; the projection performs zero business writes.

The existing global chain admits ordinary members for organization reads, so the dedicated chain and independent service ADMIN checks are necessary. Existing recovery code supplies the INSERT ON CONFLICT DO NOTHING pattern, but not proof of the new distributed window, clock or post-wait authorization behavior. Existing common envelopes are reusable; global error handling alone does not supply this feature's safe 503 semantics.

## Concrete clarifications to freeze

1. **Independent transaction boundary.** REQUIRED is compatible with separate admission/read commits only when the HTTP orchestration has no ambient transaction. Require a nontransactional controller/orchestrator invoking separate proxied services; admission must complete before projection begins. Reject unsupported joined contexts as already specified. Test this ordering and commit-failure behavior. Do not use self-invocation or return provisional DTOs before transaction completion.

2. **One sampled time and exact precision.** Derive the appended epoch microseconds and admission updated_at from the same post-lock database clock sample. Specify truncation to microseconds and integer arithmetic throughout; compare stored timestamps at that precision. Validate updated_at and every stored request before pruning, including expired corrupt entries. Define null/nonfinite/out-of-range timestamp handling as safe 503. Do not sample current_timestamp in production or substitute an application clock.

3. **Policy timestamp validation.** Provision must explicitly supply both timestamps from one database-clock sample; replace must preserve created_at and reject malformed timestamp ordering as well as a backward clock. Java and operator SQL must enforce the same selected-row validation, expected-revision/no-op/MAX precedence and rollback semantics. Constraint failure must not become successful repair or overwrite.

4. **Actual chain isolation.** The matcher must include the options path independently of HTTP method, so HEAD/OPTIONS/POST cannot escape into the global chain. Load the same session context repository used by real login. Put the gate after context loading and before any rejecting authorization/CSRF processing. HEAD must suppress bodies on every feature response, including 401/403/503, not just 405. Confirm exact-path, trailing-slash and neighboring-route behavior without extending this feature to unmatched paths.

5. **Accept negotiation.** “Media ranges allowing application/json” needs normal specificity precedence: application/json;q=0 must not become acceptable merely because */* has positive quality. Freeze this example and malformed/duplicate Accept behavior in transport tests. Keep authentication/admission precedence unchanged.

6. **Quota representation and response metadata.** Canonical JSON means compact decimal integer tokens only, without signs, fractions, exponents, leading zeros or whitespace; document the encoder/validator's exact rule. Enforce the 4096 bound on read as well as write and the 120-item bound before admission. A typed rejection must carry Retry-After to the transport without exposing stored timestamps or internal state. Rejection rolls back a candidate INSERT and all pruning/bookkeeping changes; lock acquisition itself is not a persisted quota change.

7. **Security-table deletion choice.** The storage section says FKs “may cascade,” while the selected scope specifies account/org deletion cascade. Freeze ON DELETE CASCADE for both FKs so Java, migration allowlists and the operator/probe do not diverge. No membership FK or per-request history is needed.

## Required acceptance evidence

| Concern | Minimum evidence |
|---|---|
| Initialization | Empty migration; all seven explicit subsets; native concurrent provision with one winner; loser rollback and no query in aborted transaction |
| Policy serialization | Same-revision CAS race; stale-before-no-op; MAX/no-op precedence; ABA increments; full-row rollback; missing/corrupt policy safe 503 |
| Authority/snapshot | Direct service checks; enabled/version/ADMIN/org gates; coherent RR authority/policy view; later fresh read observes commits |
| Shared admission | Two independent service instances against one PostgreSQL DB; exactly 120 accepted slots; final-slot contention; request 121 safe 429 |
| Native waits/time | Observed row wait followed by revocation denial; post-wait clock sampling; exact 60-second expiry; ceil boundaries; future/corrupt data fails closed |
| Rejection/commit | No persisted change on 429/auth loss/corruption; failed initial admission leaves no candidate row; admission/read commit uncertainty safe 503; no in-memory fallback |
| Transport/session | Actual existing-chain cookie login; stale session invalidation; method/HEAD semantics; raw empty query/body/Accept; exact envelope/no-store/Allow/Retry-After |
| Precedence | Denied authority beats malformed wire; unsupported method consumes no slot; admitted malformed GET consumes one; exhausted malformed GET returns 429 |
| Boundaries | No credential/key/vault/provider collaborators; no provider business-table writes; existing global auth/CSRF and neighboring APIs retain regression results |
| Operator | Disposable Docker PostgreSQL CLI provision/show/CAS; explicit choices; no overwrite/retry on uncertain commit; real HTTP projection matches committed policy |

H2 is structural coverage, not native locking, clock or shared-instance acceptance. The contract correctly keeps session/CSRF compatibility open pending evidence. This review authorizes no implementation beyond the stated slice and makes no merge/deploy claim.
