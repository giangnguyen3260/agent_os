# Durable pre-Vault create admission candidate

V027 adds the bounded write-admission namespace scoped by account and organization.
No secret, key digest or body is stored. The explicitly constructed gate commits
one of30 rolling60-second request slots before key-service or Vault preparation.
Failed/uncertain admission commit forbids external preparation; admitted dependency
failures retain their slot instead of refunding expensive repeated attempts.

Order: organization mutex, deployment policy, current account/membership, own
receipt recheck, mutable policy/capacity, admission row and fresh database clock.
A concurrently committed own receipt redirects the coordinator to a bounded
re-probe/historical comparison and never to another secret preparation. Existing
historical receipts skip this admission path. Fresh invalid/disabled/capacity
requests cannot consume an expensive Vault operation. Final commit gates remain
necessary because state can change after preflight.

PostgreSQL uses clock_timestamp after waits. Samples retain millisecond precision,
with strict older-than/equal60s expiry and at most30 nondecreasing timestamps.
Malformed/future/oversized rows fail closed. H2 uses its transaction timestamp
only as a compatibility fixture; it does not prove native post-wait clock behavior.
No framework token audit or production key provisioning is implied.

Shared H2/PostgreSQL authored cases cover the31st attempt, expiry of old samples,
historical receipt bypass with unchanged admission rows and disabled-provider
preflight with zero extra secret writes. The36-contender test requires native
PostgreSQL and is explicitly skipped on H2 because its transaction clock cannot
prove the post-wait condition. It requires exactly30 accepted slots.
Matching Java21/native CI logs establish runtime passes; formatting is separate.

This remains internal: RATE_LIMITED is a fixed429-capable code without public
transport/Retry-After metadata. Bounded retry delay, ingress/session/CSRF/header
gates, command receipt safe DTO, durable reconciliation resolution and public
API/UI remain required before activation. Only CREATE currently consumes the
shared write namespace; future metadata commands must join the same protocol.
No endpoint, automatic bean, merge or deployment is added.
