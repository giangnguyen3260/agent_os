# Mission Control read workspace test cases

Scope: read-only persisted Mission metadata, Tasks/Pending and safe Activity. Contract: [MISSION_WORKSPACE_CONTRACT.md](../engineering/MISSION_WORKSPACE_CONTRACT.md). Business success comes from the real backend; intercepts only inject failures. Coordinator owns fixture, native PostgreSQL probe and workflows. QC owns this document, report and `frontend/tests/e2e/mission-workspace.spec.mjs`.

## Six browser cases, three widths

Playwright projects: desktop 1440×1000, tablet 768×1024, mobile 390×844. One worker, six cases per project, 18 instances. All18passed within258total on candidate1488f16; exact run/merge/timestamps in MISSION_WORKSPACE_QC_REPORT.md. Member revocation restores membership in `finally`.

| Case | Required evidence |
|---|---|
| MW01 | Exact 31/1/1/24/1 task summary, real title/status/manager, all nine statuses populated, exact task ID order, pending reasons/date/resolver, non-pending fields null, no owner/run/spec, UI filters reset page and show backend rows. |
| MW02 | Pending 20+4, scoped statuses, disjoint pages, disabled paging boundaries, URL page state, reload/Back, Running and Pending metric navigation. |
| MW03 | Activity 20+5, exact 25 safe 2050 IDs in stable order, all five 2060 mixed/reference-less events excluded, exact four keys, real event type, valid dates, Previous/repeated reads stable. |
| MW04 | Empty Mission has true zero counts/rows and null manager ID/name despite other-Team manager reference; all three endpoints deny foreign organization, missing/foreign/wrong Team and Mission; malformed paging/status 400; invalid URL clears rows. |
| MW05 | Each Tasks/Pending/Activity refresh rejects 503, null envelope and corrupted scope/extra event key; clears private metadata and rows; unintercepted GET retry restores real content. |
| MW06 | MEMBER reads; unsupported Add Task/Pause/Cancel/Plan/Outputs disabled; unavailable explanation; native compact Menu dialog Escape/focus; no overflow; navigation hrefs; real membership removal denies every workspace endpoint and clears metadata; membership restored; no browser business writes. |

## Exact disposable fixture

Source: `scripts/mission-workspace-ci-fixture.sql`. Organization `20202020-2020-4020-8020-202020202020`; foreign `21212121-2121-4121-8121-212121212121`. IDs use `2010` Teams, `2020` Agents, `2030` Missions, `2040` Tasks, `2050` safe events and `2060` unsafe mixed events. Suffixes are twelve decimal digits. Team1/Mission1 is Mission Control Review, RUNNING, Control Manager. Mission2 is empty CREATED with an invalid other-Team manager; Mission3 belongs to Team1 and has task32; Mission4 belongs to Team2 and has task33. Foreign mission uses prefix2130.

Tasks1–9 cover the nine enum states in contract order; tasks10–31 alternate WAITING/BLOCKED. Pending reason is `Await evidence N`, resolver `Control operator`. Counts: total31, completed1, running1, pending24, review1. Task order31…1. Events25…1 are HUMAN / CONTROL_TASK_EVENT; even safe events have null mission and valid task; event25 is mission-only. The five 2060 exclusions include valid mission plus same-Team other-Mission task, inverse mixed reference, foreign task, other-Team task and no references.

Accounts: `mission-workspace-admin@example.test` and `mission-workspace-member@example.test`; password via `E2E_PASSWORD` from bootstrap. No password is stored here.

## Frontend selector coordination

Proposed selectors for FE worker Mill01a15f66-2c83-7575-888e-df6d9ecce0a9: `mission-workspace` with `aria-busy`, `workspace-tasks` containing `task-row`, `workspace-activity` containing `activity-row`. Accessible names: Status; Tasks/Pending/Activity; Running tasks/Pending tasks; Apply filters; Previous/Next; Refresh/Try again; Add Task/Pause mission/Cancel mission/Plan/Outputs; Menu; Workspace navigation dialog; Missions/Team Workspace/Mission details links. Pending FE delivery reconciliation; proposals are not evidence of implementation.

## Acceptance limits

Final independent read-only source QC follows completed frontend/backend delivery. Coordinator probe must compare native PostgreSQL exact projections/snapshot and unchanged business state. Browser source assertions do not replace runtime evidence or prove all stale-response/account-change interleavings. No runtime execution, production acceptance or Sonar pass is claimed.
