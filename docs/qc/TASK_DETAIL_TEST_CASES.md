# Task Detail QC cases

Scope: read-only task projection, task-scoped safe activity, native navigation and responsive behavior. Only QC-owned files are this document, `frontend/tests/e2e/task-detail.spec.mjs` and `scripts/task-detail-probe.py`. No fixture, production source or CI changes; no commits.

## Shared fixture and exact expectations

Reuse `scripts/mission-workspace-ci-fixture.sql` without adding seeds. Organization `20202020-2020-4020-8020-202020202020`, team `20100000-0000-4000-8000-000000000001`, mission `20300000-0000-4000-8000-000000000001`. Tasks use `20400000-0000-4000-8000-000000000001` through suffix31. Tasks1–9: DRAFT, READY, RUNNING, WAITING, BLOCKED, IN_REVIEW, COMPLETED, FAILED, CANCELLED. Pending fields: `Await evidence N`, `Control operator`, persisted waiting timestamp. Other states have null pending fields.

Task1 has exactly 24 safe events: 2050 suffix24…1, pages20+4. Mission-only event25 is excluded. Mixed-reference 2060 events are excluded, including the event referencing task1 but another mission. Tasks2–31, including WAITING task4, have no events. Other-mission task32, other-team task33 and foreign task2140 are scope-negative fixtures.

Use `mission-workspace-admin@example.test`, password from `E2E_PASSWORD`; never store credentials. Existing Playwright configuration discovers the spec automatically at 1440×1000, 768×1024 and 390×844, one worker.

## Browser acceptance

| Case | Evidence required |
|---|---|
| TD01 | Native reads for all nine states; exact title and IDs; real pending reason/resolver and semantic waiting timestamp; valid created/updated dates; unavailable acceptance and run history. |
| TD02 | Exact safe task1 event IDs and four keys, 20+4 API pages; UI Next yields four rows and boundary disabled; empty task has no invented events or private text. |
| TD03 | Mission Control title is a real task link; task heading, reload/Back, mission/team hrefs; View approval/View run/Retry task disabled; no horizontal overflow at each project width; no business HTTP writes. |
| TD04 | Both endpoints deny foreign organization403 and wrong/missing/foreign team, mission or task404; nonexistent UI task clears previous title and activity. |
| TD05 | Failure-only interception of detail/activity503, null body and malformed scope/extra payload; stale title and events cleared; unintercepted Try again restores 20 real events. |
| TD06 | Negative/malformed/oversized/duplicate pages and unknown query clear data; Reset activity page recovers; native compact Menu dialog Escape restores trigger focus. |

In this QC-owned browser suite, success data is never mocked. These browser cases use native endpoints and the existing fixture; interception only injects failures. Coordinator mock checks are separate evidence, described below. Six cases × three projects =18 instances.

## Native probe

Run from the repository with disposable services and fixture loaded:

```sh
E2E_BASE_URL=http://127.0.0.1:18080 python scripts/task-detail-probe.py
cd frontend
npx playwright test task-detail.spec.mjs
```

Probe compares all31 exact task projections (including createdAt/updatedAt) and all task activity pages against PostgreSQL; timestamps normalized semantically. Root, task and related entity projections must match exact keys. Events must match exact four-field SQL projections. Both endpoints exercise organization/team/mission/task boundaries, malformed paging and a high empty page. Raw HTTP text is checked for private fixture canaries and forbidden payload/actor/goal/spec/owner/run/output/password keys. Full business-row snapshots across teams, agents, missions, tasks, audit_events and mission_create_receipts must remain identical. Authentication precedes snapshots; no mutation HTTP methods or fixture writes are issued by this probe.

## Read-only source review

Reviewed task-detail component/template/styles/service, route integration, Mission Control title links, TaskDetailController and TaskDetailService. Backend joins task/mission/team on full organization/team/mission scope, authorizes organization membership, uses read-only repeatable-read transactions, and emits explicit DTOs. Activity requires matching task and null-or-matching mission, projects four fields, orders createdAt DESC/id and caps pages. Frontend clears data and cancels requests on reload/route/account changes, guards callbacks with epoch/account, validates task identity/scope/status/pending/timestamps and safe activity; unavailable actions are disabled. Final frozen-frontend source review identified no new issues or release-blocking source defects. The frontend validator enforces exact keys on the detail root and nested task, mission and team objects, rejecting extra or missing fields; activity events retain strict four-field validation. Backend exact projections are independently asserted by the native probe. TD01’s open helper waits for the task heading before activity completes; this is harmless for its detail assertions, while activity-specific cases await activity UI. Independent native runtime acceptance remains pending.

## Execution evidence and limits

Executed Node syntax check and Python compilation successfully. Final Playwright discovery succeeded: six cases across three widths (18 instances). Desktop TD01 runtime was attempted and failed before test execution because the Chromium headless executable is missing. Native probe was attempted but stopped at CSRF request with connection refused on127.0.0.1:18080. Default browser target127.0.0.1:8080 also refused connection. At those independent execution attempts, Docker was unavailable and E2E_PASSWORD was unset in this workspace. These are historical execution limits, not successful acceptance results. No independent browser or native acceptance pass is claimed. Coordinator owns CI wiring and service-backed execution.

Separately, the coordinator reported 38 rendered mock UI/interaction checks passing across 1440/768/390 widths, with screenshots inspected at all three widths. Reported coverage includes paging/reload, rejection of extra detail fields, scope/date/null/503 failures, extra event fields, invalid counts, Menu Escape/focus and no business writes. These coordinator-reported mock and visual results were not independently rerun by QC and do not establish native backend/PostgreSQL acceptance.

## Final coordinator CI acceptance

Corrected candidateb9a649753212dd9d4fd00adb4159e655a8f0ec94 at merged6038d6a6566b0575493fb1f49f325d1030bfc9e passed114backend tests and276real-backend browser instances (18new+258baseline), plus native31-task PostgreSQL projection/safe-event/scope/no-write checks and Docker/restart/logout. Runs39705433643(quality)/39705433640(Docker). Coordinator inspected completed logs; see TASK_DETAIL_QC_REPORT.md for exact evidence and initial assertion correction. Historical independent execution limits above do not override this later coordinator CI evidence.
