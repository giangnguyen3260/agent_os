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
