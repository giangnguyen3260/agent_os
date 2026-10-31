# BA-03 provider connection test cases

Status: authored design cases, 31 October 2026. None are executed or passed by this document. Normative target: [API contract](../engineering/PROVIDER_CONNECTION_API_CONTRACT.md); requirements and lifecycle references there apply. Comprehensive future slice, not an implementation/QC completion report. No code, migration, Figma or live-provider changes are part of this delivery.

## Fixtures and evidence rules

Use disposable organizations A/B; enabled ADMIN A1/A2, MEMBER A3, B ADMIN B1, disabled/revoked/session-credential-stale variants; one same-org and one foreign connection for each provider, enabled/disabled variants, revisions near MAX and independently unavailable key-service/envelope variants. Use generated disposable sentinel secrets in test process memory, including a deliberately low-entropy syntactically valid sentinel to test absence of offline plaintext verifiers. Never put production secrets or actual key values in reports. Instrument a credential-redacting mock HTTP transport, KMS mock with versioned independent encryption/HMAC keys, deterministic database/monotonic clocks, shared durable policy across two instances, DB fault injection and observed lock barriers. External key-service instrumentation must prove calls happen after transaction/lock release; persisted actor credential versions and page-authorizations are inspected internally, never exported in public evidence. Protocol fixtures are mocks, never “live connected” evidence.

For every write oracle, snapshot all existing domain/configuration/grant/history/receipt tables and target new tables before/after. Assert exact expected row/column deltas, no generation call, no unexpected outbox/audit and no cross-tenant effect. Count only actual executions. Each evidence entry must identify case ID, commit/environment, transport (mock/live), sanitized request/response, DB assertions, observed timing/barriers, result and artifact locator. Browser evidence uses real backend plus disposable credentials, not a local success stub. PostgreSQL races must wait on observed locks, not timing sleeps. Failure injection logs must remain redacted.

## Authority and strict wire

| ID | Action / setup | Required oracle |
|---|---|---|
| PC-A01 | Call every route unauthenticated, MEMBER, disabled account, stale session credential, revoked membership | Existing401/403 envelope, no metadata/model/receipt leak, no business mutation or provider request |
| PC-A02 | Unauthorized caller sends malformed path, query and oversized/malformed body | Authority gate wins before route-specific parsing; no input echoed |
| PC-A03 | A1 addresses org B; A1 addresses B connection/check under org A; mismatched check/connection in A | Foreign org403; scoped resource404; no foreign metadata/name query or existence leak |
| PC-A04 | A2 queries A1 receipt key; same key owned by B1 or another org | `{receipt:null}` for authorized absent own namespace; never another actor's receipt |
| PC-A05 | Remove ADMIN/disable account/change credential while command waits on org/account lock | Fresh post-wait denial; zero new receipt/event/audit/outbox/credential changes |
| PC-A06 | Missing/wrong CSRF or duplicate Idempotency-Key on each POST | CSRF rejection or400 as applicable; zero effects; GET remains read-only |
| PC-W01 | Missing/extra/duplicate fields at each object nesting, wrong nullability/type, trailing object, malformed UTF-8, JSON coercion |400 BAD_REQUEST, no secret echo and no provider call; isolated parser leaves old endpoints unchanged |
| PC-W02 | Body8192/8193 bytes, unsupported media, non-UTF8 charset, GET body, unknown/duplicate query | Valid boundary only if schema valid; excess/invalid400; bounded predecode allocation |
| PC-W03 | UUID uppercase, invalid version/variant; revision number instead of string, leading zero, negative, >MAX |400; no normalization into an accepted command |
| PC-W04 | page absent/0/100000/100001, negative, decimal, leading zeros, repeated page | Default0/fixed20; out-of-range or noncanonical400; no unbounded page size option |
| PC-W05 | Name whitespace/nonblank boundaries100/101, controls, line separators, surrogate errors | Strip and persist safe name only; invalid400; no HTML execution in UI |
| PC-W06 | Key length1/4096/4097, whitespace, CRLF, nonASCII; workspace valid/null/header injection | Valid syntax accepted without prefix/entropy guess; invalid400; no trimming secret; invalid workspace cannot become header |
| PC-W07 | Provider/mode mismatch; Anthropic options missing workspaceId, non-Anthropic extra options; URL/OAuth/project/header fields |400; modes/options exact; zero arbitrary outbound input |
| PC-W08 | Decode every success/error DTO, pending/terminal nullable fields, timestamps and revisions | Exact field sets and existing envelopes; null fields present; no raw secret/digest/key-reference/provider payload |

## Metadata, installer and historical reads

| ID | Action / setup | Required oracle |
|---|---|---|
| PC-M01 | Install each single provider, each pair and all3 without rebuilding; two API/worker instances share policy | Options show3 actual flags/fixed order and same durable revision on both instances; no default; unready instance cannot authorize from stale cache |
| PC-M02 | Create each direct mode with appropriate options |201 receipt; one Connection ENABLED revision1 credentialRevision1, one encrypted active credential/event/receipt/audit; zero check/outbound/generation |
| PC-M03 | Create multiple same-name connections across providers/orgs; reach100/org | Distinct IDs/scoped credentials; names not identity;101st409 CONNECTION_LIMIT; B quota independent |
| PC-M04 | List/detail empty and mixed enabled/disabled connections; concurrently create between offset pages | Actual total, fixed20, createdAt/id order; no foreign rows; document allowed page drift without duplicate row within a page |
| PC-M05 | Rename then no-op normalized rename | Revision+1 only; credentialRevision/envelope unchanged; no-op409 NO_CHANGE with zero extra receipt/event |
| PC-M06 | Rotate enabled and disabled connections; repeat identical bytes deliberately | Revision+1 and credentialRevision+1 each time; state/options/provider retained; old active envelope removed atomically; no provider check |
| PC-M07 | Disable then repeat; enable then repeat | First revision+1, second409 NO_CHANGE; no credentialRevision change and no automatic provider request |
| PC-M08 | Enable missing credential or disabled provider; rotate/rename/disable under disabled adapter | Enable blocked by specific409; metadata/rotate/disable allowed per contract, historical reads preserved |
| PC-M09 | Missing envelope; key-health unknown/unavailable/expired; latent ciphertext tampering before dispatch | GET performs zero KMS/decrypt/provider calls and zero refresh/enqueue/write; UNAVAILABLE for missing/known unhealthy evidence; latent tamper may still project PRESENT until unwrap; rename/disable work with HMAC up; encryption-dependent fresh commands fail closed |
| PC-M10 | Revision MAX and credentialRevision MAX separately | Any required increment409 REVISION_EXHAUSTED with zero partial effects; no wrapping; unchanged check acceptance can still follow its own gates |
| PC-M11 | Read/replay receipt after rotate/disable/rename/policy toggle and health usable→unknown→unavailable | Historical health/mask and before/after remain byte-equivalent to committed snapshot forever; Detail projects current evidence separately; GET receipt independent of encryption/HMAC services |
| PC-M12 | Legacy Agent provider/model strings and current activation path after successful check | No Agent binding/status/grant writes; activation remains blocked; no fallback or provider credential inferred |
| PC-M13 | Create/rotate/enable/read/startup/restart | Zero upstream calls including SDK background probes; no automatic connection success/model list |
| PC-M14 | Key-health evidence valid just before validUntil, at equality and after restart with no fresh evidence | PRESENT only before expiry with verified-at-write storage/no known integrity failure; equality/unknown UNAVAILABLE; repeated GETs leave all state and observer call counts unchanged |
| PC-M15 | Policy mutation with empty set, MAX revision; instance cannot lock/read authority | Reject invalid mutation atomically; no wrap/empty configuration; fail closed readiness and commands; policy writer never takes org lock |

## Idempotency, atomicity and secret-free UNKNOWN

| ID | Action / setup | Required oracle |
|---|---|---|
| PC-I01 | Same actor/org/key/body twice for every operation, varying JSON field order/spacing | First201/replay200 exact same receipt, one effect; check replay never new dispatch/check |
| PC-I02 | Same key with changed secret/name/revision/target/operation/mode/options |409 IDEMPOTENCY_CONFLICT; namespace independent of target/operation/body; no extra event |
| PC-I03 | Same key distinct actor/org | Independent namespace, fresh authority and gates; no receipt collision/leak |
| PC-I04 | Commit, then mutate connection/state/quota/provider; replay original | Receipt match wins before mutable gates; historical result returned; fresh authority still mandatory |
| PC-I05 | Concurrent same-key same-body; same-key different-body; different keys same revision | One effect for matching; one winner and digest conflict for mismatch; one revision winner for stale competing writes |
| PC-I06 | Fail connection/credential/event/receipt/audit/outbox insert independently | Entire transaction rolls back; no persisted raw key or partial active envelope/check; no upstream call |
| PC-I07 | Low-entropy secret commands inspected in DB/audit/outbox/logs/backups/Trace/APM | Only authenticated encrypted provider value and keyed request digest internally; no plain hash/verifier/prefix/suffix/raw command; public artifacts sanitized |
| PC-I08 | Change JSON formatting vs one exact secret byte; include changed expectedRevision | Canonical equivalent matches; semantic/secret/revision changes conflict; constant-time comparison path instrumented, not timed as proof |
| PC-I09 | Rotate HMAC key; replay old receipt; remove access to old key | Historical version verifies while available; unavailable503 KEY_SERVICE_UNAVAILABLE without weak fallback/new write; GET receipt still works |
| PC-I10 | Drop or corrupt committed CREATE/ROTATE response in browser | Local UNKNOWN, secret/form/frozen body cleared, no automatic resend; retain secret-free actor/org/key locator only; lookup finds safe receipt |
| PC-I11 | Receipt absent while original transaction held before commit, then commits | Null not declared failure; no automated new key/resubmission; later lookup recovers one committed outcome |
| PC-I12 | User explicitly recreates absent-result CREATE or rerotates after review | List reconciliation/warning before create; new deliberate key may create distinct ID; rotation expectedRevision protects stale second command; no false exactly-once claim |
| PC-I13 | Corrupt saved receipt JSON or lose DB response | Safe503/local UNKNOWN, no raw storage output or re-dispatch; storage restored then own lookup recovers |
| PC-I14 | Actor/org/session changes while UNKNOWN lookup or submit in flight | Private state cleared and late reply fenced; another actor cannot recover receipt; original actor with restored authority can |
| PC-I15 | Metadata-only UNKNOWN explicit same-body resend | Same key and canonical body, one effect; no automatic new key; body mutation conflicts |
| PC-I16 | Replay CREATE/ROTATE with encryption service down and historical HMAC available, after mutable gates change | Probe receipt before any encrypt call; verified replay200 exact historical receipt, zero encrypt preparation/new effects; GET receipt independent of both key services |
| PC-I17 | Barrier after absent probe; another request inserts receipt with historical HMAC version while current HMAC rotates | Final locked receipt reread wins; release all DB locks before obtaining unprepared historical key; bounded repeat verifies or conflicts; no encryption/digest fallback/duplicate commit |
| PC-I18 | Race receipt insertion while fresh encryption preparation fails | Fresh own-receipt reprobe finds raced receipt; available historical HMAC permits replay without working encryption, within preparation bound; no fresh mutation |
| PC-I19 | Continuous HMAC/preparation races or slow key service, and stale rotation AAD inputs | At most3 passes/10s total and2s per remote call capped by remaining budget; no KMS/HMAC network call under DB locks; exhaustion safe503/no effects; changed credentialRevision rejects, no wrong-AAD commit; UNKNOWN remains receipt-recoverable |

## Encryption lifecycle and deployment recovery

| ID | Action / setup | Required oracle |
|---|---|---|
| PC-E01 | Create same secret twice and inspect envelopes with internal test decryptor | Independent DEKs/nonces/ciphertexts, AES256-GCM128 tags, exact plaintext roundtrip internally only; public constant mask identical |
| PC-E02 | Tamper ciphertext/tag/nonce/AAD or swap envelopes, then execute the defined dispatch unwrap path | GET does not magically authenticate storage; unwrap detects failure before send, records integrity failure by active-envelope-version CAS, no plaintext fallback; subsequent projection UNAVAILABLE; no secret error text |
| PC-E03 | Attempt unwrap as browser ADMIN, receipt-reader identity, wrong deployment identity | Denied by KMS boundary; no API exposing key material/key references; application identity limited to approved key versions |
| PC-E04 | KMS encrypt succeeds then DB commit fails | No persisted orphan active provider credential/receipt; temporary buffers released; safe KMS audit without plaintext |
| PC-E05 | KEK rewrap concurrently with provider rotation | CAS prevents stale envelope overwrite; active plaintext remains current; rewrap alone leaves credentialRevision and provider observations unchanged |
| PC-E06 | Restart/restore encrypted DB with correct and missing KEK versions | Correct restore decrypts only in gateway; missing key remains UNAVAILABLE; metadata and disable recover; no hardcoded default key |
| PC-E07 | Attempt retire old KEK/HMAC versions while envelopes/backups/receipts retained | Deployment control blocks destructive retirement; restore/replay evidence required before permitted key retirement |
| PC-E08 | Rotate while old request already authorized | New authorizations cannot use old credential; preauthorized call may complete; no promise of remote key revocation or erasure from retained backups |
| PC-E09 | Sentinel capture across ingress, exception/toString, failed validation, provider errors, crash diagnostics and browser storage | No raw key or reversible request dump in persistent sinks, examples or report; error bodies/header capture disabled; no browser persistent secret |
| PC-E10 | Health observer checks KMS independently while metadata reads run; rotate/rewrap races integrity failure publication | Observer external I/O outside DB locks; only safe health evidence persisted; version-CAS cannot poison newer envelope; GET never refreshes; key-health success alone cannot clear known integrity failure |

## Asynchronous check ordering and result truth

| ID | Action / setup | Required oracle |
|---|---|---|
| PC-C01 | Accept access check and hold worker |201 receipt with before=after/checkId; metadata revision unchanged; Check QUEUED with null outcome, no claimed remote success |
| PC-C02 | Explicit discovery without previous access check, and access check alone | Independent check jobs; access check exactly one GET and zero retained models; discovery only models GETs; no hidden chained job |
| PC-C03 | Rotate/rename/disable before dispatch; two workers observe durable policy off/on ABA | No send from stale revision; SUPERSEDED/REVISION_CHANGED; locked singleton revision prevents both workers from using cached enabled state |
| PC-C04 | Capture acceptedActorCredentialVersion, then change account credential version before dispatch | Persisted accepted version unchanged; comparison with new current version denies even if account remains enabled ADMIN; FAILED/AUTHORITY_LOST, no send; credential version absent from public DTOs |
| PC-C05 | Mutate/revoke while first-page request blocked, then return200 | Terminal SUPERSEDED with correct revision/authority code; no published model snapshot/current success; no next-page send |
| PC-C06 | Race connection disable and singleton policy update against final authorization/publication in both lock orders on two workers | Org→policy→account→scoped order; policy writer locks policy only, never org afterward; later page/publication denied by revision; earlier authorized send may occur; no deadlock or remote cancellation claim |
| PC-C07 | Duplicate delivery and concurrent workers for same check/page ordinal | One owner/token and unique(checkId,pageOrdinal) send permission; duplicate delivery cannot consume it again or reacquire token; no duplicate send/publication |
| PC-C08 | Crash before dispatch token vs after token/before send vs after send | Queued may safely dispatch once before expiry; any token-bearing abandoned RUNNING becomes UNKNOWN without automatic resend, even if send not proven |
| PC-C09 | At queueExpiresAt equality and dispatchDeadline equality, pause worker before sweeper; restart | Queue equality FAILED/CHECK_EXPIRED with no send; running equality UNKNOWN, no page authorization/success; sweeper by deadline+5s, token/deadlines durable |
| PC-C10 | Timeout then late200; duplicate/out-of-order completion | Terminal UNKNOWN immutable; late callbacks do not overwrite/publish models or current accessible state |
| PC-C11 | Explicit new key after terminal UNKNOWN, fresh revisions | New bounded observation allowed under quotas; original UNKNOWN and receipt unchanged; never silently retried |
| PC-C12 | Successful current check at14:59 vs15:00 and historical after rotation/enable | Accessible only before expiry with exact revision/policy; at expiry STALE; history still readable; enable does not revive result |
| PC-C13 | New pending/failed access check after earlier success; successful discovery only | Latest access check drives observation; no old green status masking pending/failure; discovery does not synthesize access-check success |
| PC-C14 | Disabled provider/connection or missing key plus historical passing result | currentObservation UNAVAILABLE, safe historical Check preserved; no runtime grant |
| PC-C15 | One active check then competing access/discovery command |409 CHECK_IN_PROGRESS; no second check/outbox; terminal job releases capacity |
| PC-C16 | After first complete page, change initiating credential version before next page while retaining ADMIN | Next ordinal authorization compares persisted accepted version and denies; SUPERSEDED/AUTHORITY_LOST; no page2 send or models; fresh account object cannot mask version change |
| PC-C17 | Pause before next-page authorization until dispatchDeadline equality, no sweeper yet | DB clock sampled after locks, full live-owner/token/time predicate fails; UNKNOWN/no models; unique prior page cannot be resent; no deadline renewal |
| PC-C18 | Fully parsed200/COMPLETE or local TRUNCATED ready before deadline; hold publication lock until deadline, sweeper blocked | Publication rereads DB clock after wait and yields UNKNOWN, atomically discards staged snapshot; no transient success; terminal result cannot later be overwritten |
| PC-C19 | Pause after decrypt preparation, change policy/credential/actor authority before final page authorization | Final locked shared-policy/account/revision check denies before send; prepared secret confers no authority; transient buffers released |
| PC-C20 | Publication races policy off/on and actor credential change across two API/worker instances | Shared policy revision plus captured actor version fences publication; atomic Check/snapshot commit, no partial model exposure; both serialized orders observed without lock inversion |

## Adapter wire, pagination, errors and outbound bounds

Run common cases separately for OPENAI, ANTHROPIC and GOOGLE_GEMINI; report each actual execution.

| ID | Action / setup | Required oracle |
|---|---|---|
| PC-P01 | OpenAI adapter capture | Exact HTTPS host/path GET, Bearer key, no project/org override, no prompt/body; parse actual data[].id, no fabricated latest model or continuation |
| PC-P02 | Claude adapter capture with null/selected workspace | Bearer API key only (no x-api-key fallback), exact version2026-10-21; header omitted when null, exact validated workspace when set; limit100 and encoded after_id |
| PC-P03 | Gemini adapter capture including key with URL-sensitive characters | x-goog-api-key header only, never query key; v1beta/models GET, pageSize100 and encoded pageToken; preserve models/ IDs |
| PC-P04 | Valid200 empty list, unknown extra metadata, safe displayName absent | ACCESSIBLE, modelCount0 for discovery, no invented data; null displayName allowed; unknown fields ignored without coercing known fields |
| PC-P05 | Multi-page Claude/Gemini lists; OpenAI catalogue | Sequential correct cursors, frozen ID-sorted unique snapshot; local pages20 with exact retained total; upstream cursors not public |
| PC-P06 | Repeated/missing/nonprogress cursor, >2048byte token, conflicting duplicate ID | FAILED PROVIDER_PROTOCOL_ERROR, staged catalogue discarded, no infinite loop or extra send |
| PC-P07 | Invalid known type, malformed JSON/content type, HTML200, unsafe/oversized model IDs/names | Protocol failure, no partial success or raw body exposed; identifiers never silently truncated |
| PC-P08 | 10 requests,1000 retained models and exact1MiB/page,4MiB total with full valid document/end marker | Equality accepted only after fully consumed valid page; COMPLETE only if confirmed end/no loss; continuation at exhausted budget TRUNCATED before deadline; no arbitrary item drop or unbounded allocation |
| PC-P09 | Later-page401/403/429/5xx/schema error/transport timeout | All staged models discarded; terminal mapped denial/failure/UNKNOWN; no partial COMPLETE or automatic retry |
| PC-P10 | HTTP401 vs403 | DENIED with PROVIDER_UNAUTHENTICATED vs PROVIDER_FORBIDDEN;403 never asserted “invalid key”; HTTP check read200 |
| PC-P11 | HTTP429,5xx,3xx,404 and malicious raw provider error containing sentinel key | FAILED rate-limited/unavailable/protocol respectively; no automatic retry/redirect, no raw text/header leak; command receipt unaffected |
| PC-P12 | DNS/connect/TLS fail proven before send vs ambiguous send EOF/timeout | FAILED NETWORK_UNAVAILABLE vs UNKNOWN TRANSPORT_UNKNOWN; no denied-key inference |
| PC-P13 | Connect3s/request10s/dispatch30s; deadline before next page vs in flight vs before publication | Request deadline=min(pageAuthorizedAt+10s,dispatchDeadline); at dispatch deadline all nonterminal RUNNING outcomes UNKNOWN with no models, including between pages and before sweeper; no late TRUNCATED/COMPLETE |
| PC-P14 | Caller/provider cursor/redirect points at localhost, metadata IP, private IPv6, arbitrary host/path; DNS rebinding | No forbidden connect, redirect or auth-header forwarding; allowlisted public TLS peer verified at connect; no public custom URL switch |
| PC-P15 | Ambient proxy/auth credentials, SDK retries/telemetry/startup probes | Disabled; only captured allowlisted models requests, no generation/messages/responses/interactions/embedding/token-count/batch endpoint |
| PC-P16 | Threshold6/account/min,12/org/min, reads120/min, writes30/min; other actor/org and rolling time boundary | Correct429/Retry-After1..60; no rejected-command effects; independent scopes; valid replay not consumed as fresh write/check |
| PC-P17 | Page beyond retained results or discovery not accessible/access-only check models route | Empty page with actual total when accessible; otherwise409 RESULT_NOT_AVAILABLE, no upstream fetch triggered |
| PC-P18 | Later page crosses1MiB while prior page valid; repeat first page case | Abort entire crossing page; earlier pages only ACCESSIBLE/TRUNCATED before deadline; first page FAILED/RESPONSE_LIMIT; no retained fragment or excess decompression |
| PC-P19 | Later page crosses remaining4MiB total; test exactly4MiB fully consumed valid final page | Crossing yields prior whole pages only/TRUNCATED; equality with complete valid end and no loss yields COMPLETE; total counter includes discarded bytes, no further fetch |
| PC-P20 | OpenAI sole response1000 vs1001 valid items with no cursor; malformed/error suffix beyond local bound | 1000 fully validated items may COMPLETE;1001 FAILED/RESPONSE_LIMIT with no retained models, never COMPLETE; do not read beyond cap to search suffix |
| PC-P21 | Provider/schema/cursor error before cutoff vs error only beyond unread cap; next page exceeds remaining retained-model capacity | Observed definitive error within bound discards all staged pages/mapped failure; otherwise cutoff keeps only earlier whole pages/TRUNCATED; never retain partial last page or consume unread suffix to select error |
| PC-P22 | Cursor2048 UTF-8 bytes vs2049 including multibyte characters, repeated cursor, URL-looking cursor | Byte-based limit and repeated-token failure; only encoded query-value handling on fixed host/path; no public cursor, URL interpretation or unexpected send |

## Browser, integration and release evidence

| ID | Action / setup | Required oracle |
|---|---|---|
| PC-U01 | All-provider list/create/save; separately invoke Check access and Discover models | Check access starts only /access-checks and shows observation, no model catalogue; Discover models starts only /model-discoveries and pages its frozen snapshot with COMPLETE/TRUNCATED; no hidden chained action; discovery never overwrites access currentObservation; save distinct from result |
| PC-U02 | Rotate/disable/enable, failure/stale/history; navigate away or stop polling; inspect list/filter states | No server cancel route/CANCELLED promise: accepted work continues after local stop; list uses only Connection metadata, check revision/time on Detail; any filter applies only to loaded page, no invented server filter; current health/history distinct, enable not runtime grant |
| PC-U03 | Keyboard/focus/error recovery and1440/768/390 layouts from parallel accepted UI design | Real backend fields fit, labels accessible; secret never in URL/DOM attributes/toasts/analytics; no invented Figma parity claim |
| PC-U04 | Navigation/logout/authority loss/late replies with secret form and UNKNOWN | Form/transient commands cleared, no local/sessionStorage/IndexedDB/service-worker secret; identity/epoch fences prevent late display |
| PC-D01 | Apply future migration over populated existing PostgreSQL, restart backend/worker | Existing rows/digests/receipts/configurations retained byte-for-byte where required; new constraints/tenant FKs and pending jobs survive |
| PC-D02 | Real PostgreSQL lock-wait revocation, mutation races, rollback injections, KMS restore and dispatch restart | All corresponding case oracles hold with actual DB locks and restart evidence; H2/mock-only evidence insufficient |
| PC-D03 | Static network route coverage and capture for all complete flows | Zero live billable generation endpoints; no usage/cost-zero fabrication; budget/grant/activation enforcement remains a separate prerequisite |
| PC-D04 | Optional explicitly authorized disposable live models-only request per enabled provider | Record exact mode/version/date and sanitized outcome; no production secret in evidence; failure/UNKNOWN honestly reported; mock not reported live; no nonbillable guarantee |
| PC-D05 | Final independent review of implementation, key runbook, UI handoff and test ledger | Enumerate executed pass/fail/not-run per ID/provider/environment; unresolved security/UNKNOWN/gateway gaps block corresponding acceptance; authored count never treated as passed |

Generation remains prohibited even if all these cases pass until the separate runtime grant, binding, budget-reservation and usage/reconciliation contracts are implemented and accepted. No live smoke is required to author this design or run the mock protocol suite.
