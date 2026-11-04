# Local Vault prerequisite for provider credentials

User decision: 4 November 2026, Vault on Docker. This opt-in Compose overlay
prepares a single-node TLS/Raft Vault; it does not enable CREATE/ROTATE or wire
the backend adapter. Do not treat it as a production HA deployment.

## Prepare and start on the Mac

Use a reviewed official hashicorp/vault image pinned by digest, for example the
exact RepoDigest returned after reviewing/pulling a selected supported release.
Set AGENTOS_VAULT_IMAGE to that complete image@sha256:... value. No floating or
default version is supplied here; operator release/security review remains required.

Create TLS outside the checkout:

    bash docker/vault/prepare-local-tls.sh "$HOME/agentos-local-vault-tls"
    export AGENTOS_VAULT_TLS_DIR="$HOME/agentos-local-vault-tls"
    export AGENTOS_VAULT_IMAGE='hashicorp/vault@sha256:REPLACE_WITH_REVIEWED_DIGEST'
    docker compose -f compose.yaml -f compose.vault.yaml config --quiet
    docker compose -f compose.yaml -f compose.vault.yaml up -d vault

Replace the digest placeholder before use. Ensure the container vault user can
read the mounted directory and private key. On Linux, assign an appropriate
group matching the container vault user; do not make the private key world-readable.
The local self-signed certificate is trusted explicitly by VAULT_CACERT, expires
after30 days and contains localhost/vault/127.0.0.1 SANs. Never use skip-verify.
Use a managed CA for deployments beyond this isolated local environment.

No Docker operation is performed on the user's Mac by adding these files.
Startup fails without TLS/image inputs. Before init/unseal, vault status is
nonzero and the Compose healthcheck correctly stays unhealthy.

## Operator bootstrap (interactive, private terminal)

    docker compose -f compose.yaml -f compose.vault.yaml exec vault vault operator init
    docker compose -f compose.yaml -f compose.vault.yaml exec vault vault operator unseal
    docker compose -f compose.yaml -f compose.vault.yaml exec vault vault login
    docker compose -f compose.yaml -f compose.vault.yaml exec vault vault secrets enable -path=agentos kv-v2
    docker compose -f compose.yaml -f compose.vault.yaml exec vault vault policy write agentos-provider-app /vault/policies/provider-app.hcl

Repeat interactive unseal until its threshold is met. Init output contains root
and unseal material: keep it in an external private password/secret store, never
application DB, source, CI artifact, logs or chat. Login prompts for the bootstrap
token rather than passing it in command arguments. Provision/renew a separate
short-lived application identity using the policy, then revoke bootstrap tokens
and clear CLI token helper state. No app token is generated or embedded here.

The app policy grants create/read only on the dedicated KV-v2 data prefix. Writes
must use CAS=0 and unique scoped preparation paths; reads must pin exact version
and verify org/connection/provider/revision binding. The app cannot overwrite,
delete/destroy, enumerate or administer secrets. A separately reviewed operator
identity will own reconciliation; never delete possibly committing preparations.

Raft storage survives container restart. Stop/start requires unseal again.
Never use down -v to recover a sealed Vault: it destroys stored credentials.
Back up Vault with a separately protected snapshot and preserve recovery keys;
application DB restore alone cannot restore external secrets.

## Acceptance still required

Actual Docker startup, init/unseal, restart retention, denied application
overwrite/delete/list, TLS identity failures, scoped versioned write/read and
private-log evidence are not yet executed. The KV adapter, managed HMAC keys,
durable preparation reconciliation, receipt/application transport and responsive
create form remain separate implementation work. Existing CREATE endpoints stay
absent; no fake successful storage or key fallback is installed.

Official references checked4 November2026:
- https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2
- https://developer.hashicorp.com/vault/docs/configuration/storage/raft
- https://developer.hashicorp.com/vault/docs/configuration/listener/tcp
