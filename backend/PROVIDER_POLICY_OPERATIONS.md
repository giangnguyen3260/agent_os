# Provider deployment policy operations

This is a trusted deployment operation, separate from organization ADMIN access.
Run commands from the repository root with access to the deployment's Docker
Compose PostgreSQL service. Never grant application users Docker or database
operator access. The CLI accepts provider names and a revision only; it does not
accept provider credentials or print database passwords.

V024 creates empty policy and admission tables. Startup does not choose providers.
Until explicitly provisioned, the authorized options API returns a safe 503.

Inspect the current policy:

```sh
python3 scripts/provider-policy-admin.py show
```

Choose a nonempty subset explicitly. Supported names are `OPENAI`, `ANTHROPIC`
and `GOOGLE_GEMINI`; these examples are operator choices, not system defaults:

```sh
python3 scripts/provider-policy-admin.py provision --providers OPENAI,ANTHROPIC
python3 scripts/provider-policy-admin.py show
python3 scripts/provider-policy-admin.py replace --expected-revision 1 --providers GOOGLE_GEMINI
```

`provision` inserts revision 1 and cannot overwrite an existing policy. `replace`
locks the singleton and compares the supplied revision before comparing provider
flags. Successful replacement increments revision once and preserves creation
time. A stale revision, unchanged subset or exhausted BIGINT revision makes no
change. Revision values are decimal strings in operator and public JSON output.
Disabling then re-enabling a provider advances the revision twice.

Exit 0 means a confirmed policy result, exit 1 means a confirmed read/rejection
status such as UNPROVISIONED, NO_CHANGE or REVISION_CONFLICT, and exit 2 means
UNCONFIRMED. Database/process failure, timeout or ambiguous output is unconfirmed:
the transaction may have committed even when its acknowledgement was lost.
**Do not automatically retry. Run `show`, inspect the current revision and flags,
then decide explicitly.** A second provision attempt never becomes an update.
Raw database diagnostics are not printed. Investigate server/operator logs using
the deployment's access controls when necessary.

Append `--sql-only` to inspect the nonsecret psql script without accessing Docker.
Replacement uses psql `\gset`/`\if` around a materialized locking query whose
EXISTS result always has exactly one row. The later clock/decision statement runs
only when the singleton was actually locked; absence returns UNAVAILABLE even
if another operator provisions before the next statement. Run emitted scripts
with psql, rather than a generic SQL-only client. Arguments
are validated before any subprocess launch; provider names and positive BIGINT
revisions become validated literals, never interpolated shell commands. The CLI
uses the PostgreSQL container's existing environment for psql credentials.

Public surface in this slice:

```text
GET /api/v1/organizations/{organizationId}/provider-connections/options
```

Use the existing human login session and current same-organization ADMIN role.
The response contains only organizationId, string policyRevision, and ordered
provider descriptors with stored enabled flags. It supplies no usable key,
credential reference, health claim, connection list or runtime grant. All feature
responses use no-store. HEAD/OPTIONS and command methods are unsupported; an
authorized request receives 405 with Allow: GET (HEAD has no body).

Authenticated GET admission uses a shared PostgreSQL actor/organization bucket:
120 admitted requests per rolling 60 seconds. An admitted malformed request or
later policy failure still consumes a slot; denied identities and unsupported
methods do not. A 429 includes bounded Retry-After seconds. The projection makes
no business writes; quota/session bookkeeping is deliberate security state.
There is no in-memory fallback, automatic policy repair or retry after uncertain
commit. Provider credentials remain outside the application DB; their existing
stored verification material is scoped one-way HMAC, not reversible ciphertext.

The disposable CI fixture/probe exercises operator commands and actual session
HTTP behavior on CI PostgreSQL. It must never be pointed at user or production
data. No migration, provisioning or deployment to the user's Mac is performed by
this change. SonarQube remains paused.

## Explicit managed Create assembly

Create remains disabled unless `agentos.provider-create.enabled=true`. When no
coordinator has been supplied by a test configuration, enabling it requires all
of these `agentos.provider-vault.*` settings:

| Setting | Required value |
| --- | --- |
| `url` | Trusted HTTPS Vault origin |
| `mount` | Explicit KV v2 mount |
| `ca-file` | Absolute readable deployment CA certificate bundle |
| `app-token-file` | Absolute mounted file containing the application Vault token |
| `hmac-token-file` | Separate absolute mounted file containing the HMAC token |
| `credential-key-version` | Positive credential integrity key version |
| `command-key-version` | Positive command receipt key version |
| `writer-jdbc-url` | Explicit PostgreSQL JDBC URL for the writer role |
| `writer-username` | Dedicated writer database username |
| `writer-password-file` | Absolute mounted file containing the writer password |

The normal application datasource must retain the restricted list runtime role.
The managed coordinator creates separate writer transactions and annotation-aware
receipt/connection service proxies; it never grants writer access to list reads.
Provision the writer role and Vault policies explicitly before enabling Create.
No root token, default secret, policy provisioning or production enablement is
provided. Missing or invalid local settings stop startup with a fixed failure
message. Mounted token files are reread for each Vault operation to support
rotation; token files accept one trailing newline and otherwise visible ASCII.

Managed Create configures Tomcat upload timeout enforcement with a 10-second
socket read timeout during request body upload. This bounds an individual blocked
upload read, rather than imposing a total deadline on a continuously progressing
upload. Existing body size and concurrency limits still apply. Unknown Vault
preparations remain pinned for explicit operator reconciliation; the managed
assembly does not resolve or delete them automatically.

## Independent internal Rename assembly

`agentos.provider-rename.enabled=true` constructs the internal Rename coordinator
only. It does not register a public Rename route or change own-receipt HTTP routing.
Create may remain disabled with all `agentos.provider-vault.*` settings absent.
Rename requires these explicit `agentos.provider-rename.*` settings:

| Setting | Required value |
| --- | --- |
| `url`, `mount` | Trusted HTTPS command-key Vault origin and explicit KV v2 mount |
| `ca-file` | Absolute readable CA certificate bundle for that origin |
| `command-token-file` | Absolute mounted command-key reader token file |
| `command-key-version` | Positive retained command HMAC key version |
| `writer-jdbc-url` | PostgreSQL URL for the same application database |
| `writer-username` | Explicit dedicated Rename writer principal |
| `writer-password-file` | Absolute mounted file containing its password |

The command token can use `docker/vault/provider-command-reader.hcl`. It needs only
read access to the command-receipts key, not provider credential values or
credential-integrity keys. Historical command key versions must remain available
for replay. No credential application token, credential key version, secret store,
health observer or provider client is constructed for Rename. If the credential
store fails while the command HMAC service remains available, that failure does
not introduce a Rename dependency. Failure of the command service still denies
completion safely. Mounted token reads are fresh and bounded; local startup
failures use the fixed `PROVIDER_RENAME_CONFIGURATION_UNAVAILABLE` message.

The writer datasource is private to its transactional services and does not
replace the normal application reader. Provision its explicit table privileges
before enabling the internal feature. It reads retained credential metadata for
existing persistence validation, but does not insert, update or delete credential
versions. Metadata mutation, immutable event and receipt use one owned completion
transaction. External command-key access occurs after authority/admission commit
and before the completion transaction, never under database locks. The governed
transaction rechecks current authority and the receipt before business mutation.

Create and Rename share the durable actor/organization 30-request rolling
60-second admission row. Historical receipt replay bypasses fresh admission;
current authority and historical command-HMAC comparison are still required.
Fresh Rename commits admission before HMAC and current target/revision/no-change
checks, so an admitted stale/no-change/zero-revision or dependency-failed attempt
retains its slot. A quota rejection supplies the existing bounded Retry-After.
Create's provider-enabled/capacity preflight is preserved and is not applied to
Rename. Fresh Rename still requires the deployment-policy singleton to exist.
Provider-disabled and disabled-connection metadata can be renamed. Same name at
maximum revision yields NO_CHANGE; a different name requiring increment yields
REVISION_EXHAUSTED. These configuration operations grant no runtime eligibility.


## Opt-in Rename HTTP candidate

The backend HTTP candidate separately uses
`agentos.provider-rename.http-enabled=true` (default false). Supply the explicit
Rename coordinator; managed assembly additionally requires
`agentos.provider-rename.enabled=true` and the command-only settings above.
Rename-only activation does not require Create or provider credential configuration.
The flag enables the narrow Rename POST and historical Rename projection on own
receipt GET; that GET is present when Create OR Rename HTTP is enabled.

The Rename HTTP Tomcat customizer enforces a 10-second idle upload read timeout
independently of Create. It does not impose a total slow-trickle deadline. Eight
nonqueued per-instance permits bound Rename body/coordinator work, separately from
the shared durable 30/minute write quota. Reverse-proxy limits remain operational
configuration, not evidence of application acceptance. Native managed tests and
matching CI are pending for this candidate; UI is not implemented by this flag.
