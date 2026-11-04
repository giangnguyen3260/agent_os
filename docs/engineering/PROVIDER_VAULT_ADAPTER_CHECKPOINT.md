# Vault credential adapter checkpoint

4 November2026. Explicit internal VaultCredentialSecrets implements the existing
CredentialBoundary.Secrets port. No Spring bean, endpoint or token is installed.

Configured HTTPS origin and a single validated KV-v2 mount are server-owned.
The adapter disables redirects/proxies and retains platform hostname validation.
Token supplier returns fresh disposable characters; a separate explicit
reconciliation callback is mandatory. No root/default/fallback token exists.
TLS trust uses the JVM truststore; the package-scoped TLS constructor permits
the controlled HTTPS test without changing process-global trust.

Each store uses a fresh random locator and CAS0; version1 must be returned.
Scope fields (org/connection/provider/revision) accompany the credential in the
external Vault only. The opaque locator/version are the only returned reference.
Credential bytes are base64 encoded in the Vault JSON, never application DB.
Read pins version1, validates exact stored scope, live version metadata and
visible-ASCII credential bounds, then returns a disposable owned buffer.
The caller still must verify the independent credential HMAC before outbound use.

Response bodies are capped32KiB, connect/read timeouts5s, body deadline10s checked
between bounded reads. A blocked read may complete at its socket timeout after
the deadline. No transport claim of instantaneous cancellation is made.
Failures have fixed redacted type without original cause. Adapter-owned byte/
token buffers are erased best effort; JVM/TLS/JSON immutable copies cannot be
universally erased. There is no logging of upstream errors or credential bodies.

store/read/failure notification refuse active transactions or synchronization.
CredentialBoundary invokes preparationFailed after an attempted store failure;
the injected callback must durably reconcile ambiguous writes and possibly
committing versions. This adapter does not delete secrets or claim cross-system
atomicity. Direct adapter callers must retain the same reconciliation obligation.

One isolated JUnit method passed locally via ECJ/Java17 against actual source,
Jackson2.18.2 and Spring6.1.14. It starts a real local HTTPS server with a temporary
trusted certificate and covers roundtrip/versioned read, scope substitution,
redirect refusal, token cleanup and no network call under transaction. This is
a controlled Vault-shaped fixture, not actual Vault acceptance of the Java adapter.
Google Java Format1.22 applied. Full Java21 project CI remains separate.

Remaining release gates: actual Java-adapter-to-Vault native integration, malformed/
oversized/deleted/version substitution and outage matrices, durable reconciliation
implementation, scoped managed HMAC service, token renewal/trust provisioning and
CREATE receipt/application/HTTP/UI wiring. PR52 real Vault container tests are
separate evidence and must not be represented as this adapter's acceptance.
No CREATE/ROTATE completion, provider call, merge or deployment is claimed.

## Native integration candidate

The disposable Vault workflow now invokes VaultCredentialSecretsVaultIT through
the actual Maven/Java21 project twice: before and after Vault restart/unseal.
The first invocation writes a synthetic credential with the application token
and retains only a safe random scope/locator fixture in the private temporary
directory. The second JVM reads the same reference and validates exact bytes.
Both phases require scope substitution and unsupported-version rejection.
Tokens pass only through a private temporary file, not command-line values.

The existing real Vault shell policy/TLS assertions remain in the same workflow.
Adapter, credential-port, test and POM changes now trigger this gate. The IT is
explicitly environment-gated and selected by the Vault harness; ordinary unit
test success must not imply the native IT ran. This entry records authored wiring,
not a completed native pass. Full Java21/real Vault logs determine acceptance.
