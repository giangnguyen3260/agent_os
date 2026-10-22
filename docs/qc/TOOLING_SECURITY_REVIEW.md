# Independent tooling security QC / SA review

2026-10-22 UTC · branch `fix/tooling-security` · base `90700ac` · draft PR 17.

## Disposition and scope

**Accepted source within this bounded tooling patch scope; final execution gates remain separate.** No blocking dependency/API issue identified. The final source explicitly audits dev dependencies and preserves Angular 21, TypeScript 5.9.3 and existing tmp protection. Independent patched audit and MCP execution passed. Main reports clean installation and final cleanup-adjusted tooling, including-dev audit, formatting, lint and build passes (session 16668, exit 0). No completed remote CI or Docker Alpine execution is claimed.

Reviewer changed only this document. No dependency installation, application source changes, commits, API writes, dev-server/cache-producing commands or Docker execution were performed by this reviewer.

## Final patch reviewed

| Item | Reviewed final behavior |
| --- | --- |
| Overrides | Parent-scoped exact CLI → SDK 1.32.0; build → Vite 7.3.7; external-editor → tmp 0.2.7 retained |
| Lockfile | Only SDK 1.31.0 → 1.32.0 and Vite 7.3.6 → 7.3.7 artifact version/URL/integrity replacements; 12 changed lines |
| Direct dependencies | Unchanged; no Angular major migration or forced audit fix |
| Audit gate | `npm audit --include=dev --audit-level=low`; failures propagate in Quality and frontend Docker build |
| Consumer checks | Existing tmp regression followed by new actual Angular CLI MCP and configured Angular/Vite dev-server smoke |
| Fixture cleanup | Nested finally removes the temporary outside-root fixture even if server shutdown rejects |
| Sonar | Remains explicitly paused; does not waive the active dependency security gate |

No advisory quarantine, dev omission or audit failure suppression was found. Quality uses Node 24 and npm ci. Docker uses node:24-alpine, runs audit and test:tooling before build, and excludes host node_modules/.angular from its context.

## Evidence and provenance

| Evidence | Result and provenance |
| --- | --- |
| Baseline audit | Independently completed full JSON: four affected package entries, 1 low / 3 high; SDK 1.31.0 and Vite 7.3.6. Historical baseline only |
| Patched full `npm audit --json` | **Independently executed: exit 0**, empty vulnerabilities, all severities zero; dependency total 739, including dev graph |
| Actual parent resolutions | Independently used createRequire anchored at CLI/build: SDK **1.32.0**, Vite **7.3.7** |
| Patched CLI MCP runtime | **Independently executed: exit 0.** SDK-client initialize, tools/list, list_projects, then get_best_practices using the discovered workspacePath; actual project found, no tool errors, nonempty practice content, SDK structured-output validation exercised; client/transport closed in finally |
| Fresh npm ci / npm ls | PASS **reported by coordinator**, not independently executed |
| Full test:tooling | PASS **reported by coordinator** before final nested-finally cleanup edit: legacy tmp, CLI MCP initialize/list_projects, real loopback ephemeral-port Angular dev server HTML/main.js and outside-fixture denial |
| Final cleanup-adjusted rerun | **PASS reported by coordinator**, session 16668 exit 0: test:tooling, explicit including-dev audit with zero vulnerabilities, format, lint and build. Covers the final cleanup script; no new independent execution claimed |
| Source checks | Reviewer ran node --check on new smoke and git diff --check; passed. Final nested-finally change inspected directly |
| Docker / remote CI / browser / HMR | Not independently executed or verified |

Independent runtime was Node v24.19.0 / npm 11.9.0. npm emitted an unknown http-proxy config warning; no configuration values were inspected or disclosed. Main's reported passes and independent executions above are distinct evidence.

## Dependency and compatibility assessment

Installed CLI/build are **21.2.27**, compiler-cli **21.2.25**, TypeScript **5.9.3**. Registry metadata shows CLI 21.2.25 pins SDK 1.30.0; CLI 21.2.27 pins SDK 1.31.0. Both inspected build releases pin Vite 7.3.6. Overrides intentionally exceed exact upstream child pins, which is why actual consumer validation matters.

[Angular's official compatibility table](https://angular.dev/reference/versions) supports Angular 21.2.x with Node ^24.0.0 and TS >=5.9 <6.0. Installed build declares TS >=5.9 <6.0; compiler-cli declares >=5.9 <6.1, so the narrower intersection supports TS 5.9.3. Preserve compiler-cli's exact compiler 21.2.25 peer relationship; no need to align unrelated packages arbitrarily.

Live npm metadata confirms SDK 1.32.0 supports Node >=18 and Zod ^3.25 || ^4.0, compatible with CLI's Zod 4.6.6. SDK 1.32.1 is also published; the completed audit establishes no requirement to prefer it over the exact minimum fix. SDK exports retain the server/mcp.js, server/stdio.js and types.js paths used by CLI. Vite 7.3.7 retains Node ^20.19.0 || >=22.12.0 and the inspected dependency/peer ranges; no declared Node 24 conflict was found.

CLI registers tools using Zod object schemas and SDK registerTool, with structured output. Independent patched calls exercised schema serialization and client-side output validation. This supports the observed consumer, not a claim of complete SDK API equivalence. Angular consumes Vite plugins/middleware and transformIndexHtml; its production build alone cannot establish dev-server compatibility. This workspace has no configured SSR target, so SSR API execution is outside the required smoke scope.

## Security findings and advisory limits

The complete baseline npm JSON identifies SDK advisories GHSA-22jm-h49p-29qw, GHSA-6prh-2h8m-c8cw, GHSA-46jr-4wx8-v453 and GHSA-rvq5-wwqv-78pq, all fixed at 1.32.0. It identifies Vite advisories GHSA-698v-4r3x-3vcv, GHSA-r825-w8mm-5pxw, GHSA-587h-7wmj-h5pm, GHSA-9jrq-w75r-8gcw and GHSA-rq7h-c2jc-7f22, with affected Vite 7 range through 7.3.6. CLI/build entries are propagated consumer findings, not additional independent advisories. The patched audit has no findings. npm's suggested Angular 22 major update was not evidence that a major migration was necessary.

Primary upstream references: [SDK memory validation advisory](https://github.com/modelcontextprotocol/typescript-sdk/security/advisories/GHSA-46jr-4wx8-v453), [Vite filesystem advisory](https://github.com/vitejs/vite/security/advisories/GHSA-rq7h-c2jc-7f22). Registry metadata was read through npm view.

Two limits are essential:

- The SDK memory advisory excludes stdio-only servers. The reviewed CLI entry uses stdio, not HTTP MCP. For an HTTP deployment, the added maxToolInputElements protection is opt-in and request-body limits also matter; a clean package audit alone would not prove that deployment hardened. No HTTP MCP configuration change is required by this CLI-only evidence.
- The Vite filesystem advisory depends on an imported in-project path colliding with an absolute host path and populating safeModulePaths before a later raw-filesystem request. The new plain outside-root `/@fs/` denial test is useful defense coverage but **does not reproduce that exact collision exploit**. No claim of advisory-specific exploit reproduction is made.

Development dependencies execute on developer/CI hosts. The normal start script listens on 0.0.0.0, so a clean graph matters even though the smoke deliberately binds loopback.

## Smoke quality, bounds and cleanup

The new script loads the SDK client/stdio transport through the CLI's own resolution context and invokes the local CLI entry, avoiding an unrelated npx download. It initializes, lists tools, calls list_projects and asserts this actual workspace's project in structuredContent, rejecting tool errors. SDK source inspection confirms request timeouts and output-schema validation apply to these calls.

The dev-server smoke invokes the actual Angular workspace consumer with loopback host, port 0 and HMR/live reload disabled. It discovers the assigned URL, verifies app-root HTML, JavaScript MIME/nonempty main.js, and 403/404 plus marker absence for its controlled outside-root fixture. It creates no sensitive host-file probe. It does not prove browser rendering, CSS/client transport behavior, HMR, SPA route refresh, or the exact advisory collision.

Budgets: MCP initialize 20 s, list/call 10 s each, readiness 75 s, each of three HTTP requests 10 s. Normal Unix cleanup targets the detached process group with TERM then KILL after 3 s and awaits close. This Node process-group mechanism applies on Linux Alpine without external shell utilities; port 0 requires no fixed port reservation. SDK transport has its own stdin/TERM/KILL close behavior. Fixture deletion is now protected by nested finally.

Low-priority residual teardown limits: stopServer returns if the group leader already exited, potentially leaving an independently surviving descendant; final close waiting has no separate deadline if a descendant retains inherited pipes. These are source-level robustness observations, not reproduced leaks. Per-operation timeouts therefore do not constitute a proven strict wall-clock bound for every abnormal teardown. MCP stderr is drained rather than retained, limiting failure diagnostics. Neither issue blocks the normal-path bounded acceptance reported here.

## Override expiry and remaining gates

Review each override at the next Angular 21 update. Remove CLI → SDK when the selected upstream Angular 21 CLI resolves an advisory-clean SDK >=1.32.0 without it; remove build → Vite when the selected upstream Angular 21 build resolves advisory-clean Vite >=7.3.7 without it. Remove independently as each condition holds. Regenerate the lockfile, clean npm ci, verify parent-anchored resolutions/peers, rerun full including-dev audit and consumer smokes, and check current advisories before acceptance. A published upstream release alone is insufficient until the selected locked graph consumes it. Avoid retaining exact pins indefinitely or migrating Angular major solely to remove them.

The coordinator-reported cleanup-adjusted rerun is complete and passed (session 16668, exit 0). Final-revision CI/Docker results remain pending and must be recorded when available. Review document frozen after this evidence update; nonblocking abnormal-teardown observations remain unchanged. This review accepts the final source within its stated scope; it does not convert pending execution, Sonar pause, or untested browser/HMR/advisory exploit paths into passes.
