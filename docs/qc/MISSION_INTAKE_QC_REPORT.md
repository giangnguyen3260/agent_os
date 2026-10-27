# Mission task intake QC evidence

Implementation candidate on feature/mission-intake-controls, stacked on PR31 closure5597bac2822107f2bb47892982148f27b4bd1ffc. No merge or deploy.

Local frontend lint, format, production build and production int64/DTO/privacy/idempotency/state harness passed. Java import rules, browser/native syntax and diff checks passed. Browser51cases collected across3projects is not runtime execution. Host lacks Java21/Maven/Docker; backend/runtime/visual results remain pending CI. Google formatting patch diagnostic will provide canonical formatting; required quality gate is unchanged. Native MissionIntakePostgresIT is explicit and fails if required PostgreSQL configuration is missing; configured test environment falls back to retained TASK_EDIT_TEST_*.

Scope: real ADMIN-only Mission task intake flag/version mutation, review and exact idempotent receipt recovery. Pause blocks fresh Task Create and existing Draft Edit policy; no task state/runtime/dispatch transition. Version is canonical int64 string. Current authority, parent locks, fingerprint, history replay ordering, atomic receipt/audit/update and exhausted/unknown Task Create gates are acceptance requirements, not reported passes yet. Synthetic fixture data is CI-only.

Workflow changes: dedicated new browser slice and immediate visual artifact precede full retained702baseline. No browser retries/skips; full baseline remains required. Prior verified CI fetched21.8MB from Azure in11min58s despite changing onlyone sources file; this candidate replaces Azure URLs in every apt .list/.sources configuration on disposable runner and asserts none remain. Signed package checks remain. No previous slice's pass counts are reused. Sonar paused.
