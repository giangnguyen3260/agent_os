# Member role management — final QC

Accepted for the bounded development scope below. [PR24](https://github.com/giangnguyen3260/agent_os/pull/24) remains draft/unmerged, stacked on feature/approval-grant-management. This is not production release or Mac deployment acceptance.

## Tested candidate and evidence

- Application/test candidate: `81bc489a5449b870522ba7c19f1baa63921d0870`.
- Tree: `c19015eb49235e522d2ed8ffc9ffb0222ab54b77`.
- Tested PR merge: `515dab115d92ca79c2f776e185aa47801f852494`, base `3da67e2fa03432ad63e28509e53d188ea8bd91a0`. GitHub commit metadata independently binds that merge to these two parents and this tree.
- Final documentation changes after this candidate do not modify application, migration, dependency, workflow or test sources.

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend + quality | 192 tests passed:176 baseline +16 role tests; zero failures/errors/skips. Java format/import, full dependency audit0, FE lint/format/tooling, production DTO/command validators and build passed | [Quality40085396950](https://github.com/giangnguyen3260/agent_os/actions/runs/40085396950), job120847177677 |
| Docker + PostgreSQL | Healthy stack, V014 migration, all native regression and role probes passed | [Docker40085396952](https://github.com/giangnguyen3260/agent_os/actions/runs/40085396952), job120847177919 |
| Real browser | 471 passed, zero failures/skips:435 baseline +36 role instances (12 cases ×3 viewports),14.5minutes. Corrected Mission validation/focus case passed at all3 widths | Same Docker log; browser completed2026-10-24T10:37:56Z |
| Restart/logout | Exact persisted session survives backend restart; logout rejects replay | Same Docker log,2026-10-24T10:38:36Z |
| Visual review | 21 synthetic PNGs:edit/review/recorded/history/error/unknown/denied at1440×1000,768×1024,390×844; full-page heights may exceed the viewport | [Artifact12556864919](https://github.com/giangnguyen3260/agent_os/actions/runs/40085396952/artifacts/12556864919), member-role-management-visuals |

Artifact SHA256 verified after download: `afc61e10691c421dae197fdbbf49077e5825aae86065ce71088de8816c8bd9f7`. Coordinator inspected all21 final captures, grouped by state: dark readable body text, visible warnings/actions/focus outline, wrapped identifiers and no observed overlap or clipping in captured layouts. Browser horizontal-overflow assertions also passed. This is bounded Chromium viewport and captured-state evidence, not exhaustive accessibility or real-device acceptance. All captures use disposable synthetic QC data.

## Delivered behavior

A fresh enabled/version-matching organization ADMIN changes another enabled current member between ADMIN/MEMBER, never self. The locked enabled acting Admin preserves a surviving usable Admin. Separately issued approval grants, global credentials/sessions and other-organization roles remain unchanged. A demoted MEMBER with an otherwise eligible explicit grant can still approve the bounded existing request; administrative inventory/writes require their own fresh ADMIN checks.

V014 adds retained role revision ledgers, restricted before/after events with actor snapshots/reasons, and durable actor/org/key receipts. Membership change, revision, event, receipt and safe audit commit atomically. Strict command/DTO validation, canonical compact JSON UTF-8 hashes, paired supplementary Unicode, isolated-surrogate rejection, Java strip/code-point semantics, stable history ordering and exact receipt correlation are exercised. No legacy provenance is invented and no membership cascade resets retained history.

Native PostgreSQL probes exercise durable results/hash/replay/ABA/removed-target history, revision overflow, injected final-audit failure with full business rollback, actual membership/ledger lock waits and post-wait timestamps. Peer Admin demotions are observed behind the organization mutex, yielding201/403 with one peer Admin retained. Reversed actor/target writers across organizations observe globally sorted account locks. An ADMIN-only grant writer observed behind demotion is denied403 after release; the demoted explicit grantee still commits its eligible approval. Actor-only historical replay rechecks issuer authority after its lock wait. Credential, grant, other-org and runtime preservation assertions pass.

The real responsive UI provides blank new-role selection, required reason, fresh review before unchecked acknowledgement, final-only POST, immutable historical result, member refresh and restricted paginated history. Browser cases cover actual entry links, grant preservation on demotion, strict private DTO rejection, self/disabled/foreign boundaries, dirty Back/unload and reused routes, lost/malformed committed responses with identical body/key replay, null receipt plus advanced role state remaining unknown, observed same-ID authority loss, private-data erasure with opaque same-actor GET-only recovery, shared-session actor change, late history403 during unresolved committed POST, and obsolete-response suppression.

## Review disposition and limits

Independent source reviews are recorded chronologically in [MEMBER_ROLE_MANAGEMENT_REVIEW.md](MEMBER_ROLE_MANAGEMENT_REVIEW.md). A separate QC worker independently retrieved completed Quality/Docker logs and step/job results, verified candidate/tree/merge/base binding,192/471 counts including16/36 role tests, all native gates, the Mission focus case at all3widths, restart/logout/teardown,21-file artifact upload count/digest and draft/unmerged PR state. Download hash verification and all21-image visual inspection were performed by the coordinator, not independently repeated by that reviewer. No known blocking source finding remains in this bounded scope.

Earlier incomplete runs are not final acceptance evidence. The first backend failure used a MEMBER variable already promoted to ADMIN; a fresh MEMBER now supplies the negative-authority oracle, with promoted-ADMIN foreign404 checked separately. Dependency audit required scoped build Undici7.30.1, body-parser2.4.0 and fast-uri3.1.9; CLI Undici6.29.1 stayed unchanged. Two later browser failures were ambiguous generic alert selectors; denial/validation text filters and private-DOM absence checks resolve them. A baseline Mission tablet case raced deferred invalid-field focus before Context fill; four added invalid-state/focus assertions preserve exact Budget/Context and navigation/no-write checks. No sleep, retry, timeout increase, assertion removal or production authorization relaxation was used for these test corrections. Source-review history retains each failed candidate and partial result.

SonarQube remains paused. Invitations, account/membership lifecycle, Owner/first-Admin recovery/step-up, Task execution, provider integration and production-scale/security hardening are separate. Organization serialization is a correctness tradeoff, not load-test acceptance. Supported future membership removal/rejoin writers must participate in retained revision/incarnation rules; privileged direct SQL bypass is outside ABA protection. No branch was merged and no user Mac was accessed or deployed to.
