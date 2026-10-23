# V013 approval grant management rollout

Additive after V012. Back up the approval ledger, management tables and audit data before rollout. Apply Flyway forward migrations; do not rewrite V001–V012 or run disposable CI fixture SQL in production. No production account, membership, Team or grant is seeded.

V013 adds a composite grant-scope unique key, recipient revision ledgers, restricted management events and durable actor-scoped receipts. Existing grants retain their actual expiry/revocation and do not receive invented history. Global account and organization foreign keys preserve scope references; membership deletion does not cascade management provenance. API immutability is enforced by application paths, not DB triggers. Restricted reasons belong only in management history, never generic audit payloads.

Org Admins may issue to another current enabled MEMBER/ADMIN, with explicit Team/PUBLISH_PREVIEW LOW/MEDIUM/HIGH and 1–168 hours. Revoke targets one grant ID, including legacy/expired/unsupported or removed-recipient grants. Existing matching grants remain intact. Receipt recovery reauthorizes the original issuer's current ADMIN status; historical operation results are not current decision authority.

Writes serialize on the organization mutex before globally sorted human account locks and scoped membership locks, then reauthorize after waits. This bounded serialization supports correctness but is not a production throughput claim. Approval decisions and information writers retain their current grant checks; revoke adds no Task/Mission transition or provider/tool execution.

Runtime acceptance is tracked in docs/qc/APPROVAL_GRANT_MANAGEMENT_REVIEW.md. Test collection and local frontend builds do not establish migration/concurrency/browser acceptance. Keep PR23 draft until the exact candidate passes required CI and visual review. SonarQube remains paused. Configure deployment backups, database permissions and encryption at rest separately; never hash restricted history text that must be displayed to authorized administrators.
