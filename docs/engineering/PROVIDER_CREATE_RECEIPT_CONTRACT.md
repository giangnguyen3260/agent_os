# Provider Create receipt projection proposal

Engineering draft for review. If accepted, this amends only the Create receipt
projection in §4 of [the provider connection API contract](PROVIDER_CONNECTION_API_CONTRACT.md).
It covers the successful Create response, its matching replay, and the same Create
receipt when exposed through a future own-receipt lookup. Other commands and the
current connection list/detail projections retain their existing contracts. This
draft defines a projection over V023 and its retained configuration events.

## Exact response data

A successful Create command returns the existing `ApiResponse` envelope
`{data,meta}`. `data` has exactly these fields.

| Field | Value |
| --- | --- |
| `organizationId` | Receipt organization UUID |
| `commandId` | Durable receipt command UUID |
| `operation` | `CREATE` |
| `connectionId` | Receipt connection UUID |
| `actorId` | Receipt actor UUID |
| `committedAt` | Durable receipt `recorded_at`, formatted as described below |
| `before` | Explicit JSON `null` |
| `after` | Historical `ConnectionConfigurationSnapshot` described below |
| `checkId` | Explicit JSON `null` |

`ConnectionConfigurationSnapshot` has exactly these fields.

| Field | Value |
| --- | --- |
| `organizationId`, `id` | Same UUIDs as the receipt's `organizationId`, `connectionId` |
| `provider`, `credentialMode` | Stored supported provider/mode pair from the retained Create event |
| `name` | Stored normalized name from that event |
| `options` | Exactly `{}` for OpenAI or Gemini. Exactly `{workspaceId}` for Anthropic, including explicit `null` when the stored workspace is absent |
| `state` | `ENABLED`, the state at Create |
| `revision`, `credentialRevision` | Canonical decimal strings, both `"1"` for Create |
| `createdAt`, `updatedAt` | Timestamp of the retained Create event, equal for Create |

UUIDs use the canonical lowercase spelling in the main API contract. All three
timestamps use UTC RFC3339 with six fractional digits. `committedAt` retains the
proposed wire name for compatibility and represents the receipt's persisted
recording timestamp. `ProviderReceiptService` samples the database clock before
inserting the receipt within the write transaction. The completion boundary
confirms commit before releasing the response. The clock sample does not measure
the actual database commit instant. The projector should use the persisted
microsecond precision for both fresh and replayed values and verify equality on
native PostgreSQL. If commit-instant semantics are required, that would need a
different timestamp contract and storage source.

The snapshot excludes `credentialState` and `credentialMask`. V023 pins the
configuration event and carries no historical availability observation. The
client can read current credential state from list/detail, where supported. This
keeps the receipt stable when observations expire or change.

## Replay, lookup and failure

- A confirmed first commit returns `201`. An authorized matching replay returns
  `200` with identical receipt `data`. The response envelope's `meta.requestId`
  belongs to the current request and may differ. The future own-receipt lookup
  keeps its proposed `200` with `{receipt:CreateReceipt|null}` data for this Create
  receipt. A missing receipt is a read result, with the existing UNKNOWN semantics.
- The source is the scoped V023 receipt with its pinned creation and after event.
  Create requires both pins to name the same event. A later rename, rotation,
  state change or health observation cannot change its historical payload.
  Projection requires no live metadata, health, provider or secret-manager read.
- Only a valid committed Create outcome can enter the success response path.
  Missing or malformed persisted receipt/event data fails closed to `503` with
  `PROVIDER_CONNECTION_UNAVAILABLE` and the fixed error envelope. Reject an
  unsupported operation at this Create-specific projector boundary. Existing
  authorization and idempotency errors keep their public mappings. UNKNOWN has
  no success data and remains eligible for explicit own-receipt lookup when that
  route is available. Preserve `Cache-Control: no-store`.
- The allowlist excludes idempotency keys, key material, opaque Vault references,
  command digests, event IDs, internal errors and health metadata. No schema
  migration is required for this projection proposal.

## Acceptance for the implementation slice

1. Verify the exact envelope and receipt/snapshot field sets, nullable fields,
   each provider/mode pair and both Anthropic workspace states. Verify canonical
   UUIDs, string revisions and UTC timestamps to six fractional digits.
2. Execute fresh Create and matching replay against native PostgreSQL. Require
   identical receipt data before and after a later connection revision, and
   verify that the projection is independent of current availability changes.
   Check timestamp equality against the stored receipt and event values.
3. Exercise malformed or mismatched receipt/event data, unexpected operation and
   uncommitted outcomes. Verify fixed error projection and no provisional receipt,
   internal identifiers or sensitive fields in serialized output.
4. Record matching focused CI. Public route registration, security wiring, the
   own-receipt endpoint and the Create form each have separate acceptance gates.
