# Coding standards and review gate

User requirement: clean code, Google coding style and SonarQube checks. Applies to all new code and changed files. Baseline formatting is a separate mechanical change from functional edits.

## Java

Use Google Java Style via pinned google-java-format and Maven Spotless. Run `mvn spotless:apply` to format, `mvn verify` to check formatting, execute tests and produce JaCoCo XML. Use Java21. No manual formatting exceptions without review. Formatters do not replace review of naming, cohesion or business invariants.

## Angular / TypeScript

Use Google's `gts` ESLint configuration and Prettier configuration, with Angular application TypeScript settings retained. Use two-space indentation, explicit contracts, descriptive names, typed reactive forms and small feature-owned services. Angular decorators require a documented lint exception if the Google config enables a conflicting rule; never disable the whole linter. Run `npm run lint`, `npm run format:check`, `npm run build`. Use `npm run format` for formatting. Runtime auth/tenant authorization always remains server-side.

## Clean code and security

Keep domain rules separate from HTTP/persistence; constructor injection; DTO allowlists; avoid cross-module repository access. No blanket catches, silent errors, secret logging, arbitrary `any`, duplicate business rules or speculative abstractions. Prefer readable functions and meaningful tests over a rigid function-length limit. Parameterized persistence, explicit tenant ownership and auditable state transitions are mandatory. Do not use `NOSONAR`, broad exclusions or suppression to manufacture a passing gate. Narrow justified exceptions require SA/QC review and a tracked reason.

## SonarQube

Sonar scanning is temporarily paused at the user’s request. The scanner/gate setup below describes reactivation requirements; it is not an active CI requirement during this pause. Current acceptance still requires formatting, tests, coverage generation, frontend lint/format/tooling/build, dependency audit and applicable native/browser checks. A green run does not mean a Sonar gate or production security review passed.

The repository supplies scanner inputs; the quality profile and gate live on the Sonar server, not in this properties file. Configure server-side new-code gate: coverage >=80%, duplication <=3%, no new reliability/security issues, maintainability rating A, all new security hotspots reviewed. Confirm exact available metrics against the deployed Sonar version. Any policy exception requires a named owner and expiry, never silent acceptance. Pin the new-code baseline (reference branch or release version) before interpreting metrics.

When Sonar scanning is reactivated, CI waits for the server quality gate. Missing SONAR_HOST_URL or SONAR_TOKEN fails the job; do not mark it Pass. Use a narrowly scoped analysis token as a CI secret. Fork pull requests do not receive secrets; their Sonar step will remain blocked until a trusted pipeline can analyze them. Do not switch to pull_request_target to run untrusted code with secrets.

Java coverage input is JaCoCo XML. Frontend LCOV is reserved for the later FE test slice; absent coverage is a gap, not a reason to exclude FE production code. Authentication, encryption, load, browser E2E and Sonar runtime verification are not completed merely because foundation build checks pass.

## QC definition of done

BA acceptance + code review + formatting/lint/type checks + relevant unit/integration/security/E2E tests + independent QC evidence, plus the actual Sonar gate when scanning is reactivated. Status is PASS / FAIL / BLOCKED / NOT_RUN. A coded item with blocked required checks is not Verified or release-ready. QC records test data, steps, expected/actual result, evidence and defect severity; developer fixes and QC retests. Static analysis never replaces functional or penetration testing.

References:
- https://google.github.io/styleguide/javaguide.html
- https://google.github.io/styleguide/tsguide.html
- https://github.com/google/google-java-format
- https://github.com/google/gts
- https://docs.sonarsource.com/sonarqube-community-build/quality-standards-administration/managing-quality-gates/introduction-to-quality-gates

## Temporary dependency override

The gts7 -> inquirer -> external-editor chain selected vulnerable tmp. Scope the override to external-editor/tmp0.2.7: the 0.2.6 fix for GHSA-ph9p-34f9-6g65 also required the follow-up patch for GHSA-7c78-jf6q-g5cm. Do not use npm audit fix --force to downgrade Angular. `npm run test:tooling` verifies the real consumer can create/read/cleanup a temporary file through the patched dependency without launching an editor, and rejects string/array traversal prefixes. Recheck the override after upstream gts/external-editor updates. This limited smoke test is not a general interactive-editor or penetration-test result.

## Tooling advisory overrides — October2026

Angular CLI21.2.27 pins SDK1.31.0 and build21.2.27 pins Vite7.3.6. Scoped overrides select SDK1.32.0 and Vite7.3.7 to address current advisories while retaining Angular21. Quality and Docker build require `npm run audit:check` including dev dependencies and `npm run test:tooling`, which exercises actual CLI MCP and Angular dev-server consumers as well as the existing tmp checks. No audit suppressions, omitted dev graph or forced Angular major upgrade. Frontend/tooling maintainers must review on the next CLI/build update and remove overrides once upstream selects patched children with all gates passing. See docs/qc/TOOLING_SECURITY_QC_REPORT.md for bounded evidence and limitations.

## Pattern tooling advisory correction — October31,2026

GHSA-3g3m-2fjr-6pw4 requires glob14.0.2 and GHSA-g3hv-75wg-m249 requires minimatch11.0.1. A global minimatch override broke old ESLint ESM default imports. Pin ESLint10.14.0, override gts7’s declared ESLint9 dependency to the same `$eslint`, pin glob14.0.2 and select minimatch11.0.1 for modern minimatch consumers. The resulting lock graph has no older minimatch or eslintrc; Angular21.2, gts7’s Google config and the project ESLint config remain unchanged. Explicit globals14 preserves the Google config’s previously resolved undeclared import. This crosses an upstream declared gts version boundary: retain real consumer verification and revisit the override on the next gts update, rather than claiming upstream support.

`npm run test:tooling` checks both ESLint module entry points, the shared gts ESLint instance, real no-var/equality/floating-promise diagnostics, file glob selection/ignores, package walking, cache cleanup, CLI MCP and dev-server boundaries. The Google-rule fixture uses an actual temporary TypeScript project/file so CI single-run programs read the intended source. Keep both CI and local modes working; do not suppress rule assertions or audit findings to accommodate the override. See [dependency QC](../qc/TASK_DEPENDENCIES_QC_REPORT.md) for actual candidate evidence and [upstream glob advisory](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4) / [minimatch advisory](https://github.com/advisories/GHSA-g3hv-75wg-m249).
