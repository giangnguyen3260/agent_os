# Provider options and deployment policy verification

Candidate date: 2 November 2026. Baseline: PR45 Markdown closure
`eac1bc08775ced4f3f0f35a64eac896ae241afb4`. Branch:
`feature/provider-options-policy`. This report distinguishes authored code and
local checks from actual completed Java21/native/Docker acceptance.

The candidate exposes only provider options to current organization ADMINs.
V024 creates empty deployment policy and shared security admission tables.
Explicit operator provisioning and revision-checked replacement use PostgreSQL;
there are no automatically enabled providers, public policy writers, provider
calls, secret-manager adapters or new FE screens. Existing credential storage
remains scoped one-way HMAC plus opaque external references.

Independent requirements review is in
`docs/engineering/PROVIDER_OPTIONS_POLICY_REQUIREMENTS_REVIEW.md`;
adversarial implementation review is tracked separately in
`docs/qc/PROVIDER_OPTIONS_POLICY_REVIEW.md`. The source contract and operator
runbook define admission/write/read boundaries and uncertainty handling.

## Local checkpoint

- Python AST parsing passed for the operator CLI and Docker probe.
- CLI SQL-only emission passed for show, explicit provisioning and replacement
  through the maximum BIGINT revision. It performed no database operation.
- Missing/duplicate providers, prohibited show flags, leading-zero and oversized
  revisions were rejected before any subprocess execution.
- Google Java import check passed at this checkpoint.
- Host Java is17 and Docker is unavailable. Full existing Java21 sources cannot
  compile on17; no production source was backported for a test claim.

These checks do not establish PostgreSQL transaction behavior, HTTP security
compatibility or completed CI. Authored core/HTTP H2/native tests and the actual
Docker operator/session probe must pass on the exact published source before
acceptance. No test count, native lock/clock result or Docker pass is inferred
from test source. The existing full Quality and retained Docker/browser gates
remain required. Any remaining coverage limitations must stay explicit.

No user deployment, Mac command, merge or production provisioning occurred.
SonarQube remains paused. No new UI or screenshot acceptance is claimed.
