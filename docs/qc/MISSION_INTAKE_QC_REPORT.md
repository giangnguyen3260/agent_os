# Mission task intake QC evidence

Implementation candidate on feature/mission-intake-controls, stacked on PR31 closure5597bac2822107f2bb47892982148f27b4bd1ffc. No merge or deploy.

Local frontend lint, format, production build and production int64/DTO/privacy/idempotency/state harness passed. Java import rules, browser/native syntax and diff checks passed. Browser51cases collected across3projects is not runtime execution. Host lacks Java21/Maven/Docker; backend/runtime/visual results remain pending CI. Google formatting patch diagnostic will provide canonical formatting; required quality gate is unchanged. Native MissionIntakePostgresIT is explicit and fails if required PostgreSQL configuration is missing; configured test environment falls back to retained TASK_EDIT_TEST_*.

Scope: real ADMIN-only Mission task intake flag/version mutation, review and exact idempotent receipt recovery. Pause blocks fresh Task Create and existing Draft Edit policy; no task state/runtime/dispatch transition. Version is canonical int64 string. Current authority, parent locks, fingerprint, history replay ordering, atomic receipt/audit/update and exhausted/unknown Task Create gates are acceptance requirements, not reported passes yet. Synthetic fixture data is CI-only.

Workflow changes: dedicated new browser slice and immediate visual artifact precede full retained702baseline. No browser retries/skips; full baseline remains required. Prior verified CI fetched21.8MB from Azure in11min58s despite changing onlyone sources file; this candidate replaces Azure URLs in every apt .list/.sources configuration on disposable runner and asserts none remain. Signed package checks remain. No previous slice's pass counts are reused. Sonar paused.

## Candidate C2 verification — 2026-10-28

C1 `67ae2c25706fcf7c81d17f01be352b494004e617` stopped at Spotless in both workflows; no backend/runtime pass is attributed to it. Applied the diagnostic Google Java Format artifact 12744991371 to the ten intake Java source/test files, without changing behavior or disabling checks.

C2 source commit `5e7d0c03684c0dff391a129a5f7fd7dd38619147`, tree `47ae6f2a6cc5c9433192040be2b6eecf19ef2efe`. [Quality run 40613118449](https://github.com/giangnguyen3260/agent_os/actions/runs/40613118449), job 122569204914, passed: 526 backend tests, zero failures/errors/skips, including 18 MissionIntakePostgresIT, 12 MissionIntakeIntegrationTest and 16 MissionIntakeWireTest. Java imports/formatting/coverage, frontend lint/format/tooling/retained validator harnesses and production build passed; dependency audit reported zero vulnerabilities. The new intake production harness runs in Docker CI, whose runtime results are still pending at this checkpoint.

Final runtime and visual evidence must be recorded separately. Initial independent QC authored/reviewed the source, test plan and browser tests; final CI evidence review is performed by the coordinating agent and must not be presented as a separate independent-agent sign-off.

C2 Docker run 40613118454/job122569204985 built a healthy stack; 437 regular backend tests passed with zero failures/errors/skips. All native probes including Mission intake passed. New browser slice stopped after six passes at the dirty native reload test: page.reload waited for a navigation deliberately cancelled by beforeunload dismissal. Corrected only that test action to location.reload via page.evaluate, following retained Team Settings/Draft Edit tests; native dialog type/dismissal, URL, preserved draft, zero POST and persisted source assertions remain. No retry, skip or timeout increase. Baseline and restart were not reached and are not reported passing.

## C3 visual review and UX correction

C3 `7efd43ba364f5a3f32b8c784b4a3bc2380a2c8d6`, tree `56b2fc011b15969b5ddcbf01d4103b75b79a9930`, tested merge `e3f8aa144d87d0926a19c478fc531e5e6ddd0928`: Quality run40614333208/job122573176312 passed526 tests with zero failures/errors/skips. Docker run40614333275/job122573177071 passed native intake and51 browser cases, then started retained regression. Artifact12784169557 contains24 PNG and24 unique manifest rows; ZIP825837 bytes, SHA256 `4d77f2cd425a59a564b3796d2d4001cc482f2c352a7965f0ce8a31b0c2a4029b` matched REST and downloaded bytes. Image hashes and project widths all matched. Coordinator viewed all24 via four contact sheets plus original desktop review/mobile unknown/mobile blocked.

Visual review found readable dark body text, wrapping and no horizontal overflow, but raw blocker codes on the blocked screen were unsuitable product copy. C4 replaces those codes with Vietnamese explanations, preserving exact API blockers and fail-closed behavior. Existing three blocked browser scenarios now assert human-readable reasons and absence of raw codes. New C4 runtime/visual acceptance is required; C3 images are diagnostic and are not substituted for final screenshots.
