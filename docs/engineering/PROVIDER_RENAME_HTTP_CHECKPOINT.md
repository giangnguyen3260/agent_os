# Rename HTTP and own-receipt checkpoint

10 November 2026. Backend candidate only; native tests authored and matching
CI acceptance pending. Baseline PR75 final
`339083fad7c559eeeacc60f244323f9a3833ae26` passed all eight workflows, including
Docker, Quality and Vault. Baseline success does not establish this new slice.

## Candidate behavior

`agentos.provider-rename.http-enabled=true` independently enables the narrow POST
`/api/v1/organizations/{organizationId}/provider-connections/{connectionId}/rename`.
It defaults off and requires an explicit Rename coordinator. Managed coordinator
assembly separately uses `agentos.provider-rename.enabled=true` and its existing
command-only settings. Neither flag enables Create or supplies credential services.

The security chain resolves current human account and organization ADMIN before
actual session CSRF; strict canonical organization/target/key, request headers
and raw path validation precede bounded body decoding. The frozen UTF8 parser
accepts exactly expectedRevision/name. Eight per-instance nonqueued permits bound
body reads and coordinator invocation, with release on every acquired path.
The existing shared durable write quota remains a distinct later coordinator gate.
Historical replay still requires current authority and historical command HMAC.

An HTTP-enabled Tomcat customizer enforces a 10-second idle upload read timeout
without Create/Vault activation. This bounds a blocked upload read, not total
slow-trickle duration. Other servlet engines are outside this managed assembly.

Own receipt GET activates when Create or Rename HTTP is enabled, retains shared
read admission and raw URI/OptionsGate protection, and dispatches historical
CREATE or enabled RENAME explicitly. Unsupported/disabled Rename projection fails
closed. Missing receipt remains explicit null; unavailable remains safe503.
The existing reader datasource and capability guard remain in force. No writer
runtime replaces the reader and no provider/credential/health call is introduced.

## Evidence and required focused coverage

Thirteen local tests passed: four request-validator cases, five receipt-dispatch
cases, two receipt feature/matcher cases and two Tomcat configuration cases.
The Rename security chain compiles with the pinned dependencies. Two native PostgreSQL HTTP gate/projection tests and two additional managed
Vault HTTP/socket tests are authored. The managed fixture retains its direct
coordinator test, disables Create/list, checks current session/CSRF and own
historical receipt recovery during a command-key outage, and exercises both
eight-slot saturation/idle-release cycles before a real successful Rename.
Source compilation and independent review pass. The initial pending statement is superseded by corrected-head native runtime
evidence below.

Acceptance requires actual session HTTP and PostgreSQL evidence for Rename-only
and combined activation, auth/CSRF before body access, canonical raw path/header/
UTF8 rejection, first201/replay200, stale/NO_CHANGE/quota projection, actor/org
isolation and historical own GET recovery/null/unavailable. Managed timeout/permit
release and retained Create behavior must be covered at the tested implementation
head. Preserve existing exact error envelope and safe no-store responses.

This slice completes backend ingress/lookup only when those matching tests pass.
UI confirmation, responsive states and persisted browser Rename remain subsequent
work. No merge, deployment or overall provider lifecycle completion is claimed.

## First native CI and helper correction

Implementation `155d00a2` passed focused Java21/PostgreSQL run
[42830637891](https://github.com/giangnguyen3260/agent_os/actions/runs/42830637891):
279 tests, zero failures/errors, one retained intentional H2 compatibility skip.
Both new Rename HTTP tests and both retained Create HTTP tests passed.
Vault run 42830637976 passed both direct TLS phases and retained Create tests,
but both new HTTP cases stopped in the shared login helper: the auth CSRF endpoint
correctly returns Spring Security's composite cache header, while the provider
helper required exactly `no-store`. A separate auth-CSRF helper checks that its
cache header contains `no-store`; provider success/error checks remain exact.
This test-only correction passed its managed runtime result below.

## PR76 executed native HTTP acceptance — 10 November 2026

Corrected implementation `01ff76694d30dc6a8ff74197f55a44f17ed03432`, tree
`ab13082`, passed [focused Java21 42831769011](https://github.com/giangnguyen3260/agent_os/actions/runs/42831769011):
279 tests, zero failures/errors and one retained intentional H2 compatibility skip.
[Vault 42831768792/job129883369384](https://github.com/giangnguyen3260/agent_os/actions/runs/42831768792/job/129883369384)
passed both direct TLS phases, all three managed Rename cases and both retained
Create cases, with zero failures/errors/skips. This establishes the new native
Rename ingress/own-receipt and managed timeout/slot-release acceptance. Production
behavior is unchanged from initial PR76 head `155d00a2`; the correction only fixes
the auth-CSRF test helper's cache-header expectation.

Initial [Quality 42830638542](https://github.com/giangnguyen3260/agent_os/actions/runs/42830638542)
passed 1,623 tests, zero failures/errors and one intentional compatibility skip.
Matching corrected-head [Quality 42831768844](https://github.com/giangnguyen3260/agent_os/actions/runs/42831768844)
also passed all 1,623 tests with zero failures/errors and the retained skip, plus
frontend style/build checks. [Docker 42831769004](https://github.com/giangnguyen3260/agent_os/actions/runs/42831769004)
remains running; do not infer all seven workflows green from targeted acceptance.
The final documentation-only checkpoint preserves this tested implementation/run
identity. No merge/deployment or Rename UI acceptance is established.

## Next bounded UI deliverable

No Rename UI is implemented here. Reuse the list's actual configuration revision
and scoped reload, plus existing CSRF/name/error helpers. Add an explicit Rename
capability signal; X-Provider-Rename-Enabled is proposed and not implemented.
Create availability and adapter enabled flags cannot determine Rename eligibility.

Add per-connection normalized name edit, before/after confirmation and a strict
Rename receipt parser; retain Create-only validation independently. Model stale,
NO_CHANGE, canonical Retry-After and authority loss explicitly. UNKNOWN permits
explicit own receipt recovery and, where offered, exact metadata resend using the
same reviewed body/key; changed name/revision requires fresh review/key. No automatic
retry. Responsive contracts and one real HTTP/PostgreSQL persisted Rename remain
required UI evidence; native backend passes do not substitute for them.
