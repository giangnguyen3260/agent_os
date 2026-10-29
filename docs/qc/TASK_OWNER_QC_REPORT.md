# Task owner configuration QC

Status: **C6 accepted for the bounded draft Task owner configuration contract.** Both required workflows completed/success. Draft [PR34](https://github.com/giangnguyen3260/agent_os/pull/34) is stacked on PR33, unmerged and undeployed. SonarQube scanning remains paused.

## Candidate identity

- Source: `4fd55ccc90518a20f01967631380d642138498c3`.
- Tree: `1edcca1ad3a809f710b7dcd12c4c118c186853a1`.
- Tested merge: `f7f4a02984a4e99097d03fe060f5cb5a35daeef6`.
- Base: `ef94202a4d98b16c0804f0f32a69c8fce7d89d85`.
- [Quality40892374253/job123512317309](https://github.com/giangnguyen3260/agent_os/actions/runs/40892374253/job/123512317309).
- [Docker40892374203/job123512317793](https://github.com/giangnguyen3260/agent_os/actions/runs/40892374203/job/123512317793).

Completed Quality logs bind the checkout to that merge and confirm **762 tests, zero failures/errors/skips**, followed by BUILD SUCCESS. Owner integration52, native PostgreSQL59 and wire10 are included in762, not additional tests. Google Java formatting/import rules, frontend dependency audit (zero vulnerabilities in this run), lint, full source formatting, tooling/security/state harnesses and Angular production build passed.

Completed Docker logs bind the same merge and confirm the following. Counts describe separate CI invocations, not unique assertions.

| Gate | Completed C6 result |
| --- | --- |
| Regular backend tests during Docker build | 556; failures/errors/skips all zero; subset repeated separately from Quality762 |
| Actual owner PostgreSQL/HTTP probe | PASS: persistence, clear, privacy, candidates, ABA, replay, races, rollback, strict wire, authority and protected business data |
| New owner browser slice | 81 passed, one worker, zero retries |
| Focused retained INV-B08 check | 3 passed at desktop/tablet/mobile; additional repeated checks |
| Complete retained browser regression | 780 passed, one worker, zero retries; INV-B08 also passed in all three projects here |
| Disjoint new + retained browser instances | 861 passed; the focused3 are additional, not silently included in861 |
| Other retained native/concurrency probes | Completed/success, including account and recovery barriers |
| Backend restart/session/logout | PASS: exact session survives restart; logout rejects replay |

The completed visual upload log confirms ZIP SHA256 `446a20ec730855be42cbcd0ebb3ef4dd938e8c1f2af9bc3b1a05501c3781b7f9`, matching REST and downloaded bytes. No browser failures, skips or retry passes are used as acceptance. Native and browser tests run against disposable real services; they were not run locally where Maven/Java21/Docker are unavailable.

## Delivered scope

Current ADMIN enters **Task Detail → Phân công Agent** for an existing DRAFT task. The screen reads persisted current ownership, distinguishes unassigned from unavailable without foreign identity disclosure, and offers a native searchable paginated chooser. An explicit choice or clear must be reviewed and acknowledged before saving. The actual receipt records before/after, revision and time; Task Detail reload shows persisted current configuration.

New targets must be same-organization, same-Team DRAFT/ACTIVE Agents. A currently ineligible scoped owner remains visible and replaceable; a raw foreign/different-Team owner is UNAVAILABLE/null and explicitly clearable. No-op uses the raw pointer, so unavailable-to-unassigned is a real change. Parent lifecycle/intake gates and owner-revision exhaustion block fresh writes; unrelated exhausted input/intake counters do not consume owner revision.

V020 retains existing Task rows/owner pointers and creates no synthetic baseline. Each accepted command changes only owner ID, owner revision and updated time, atomically with restricted owner event, actor-scoped receipt and safe TASK_OWNER_UPDATED audit. Task input, dependencies, lifecycle/pending data, input revision, Mission intake and Agent/runtime/grant state are preserved. The input-edit snapshot domainV2 additionally binds owner revision to reject owner ABA; old input-command digests and receipt replay remain unchanged.

## Security, concurrency and recovery evidence boundary

The real PostgreSQL/HTTP probe and owner native suite cover actual assign/change/clear, scoped candidates and literal paging, foreign redaction, safe Trace, exact same-key historical replay, actor-private receipt lookup, competing writes, candidate rename/move, reciprocal input-edit/source conflicts and owner ABA with restored timestamp. Injected event/receipt/audit insert failures must roll back the entire transaction. Strict real HTTP rejects malformed body/header/query/media/UTF8 inputs and returns safe errors for malformed or event-inconsistent persisted receipts.

Native write effects compare every business-table hash, all protected Task/other Task/Team/Mission/Agent/dependency data and all preexisting event/receipt/audit rows, plus exact new provenance. Authentication state is deliberately excluded from business snapshots. Row-lock revocation uses one newly observed organization-lock waiter, the same PID on a second observation and a still-pending HTTP future before role/enable/credential revocation. This is bounded attribution under an isolated sequential no-other-traffic run, not full request-ID tracing.

Browser cases use actual backend successes and real DB effects. Lost/malformed delivery faults first fetch a real committed201, then alter only transport delivery. Explicit retry must preserve raw body/key and return historical200 without a second mutation. UNKNOWN is entered before delivery finishes, so retry observation waits for recovery controls to become enabled. Role/account/route/epoch fences, fresh preflight, failed/malformed authority clearing, saved-focus/no-discard behavior and opaque same-actor receipt recovery are separately asserted. Held requests are drained before late-response checks. Local/session storage checks are limited to their asserted strings; they do not certify every persistence channel.

## Visual evidence

C6 artifact12906556221 `task-owner-visuals`,1435181bytes; ZIP SHA256 `446a20ec730855be42cbcd0ebb3ef4dd938e8c1f2af9bc3b1a05501c3781b7f9` matches REST and downloaded bytes. Exactly30 original PNG and30 unique JSONL records cover edit, chooser, review, saved, blocked, conflict, unknown, denied, recovered and unavailable at1440/768/390. All image hashes, scope paths and viewport widths are checked; coordinator and independent QC inspect the actual captures. See [visual manifest](TASK_OWNER_VISUAL_MANIFEST.md) and [independent review](TASK_OWNER_FINAL_REVIEW.md).

No blocking captured visual finding was observed. This extends existing Forest conventions; no Figma frame was edited or independently matched. Chromium at three widths is not all-browser/every-pixel testing or formal accessibility certification. This feature does not activate Agents, dispatch tasks, supply effective grants, edit dependencies or implement execution/owner-history UI. No Mac or production deployment is claimed.

## Failed candidates and corrections

| Candidate | Actual failure | Correction before next run |
| --- | --- | --- |
| C1 cd37acb | Required Google Java formatting failed before acceptance. | Applied canonical CI formatting patch; artifact12866257107 ZIPsha db1b8eeef03d2cb6e98f27b8819b6052dc6eb2dfdf19544d42997c66cca96b00 matched REST/download. |
| C2 9091874 | New browser1pass/1failure: clicked compact Menu on desktop. | Assert desktop sidebar versus tablet/mobile native Menu; all chooser assertions retained. |
| C3 e876ab1 | New browser2passes/1failure: unavailable message substring differed from actual human text. | Require the full actual semantic message; foreign/raw-pointer/clear/privacy checks retained. |
| C4 d18275a | New browser13passes/1failure: listener captured original malformed201 during retry-button auto-wait. | Wait for settled UNKNOWN recovery controls before installing replay listener; still require identical body/key and200. |
| C5 8a5af73 | New81passed; retained618passed/1failure at INV-B08 mobile;161unrun. Persistent dialog.accept listener outlived test teardown. | Explicitly await each Back/unload/accepted-exit dialog and verify navigation/no-write; add focused3, retain all780. |

C2–C5 Quality runs passed their backend/frontend gates; those are historical results, not a substitute for latest complete C6 acceptance. Application source has not changed since C2; subsequent fixes concern test oracles/lifecycle and CI feedback order. Every retained step remains, with one worker, zero retries and unchanged timeouts. C6 overall Docker and Quality conclusions are both success. The independent final review records the same bounded acceptance. Documentation-only closure updates this report, README, API boundary, migration note and visual/review records; application, tests, fixtures, probes and workflows remain byte-identical to accepted C6. No fresh runtime pass is claimed for the later Markdown-only closure commit.
