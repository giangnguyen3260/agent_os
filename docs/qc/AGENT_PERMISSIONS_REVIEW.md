# Agent Permissions QC review

Scope: source inspection and independent browser-case authoring on feature/agent-permissions. Only QC review, case matrix and browser spec are owned. No app/workflow/fixture edits or commits by this QC task.

## Findings and contract precedence

Read `AGENT_PERMISSIONS_CONTRACT.md` and `AGENT_PERMISSIONS_GAP_REVIEW.md`. Final contract overrides preliminary Back-only wording and preliminary `grantor`: final wire uses `grantedBy`, exact 11 top-level keys, MODEL/TOOLS/SKILLS order and explicit null effective/grantedBy/scope. Actions are Back, Refresh and Open Agent Studio. Requested strings remain opaque, including markup, newlines, empty strings and whitespace. UNAVAILABLE is successful infrastructure absence, not a permission denial or an effective grant set.

V002 CHECK supports only ADMIN/MEMBER. Tests use a legal ADMIN→MEMBER transition (still readable) and actual membership deletion (denied), never UNKNOWN roles.

Final available frontend HTML and component were inspected before locator freeze: `agent-permissions.component.html/.ts` and Studio HTML. Assertions use Vietnamese group labels Mô hình/Công cụ/Kỹ năng; null Chưa cấu hình; empty Chưa nhập nội dung; effective Chưa thể xác minh; grantor/scope Không có dữ liệu. Back and Open Agent Studio are links. Refresh is a button and the current error retry action; there is no separate Thử lại button. UI hooks match contract. Revision and time assertions target rendered metadata/time.

### Initial findings and follow-up resolution (source evidence)

1. At inspection, `AuthService.getWorkspace` maps HTTP 403 to `AuthFailure('server')`; Permissions.failure only marks AuthFailure unauthorized/not-found denied. Membership deletion therefore appears liable to render `permissions-error` instead of required `permissions-denied` before the permissions GET is reached. B04/B06/B07/B08 retain strict denial assertions. Do not weaken them to generic alerts. Resolved in the follow-up source review below; the initial observation is retained for traceability.
2. At inspection, Permissions sidebar and compact menu marked Organization map selected rather than Agents. This is a navigation fidelity issue; QC did not edit production HTML.
3. Fixture details checked: Agent 4 skills are three spaces, and other account is ADMIN/member of its project org. Tests preserve whitespace and temporarily remove/restore other membership for a real actor-switch denial. Main fixture seeds native human approval grant only; B01 creates/cleans a project-member human grant to make non-inheritance meaningful at every width.

## Coverage and evidence

Eight cases authored in `frontend/tests/e2e/agent-permissions.spec.mjs`; **24 collected across three existing projects**. Exact describe: `agent permissions independent QC`. Case mapping and exact screenshot manifest are in `AGENT_PERMISSIONS_TEST_CASES.md`. Manifest: `agent-permissions-{desktop|tablet|mobile}-{overview|missing|empty|active|refreshed|error|denied|reused}.png` (24 expected).

Real backend required for all normal responses, lifecycle/configuration projections, revision writes, foreign scope, membership changes and shared sessions. Fault handlers route.fetch actual backend 200 before producing 503/malformed bodies; delayed old success/error starts from actual GET. Same-component reuse uses existing pushSpaHistory helper, with same-document marker plus actual backend navigation and Back/Forward reads. Explicit barriers fail instead of silently timing out. Business snapshots bound read-only evidence around direct opens; intentional test setup/configuration writes are separate.

| Check | Result |
| --- | --- |
| Node syntax check | Passed after authoring; repeated final verification recorded in task handoff |
| Playwright collection | 24 cases collected (8 per desktop/tablet/mobile), no execution |
| Actual backend browser cases | **0 executed; 0 claimed passed** |
| Screenshots/hash/visual acceptance | **0 captured; pending main actual run** |
| Existing 585-browser baseline | Not executed by this QC task |
| Native integration/probe, lint/build/security, production rollout | Not claimed; owned elsewhere |

Execution blocker: this authoring environment has no `docker` executable (`docker compose ps`: command not found); `psql` is also unavailable. Spec intentionally uses native Docker PostgreSQL fixture operations and does not substitute mocks. Test collection/syntax are authoring checks only, not backend acceptance. Source findings above are unverified runtime risks, not manufactured test results.

## Final source freeze handoff

Re-read main's actual fixture/native probe, BE response DTO/service and final available FE HTML/service/component before freeze. Native probe explicitly expects unsupported Permissions PUT **ADMIN 405 / MEMBER 403** (security may reject before route dispatch); browser QC adds no permissions write API and does not invent a uniform 405 expectation. Studio PUT copies the eight existing Agent request fields plus current concurrency/maxTokensPerRun/monthlyBudget and expectedRevision, with actual CSRF and unique idempotency key, matching native probe.

Initial requested fixture strings: model `requested-model`; tools `Read document; Write Figma\n<script>not executable</script>`; skills `Opaque skill request\nNo grant inferred`. These are raw requested data, never grants. Browser assertions compare native persisted strings rather than prohibit those words globally. Agent 4 is model/tools empty string and skills exactly three spaces.

Final verification: `node --check` passed, Prettier check passed, exact CI-grep Playwright listing succeeded with **24 tests in one file**. No backend browsers executed. Main's **9 BE cases / 351 Quality / 328 regular** are planned figures supplied by main, not passes established here. Existing 585 baseline remains unchanged by QC.

Source SHA-256 at inspection (for detecting later owner changes, not screenshot hashes):

- FE HTML: `0c94daef37596623ea26129c3d7728364464b4ceb167ca12df91448fc2c227b5`
- FE component: `b22a86be7c48d1bd9eaace226c34042d1661218318a868e9eac6af32f3b9b1cc`
- BE response DTO: `9dd2be9cdae096697cefccbf8e3d0f5f513669913b524bf2f79a5e66033f3294`

This initial freeze is superseded by the follow-up freeze below. Browser execution remains required; neither freeze constitutes runtime acceptance or publication approval.


## Follow-up freeze: FE fixes and main response-delivery barrier

Reviewed the narrow owner changes after the first freeze. Both initial source findings are **resolved in source**:

- Permissions now calls `AgentPermissionsService.workspace(id)`, which uses raw HttpClient GET `/api/v1/auth/workspaces/{id}`, `firstValueFrom` and the existing 30-second timeout. It preserves HttpErrorResponse status rather than flattening 403 through shared AuthService. The component retains epoch/actor checks after both awaited session/workspace reads. A raw 403 reaches the existing denied branch; 503 remains a generic load error. The exact workspace validator accepts only current ADMIN/MEMBER and checks the requested organization and name. Invalid 200 DTO classification was subsequently corrected to a load error, as recorded in the final amendment below. No shared AuthService diff was present.
- Desktop and compact navigation now mark Agents selected, and Organization map is an ordinary scoped link. All previously frozen row labels, hooks and action roles remain unchanged; no English/Vietnamese oracle adjustments were necessary.

Executed the updated production-transpile harness with `node frontend/scripts/test-agent-permissions-validation.cjs`: **passed**. It explicitly checks workspace 403 versus 503/generic server errors, private-data clearing, no subsequent permissions read on workspace failure, valid/malformed workspace context, and existing stale success/error, actor, route, focus/visibility and pending workspace fences. This is a harness pass, not an actual backend/browser pass.

Main patched only the browser helper's late-response completion evidence: capture the original intercepted `route.request()`, await the handler completion, then await original request `response()` and, if non-null, `response.finished()`, then two animation frames before existing DOM oracles. Reviewed this narrow change: it prevents checking an already-denied DOM before the late response has reached the page, while permitting a null response for legitimate cancellation. Existing handler errors still propagate except explicitly released cancellation errors. Timeouts/retries and strict denied/private-data assertions are unchanged. QC left the spec untouched during this patch. No native fixture/probe modifications were made by QC.

Follow-up SHA-256:

- FE HTML: `0592889be311eca1368464cf5e4221d3d19fd180b533b91a6b49ad809f085ba8`
- FE component: `2abbf186c2deb24f9ffc1983fa18a2c264fbf2aeab579680073828194e8d2cf5`
- FE service: `7e95039cdf3b70cfb9b034d29cf2a2244951f5eae4ad59effdfa01f65e35e369`
- Browser spec after main's helper patch: `16b43574e49ad216ed873c38de24845164d51a17a023cf5f04657e5549f32342`

Still **8 browser cases × 3 widths = 24 authored/collected**, **0 actual backend/browser cases executed by QC**, **0 screenshots captured by QC**. Manifest unchanged. No test oracle relaxation, baseline edits or runtime acceptance claim.

### Final narrow source acknowledgment before main publication

Rechecked the current raw workspace endpoint/validator, component use and actor/epoch fence, both selected navigation entries, and main's original-page-request delivery barrier. All four source hashes match the follow-up freeze above. The service reads exactly `/api/v1/auth/workspaces/{encoded id}` and retains raw HTTP status; exact workspace validation precedes the Permissions read; both menus select Agents. The helper captures `route.request()` and awaits `response()` / conditional `response.finished()` plus two animation frames before existing late-response DOM assertions. No assertions were removed or relaxed. No shared AuthService diff is present.

**QC source acknowledgment complete; freeze unchanged.** No new tests requested or run in this final narrow review. Earlier harness/syntax/format checks and 24-case collection remain the only QC execution evidence; actual backend browser passes and screenshots remain unclaimed. Main may continue its publication process with this source acknowledgment; it is not a runtime acceptance claim. QC changed only this review document in this final acknowledgment.


### Final amendment: malformed workspace 200 is an error

Reviewed the arriving narrow FE correction: after the existing actor/epoch check, failed `validPermissionsWorkspace` calls `clear()`, sets `Dữ liệu workspace không hợp lệ. Vui lòng thử lại.`, and returns before any Permissions read. `load()` already resets denied false. A malformed successful transport response therefore erases private identity/context/rows and stops loading with an error rather than impersonating authority loss. Actual HTTP 403 still follows the denied branch; 503/server failures still follow the generic error branch. Validator strictness and all fences remain intact.

Re-ran `node frontend/scripts/test-agent-permissions-validation.cjs`: **PASS**. Updated harness explicitly asserts malformed workspace error, denied false, private data erased, loading false and zero Permissions GETs, plus preserved stale-response fences and raw workspace 403-versus-503 behavior. This is a source/harness acknowledgment only; no backend/browser execution was added.

Component SHA-256 now `7e21ad17941a9ea0b0f0611bd5de8df3e07aadbceee2967771b33e06dd2ae2d4`. HTML, service, browser spec and native probe hashes match the prior freeze. No row/hook/label changes, browser oracle changes, spec edits or native script edits by QC. Earlier browser malformed cases target the Permissions DTO and remain correct. **QC source freeze acknowledged with this amendment; runtime acceptance remains unclaimed.**
