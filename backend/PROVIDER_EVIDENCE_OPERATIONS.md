# Provider evidence storage and role rollout

This foundation creates empty evidence storage; it supplies no successful observer,
verified credential producer, public publisher or public list endpoint. Missing
observations mean UNAVAILABLE, never a successful default. Application DB storage
remains scoped HMAC/reference metadata, without recoverable credentials.

## Roles and deployment boundary

Apply V025 through the migration principal. The migration principal owns tables;
it must not be the HTTP runtime or future publisher identity in a hardened deployment.
Run `backend/operations/provider-evidence-roles.sql` only as an operator against the
intended deployment after reviewing it. It creates two restricted NOLOGIN capability
roles in one transaction. Existing names cause a fixed refusal; there is no automatic
overwrite, membership reassignment or privilege repair. It accepts no credentials.

Reader receives schema USAGE and SELECT on immutable credential-version metadata
and the three private evidence/fence tables only. This is an internal application
capability; it is not permission to expose references or verifiers through HTTP. Publisher receives no
schema/data mutation grants: merely creating a role is not an authenticated publication
protocol. Do not grant general UPDATE/DELETE or ownership to make tests succeed.
Trusted publication functions, provenance, epoch/CAS/time checks and sticky failure
must be reviewed and tested before any publisher grant is introduced.

Login provisioning and membership are separate operator decisions. Neither role is
connected to an application LOGIN by this script. Role creation alone does not remove
privileges inherited through PUBLIC, ownership, other memberships, schema CREATE,
SECURITY DEFINER routines or superuser. Inspect the effective deployment identity,
not only these role declarations. Do not attach this capability to the current Docker
administrator and call it least privilege.

Current Compose uses its bootstrap database administrator for application access.
This slice does not silently change existing login/migration credentials, apply role
SQL on the user's Mac or certify that deployment as protected. A future rollout must
use separate migration, HTTP and publisher logins, explicit secrets outside Git,
appropriate session/admission/business grants, and a restore/upgrade rehearsal.
Existing credential-version UPDATE/DELETE permissions must be denied to runtime
writers; immutable reference bindings must not rely only on Java conventions.

## Read/publication prerequisites

Evidence must bind organization, connection, provider, credential revision, external
reference/version and recorded HMAC/domain versions. It must not inherit from a
retired version or from syntactically valid Metadata. Empty storage and an unavailable
relation differ: the former can project UNAVAILABLE; the latter fails safely503.

Publication must capture actual observation start outside business locks, preserve
that timestamp across waits, expire within60 seconds, compare revisions/epochs,
reject stale or conflicting observations and never clear known integrity failure
through health success. No network/key operation under a DB lock is permitted.
This algorithm and trusted verification producer remain separate implementation work.

## Verification

Native PostgreSQL tests must execute this actual role script on disposable storage,
verify real effective privileges under SET ROLE (including failed writes), empty
migration, complete field/constraint/FK allowlists and every accepted/rejected value.
Creating roles requires an explicitly privileged disposable test administrator.
Do not run these role tests against user/production databases. H2 is only structural
compatibility. Test source is not an executed result; record actual CI source/counts.
No merge, deployment, provider call or SonarQube pass is implied.
