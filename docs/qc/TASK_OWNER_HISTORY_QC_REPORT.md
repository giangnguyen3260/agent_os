# Task owner history QC

Status: implementation prepared on `feature/task-owner-history`, based on PR34 closure `1d955d1a378fa6f2056e8fd1cdbb8eeeaf09731d`. No runtime acceptance, merge or deployment claimed. Sonar scanning remains paused.

Scope: current ADMIN reads actual V020 owner events, immutable before/after snapshots and current same-organization member names (explicit null after membership removal), fixed20 pages ordered by owner revision. No synthetic initial assignment, lifecycle gate, Agent current metadata lookup, receipt internals, new schema migration or business writer. See [contract](../engineering/TASK_OWNER_HISTORY_CONTRACT.md) and [requirements review](TASK_OWNER_HISTORY_REQUIREMENTS_REVIEW.md).

Local source/tooling checks passed: frontend lint, full source formatting, production validation/state harness and Angular build; Java import rules; Python native probe AST; workflow YAML; browser syntax, scoped formatting and collection. New browser selection is42 invocations, existing owner81 and retained780 form861 retained instances, with focused INV-B08 three additional repeats. These are collected counts, not browser pass claims.

Required remaining evidence: Java21/Maven formatting/compile/tests, focused actual PostgreSQL history suite, native HTTP/CSRF/DB no-write/privacy/corruption probe, all new and retained browser steps,15 PNG/JSONL state/viewport/hash records and actual visual review, and Docker restart/logout. Local Java17 has no Maven/Java21 or Docker. CI job runtime budget increases45→60 minutes because accepted PR34's Docker run took43+ minutes before this added slice; no test timeout, retry or retained selection is weakened.

Independent [cases](TASK_OWNER_HISTORY_TEST_CASES.md) and [review](TASK_OWNER_HISTORY_REVIEW.md) remain authored/source-level evidence until actual execution. Existing owner writes, receipts and fingerprints are unchanged; the full retained suite must verify that claim.


Before C1, independent QC found two native-oracle gaps. Main included account/membership rows and xmin/ctid in business snapshots, retained raw error bytes privately, required strict safe JSON envelopes/codes/messages/details, and checked known private markers. Offline negative error counterexamples now reject HTML/private JSON and invalid-calendar fallback timestamps; no HTTP/SQL or successful business DTO was mocked in that check. Snapshot evidence concerns committed row values/tuple identities, not forensic observation of rolled-back SQL attempts; native PG read-only/isolation tests and source SELECT-only review supply the transaction boundary. See independent review dispositions.
