# Task draft dependency editor — final review

Status: VERIFIED for the frozen configuration-editor scope on candidate C9. Quality and Docker completed successfully; this is not a production release approval.

## Revision and scope

- PR37: https://github.com/giangnguyen3260/agent_os/pull/37, `feature/task-draft-dependencies`, stacked on PR36. Draft, unmerged and undeployed.
- Application/test candidate: `c5f089fda148a18ac3b11f00bf4b2277b6c571e7`; tree `0262bd3d403e6033a547ad21a416489d86fb6dee`.
- Tested merge: `ad983e727fdbb0d75fcba708f0beda3892f4eb93`; base PR36 closure `840dcfe2c872e92c69e5d5cebc42e2809ba5d708`.

Current enabled credential-matching organization ADMIN can replace an eligible DRAFT task’s complete same-Team/Mission dependency set, up to20 targets, with clear-all, paginated choice and complete review. Every scoped node/edge participates in graph integrity and snapshot validation. All known target states remain valid prerequisites; configuration does not imply readiness, execution or status transitions. Invalid existing graph/metadata fails closed, without partial foreign rows or a repair affordance.

V021 retains existing tasks/edges at revision0 and adds monotonic dependency revision plus immutable event/receipt provenance. Org-first serialization, ordered locks, post-wait authorization, exact-command replay before mutable gates and historical receipt decoding protect writes/recovery. Input/owner snapshot domains append dependency revision while preserving historical command digests. Production receives no synthetic baseline or seed; disposable CI fixtures create real effects through actual APIs.

## Verification

| Gate | Evidence | Status |
| --- | --- | --- |
| Quality | Run41170335575/job124446722150 | PASS |
| Java21/H2/PostgreSQL | 926 invocations:57 dependency H2+20 dependency native PG+849 retained;0 failures/errors/skips | PASS |
| Frontend security/style/build | Including-dev audit0; lint/format/tooling/production harnesses/build | PASS |
| Real dependency HTTP/PostgreSQL | Docker41170335194/job124446721428, full DAG, replay/recovery, strict wire, scoped authority, reciprocal race, ABA | PASS |
| Dependency browser | 75 cases across1440/768/390, real backend | PASS |
| Owner-history/owner/navigation browser | 42+81+90 retained cases completed successfully | PASS |
| Legacy browser | 780 passed;75+42+81+90+780 =1068 disjoint invocations | PASS |
| Focused repeats | 3 invitation native-dialog checks, additional to1068 | PASS |
| Retained native/restart/logout | Real PostgreSQL/SMTP, account barrier, exact session survival and logout replay denial | PASS |
| Responsive evidence | 21 actual PNGs+21-record JSONL; coordinator validation and independent QC | PASS, bounded |
| Sonar | Temporarily paused by user instruction | NOT_RUN |

Artifact13031157385 `task-dependencies-visuals`:897211 bytes; verified ZIP SHA256 `ef02c89723aba3c2fd0fcae1b4c9234e6f33e324c5adc694a94b436dfafe134d`. Each file hash, PNG dimension, project viewport, scope and route matches its JSONL row. Main reviewed14 current mobile/tablet captures and current desktop saved; other6 desktop images are byte-identical to inspected C7 images. Independent QC individually viewed all21 with no visual blocker. See [visual manifest](TASK_DEPENDENCIES_VISUAL_MANIFEST.md).

Docker41170335194/job124446721428 completed SUCCESS:656 regular backend tests/0 failures/errors/skips inside the image build; including-dev audit0;75 new dependency browser cases at05:05:11 UTC;42 owner-history at05:08:40;81 owner at05:11:37;3 focused repeats at05:13:11;90 navigation at05:21:45;780 legacy at05:47:32. Actual native dependency HTTP/PostgreSQL probe passed at04:57:41. Exact-session restart survival/logout replay rejection passed at05:48:33 UTC. No skipped acceptance step, test-timeout increase or retry workaround was introduced.

## Findings resolved

- Initial test authentication/pending/status fixtures were corrected without changing production identity versions, constraints or required failure assertions. Exact DTO field order remains tested; only the unspecified audit-map key order is unordered.
- Legacy edit fixtures now use valid nonempty input/criteria, preserving historical digest/replay and exact ABA row equality. The independent hash oracle uses compact JSON string UTF-8, including supplementary Unicode.
- MockMvc’s encoding setter overwrote duplicate Content-Type headers. The H2 fixture disables only forced request-encoding replacement, sets UTF-8 before adding the duplicate and retains exact400/no-write assertions.
- CI TypeScript single-run programs ignored a lintText overlay. Real temporary project/files now verify actual Google diagnostics without dropping assertions.
- Actual browser case21 found a reused-route bug after leaving a pending save: old saving/UNKNOWN phase prevented the new load. Resetting phase for the new scope preserves epoch cancellation and private clearing. Constructor regressions cover both phases with late success/error, new context loading and no extra POST; mutation controls fail without the reset. All75 real browser cases now pass.
- Shared session validation, precise transient-versus-confirmed authority loss and multiline private-field compatibility have bounded source/harness/native coverage recorded in [QC history](TASK_DEPENDENCIES_QC_REPORT.md) and [independent source review](TASK_DEPENDENCIES_REVIEW.md).

## Tooling and limits

New upstream glob/minimatch advisories blocked an otherwise passing backend. Patched glob14.0.2/minimatch11.0.1 plus ESLint10.14.0 eliminate the known audit findings. Google gts7 config and Angular21.2 remain unchanged; gts’s declared ESLint9 dependency is explicitly overridden to the shared root10. This compatibility decision is covered by actual Google rules, CJS/ESM, glob/ignore selection, package/cache, CLI MCP and dev-server checks; it is not an upstream support promise. Revisit on the next gts update. See [coding policy](../engineering/CODING_STANDARDS.md) and [tooling QC](TOOLING_SECURITY_QC_REPORT.md).

Independent QC found no concrete blocker in the bounded source corrections or21 captures. Nonblocking visual copy issues remain: conflict guidance repeats twice and UNKNOWN guidance appears outside/inside its card. Capture review is not a complete accessibility audit or Figma pixel-parity claim.

This slice adds configuration, not dependency history UI, graph repair, planning, run/queue/output/review execution, provider integration, effective tool authorization or runtime metrics. No load/penetration/production-release certification, Sonar gate, deployment to the user’s Mac, merge or release is claimed.

## Evidence links

- [Quality run](https://github.com/giangnguyen3260/agent_os/actions/runs/41170335575)
- [Docker run](https://github.com/giangnguyen3260/agent_os/actions/runs/41170335194)
- [Frozen contract](../engineering/TASK_DEPENDENCIES_CONTRACT.md), [backend authored cases](TASK_DEPENDENCIES_BACKEND_TEST_CASES.md), [browser cases](TASK_DEPENDENCIES_TEST_CASES.md), [migration](../../backend/TASK_DEPENDENCIES_MIGRATION.md).

## Documentation closure provenance

The follow-up closure changes Markdown only. Application, migrations, tests, dependency lockfiles, Docker files and workflows remain byte-identical to verified C9. Runtime evidence refers to C9 and its tested merge, not the later documentation SHA. Sonar remains paused and the PR remains draft/unmerged/undeployed.
