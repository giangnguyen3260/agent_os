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
Source compilation and independent review pass. Execution awaits CI; no
candidate CI PASS is claimed.

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
This test-only correction needs its own managed runtime result.
