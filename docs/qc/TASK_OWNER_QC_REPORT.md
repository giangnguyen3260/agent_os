# Task owner configuration QC

Status: implementation and source corrections in progress on `feature/task-draft-owner`, based on PR33 closure `ef94202a4d98b16c0804f0f32a69c8fce7d89d85`. No runtime acceptance, merge or deployment is claimed.

The [frozen contract](../engineering/TASK_OWNER_CONTRACT.md) defines explicit assignment/replacement/clear of a DRAFT task's configured owner. Real same-organization, same-Team DRAFT/ACTIVE candidates are required. Unavailable foreign owner projection is safe and explicitly clearable. This slice adds no runtime, grants, status or dependency writer.

Required acceptance: Google Java style and actual backend compilation/tests; V019→20 retention and native PostgreSQL lock/revocation/races/rollback; strict HTTP and historical exact receipt recovery; frontend lint/format/build and validator checks; new real-backend browser cases plus all 780 retained instances; 30 actual responsive captures with hashes and independent review; Docker session restart/logout. Counts and links will be recorded from completed runs, not inferred from authored tests.

Local environment has Java17, without Maven/Java21 or Docker. Backend runtime must be verified by the configured CI runners. SonarQube remains paused and is not a passing gate. The independent source [review](TASK_OWNER_REVIEW.md) records concrete corrections and evidence limits; [cases](TASK_OWNER_TEST_CASES.md) are authored acceptance requirements, not pass results.


## C2 completed quality evidence

Source `9091874f4d67deb3f0a142d00cff44c8a4a7d700`, tree `12d81c991325845a4bc69b17a5c90c7b15879654`, tested merge `5f177e993cc44c2a0646e3bc76b87848ceae2f82`, base `ef94202a4d98b16c0804f0f32a69c8fce7d89d85`. Draft [PR34](https://github.com/giangnguyen3260/agent_os/pull/34), stacked on PR33; unmerged and undeployed.

[Code quality run40801695696](https://github.com/giangnguyen3260/agent_os/actions/runs/40801695696), job123211050516, completed/success. Completed log confirms merge checkout and **762 tests, zero failures/errors/skips**: owner integration52, wire10, native owner59; retained native suites and reciprocal input-edit ABA cases included. Google Java formatting/import rules, frontend dependency audit (zero vulnerabilities), lint, full source formatting, tooling/security/state harnesses and production build passed.

C1 `cd37acb0186cd9a0681e1022ff758202905fd438` failed the required Java formatting gate before test acceptance. Applied the canonical CI Google-format patch from artifact12866257107; downloaded ZIP SHA256 `db1b8eeef03d2cb6e98f27b8819b6052dc6eb2dfdf19544d42997c66cca96b00` matched REST digest. C2 is that formatting correction, with no disabled gate.

[Docker run40801695688](https://github.com/giangnguyen3260/agent_os/actions/runs/40801695688), compose job123211050994, remains in progress. Step metadata records successful retained native checks through Mission intake history and the new Task owner PostgreSQL probe; full job log, browser results, artifacts and final restart/logout remain pending. New browser collection is **81** cases (27×3); retained collection is **780**. These collection counts are not browser pass claims.


## C2 Docker result and C3 correction

Docker40801695688/job123211050994 completed/failure at the second new desktop browser case. Completed log confirms **556 regular backend tests, zero failures/errors/skips**, all preceding retained native steps, and `PASS: task owner actual persistence/clear/privacy/candidates/ABA/replay/races/rollback/strict wire/authority and protected business data`. The first actual assignment/change/clear/reload browser case passed. The next case timed out clicking `Menu` on desktop, where the intended layout uses the visible sidebar; this is a responsive testcase defect, not an established missing desktop Menu requirement. Totals: one browser pass, one failure, 79 unrun; retained browser and restart/logout did not run. Partial artifact12866449133 is not visual acceptance.

C3 corrects the responsive navigation oracle (desktop sidebar versus compact Menu). The workflow moves the isolated owner fixture/probe/new browser step earlier to shorten feedback; every retained fixture/probe and all 780 retained browser instances remain required, with one worker, zero retries and max-failure early stop unchanged. No backend or frontend application change is needed for this observed failure. C3 results remain pending.


## C3 result and C4 message-oracle correction

C3 `e876ab1f4a5a1a8d40b799613f41adbe7c6434e9`, tree `05c3bae508eecafd755c6e85d3b1e03b071acece`, merge `f2f1619dcda6124ac66825b997ea12063d27c7c2`, passed [Quality40803417461](https://github.com/giangnguyen3260/agent_os/actions/runs/40803417461)/job123216600318 with762 tests0/0/0 and frontend gates. Docker40803417451/job123216600174 re-passed owner native checks, then failed browser case3 after two passes. The unavailable-owner oracle required substring `không khả dụng`; the actual intended human message is `Agent đã được cấu hình nhưng không còn khả dụng trong team này`. The full source message and the test's shorter substring do not match. C4 requires the full semantic message, preserving foreign-owner state/API/raw-pointer/clear/receipt/privacy assertions. No application change or timeout/retry increase is made. Main compared remaining explicit messages, state hooks, action labels and viewport branches to production source; further runtime evidence remains required rather than inferred.


## C4 browser synchronization finding and C5 correction

C4 `d18275a4cb1c10872a5077234c5b004f395bf859`, tree `c2e0553bde5740f04de36d9d7691ef589ed48842`, merge `7241d3883a31ed571f533f7d4ef9782f6fd794cd`, passed Quality40804171284/job123219021745 with762 tests0/0/0. Docker40804171283/job123219022075 re-passed native owner checks; browser reached **13 passes, one failure** at malformed committed reply + exact retry. The response listener intended for retry200 captured the first malformed201: UI enters UNKNOWN before the first POST settles, while recovery buttons are still disabled. Registering a POST listener before waiting for that busy state to finish can observe the original response during click auto-wait. C5 waits for the actual recovery controls to become enabled before installing the explicit retry listener; other injected-fault cases similarly wait for receipt recovery readiness before inspecting committed results. Original receipt, exact body/key, second-attempt200, one revision and unchanged protected-state assertions remain. No app change, extra retry, timeout increase or accepted201 replay is introduced. Remaining browser/runtime/visual gates still pending.


## C5 current evidence checkpoint

C5 source `8a5af735a7b52a252ca914b7f705ae42053eee69`, tree `06d3018b282cfc9cbd7d8df5c2b692741b5010a2`, tested merge `63d853ff9d67f65a7150af2a4281960355c1d51b`, base `ef94202a4d98b16c0804f0f32a69c8fce7d89d85`. Quality40805177660/job123222292544 completed/success; full log confirms762 tests0/0/0, source binding and all frontend gates.

Docker40805177734/job123222293193 is still running retained browser regression. Job metadata independently records new owner native, new owner browser and visual upload steps completed/success. Exact new-browser count and full native output await completed job logs; authored/collected count remains81. Retained780 and final restart/logout remain pending; no combined acceptance yet.

Artifact12867957654 `task-owner-visuals`,1433402 bytes, ZIP SHA256 `6fd4e8992f7f15dfd9a4bba34a3418e49583f8a95874a816a13e0d0291a07da5` matched REST and downloaded bytes. Main and independent QC verified all30 original PNG hashes, unique ten-state × three-viewport JSONL records and1440/768/390 widths, then visually inspected the captures. No blocking captured visual finding. See [manifest](TASK_OWNER_VISUAL_MANIFEST.md) and [preliminary independent final review](TASK_OWNER_FINAL_REVIEW.md). These are real API-backed synthetic fixture captures; no Figma frame was edited or independently matched.


## C5 completed failure and C6 retained-dialog correction

Completed Docker40805177734/job123222293193 logs confirm **81 new browser passes**, the actual owner native PASS, and556 regular backend tests0/0/0. Retained regression reached618 passes, then INV-B08 mobile failed with `dialog.accept: Test ended` at the persistent final `p.on('dialog',d=>d.accept())` listener.161 retained cases and later restart/logout did not run. Overall Docker failed; C5 is not combined acceptance despite the new feature/visual steps passing.

C6 repairs only that retained testcase's native dialog lifecycle: remove the default persistent handler for this case; assert and await each Back dismissal, unload dismissal and accepted exit through separate owned event promises; await accept completion before final URL/no-write assertions and teardown. Production invitation and owner code remain unchanged. A focused three-viewport INV-B08 step runs after its real fixture is provisioned for faster feedback. The complete retained780 step remains unchanged, so the focused3 are additional repeated verification, not a smaller baseline. One worker, zero retries and timeouts remain unchanged. New81 +retained780 still form the861 disjoint full regression instances; focused3 are reported separately.
