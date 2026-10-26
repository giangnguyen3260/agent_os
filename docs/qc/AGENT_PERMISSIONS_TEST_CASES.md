# Agent Permissions independent QC cases

Authoring scope: only this document, AGENT_PERMISSIONS_REVIEW.md and `frontend/tests/e2e/agent-permissions.spec.mjs`. No application, fixture, workflow or commit changes. Authoritative source: `docs/engineering/AGENT_PERMISSIONS_CONTRACT.md`; companion gap review is historical where they conflict. Exact suite name: `agent permissions independent QC`.

Eight meaningful browser cases × desktop 1440×1000, tablet 768×1024 and mobile 390×844 = **24 new collected cases**. `frontend/playwright.config.mjs` supplies workers=1, retries=0 and separate project contexts. These cases target actual backend data; a collected case is not a pass.

| Case | Actual setup and action | Required evidence |
| --- | --- | --- |
| AP-B01 | Login current MEMBER; create a disposable real human approval grant for that member; GET Permissions through enabled Studio link | Exact 11-key projection, exactly three ordered opaque requests, every effective/grantedBy/scope null and UNAVAILABLE despite human grant. Verify actual persisted strings, Back and Open Agent Studio scoped destinations; compact native menu Escape/focus. Remove only the disposable grant in finally. |
| AP-B02 | Read agents 2,3,4,5 then navigate to their Studio | DRAFT missing config is distinct from ACTIVE present null; SUSPENDED present empty model/tools and whitespace-only skills is unchanged; ARCHIVED readable. Correct lifecycle, row presence, missing hook and enabled Studio Permissions navigation. Agent 1 DRAFT covered by B01. |
| AP-B03 | GET current Studio configuration, actual CSRF/idempotent PUT with unique opaque multiline model, then Refresh | Successful backend write; revision increments exactly one; GET equals stored revision and exact string, including angle brackets/whitespace; text is escaped; no inferred grants. This deliberately changes the disposable DRAFT config. |
| AP-B04 | Use actual org-5 foreign Agent under current org, then its actual foreign organization | Backend 404 and 403 respectively. UI denied, no previous identity/team/request text; no fabricated foreign success body. |
| AP-B05 | Start with actual successful read; each fault interceptor first route.fetches actual GET 200 and validates persisted data, then delivers 503 or malformed data:null | Both errors remove all private rows/context; Refresh retries only GET, obtains actual backend data and clears error. Exactly two intercepted requests per fault, zero client writes. |
| AP-B06 | Actual ADMIN→MEMBER SQL role transition, Refresh; legal membership deletion, API GET and focus | MEMBER remains readable. Deleted membership returns actual 403 and UI denied, all private content gone. Restore ADMIN membership in finally. No UNKNOWN role and no constraint bypass. |
| AP-B07 | For each late-success/late-503 variant, hold an actual fetched GET 200; delete membership, verify actual GET 403 and focus revalidation; release old reply | Loading already clears private data; denial wins over stale success and stale transport error. No revived identity, rows, team or error. Restore membership and release/finish handler in finally. Explicit 15-second barriers fail on timeout. After release, main's helper additionally awaits the original browser request response/completion (or cancellation) and two animation frames before the strict DOM assertions. |
| AP-B08 | Hold actual GET; sibling page logs out and logs in as different real shared-session actor with temporarily deleted membership; focus original page; release old reply. Restore actor membership/login. Hold old Agent GET and navigate to ACTIVE Agent via existing history helper, then real Back/Forward | Session endpoint proves actor IDs differ; old private data cannot survive actor denial. Reused component receives real backend new identity, ignores old response, retains same document marker; Back and Forward each load actual appropriate GET. Restore other ADMIN membership in finally. |

Every direct `open` compares native business snapshots before/after inspection: agents/configuration, configuration versions/receipts, human grants, tasks and audit events. Auth/session rows are intentionally excluded because authentication updates infrastructure state. This is bounded browser no-write evidence, not a claim of exhaustive database immutability; native probe and integration coverage remain main/BE responsibilities.

## Fixture agreement

Main owns `scripts/agent-permissions-ci-fixture.sql` and native probe. Browser projects use n=2/3/4; native n=1 and foreign n=5. IDs: Agent `50820000-0000-4000-8000-{n*1000+a padded to 12 digits}`, Team prefix 50810000 suffix n*100+1. Organization is resolved from unique project ADMIN membership and must be exactly one UUID. Emails `permissions-{surface}-{admin|member|other}@example.test`. Password is exclusively `E2E_PASSWORD` and never printed.

Inspected fixture has `other` ADMIN in its own project org: B08 temporarily deletes that membership to obtain real denial, then restores ADMIN. Present-empty Agent 4 has skills exactly three spaces. The seeded native member human grant is asserted present; B01 additionally inserts its own per-project member grant and cleans it up. No fixture edits needed from QC.

## Screenshot manifest — exact handoff to main

Output directory: `E2E_VISUAL_DIR`, otherwise `qa-visuals` relative to frontend run directory. Prefix `agent-permissions-`, suffix `.png`; full-page capture only after state assertions and no-horizontal-overflow assertion.

| State | Case | Filename for each surface |
| --- | --- | --- |
| overview (requested) | B01 | `agent-permissions-{desktop|tablet|mobile}-overview.png` |
| missing | B02 | `agent-permissions-{desktop|tablet|mobile}-missing.png` |
| empty | B02 | `agent-permissions-{desktop|tablet|mobile}-empty.png` |
| active | B02 | `agent-permissions-{desktop|tablet|mobile}-active.png` |
| refreshed | B03 | `agent-permissions-{desktop|tablet|mobile}-refreshed.png` |
| error | B05 | `agent-permissions-{desktop|tablet|mobile}-error.png` |
| denied | B04 | `agent-permissions-{desktop|tablet|mobile}-denied.png` |
| reused | B08 | `agent-permissions-{desktop|tablet|mobile}-reused.png` |

Exactly 8 states × 3 widths = **24 expected PNGs**. This is a planned manifest, not a claim that files exist or have been visually accepted. Main must count/hash captures after an actual passing run.

## Execution

From frontend with disposable real stack/fixture already initialized:

```sh
npx playwright test tests/e2e/agent-permissions.spec.mjs --grep 'agent permissions independent QC' --workers=1 --retries=0
```

Do not replace actual backend setup with fulfilled success JSON. Faults are the only response alterations, after fetching real responses. Do not downgrade denial to generic error or catch timeout failures to make these cases pass. Preserve the separate 585-browser baseline; no baseline execution is claimed here.


Follow-up source review: FE workspace raw GET now preserves 403 versus 503; both navigation menus select Agents. Vietnamese labels, action roles, suite name, eight cases and 24-image manifest remain frozen. Main strengthened only late-response delivery synchronization; no expected denied/private-data outcomes were relaxed. Updated production-transpile harness passed; actual backend/browser execution remains pending.
