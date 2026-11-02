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

## Initial published source and corrective work

C1 `bc7320c81dc6e1f3bff39558256a3c7ab78b8e82`, tree
`902f3f511f71654f91e24bf31e9f721fa3b5e2d1`, draft PR46 stacked on PR45.
Focused run41337581413/job124940552811 executed Java21: 7 core H2 passed,
7 actual-session HTTP tests ran with one failure (14 total,1 failure,0 errors/skips).
The strict-wire test expected400 but observed200 at its query-suffix loop;
the Java client can normalize an empty query before sending it. Corrective
work uses a raw request-target test rather than inferring servlet input from a
Java URI. Native step did not run after that failure; no native pass is claimed.

Independent QC also reproduced bounded-parser stack exhaustion and permissive
Accept lexical parsing in the initial source. BE is replacing the recursive
quota regex and tightening wire validation, with service/HTTP regressions.
Operator output is now allowlisted and strictly typed; duplicate/malformed JSON,
extra stdout and timeout/nonzero process results cannot publish success. The
lock query uses a materialized locking CTE and outer EXISTS (exactly one boolean
row) before psql's conditional second statement. Missing policy cannot be
adopted from a later snapshot. Native interleaving verification remains pending.

QC-authored Docker-free operator tests passed11/0 locally. A later isolated
Java17 core-only build passed12/0 on in-progress corrective source; this is
structural H2 evidence, not the C1 source, full Java21 application or native
acceptance. Updated source must be published and tested before closure.

## C2 Java21 focused execution

Corrected source `da94917297e91a35115097bf5f2d9aaf4786952e`, tree
`ef9f321a6507eae9d70cfc2b9f10a39a70372bb0`. Dedicated run41338037393,
job124941933369 completed successfully. Actual logs record:

| Suite | Executed | Failures/errors/skips |
| --- | ---: | --- |
| Core H2 | 14 | 0/0/0 |
| Actual-session HTTP H2 | 9 | 0/0/0 |
| Core native PostgreSQL | 21 | 0/0/0 |
| Actual-session HTTP native PostgreSQL | 9 | 0/0/0 |
| Total Java21 focused | 53 | 0/0/0 |
| Docker-free CLI unittest methods | 11 | 0 |

The native log includes observed lock waits, post-wait authorization/clock,
RR snapshot and storage constraint assertions; this is not an inferred pass
from H2. Full Quality and actual Docker operator/probe/browser gates remain
pending for C2. C1 full Quality separately ran1418 with2 failures/1 error
(both bare-query assertions and an untyped test timestamp cast); C1 Docker
ran916 with1 failure and stopped during build. Those failed runs are not
promoted to accepted regression results. C2 native tests use typed JDBC timestamp
queries instead of assuming queryForList timestamp representation.

A repeated local H2 contention run later produced safe unavailable because H2's
transaction-start clock can precede a row written while a caller waits. Production
future-state rejection remains unchanged. C3 moves the shared final-slot contention
assertion to mandatory PostgreSQL and retains H2 as structural coverage. New C3
storage-value/cascade and HTTP outage/nonhuman-session tests still require actual
Java21 execution. In-progress local isolated H2 core passed13/0 after that move.

## C2 full regression closure; C3 test hardening candidate

Main inspected completed Quality run41338037343/job124941932901 and Docker
run41338037350/job124941933406, both SUCCESS, on C2. Quality executed1438 backend
tests with0 failures/errors/skips; frontend checks/build and audit0 passed.
Docker executed925 regular backend tests with0 failures/errors/skips; all1101
retained disjoint browser invocations and3 focused repeats passed. Native provider
operator/cookie-session options probe and emitted-SQL CLI3 absent-lock protocol
passed at18:47:05 UTC on1 November2026. The latter executes the CLI-emitted psql
script with an observed test barrier, not a racing invocation of CLI replace.
Exact existing-session restart and logout rejection passed at19:21:18 UTC.
Retained native/SMTP gates also completed. No new UI screenshots are claimed.

C2's configured gates are closed. C3 adds tests and documentation only: native-only
last-slot contention, invalid storage values/cascades, safe storage outages,
nonhuman sessions and quoted/duplicate Accept parameters. Production Java and V024
are byte-identical to C2. C3 requires its own focused/full regression result; C2's
counts are not silently assigned to the new candidate. No raw secrets, public
writer, provider call, live network acknowledgement fault or universal log-privacy
proof is introduced. Native locks and controlled commit faults have bounded
assertion coverage; they do not cover every possible database/network failure.
