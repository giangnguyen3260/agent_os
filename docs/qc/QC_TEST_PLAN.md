# AgentOS Foundation QC Test Plan

Date: 2026-10-15. Owner: dedicated Expert QC, independent of SA/BE/FE.
Scope: planning only; these two QC documents. No code changes or application tests executed. Parent is beginning foundation coding and quality toolchain work.

## Baseline and traceability

- Engineering baseline: `/workspace/scratch/24b4696504b1/ENGINEERING_DELIVERY_PLAN.md`, especially §§5–8, 10, 12–14 and E00–E03.
- BA baseline: `/workspace/scratch/24b4696504b1/agentos-v0.1/BA_REQUIREMENTS_REVIEW.md`, D01–D08, C01–C06, AC01–AC12. This sibling source is a proposal baseline; delivery-plan decisions supersede only explicitly resolved items.
- Local `API_BOUNDARY.md`, ADRs and forthcoming versioned contracts must be reconciled before execution. Proposed endpoint names/status codes are not silently treated as approved contracts.
- `TC-FND-001`–`TC-FND-036` in TEST_CASES_FOUNDATION.md are stable IDs. Each maps to BA IDs where applicable and delivery-plan E/section references. `U-Q` means the user's explicit Clean Code, SonarQube and Google coding style requirement; it is not an invented BA acceptance ID.
- Foundation coverage of BA runtime/mission requirements is limited to security prerequisites. AC02–06, AC08, AC12 and full AC11 need separate later-slice test design; this plan does not claim full product coverage.

## Readiness and execution

Before running, obtain immutable code revision/source digest, change list, build instructions, approved support matrix/lockfiles, versioned API/error and actor/action/scope contracts, migrations, test commands, CI configuration and accessible environment. QC reviews code and executes applicable builds/tests independently before completion; author reports alone are insufficient.

Use disposable PostgreSQL with real migrations (not H2 as proof of locking/tenancy), shared session store, isolated KMS/secret-manager test keys, controllable clock, two API replicas when applicable, browser tooling and mock provider/tool counters. Never use production credentials or real PII. Capture environment/tool versions and redact evidence.

Policies O01–O03, O05–O07 remain open where the baseline says so. Exact TTL, limits, grant authority, recovery/bootstrap flow, classification and revocation boundary require recorded decisions. Unavailable feature/environment or missing acceptance policy makes an attempted case Blocked, never Pass. Foundation cases may run while later-slice cases remain unimplemented; explicitly report that limitation.

## Status, severity and evidence

| QC status | Stored value | Meaning |
|---|---|---|
| Pass | PASS | Independent execution on identified revision satisfies every expected result; evidence attached. |
| Fail | FAIL | Execution or review establishes a reproducible deviation; defect linked. |
| Blocked | BLOCKED | Execution cannot conclude; dependency, owner and unblock condition recorded. |
| Not Run | NOT_RUN | Planned; no execution evidence. This is the initial status of all 36 cases. |

Critical: privilege escalation, cross-tenant access, credential disclosure or unauthorized side effect. High: broken session, crypto/integrity, essential contract or quality gate. Medium: bounded UX/accessibility/style defect. Severity is impact, separate from delivery priority.

For each execution record: TC ID and variant, revision, UTC time, QC executor, prerequisites/data, exact command or manual actions, expected/actual outcome, exit code, report/log/screenshot/request-ID links, status and defect/blocker reference. A case with multiple variants passes only when all applicable variants pass. Keep secrets out of artifacts; include evidence that prohibited writes/calls did not happen.

## Delivery gates and independence

1. Parent supplies concrete implementation and quality-toolchain outputs. QC checks changed code, reconciles contracts, then independently executes applicable positive, negative, integration and browser cases.
2. Build, formatter, lint and static scan must cover applicable BE/FE code. Pin Google Java Style formatter/check and documented Google-derived FE TypeScript/HTML/CSS rules; resolve unsupported rules explicitly. Clean Code review checks responsibilities, naming, duplication, error handling and test clarity, not a claimed universal score.
3. SonarQube proposed **new-code** gates: coverage ≥80%, duplication ≤3%, zero new blocker/critical issues, all new security hotspots reviewed with rationale. Parent must configure an explicit new-code baseline and metric mapping for the installed SonarQube version. Missing policy/tool/report is Blocked; scanning alone does not prove a gate passed. These numeric gates remain proposed pending adoption, not measured results.
4. Required checks must prevent merge/release when failing or missing. Passing CI does not replace QC security/behavior checks or human final acceptance (BA D05).
5. Defect loop: QC logs revision, repro, impact and evidence → BA confirms disputed requirement, SA/BE/FE owner fixes → QC independently reruns failed case plus affected regression on the new revision. Authors cannot self-close QC defects. Reopened failures stay Fail; unavailable retest stays Blocked.
6. Foundation acceptance needs all applicable gate cases Pass, no unresolved Critical/High defect, and an explicit deferred/blocked list with owner and scope impact. No silent waiver; any authorized risk acceptance is recorded separately and does not relabel a failed case Pass. No production-security or load claim follows from this foundation plan.

## Current result

36 planned cases; all NOT_RUN. No coding build, test, scan, browser run, SonarQube analysis or benchmark has been executed by QC. Only document structure/content will be checked in this task.
