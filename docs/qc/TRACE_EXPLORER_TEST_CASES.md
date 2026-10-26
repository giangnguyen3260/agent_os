# Trace Explorer PRnext acceptance cases

Authority: `docs/engineering/TRACE_EXPLORER_CONTRACT.md`, including the appended legacy preview boundary. Expected counts are frozen; discrepancies must be reported to main. This document describes authored acceptance, not a successful execution record.

## Isolation and execution

Only the four assigned QC files are owned by this author. Main owns fixture, navigation and workflows; application workers own FE/BE. No commits, schema changes or constraint drops.

Fixture: `scripts/trace-explorer-ci-fixture.sql`. Organization suffixes are native=1, desktop=2, tablet=3, mobile=4, foreign=5 in `50900000-0000-4000-8000-{suffix:012d}`. Prefixes `50910000`, `50920000`, `50930000`, `50940000` identify Team, Mission, Task, event; their suffixes are respectively n*100+1, n*100+m, n*100+t, n*1000+e. Disposable credentials use `trace-{surface}-{admin|member|other}@example.test` and `E2E_PASSWORD`. The exact original role, enabled flag and credential version of the `other` account are restored in finally blocks.

Each surface has 38 persisted events and **33 visible events**: 1–32 and 38. Events 1–30 share a timestamp and match normal Mission/Task. Event31 resolves its Mission from its Task; event32 has null Team/Task; event38 preserves literal `%_!\<script>` and opaque actor text. Events33–37 are excluded (org-only, foreign Task, dangling Task, mismatched Mission/Task, foreign Mission). SQL expected projections select only accepted fixture IDs and safe metadata; they do not copy the production visibility predicate or return raw payload. SQL fingerprints hash payload without returning its content.

Browser describe is exactly `trace explorer independent QC`. Existing Playwright config supplies 1440×1000, 768×1024 and 390×844, one worker and zero retries; the suite asserts those worker/retry settings and runs serially. Eight cases × three projects = 24 tests. The separate baseline remains 609 tests.

```sh
E2E_BASE_URL=http://127.0.0.1:18080 python3 scripts/trace-explorer-probe.py
cd frontend
npm run test:e2e -- --grep 'trace explorer independent QC'
npm run test:e2e -- --grep-invert 'trace explorer independent QC'
```

Set `E2E_PASSWORD` through the existing disposable bootstrap workflow; do not print it. A healthy real stack, Docker Compose/psql and the main fixture are required. Collection is not execution or acceptance.

## Browser matrix

Every row executes at each of the three widths. Successful lists/details come from actual authenticated backend responses. SQL checks use actual PostgreSQL. No synthetic successful fixture response is served.

| Case | Assertions | Screenshot state |
| --- | --- | --- |
| TE01 | ADMIN actual list total33, page0/20 and page1/13, deterministic same-time ID order, SQL persisted IDs excluding33–37, real page/total, paging boundaries, no selected first event, selected desktop Trace navigation, business snapshot unchanged | overview |
| TE02 | MEMBER selection separate actual detail equals list event; DOM safe IDs/titles/types; actual task navigation and history return; task-derived Mission31, null Team/Task32 and no links; literal38 escaped; compact dialog has one Trace link, Escape and focus return | selected |
| TE03 | Literal `%`, `_`, `!`, backslash and script label each match only38; intersecting q/Mission/Task filters total30; invalid UUID visible alert and no trace GET; drafts do not apply until submission; real reset clears IDs/selection and returns33 | filtered |
| TE04 | Foreign, other-Mission and missing Mission filters return real empty success with no rows; excluded detail33–37 returns404; foreign org403; unknown/duplicate/noncanonical/invalid query400 | empty |
| TE05 | Corrupt fetched real200 into wrong org, forbidden extra field or wrong selected ID; inject503 only after a real200; list/selection cleared, error distinct from empty; real Refresh recovers; malformed workspace200 and workspace503 are errors | error |
| TE06 | Hold fetched real200 list; delete own membership, repeat Refresh, observe actual workspace403, release old response and assert denied/cleared; preserve user draft; real API403; disabled and credential-version loss deny and restore original account/membership | denied |
| TE07 | Hold fetched detail200, same-document Angular route-param reuse to foreign org, release and ensure no old data; pending workspace200 plus real cookie account change and focus/session check cannot restore private content; held list plus actual SPA navigation destroys component | reused |
| TE08 | Real isolated event99 insertion, hold actual Refresh200, pending DOM cleared; release shows total34 and new first row; selection stays cleared; focus/visible-return actual GETs; finally delete only event99 and compare business snapshot | refreshed |

Captured filenames are exactly `trace-explorer-{desktop|tablet|mobile}-{state}.png` under `frontend/qa-visuals/`, matching main's upload glob. Eight states per width = 24 unique captures on a completed passing run. Each capture asserts no horizontal document overflow. These are synthetic-account screenshots, not production data. No captures are claimed before execution.

Held responses have explicit arrival, release and completion gates. Tests require the real intercepted response to be200, assert the pending private DOM, trigger the change, then release and drain rendering before checking stale data. Release occurs in finally; no optional interception, sleeps, fabricated successful data or fake empty success. Route reuse uses the existing `pushSpaHistory` helper; it is synthetic same-document router navigation, not a claimed browser Back/Forward test. The separate TE02 `goBack` is actual history navigation.

## Native HTTP/SQL matrix

The stdlib probe uses CookieJar, actual CSRF retrieval and form login, with independent ADMIN/MEMBER/other cookie clients.

| Boundary | Required checks |
| --- | --- |
| Source/projection | SQL expected33 accepted IDs; exact event and nested metadata key sets; UTC timestamps normalized; exact list/detail envelopes, explicit nulls; current generatedAt and runtimeStatus UNAVAILABLE |
| Pagination | Exact filtered totals, fixed20, page0/1/2/100000, beyond-last empty retains33, same-time stable ID order |
| Literal filters | %, _, !, backslash, script text, mixed case, Java-strip whitespace, NBSP preserved, blank,100 Unicode code points, injection-looking text; Mission/Task and intersecting filters; foreign/nonexistent IDs yield empty without metadata |
| Validation | Unknown/duplicate parameters including size/sort, empty/invalid/uppercase/non-RFC UUIDs, noncanonical decimal pages, out-of-range pages,101 Unicode code points; detail rejects every query parameter and malformed IDs |
| Detail and scope | Every visible event compares against SQL;31 task-derived Mission;32 explicit nulls;33–37 excluded404; foreign/missing event404; foreign organization403; anonymous401; ADMIN/MEMBER both read |
| Legacy HTTP | Both `/api/v1/organizations/{org}/audit-events` and `/api/v1/organizations/{org}/missions/{missionId}/trace`: ADMIN/MEMBER404; foreign-org403; foreign/missing Mission404 preserved. All raw response bodies scanned for payload keys/sentinels. Source review must separately prove no service/query call |
| Read-only | Global sorted SQL-only business digests before/after reads: organizations, teams, agents, missions, tasks, dependencies, audit events, approval_grants, agent_draft_config, agent_configuration_versions and all installed receipt tables. Audit projection uses md5(payload) AS payload_digest; no raw payload/config/grant/receipt leaves SQL |
| Refresh | Insert only native event1099 with empty payload literal required by schema; real native GET total34 and first ID; detail200; finally remove only that ID and restore fingerprint |
| Current authority | Delete only own other-account membership, disable it, or increment credential version; list/detail401/403; restore exact original values in finally; new login list/detail200 after each mode |

Business fingerprints do not include session/login audit state, which necessarily changes during authentication. Audit payload content is covered through SQL md5(payload), and all business rows are hashed in SQL before any digest leaves PostgreSQL. Browser before/after hooks enforce the same global boundary for every case; isolated writes are removed before the after-check. Native HTTP Refresh verifies a new GET; browser TE08 separately verifies the actual Refresh control.
