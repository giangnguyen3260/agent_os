# Mission directory test cases

Target: `feature/mission-directory`; acceptance contract: [Mission directory](../engineering/MISSION_DIRECTORY_CONTRACT.md). Owned executable: `frontend/tests/e2e/mission-directory.spec.mjs`. Main owns fixture, native PostgreSQL probe and CI workflow.

Run the same six scenarios against the real application at desktop 1440×1000, tablet 768×1024 and mobile 390×844 (18 instances). Dedicated fixture accounts require `E2E_PASSWORD`; `E2E_BASE_URL` defaults to localhost:8080. Membership revocation also requires Docker Compose/PostgreSQL access from the test process. Execute serially as configured; restore membership in `finally`. Never run against production.

| Case | Required assertions |
|---|---|
| MD01 | Exactly 27 missions, fixed 20/7 pages, no duplicate IDs, createdAt descending/id ascending order, disabled paging bounds, URL page, reload/back, explicit empty page 100000 and recovery. |
| MD02 | Literal `%`, `_`, `!` match the fixture; backslash returns no match. All nine statuses have exact totals computed from the complete real directory. Case/whitespace search, combined status/query, page reset from page 1, reload, empty search and clear URL. |
| MD03 | Mission 25 has exactly 7 recorded tasks, 2 completed, 2 pending and the scoped manager. Legacy unassigned mission has zero counts/null manager, no detail link and honest zero-task copy. Exact safe projection keys, organization scope and private sentinel exclusion. Real detail navigation and Browse missions return. |
| MD04 | Real Team chooser returns 25 Teams over 20/5 pages with 25 unique IDs. Escape closes/restores focus. Second-page selection filters by Team with response metadata and URL preserved on reload; direct create reaches the existing scoped wizard without saving. |
| MD05 | MEMBER can read but has neither create link nor create button. Actual SQL membership deletion followed by Refresh clears rows and shows denial; API returns 403; membership restored even on failure. |
| MD06 | Only error injection is allowed: 503, null envelope, real response corrupted to foreign organization, real response corrupted to negative count. Each Refresh clears stale rows; removing interception and Try again recovers 20 real rows. |
| MD02 (scope checks) | Real API rejects negative/too-large/noninteger page, unknown status, malformed UUID and overlong query with 400; corresponding route clears rows with an alert. Missing and foreign Team return 404; foreign organization returns 403 and denied UI. |
| MD04 (create/navigation checks) | ADMIN create chooser searches the real Team directory; archived Team cannot be selected. Cancel restores focus. Compact Menu contains scoped Missions navigation, Escape restores focus, no horizontal document overflow. No organization API writes during chooser/navigation. |

## FE selector handoff — reconciled against delivered sources

Read-only reconciliation against the delivered component/template/service confirmed the selectors below. Runtime acceptance remains pending.

| Surface | Exact selector contract |
|---|---|
| Directory / row | `data-testid="mission-directory"`, `aria-busy="false"` when settled / `data-testid="mission-row"` |
| Filters | labels `Search missions`, `Status`; buttons `Apply filters`, `Clear filters`, `Previous`, `Next` |
| States | `role="alert"`; buttons `Refresh`, `Try again` |
| Team filter | button `Select Team`; visible native dialog; Team-name buttons; dialog buttons `Previous`, `Next` |
| Chooser search | label `Search teams`; native search form submitted with Enter |
| Create | unfiltered ADMIN button `Create Mission`; filtered eligible Team link `Create Mission`; archived Team-name button disabled in create mode |
| Reads | scoped link `View details`; saved detail link `Browse missions` |
| Compact nav | button `Menu`; dialog named `Workspace navigation`; link `Missions` |
| Authentication | labels `Email`, `Mật khẩu`; button `Đăng nhập` |

Additional acceptance review must confirm archived Teams remain selectable in filter mode, selected Team/search/status/page survive browser forward navigation, wizard Browse missions respects the unsaved guard, and late requests cannot restore data after scope/account changes. Current cases cover back navigation and membership revocation; these additional interactions are not claimed executed or fully automated.

The independent native PostgreSQL probe must compare projection/counts/order/filters and unchanged business snapshots. Browser request observation does not substitute for that probe. Account-enabled and credential-version authorization require backend/native evidence separate from membership revocation.

Main runs the native probe before the browser suite. Create flows stop at wizard navigation and never save a mission; fixture business counts remain unchanged.
