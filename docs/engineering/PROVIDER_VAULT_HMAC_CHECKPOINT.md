# Versioned Vault HMAC reader candidate

Explicit VaultHmacKeys implements both existing key ports with a SecretKey return.
Separate configured instances/purposes and Vault keys are required for credential
integrity versus command receipts. This is a KV-exported managed-key integration,
not a non-exportable Vault Transit/HSM implementation: usable HMAC key bytes enter
the trusted JVM only. No app DB key, fallback, write API or automatic bean exists.

Current version is an explicit positive integer deployment input. Historical
key(version) never substitutes current material: canonical version, KV metadata,
purpose, algorithm and exactly32 decoded bytes must match. Missing, deleted,
destroyed, foreign-purpose, oversized or malformed responses fail closed.
SecretKeySpec owns a copy and returns fresh encoded copies; transient adapter
buffers are erased best effort. JVM/provider copies remain outside any universal
erasure guarantee. No key cache or key-value logging is added.

The bounded TLS VaultHttp transport is extracted unchanged in behavior from the
credential adapter and reused. Existing redirect, hostname, bounded-body and
timeout behavior remains; both callers refuse active transactions. Explicit
token and CA trust remain operator inputs. No production token provisioning or
renewal is installed.

The separate provider-hmac-reader policy can read only two exact KV data paths;
it cannot read provider credentials or write keys. The credential application
policy cannot read HMAC paths. Operator/bootstrap identities alone provision
fresh independent random keys and versions; retain historical versions while
referenced by receipts/credential metadata and backups.

The real Vault IT candidate now checks exact separately provisioned key bytes,
historical v1/current v2 lookup, separate command-key lookup, missing-version and
noncanonical-version refusal in both pre/post-restart phases. The disposable
shell harness also checks denied writes and denied cross-policy reads. Key
fixtures/tokens are private temporary files removed with the test instance.
These assertions are authored until the matching native workflow passes.
No live credential, API, CREATE/ROTATE completion, production key rollout or
durable reconciliation is delivered by this candidate.
