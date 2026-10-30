# Task dependencies independent QC review

Date: 2026-10-30. Independent worker handoff on `feature/task-draft-dependencies`, shared repository `/workspace/scratch/24b4696504b1/agentos`. Only this file, `docs/qc/TASK_DEPENDENCIES_TEST_CASES.md` and `frontend/tests/e2e/task-dependencies.spec.mjs` were written by this worker. No commits, production changes, fixture/probe/workflow edits or existing-test edits were made. Concurrent shared-tree changes belong to their respective workers.

## Ready for main

Suite title: **task dependencies independent QC**. Actual Playwright collection: **75 tests = 25 scenarios ×3 projects**. Default per-test timeout30s, one worker, zero retries; no global or scenario timeout weakening. Selection matrix and DB/transport/visual oracle limitations are documented in the test-case file.

Expected newly generated artifacts: **21 PNGs** (loading/edit/review/blocked/conflict/unknown/saved × desktop/tablet/mobile), names `task-dependencies-{project}-{state}.png`, plus actual `task-dependencies-manifest.jsonl`. Records contain route, full scope, project, viewport, timestamp, test title, PNG SHA256 and actual PNG width/height; capture verifies signature, expected viewport width and no viewport overflow. No screenshots or manifest records have been fabricated or captured here. Main owns retained993+3 workflow and native PostgreSQL proof; add75 browser cases and21 visual files to the relevant selection/artifact expectations without changing baseline claims.

Actual locally executed checks:

- `node --check frontend/tests/e2e/task-dependencies.spec.mjs` — passed.
- `frontend/node_modules/.bin/prettier --check frontend/tests/e2e/task-dependencies.spec.mjs` — passed.
- `frontend/node_modules/.bin/playwright test -c frontend/playwright.config.mjs frontend/tests/e2e/task-dependencies.spec.mjs --list` —75 collected, not executed.
- `node frontend/scripts/test-task-dependencies-validation.cjs` — passed; actual output: “Task dependencies production wire validators and component state checks passed”. This is the FE author's harness, not independent browser evidence.

Runtime blockers observed in this worker: `docker` command absent; no `psql` binary at `/usr/bin/*psql*`; `E2E_PASSWORD` absent; HTTP request to `http://127.0.0.1:8080/api/v1/auth/session` failed with curl000/exit7; `E2E_BASE_URL` unset. No PostgreSQL commands, browser scenario execution, backend probe execution or CI runtime success is claimed. A doomed browser launch was not used as feature evidence. Main must execute the suite against the actual disposable Docker fixture and inspect real artifacts.

Suite handoff SHA256: `d13a30f3f50f6fb4598ee2f60ddcb21959cb80adcb9540a4abe59024fe96ebde`.

## Production source inspection

The contract was frozen before implementation. FE production was still being authored concurrently; this is a pinned source inspection, **not a claim that main announced a final production freeze**. Main should recheck findings and selectors against its final frozen tree before runtime.

Inspected actual route integration, Task Detail affordance, feature template/styles, service, response validators, navigation state/guards and component. The observed component handles retained selections via independent candidate-by-ID reads with concurrency4, retains IDs on hydration conflict, rechecks context, hides rendered private data before authority preflight, checks session and raw workspace ADMIN, compares source and independently obtained target tokens, freezes body/key for final POST, distinguishes UNKNOWN from definitive400/409, keeps genuine null receipt UNKNOWN, and clears private body on authority loss. The guard uses Angular navigation identity to allow the second guard for the same accepted transition; custom discard dialog ownership is separate from native beforeunload. Source inspection found no additional confirmed production defect in this snapshot. This is not proof of race, focus, layout or runtime correctness.

Pinned FE SHA256 values:

| File under `frontend/src/app/features/task-dependencies/` | SHA256 |
| --- | --- |
| `task-dependencies.component.ts` | `c65f9847186c2985af60501ab5f494559e2378905515c2fead54faacb08c3571` |
| `task-dependencies.component.html` | `34406a0392b9692ceb07e39e4adaa155145b85723ffc081d801ada06a10decd2` |
| `task-dependencies.component.css` | `3630a7bf975ba3414ab06e81a6e6f71f3405d5e5a506537a0c7341a1e9d216e8` |
| `task-dependencies-validation.ts` | `1b517131b7d56b1a241b55e722f7401757d387a8d6a50fffa5262ed131e9e224` |
| `task-dependencies.service.ts` | `71960076cc4c4fb153a20301f212f0045c79e8ba0a5502ac2f6409ccb77f1c42` |
| `task-dependencies-navigation.guard.ts` | `cd14e035acd2388d00a7141aaed149281737a6ba2d9bc92e016770b803ee20a9` |

## Main-owned native probe: read-only oracle findings

Read `scripts/task-dependencies-probe.py` after completing the browser suite. Did not edit or run it. Reviewed SHA256: `652fb3be5844475c28a9e6fe3bdf1e1ae42d22426d77bc13b566036c50f973b2`.

1. **Successful-command protection excludes entire allowed tables.** After the first POST, `main()` compares all table digests except `tasks`, `task_dependencies`, `task_dependency_events`, `task_dependency_receipts`, `audit_events`. It separately compares only source task1's protected fields. A command which correctly updates source1 but also changes another task's description/owner, creates an unrelated edge, or adds unrelated provenance can pass those success assertions. `dag()` catches a cycle but not an extra acyclic edge or metadata write. The snapshot helper really does capture all tables and tuple identities; the limitation is the successful-write comparison's whole-table allowlist. Read/rejection/replay paths still compare full snapshots and are stronger. To claim exact successful effects, compare every other task and allowed-table rows against explicit permitted source/event/key deltas. This is an oracle gap, not an observed backend defect. The browser save helper covers other scoped sources and protected columns but is not a global tuple-identity substitute.

2. **Receipt revision validation does not enforce wire integer type/lexeme.** `receipt()` uses `value['revision'] == revision`; Python accepts `True == 1` and `1.0 == 1`. `json.loads()` also erases exponent spelling. A malformed receipt revision `true`, `1.0` or `1e0` can pass the native receipt oracle at revision1, including the event-revision equality checks. Context revision already correctly uses `type(...) is int`; receipt validation should at least do the same, and strict lexical integer proof needs raw-wire checking or a JSON number parser that preserves number spelling. FE's `decodeData` and validators do reject these forms, so this finding concerns the native probe's independent strict-wire claim.

The current probe already checks each initial candidate page item's exact four fields, fingerprint syntax, actual stored title/status, full independently computed eligible ID set and C-order. Those checks are present in the reviewed snapshot and are not raised as missing. It also actually observes tuple identities through xmin/ctid in full-table snapshots, verifies historical recovery with current graph corruption, uses a real reciprocal command race, and checks cross-editor ABA fingerprint changes. No execution, forced lock-wait proof or comprehensive backend-case count is inferred from that source.

## Remaining acceptance work

Main needs actual75 browser results,21 distinct PNG/hash/scope records, native API/PostgreSQL run, final source-freeze review and its retained regression gates. Local/session-storage checks concern raw command/key strings only; IndexedDB/service-worker absence is not independently proven here. The dirty-history scenario uses real `history.back()` for native Back, separately labeled synthetic `pushSpaHistory` for reused parameters, and native beforeunload cancellation plus clean sibling reload to catch stale listener ownership. Historical command retry is an actual same-body/same-header POST with backend200, not a synthetic success. Null receipt is obtained from an actually uncommitted key, not invented over a committed response.

## Read-only recheck disposition — 2026-10-30

Main moved this review to `docs/qc/TASK_DEPENDENCIES_REVIEW.md` and corrected the cases-file link. This follow-up appends only this disposition. The root review was not recreated; probe, suite, production, fixtures and workflows were not edited. Suite remains frozen at SHA256 `d13a30f3f50f6fb4598ee2f60ddcb21959cb80adcb9540a4abe59024fe96ebde`.

Rechecked actual probe SHA256 `e6b24eb40c72279a52e1945314f4c69ac37afed628a3bd83f4b5ead55d7d0aa6`. **Both previously reported findings are addressed for their stated scope.**

1. The first successful commit now compares all other Task rows and all other sources' dependency rows using value+xmin+ctid digests. Old event, receipt and audit rows must retain their identities and values after excluding precisely the newly returned event ID, the exact organization/team/mission/task/actor/key receipt identity, and the uniquely identified new audit row. It requires one matching new event, one matching new receipt linked to that event with the actual original response, and one source dependency audit with exact event/revision payload. Source protected fields and all remaining business-table digests are still compared. This closes the original unrelated-row/whole-table-exclusion gap for that first-commit check. It does not imply those detailed successful-write assertions are repeated after every later command, or turn source inspection into actual PostgreSQL evidence.

2. Actual HTTP responses now enter `strict_json`: recursive `object_pairs_hook` rejects duplicate keys, including escaped equivalent keys; `parse_float` rejects decimal/exponent numeric tokens; `parse_constant` rejects NaN/Infinity. `receipt()` separately requires `type(revision) is int`, positive value and the upper bound2147483647 before matching the expected revision. Thus the previously identified boolean/float/exponent revision acceptance is closed. SQL JSON reads retain their separate normal decoder; they are not being mistaken for strict HTTP-wire validation.

Independently executed offline controls against the actual probe definitions via `runpy` with a non-main module name: **11 lexical negative controls passed**, covering decimals, exponents (including nested), NaN/±Infinity, top-level/nested/escaped-key duplicates, trailing JSON and invalid UTF8. One parser-only integer/null/empty-array control passed. **Six receipt revision negative controls passed**: true, false,1.0,0,-1 and2147483648 were rejected before SQL. A Python audit hook prohibited socket and subprocess/process execution during these controls. No HTTP request, PostgreSQL command, real probe `main()`, business-success fixture, substituted SQL/HTTP result, or mocked committed receipt was used. These are parser/guard checks only.

No further concrete issue was found in the correction paths reviewed. Main's stated FE authority503-versus-confirmed-permission-loss change is still ongoing and was not represented as frozen or accepted in this follow-up; the reported BE private-multiline fix was not independently retested here. Actual browser75/PNG21, native runtime, final frozen FE review and retained CI gates remain pending evidence from the execution environment.

## Bounded FE authority/hook recheck before final handoff — 2026-10-30

Main reported the bounded `authorityUnavailable` correction visible and a final FE handoff/freeze still forthcoming. This review is therefore provisional, not final frozen-FE acceptance. Read only production/harness/suite; appended only this review. Suite remains unchanged at SHA256 `d13a30f3f50f6fb4598ee2f60ddcb21959cb80adcb9540a4abe59024fe96ebde`; no scenarios added.

Actually reran the FE production wire/component harness: passed. A read-only template parity check confirmed all26 literal `dependencies-*` hooks referenced through the suite helper exist in the current template. Template SHA256 remains `34406a0392b9692ceb07e39e4adaa155145b85723ffc081d801ada06a10decd2`. Reviewed component SHA256 `44975e103b757f8f45e5342407d46332102300cabb532eb56eb22db9fb8faa14`, harness SHA256 `2dff659503cef7c51bf884c6e422655318fe3b5ae27940d9d7f9152747ac7228`. No browser/runtime/visual result follows from these checks.

The new `authorityUnavailable()` does fence captured callbacks via epoch/cancellation, remove rendered private state, clear verification/acknowledgment and preserve frozen pending body/locator while showing error or UNKNOWN. `loseAuthority()` continues clearing private data/body on definite loss. However, two actual shared-auth adapter interactions remain concrete source concerns before freeze:

1. **Malformed session data can clear pending state before feature validation.** Real `AuthService.session()` immediately writes `response.data` into its shared `currentUser` signal, without validating its shape. The component subsequently checks `active(e)` before validating the returned user, and its constructor effect treats any changed `auth.user()?.id` as an actor change, calling `loseAuthority()` (and dropping the locator for a truthy different ID). A syntactically valid malformed200 session such as an object without `id`, or with an invalid/different ID, can therefore take the actor-loss path and erase a frozen UNKNOWN body rather than the intended `authorityUnavailable()` preservation path. This is distinct from invalid JSON, which fails before the signal write. The harness's `state()` uses a constant `auth.user()` plus an independently stubbed `session()`, and creates an object from the prototype without running the constructor effect, so the passing malformed/503 regressions do not cover this real signal interaction. Preserve trusted identity until the session payload is validated, while retaining definite real actor-change erasure; final disposition needs the corrected actual adapter/effect path.

2. **Real session403 is mapped to temporary server failure.** `AuthService.request()` maps401 to `AuthFailure('unauthorized')`,404 to `not-found`,0 to `network`, and other HTTP statuses—including403—to `server`. The component's `authorize()` catches raw HttpErrorResponse403, but real `auth.session()` has already converted that403 to `AuthFailure('server')`, so it calls `authorityUnavailable()` rather than clearing through `loseAuthority()`. Workspace403 is unaffected because that read uses the feature service and retains HttpErrorResponse. This is a source-level mismatch with the requested definite401/403/404 handling, not an observed live server403.

No production/shared-auth/harness/suite edits were made to resolve these concerns. Final source/hook parity disposition remains pending main's FE handoff/freeze and review of the actual corrected paths. BE private-multiline behavior remains outside this bounded FE follow-up.

## Final-path bounded authority correction disposition — 2026-10-30

Read-only recheck of main's shared session validation and the now-visible feature raw-session adapter, with actual execution of both relevant harnesses. Only this review was appended. No new scenarios, tasks, suite changes, production changes or other file edits were made. **Both authority integration findings from the preceding section are addressed in the inspected paths.** This disposition does not publish C1 or assert a final main freeze/runtime acceptance.

Shared `AuthService.session()` now calls its exact-three-field/canonical-UUID/safe-text `sessionUser()` validator before `currentUser.set`. Malformed session200 data throws without publishing even a transient identity; existing authEpoch checks still prevent older successful/401 session requests from replacing a newer account. Login/logout and shared403-kind mapping remain unchanged. The dependency component now calls `this.api.session(this.cancel)`, not shared `this.auth.session()`. That feature adapter uses the actual strict arraybuffer read path and preserves HttpErrorResponse status, so session403 reaches the definite-loss branch. Invalid session data and temporary503/network errors use `authorityUnavailable()` without publishing shared identity or erasing the frozen pending body/key; definite401/403/404, valid different actor and wrong role still clear through the existing loss/fence paths.

Actual executed checks, from `frontend`:

- `node scripts/test-auth-generation.cjs` — passed. The harness transpiles and instantiates the real AuthService, records every signal publication, checks all nine malformed payloads publish zero additional writes, checks valid current-session publication, and checks delayed success/401 cannot overwrite a newer logged-in identity.
- `node scripts/test-task-dependencies-validation.cjs` — passed. Inspected its added real AuthService + TaskDependenciesService + constructed component section, including registered identity effects, observable transport adapters and strict raw session reads. It verifies malformed session payloads preserve pending body/locator and trusted identity with zero POSTs; actual adapter503 preserves pending state; adapter403 clears the frozen body; valid different actor and a real shared-signal actor change erase pending state/locator. These deterministic harness transport adapters are not browser or backend business-success evidence.
- Read-only parity check — all26 literal dependency hooks used by the existing browser suite remain present in the template. Source check confirms feature authorization calls the feature raw-session adapter and contains no shared `this.auth.session()` call. The suite remains unchanged.

Pinned checked SHA256 values:

| File | SHA256 |
| --- | --- |
| `frontend/src/app/core/auth/auth.service.ts` | `78769c424c462cf00369c6587f959f1086b967bbbcaf34e3f10bb927e7db0f4b` |
| `frontend/src/app/features/task-dependencies/task-dependencies.component.ts` | `02f8b13ccdc0c52e1bc2db572644ed70ba8373028fef2afd2fa1c35cf9f5182d` |
| `frontend/src/app/features/task-dependencies/task-dependencies.service.ts` | `d6962093af492f6a41cacf9d605b2a2e595806c5d9932f9106476e22991373e6` |
| `frontend/scripts/test-auth-generation.cjs` | `b388804ad0cdfa1a93c78f98b59a06a32f686e8fb8629714641342038d63637a` |
| `frontend/scripts/test-task-dependencies-validation.cjs` | `2b1e1c6c1debd1fbff0858bf347fc68159e407eae4f0a084d21044c29488e909` |
| `frontend/tests/e2e/task-dependencies.spec.mjs` | `d13a30f3f50f6fb4598ee2f60ddcb21959cb80adcb9540a4abe59024fe96ebde` |

No further concrete defect found in this bounded correction recheck. Actual browser75/PNG21, native HTTP/PostgreSQL evidence, retained gates and main's final freeze/C1 decision remain separate acceptance work.
