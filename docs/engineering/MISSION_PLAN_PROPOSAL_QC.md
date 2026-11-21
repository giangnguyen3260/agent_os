# Reviewed Agent plan import — candidate evidence

22 November 2026, Asia/Ho_Chi_Minh. Stacked on PR125 remote head `60288ab7f29ae471c0d28493378006aca6c10b4b`, whose six workflows are successful. This candidate is not merged or deployed; no paid provider evidence is claimed.

Implemented: V057 immutable reviewed import receipt with scoped normalized Task mapping; strict bounded proposal parser; current ADMIN/session/CSRF endpoints for source, preview, apply and immutable receipt reread; receipt-backed completed graph source proof; human-only owner binding and corrected-input attribution; exact reviewed fingerprint; empty target protection; all Tasks/edges plus one intake CAS/receipt/mapping/audit transaction; exact historical replay. Feature defaults off.

Local validation:

- 12 Spring/JDBC integration cases exercise a settled accepted source and real graph completion receipts: full diamond import, no execution effects, correction attribution, changed settlement/absent completion receipt, owner/intake/manual-Task conflict, receipt and audit rollback, exact current authority and owner keys, source isolation/tampering, simultaneous manual create vs import, strict HTTP/CSRF, stored receipt/mapping corruption and closed/max/control target gates.
- 3 strict-parser cases cover 20/21 Tasks, 100/101 edges, topological order, unknown authority fields, duplicate fields/keys, coercion, fences/trailing JSON, missing/self/duplicate/cyclic edges, Unicode/control/blank/byte limits and escaped hostile text as data.
- Retained dependency-context (10) and graph-completion (12) cases are included in the selected 37 backend cases. Final local selection: 37 successful tests, zero failures/errors/skips.
- 66 mocked browser cases pass without retries across desktop/tablet/mobile: 27 new proposal cases plus 39 retained context/review/graph-completion cases. New coverage includes explicit complete owner review, escaped content, correction attribution/edit invalidation, exact unknown-outcome retry, malformed receipt uncertainty, fresh authority erasure, target conflict, changed task data and duplicate/omitted/non-topological preview rejection.
- Production frontend build, lint/format, Java compilation/import/format and whitespace checks run locally. These are separate from native CI.

Native PostgreSQL wrapper `MissionPlanProposalPostgresIT` inherits the 12 integration cases, requires an isolated real PostgreSQL schema and fails on absent configuration rather than falling back or skipping. It is explicitly selected by Quality from first publication.

The existing real-backend TLS Vault/worker browser diamond now returns strict planner JSON as Graph D's simulated provider output. After explicit ACCEPT and receipt-backed Mission completion, the same browser reads that source, binds four Agents, previews all Tasks and imports the diamond into a new empty Mission. Native assertions require four DRAFT Tasks/four edges, one receipt/mapping set, one intake increment, CREATED target, no target Run or accepted plan, and exactly four total provider sends (none from import). This fixture is compiled locally; its real native Vault/browser run remains pending matching-head CI. It does not establish an actual paid OpenAI planning run.

Known v1 bounds: source Mission must use graph completion (including one-Task graphs), not historical legacy single-Task closure. Each source/correction JSON is at most 64 KiB, with a separate 128 KiB expanded snapshot cap. No durable pre-apply staging, automatic replanning, merge into manual Tasks, plan acceptance, admission or Start is introduced.
