# Provider publication SQL API and privilege manifest

BE representation for main/SA/QC review at baseline `643357e`. The frozen
[SA contract](PROVIDER_EVIDENCE_PUBLICATION_CONTRACT.md) SHA-256 is
`92d87d8cdd341e7a3f351846551d9fae232be711cecb3d35ddd176167c583669`.
F1–F7 source freeze is accepted; executable implementation acceptance remains pending.
Final F7 capacity uses REJECTED/CAPACITY, distinct from arithmetic EXHAUSTED.

The operator installer is `backend/operations/provider-evidence-publication.sql`,
separate from Flyway. V025, its exact catalogs, the old base role script and H2
behavior stay unchanged. The production client takes an explicit worker DataSource;
there is no Spring registration, default datasource, observer or HTTP endpoint.

## Six exact scalar entry points

Every function belongs to `agentos_evidence_private`. No overload, default,
variadic argument, JSON payload, caller clock/identity/next revision or dynamic
identifier is supported. PostgreSQL timestamp function identity is `timestamp with
time zone`; microsecond precision is enforced in stored/returned values.

| Function | Ordered names and PostgreSQL types | Required kind |
|---|---|---|
| `begin_health` | `p_organization_id uuid, p_connection_id uuid, p_provider text, p_credential_revision bigint` | HEALTH |
| `begin_preparation_assertion` | `p_organization_id uuid, p_connection_id uuid, p_provider text, p_credential_revision bigint` | PREPARE |
| `begin_integrity_verification` | `p_organization_id uuid, p_connection_id uuid, p_provider text, p_credential_revision bigint` | INTEGRITY_FAILURE |
| `publish_health` | `p_attempt_id uuid, p_outcome text, component_deadline_present boolean, component_deadline timestamp with time zone` | HEALTH |
| `assert_prepared` | `p_attempt_id uuid` | PREPARE |
| `publish_integrity_failure` | `p_attempt_id uuid` | INTEGRITY_FAILURE |

Provider values are OPENAI, ANTHROPIC and GOOGLE_GEMINI. Health outcomes are USABLE
and UNAVAILABLE. Both component arguments are mandatory. Only `(false,NULL)` is
absent. True requires a non-NULL finite value. NULL presence, false/non-NULL,
true/NULL and true/nonfinite reject ATTEMPT; finite civil-range/ordering/expiry
validation occurs at TIME. A later component bound cannot extend the original TTL.

Registration resolves exactly one enabled LOGIN-name/OID/org publisher binding with
the requested provider/kind grant. Ambiguity rejects AUTHORITY; never choose the
first row. SESSION_USER is authoritative; CURRENT_USER, role membership, payloads
and GUCs are not. All eight binding fields plus verifier are revalidated.

## Exact one-row result tables

Every invocation that completes normally returns exactly one row, including rejection.
Database/protocol errors may instead abort and are sanitized by the production client.
The SQL status/reason allowlist is:

| Status | Reason | Applicability |
|---|---|---|
| ACCEPTED | NONE | Capture or mutation; provisional until commit acknowledgement |
| ALREADY_FAILED | NONE | Failure publication only; no new descriptor or mutation |
| REJECTED | AUTHORITY, ATTEMPT, BINDING, TIME, INTEGRITY_FAILED, CONFLICT, CAPACITY, EXHAUSTED | Per contract precedence and operation |

Client-only failures are FAILED/DEPENDENCY_UNAVAILABLE and
UNKNOWN/COMPLETION_UNKNOWN. SQL does not return these. Precedence is authority,
attempt/arguments, binding, TIME, then sticky state/CAS/increment. Health TIME wins
over simultaneous FAILED/CAS/MAX; USABLE's FAILED wins over CAS/MAX. Preparation
rejects existing VERIFIED, and cannot clear FAILED. Valid failure promotes locked
current VERIFIED even after capture CAS advancement; existing FAILED is an unchanged
ALREADY_FAILED after earlier guards, including at MAX. Capture capacity is checked
only after its authority/argument/binding/time guards and never blocks a valid
existing publication attempt.

All three capture functions return the following ordered columns:

| Columns, in order | PostgreSQL types | Accepted / rejected |
|---|---|---|
| `status, reason` | text, text | Fixed envelope, always non-NULL |
| `attempt_id` | uuid | Non-NULL opaque handle / NULL |
| `organization_id, connection_id` | uuid, uuid | Exact scope / NULL |
| `provider, credential_revision` | text, bigint | Exact positive scoped revision / NULL |
| `external_locator, external_key_version, hmac_key_version, hmac_domain_version` | text, text, text, text | Exact immutable values / NULL |
| `verifier` | text | Exact immutable private verifier / NULL |
| `observed_at, deadline` | timestamp with time zone, timestamp with time zone | Original post-wait DB start and checked start+60s / NULL |

All three mutation functions return the following ordered columns:

| Columns, in order | PostgreSQL types | Accepted / rejected or ALREADY_FAILED |
|---|---|---|
| `status, reason` | text, text | Fixed envelope, always non-NULL |
| `organization_id, connection_id` | uuid, uuid | Exact scope / NULL |
| `provider, credential_revision` | text, bigint | Exact positive scoped revision / NULL |
| `external_locator, external_key_version, hmac_key_version, hmac_domain_version` | text, text, text, text | Exact binding / NULL |
| `publisher_id, publisher_epoch, publisher_fence` | uuid, bigint, uuid | Exact registered publisher triple / NULL |
| `assertion_kind` | text | HEALTH, PREPARE or INTEGRITY_FAILURE matching call / NULL |
| `publication_revision, state` | bigint, text | Accepted positive revision and fixed evidence state / NULL |
| `recorded_at` | timestamp with time zone | Required only for integrity; NULL for health / NULL |
| `observed_at, published_at, valid_until` | timestamp with time zone, timestamp with time zone, timestamp with time zone | Required only for health; NULL for integrity / NULL |

Accepted integrity states are VERIFIED_AT_WRITE for PREPARE and FAILED for
INTEGRITY_FAILURE. Accepted health states are USABLE or UNAVAILABLE. All non-applicable
timestamp columns are NULL. No verifier is returned in mutation descriptors. No data
is returned on rejection or ALREADY_FAILED. Time values must be finite supported
civil-range values at microsecond precision. Capture does not expose the internal
CAS snapshot or invite caller-supplied publisher selection.

The descriptor identifies current scoped evidence, not a historical command receipt.
No seventh recovery function, automatic readback/retry or health receipt ledger is
provided. Overwritten/missing/mismatching current evidence cannot resolve older UNKNOWN.

## Ownership, search path and exact publisher grants

Each function is SECURITY DEFINER, owned by `agentos_provider_evidence_owner`, with
exactly `SET search_path = pg_catalog, agentos_evidence_private, pg_temp` and fully
qualified dependencies. Owner attributes are NOLOGIN NOSUPERUSER NOCREATEDB
NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT. Worker/web/reader/publisher roles
cannot assume it. Installation refuses conflicting roles/schema or unsafe effective
authority rather than silently repairing an existing deployment. Both base reader and publisher capabilities
must also be NOINHERIT, have no outgoing role membership, and have no effective
table/column DML or ownership on the retained tables; Incoming LOGIN membership is allowed; approved reader SELECT is retained. A contaminated grant is preserved on refusal.

Before creating functions, establish restrictive owner defaults. PUBLIC execution
is revoked in the same installation transaction. The exact publisher grant list is:

```sql
REVOKE ALL ON SCHEMA agentos_evidence_private FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE agentos_provider_evidence_owner
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
GRANT USAGE ON SCHEMA agentos_evidence_private TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.begin_health(uuid,uuid,text,bigint)
  TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.begin_preparation_assertion(uuid,uuid,text,bigint)
  TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.begin_integrity_verification(uuid,uuid,text,bigint)
  TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.publish_health(uuid,text,boolean,timestamp with time zone)
  TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.assert_prepared(uuid)
  TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.publish_integrity_failure(uuid)
  TO agentos_provider_evidence_publisher;
```

Each of these six explicit function identities receives `REVOKE ALL ... FROM PUBLIC`
before its grant. No publisher table, column, sequence, CREATE, owner membership,
registration/fence/cleanup EXECUTE, default-function or ON ALL FUNCTIONS grant exists.
Helpers, if introduced, require exact signature/revocation inventory here before
acceptance; no runtime helper execution is permitted.

## Dedicated owner's retained-table grants

The following privileges belong only to the trusted NOLOGIN owner. PostgreSQL
locking SELECT requires a column UPDATE grant on parents/registry; those narrowly
disclosed grants do not authorize any routine to mutate parent columns.

```sql
GRANT USAGE ON SCHEMA public TO agentos_provider_evidence_owner;
GRANT SELECT (id), UPDATE (id), REFERENCES (id) ON public.organizations
  TO agentos_provider_evidence_owner;
GRANT SELECT (id,organization_id,provider,credential_revision), UPDATE (id)
  ON public.provider_connections TO agentos_provider_evidence_owner;
GRANT SELECT (organization_id,connection_id,provider,credential_revision,
  external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier)
  ON public.provider_credential_versions TO agentos_provider_evidence_owner;
GRANT SELECT, UPDATE (publisher_id), REFERENCES (publisher_id) ON public.provider_evidence_publishers
  TO agentos_provider_evidence_owner;
GRANT SELECT, INSERT, UPDATE ON public.provider_credential_integrity,
  public.provider_credential_health TO agentos_provider_evidence_owner;
```

No version UPDATE, ownership or row-lock privilege is granted. Version reads use
plain SELECT under organization/connection serialization locks. Supported writers
take those parents in the same order. Runtime retained-version immutability is Q3's
deployment prerequisite; trusted operator/owner tampering is outside the boundary.
The native suite must prove effective SELECT-only version access for the definer.

The owner owns new private registration/permission, attempt, assertion and provenance
tables. No runtime SELECT/DML is granted. Registry/registration shared locks are retained through commit. Effective
CREATE/function-replacement authority in private/public schemas must be denied to
all runtime identities, including inherited or explicit grants. The accepted base
role script remains unchanged.

The installer defines these additional private identities. Every one has fixed
search_path and explicit PUBLIC EXECUTE revocation. None has publisher/reader/web
EXECUTE. All except the immutable, data-free binding validator are SECURITY DEFINER.

| Helper | Signature / result | Purpose |
|---|---|---|
| authority | `(uuid,text,text)` / table `(id uuid,epoch bigint,fence uuid,login_oid oid)` | Exact original LOGIN and scope resolution |
| valid_binding | `(text,text,text,text,text)` / boolean | Bounded token checks and 32-byte base64 decode/re-encode canonicality; fixed false on decode failure |
| capture_attempt | `(text,uuid,uuid,text,bigint)` / capture table above | Shared locked capture algorithm |
| publish_attempt | `(text,uuid,text,boolean,timestamp with time zone)` / mutation table above | Shared locked publication algorithm |
| cleanup_attempts | `(uuid,integer)` / bigint | Operator-only organization/batch cleanup, 1–4096 batch |

Operator cleanup is available to the trusted installation administrator through
its existing administrative authority; no new runtime/admin capability grant is
created. Parent/registry REFERENCES column grants above permit private registration
FKs; they grant no credential-version mutation authority. Owner default function
EXECUTE is revoked globally, rather than relying on a per-schema REVOKE to subtract
PostgreSQL's global default PUBLIC EXECUTE.

The exact private catalog is in the installer, with no additional hidden tables:

| Table | Column allowlist / keys |
|---|---|
| publisher_principals | publisher_id, login_name, login_oid, organization_id, enabled; publisher PK/FK, unique name/OID/org, org FK |
| publisher_permissions | publisher_id, provider, kind; composite PK; registration FK; fixed provider/kind checks |
| publication_attempts | attempt_id, kind, login_name, login_oid, publisher_id, publisher_epoch, publisher_fence, organization_id, connection_id, provider, credential_revision, external_locator, external_key_version, hmac_key_version, hmac_domain_version, verifier, integrity_present, integrity_revision, health_present, health_revision, observed_at, deadline, capture_xid, consumed; attempt PK, org/deadline/UUID index; positive revisions and explicit presence/null checks; finite civil/TTL checks |
| integrity_assertions | assertion_id, attempt_id, kind, organization_id, connection_id, provider, credential_revision, external_locator, external_key_version, hmac_key_version, hmac_domain_version, login_name, login_oid, publisher_id, publisher_epoch, publisher_fence, captured_at, accepted_at, outcome, protocol_version; assertion PK, unique attempt FK, unique version/kind; fixed transition/outcome/protocol and time checks |
| integrity_provenance | organization_id, connection_id, provider, credential_revision, publication_revision, assertion_id; version/publication-revision PK, unique assertion FK, positive revision |

All columns are NOT NULL except captured integrity/health revisions, which have
explicit boolean presence counterparts. There are no defaults, identities, sequences,
credential payloads, health receipts, or cascading deletions. Name/OID use pg_catalog
name/oid, capture_xid uses xid8, booleans use bool, protocol version uses int4,
UUIDs use uuid, revisions/epochs use int8, timestamps use timestamptz(6), and remaining
private fields use text. Capture xid rejects same-transaction unpublished attempts;
other sessions cannot see an uncommitted attempt. Integrity pinning derives solely
from exact linked assertions/provenance, with no caller-writable pin flag.

## Capacity, cleanup and client proof boundaries

Under final F7, at most 4096 unpinned operational attempts per org
count across all identities/kinds/states, including consumed attempts. Only exact
integrity-assertion/provenance-linked pinned attempts are exempt. Count/insert/pin
serialize under the org mutex; full returns REJECTED/CAPACITY without mutation.
There is no eviction, implicit sweep, automatic retry, or publisher cleanup grant.
Operator cleanup holds the org mutex and rechecks locks/links, eligibility and safe
arithmetic using post-wait DB time at or after original deadline plus five minutes.
It removes only eligible unpinned attempts, never evidence/assertions/provenance or
registry rows. Restrictive FKs protect pinned history. No capacity path silently evicts operational state.

The six fixed JDBC prepared statements run on fresh explicit worker connections in
short writable READ COMMITTED transactions. An ambient transaction is refused.
Capture/accepted data is released only after commit acknowledgement. Returned failure
objects retain no Throwable, cause, suppressed diagnostics, SQLSTATE, SQL or binds.
Uncertain completion is UNKNOWN; explicit known failure is FAILED. Close errors
cannot invalidate an acknowledged commit. There are no logs, retries or readbacks.

Private value types redact toString and reject Jackson serialization both directly
and through containers/nested properties. This protects the normal Jackson boundary;
it is not a claim against an intentionally annotation-disabled/exfiltrating mapper.
Direct production-class fault/serialization tests and real-LOGIN native tests are
separate authored checks. Neither proves an external verification happened.
Java21, formatting, JUnit/native execution and independent implementation acceptance
remain pending; no Q3/Q4 closure, trusted observer or production PRESENT is claimed.
