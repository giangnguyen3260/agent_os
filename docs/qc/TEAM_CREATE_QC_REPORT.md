# Create Team — final development QC

Decision: **PASS for the bounded development scope**, independently reviewed on 20 October 2026 (Asia/Ho_Chi_Minh). PR6 remains draft and unmerged; this is not production acceptance.

## Revision and scope

Application: `5e1548061a048a8503d2317613feebf5503db348`; CI tested merge `368dc517` onto dashboard base `8b44a11df5314823ca303c70eb97bbe32367a413`. Later documentation-only commits do not change the tested application.

Basics → Setup → Review creates a persisted DRAFT. Name/purpose validation, optional manager, up to 50 distinct eligible agents, manual intake, integer concurrency and USD budget are implemented. Limits start blank and represent configuration, not observed or enforced runtime capacity. Detail reload reads the API. No activation or agent creation is included.

## Executed evidence

| Check | Result | Evidence |
|---|---|---|
| Backend integration/unit tests | 49 passed; zero failures, errors or skips; includes 10 Team tests | [Quality run 39301625927](https://github.com/giangnguyen3260/agent_os/actions/runs/39301625927), job 118228012247; aggregate at 2026-10-19 18:21:15 UTC |
| FE lint, formatting, tooling regressions and production build; Java format/import checks | Passed | Same Quality run |
| Docker build and PostgreSQL migrations/fixtures | Passed | [Docker run 39301625903](https://github.com/giangnguyen3260/agent_os/actions/runs/39301625903), job 118228010504 |
| Browser tests against real PostgreSQL | 72 instances passed in 54.9s; 54 baseline plus 18 Team | Same Docker run; completion 2026-10-19 18:24:20 UTC |
| Account-first login/reset barrier and session restart/logout probes | Passed | Same Docker run; final session probe 18:24:56 UTC |
| Local render/interaction review | 15 states across 1440, 768 and 390px; no horizontal overflow | Mocked HTTP fixture inspection; visual evidence only, not database evidence |

The six Team browser scenarios run at all three widths: validation/Back/Cancel without premature POST; actual Draft/configuration/manager and worker assignment plus detail reload; unknown-outcome retry using identical key/body without duplication; MEMBER and CSRF denial; admin revocation during editing; and detail GET failure/retry without a second POST. Unknown outcomes cover dropped response, 503 and malformed success response across the three projects.

Team concurrency, rollback and negative constraints are H2 integration evidence. Native PostgreSQL browser coverage verifies creation, assignments, replay, revocation and migrations; it does not establish forced-concurrency behavior or production throughput on PostgreSQL.

## Reviewed invariants

Fresh account and ADMIN membership checks apply to every creation and replay. Candidate reads are ADMIN-only; detail reads require current membership. Tenant-scoped reads do not disclose foreign resources. Writes lock organization, account, membership and sorted agents; claims cannot transfer agents from another team. Team, assignments and idempotency receipt commit atomically. Identical retries return the original response; changed bodies under the same key conflict. Candidate ownership is checked after receipt replay. GET authorization does not acquire organization write locks.

The UI preserves values between steps, validates before review, confirms unsaved navigation and clears private state when scope/access changes. Ambiguous write outcomes retain the immutable request and key. Success requires a valid DRAFT response; detail failures retry GET only.

## Remaining scope

Activation, runtime execution/capacity enforcement, agent creation, complete management/roles, exhaustive accessibility review, production load/failover and release hardening remain open. SonarQube is temporarily paused at the user's request and was not passed. Docker was verified in CI; no deployment on the user's Mac is claimed. Review the V005 rollout note before applying to existing data.
