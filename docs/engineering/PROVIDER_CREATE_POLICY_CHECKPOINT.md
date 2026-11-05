# Governed create policy/capacity candidate

An explicit executeCreateGoverned receipt entry point and executeCreatePrepared
completion entry point preserve the old internal completion primitive while
adding the create-specific locked gates. The internal create coordinator now
uses the governed entry point for fresh execution and historical replay.

Lock order is organization, singleton deployment-policy row, account, membership,
then the established connection/credential path. Authority is validated after
locks; receipt equality/replay precedes mutable policy and capacity checks.
Missing policy fails closed only for fresh writes. Historical matching replay
remains available during policy disable/outage, subject to fresh human authority.
Provider disable yields409 PROVIDER_DISABLED; total connection count100 including
disabled yields409 CONNECTION_LIMIT. Count/write share the organization mutex.

Authored shared H2/PostgreSQL cases cover replay after disable, disabled fresh
write, missing policy, unchanged business rows on denial and100 mixed-state
connections blocking the next create. Existing coordinator replay/revocation
cases now use an explicit enabled policy fixture. Matching configured CI logs
establish passes; formatting/diff checks are not runtime evidence.

This is a final commit gate, not the complete public command admission service.
The coordinator still needs preflight provider-policy and capacity checks before
external preparation, plus durable fresh-write rate admission before Vault I/O.
No sliding-window quota or429 Retry-After is claimed here. No unreviewed default
policy is seeded in production. Explicit policy operators must follow the same
lock/ABA contract; no policy-management endpoint is added. This candidate adds
no public CREATE transport/UI, deployment credentials, merge or deployment.
