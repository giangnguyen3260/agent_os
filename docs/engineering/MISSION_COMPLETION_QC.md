# Single-Task Mission completion — local QC

Date: 21 November 2026 (Asia/Ho_Chi_Minh). Scope: [contract](MISSION_COMPLETION_CONTRACT.md), [delivery plan](DELIVERY_PLAN_2026_11_21.md). Source is local on top of PR119's verified tree and unpublished acceptance-harness commit `8c5d8ff`.

| Check | Completed evidence and limit |
| --- | --- |
| Java 21 compilation | Changed production/test sources compile with parameter metadata. Java import, Google Java Format and diff checks pass. |
| Feature backend | 12 `MissionCompletionIntegrationTest` cases pass through Spring transactions/H2 and MockMvc. All 52 migrations apply. Fixtures contain recorded synthetic execution evidence, not provider calls. |
| Existing runtime regression | 12 selected cases pass across RuntimePersistenceTest, OpenAiExecutionHttpTest, OpenAiTextTransportTest and OpenAiLiveAcceptanceTest with V052 present. |
| Frontend | Angular production build, ESLint and changed-file Prettier checks pass. |
| Browser | All 24 cases passed: 8 scenarios across desktop/tablet/mobile exercise confirmation/review/completion, escaped output, unsupported scope, exact pending-command retry, role revocation, actual workspace HTTP403, conflict, output integrity and receipt identity. APIs are intercepted fixtures; this is not real-backend acceptance. |
| Independent BA review | Corrected missing PR119 execution provenance, accepted text reread and HTTP403/timeout handling. No remaining source blocker was reported in the reviewed scope. |

The backend tests include direct COMPLETE denial, untrusted REVIEW denial, added cancelled Task denial, altered configuration/control/content/request/usage denial, historical UNKNOWN and active claims, unsettled budget and unfinished dispatch, current ADMIN/credential/tenant checks, strict three-text-field commands (duplicate/unknown/numeric/trailing input denial), same-key replay/competition and different-key exclusion. An actual receipt-insert constraint failure proves Mission/control/audit rollback. Historical SUBMIT_REVIEW replay after COMPLETE never changes the current Mission.

The first local Spring attempt used an overly broad cached classpath containing incompatible SLF4J1 and SLF4J2; the corrected runtime classpath was used for passing results. A missing `-parameters` compiler option and fixture dispatch-revision collision were corrected. One browser assertion observed pending UI before the mocked POST arrived; it now waits for the request and uncertain-response status. Tests run with retries disabled. No production dependency, security or audit gate was weakened.

Reproduce the backend selection with the project's Java21/Maven environment:

```bash
mvn -B -ntp -f backend/pom.xml '-Dtest=MissionCompletionIntegrationTest,RuntimePersistenceTest,OpenAiExecutionHttpTest,OpenAiTextTransportTest,OpenAiLiveAcceptanceTest' test
```

Build/serve the frontend and run `mission-completion.spec.mjs` with Playwright on all three configured projects. The local validation used the cached Chromium and an isolated static server; standard CI supplies its own installed browser. Do not enable retries to hide failures.

Feature is opt-in: `agentos.mission-completion.enabled=true`. The application database principal needs SELECT/INSERT on the new receipt table and the existing scoped lifecycle/evidence access; do not grant credential-metadata access merely for completion. Default-disabled operation retains the existing runtime behavior.

Remaining gates: native PostgreSQL migration/locking/races (including actual TaskCreate vs SUBMIT_REVIEW), real-backend browser acceptance, matching new-head CI after authorized publication, merge and deployment. Actual OpenAI invocation remains an independent operator-credential gate. Starting from a newly created Mission is not delivered: planning/full-plan acceptance/start/dependency admission are separate work.
