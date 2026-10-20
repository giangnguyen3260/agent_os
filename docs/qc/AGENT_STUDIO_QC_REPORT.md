# Agent Studio QC — execution pending

Implements Draft Studio view, editing, before/after diff and versioned save with real storage, strict typed limits, optimistic concurrency and durable retries. Branch feature/agent-studio stacks on draft/unmerged Agent creation PR7. Activation, effective grants, providers and runtime limit enforcement remain outside scope. Sonar remains temporarily paused at the user's request.

Local FE lint/format/production build and Java formatting/import checks passed. Mock HTTP rendered inspection covered18 states at1440/768/390 with no horizontal overflow, three ambiguous-save retry flows and three stale-conflict/explicit-reload flows. These are visual/interaction checks, not persistence evidence. Backend Maven execution was blocked by local runtime/dependency availability; Java21 CI is required.

Planned gates: six new backend integration tests in addition to57 baseline; browser cases across three widths alongside96 baseline; native PostgreSQL concurrent same-key replay, two-key stale revision and durable original-response probes. H2 rollback/concurrency evidence and native HTTP concurrency must be reported separately. Final results/revisions/run links are pending actual execution, and no development PASS is claimed yet.

See docs/engineering/AGENT_STUDIO_CONTRACT.md, backend/AGENT_STUDIO_MIGRATION.md and the final acceptance case list.
