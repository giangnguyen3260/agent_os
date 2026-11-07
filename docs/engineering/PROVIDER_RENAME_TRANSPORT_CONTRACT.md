# Provider Rename transport contract

Engineering freeze for the bounded Rename parser, historical receipt projector
and response writer slice. This extends the configuration snapshot amendment in
[the Create receipt contract](PROVIDER_CREATE_RECEIPT_CONTRACT.md) to `RENAME` only.
It supersedes the original API proposal's use of health-bearing `Connection`
snapshots within Rename receipts. Implementation acceptance follows completed
matching tests. Route, lookup, coordinator and UI delivery are subsequent slices.

## Request boundary

The future command uses `POST /api/v1/organizations/{organizationId}/provider-connections/{connectionId}/rename`.
Both path IDs and the Idempotency-Key are canonical lowercase RFC UUIDs with
variant 2 and version 1–5. The isolated parser receives the trusted target UUID
and an owned body buffer or bounded stream. It constructs the existing
`CommandBoundary.Rename` representation.

JSON contains exactly `{expectedRevision,name}`. Both fields are required strings.
`expectedRevision` matches `0|[1-9][0-9]*` and is in signed int64 range
0..9223372036854775807. JSON numbers, signs, leading zeros, spaces and exponent
forms are rejected. Zero remains a valid canonical command/digest input, matching
`CommandBoundary.Rename`. A fresh business mutation rejects zero through the
existing revision rules. Parser acceptance never establishes mutation eligibility.

Normalize name using the existing command boundary. Strip surrounding Unicode
whitespace according to that implementation. The result contains 1–100 Unicode
codepoints with no ISO control, U+2028, U+2029 or unpaired surrogate. The later
fresh business operation returns `NO_CHANGE` if the normalized name is unchanged.

The byte limit is 8192 before decoding, including whitespace. Accept only valid
UTF-8 and one complete JSON object. Reject unknown, missing or duplicate fields,
null/wrong types, coercions, malformed encoding, trailing JSON and overflow. A
single leading UTF-8 BOM is accepted and counts toward the byte limit, consistent
with Create. Repeated BOMs and UTF-16/UTF-32 in either endian order, with or without
a BOM, are rejected. Parse the validated decoded characters so byte-parser
encoding autodetection cannot reinterpret an otherwise valid UTF-8 byte sequence.
A stream reader may consume one overflow sentinel byte and then rejects without
draining the remainder. Owned buffers are cleared on success and failure. Parser
errors carry fixed safe text with no request content or original exception cause.

## Exact receipt and response

The existing `ApiResponse` envelope is exactly `{data,meta}`, with `meta` exactly
`{requestId}` for the current request. Receipt `data` contains exactly these fields.

| Field | Meaning |
| --- | --- |
| `organizationId`, `commandId`, `connectionId`, `actorId` | Canonical stored receipt UUIDs |
| `operation` | `RENAME` |
| `committedAt` | Persisted V023 `recorded_at` in UTC RFC3339 with six fractional digits |
| `before`, `after` | Required non-null historical configuration snapshots |
| `checkId` | Required explicit JSON null |

Each snapshot has exactly
`{organizationId,id,provider,credentialMode,name,options,state,revision,credentialRevision,createdAt,updatedAt}`.
Its organization and ID equal the receipt's organization and connection. Provider
and credentialMode form one of the three supported pairs. Options are exactly
`{}` for OpenAI/Gemini and `{workspaceId}` for Anthropic, including explicit null
when absent. A non-null workspace obeys the existing command schema.

The following invariants are required before any success response is written.

- `before.revision` is a positive int64 string less than MAX. `after.revision`
  equals before plus one. Both are canonical decimal strings. Check before the
  addition so overflow cannot wrap. Credential revisions are positive canonical
  int64 strings and equal on both sides, with credential revision at most the
  corresponding configuration revision.
- Provider, mode, options, state and createdAt are equal across the two snapshots.
  State is `ENABLED` or `DISABLED`. Both names are valid normalized stored names
  and differ. Their other historical configuration values remain equal.
- `createdAt <= before.updatedAt <= after.updatedAt <= committedAt`, comparing
  instants. Every public timestamp uses UTC with six fractional digits. Preserve
  persisted microsecond precision so fresh and replayed projection agree.
- The source is the scoped V023 receipt and its pinned creation/before/after
  events. Historical projection uses those retained events, independently of
  subsequent configuration or availability changes. `committedAt` retains the
  established wire name and means the stored receipt recording time sampled
  within the transaction. Commit confirmation gates release of success data.

The field allowlist excludes credential health/mask, secret values, Vault locators,
verifiers, HMAC metadata, idempotency keys and event IDs. Current availability
belongs to current list/detail reads. Rename projection requires no credential,
health or provider calls.

First confirmed commit returns 201. A matching replay returns 200 with identical
receipt data, while meta.requestId may differ. Set JSON content type and
`Cache-Control: no-store`. Validate all success data before touching the response.
Malformed/missing committed Rename receipt data uses fixed
`503 PROVIDER_CONNECTION_UNAVAILABLE`. Other-operation data is refused at the
Rename projector boundary. Existing Create projection continues to accept only
its own supported operation.

Rejected, UNKNOWN and unavailable completion outcomes use existing allowlisted
public error mappings and exact `ApiErrorResponse` envelope `{error,meta}`. Error
is `{code,message,details}` with empty details and fixed server text. No provisional
receipt is released. Only a known 429 with valid 1–60 second metadata writes
Retry-After. Success/replay status and historical payload semantics apply to this
response slice, while authenticated invocation remains future integration work.

## Concrete subsequent dependencies

1. Share the existing durable 30/min/account/organization write namespace with
   Create. Fresh Rename admission rechecks current authority, while historical
   matching replay bypasses fresh quota. Provider-disabled and connection-capacity
   Create gates must not block Rename. Preserve established lock order and atomic
   mutation/event/receipt behavior.
2. Assemble an explicit Rename coordinator using current/historical command HMAC
   versions and the actual transactional receipt proxy. Metadata Rename must
   remain available during credential-store outage when required command HMAC
   material is available. No credential preparation belongs to this operation.
3. Add narrow ADMIN/session-CSRF ingress, strict target/header/body validation,
   bounded request reads and safe response mapping. Preserve unrelated routes.
4. Extend own-receipt lookup with an explicit Create-or-Rename dispatcher and
   configuration snapshots. Recheck actor/organization authority and preserve
   explicit missing-receipt versus unavailable semantics. Metadata-only exact
   resubmission uses the same key/body after explicit user action, subject to the
   frozen recovery contract. Changed commands require fresh review and a new key.
5. Add reviewed UI before/after confirmation, stale/no-change/denied states,
   historical UNKNOWN recovery and responsive tests. Native HTTP/PostgreSQL
   acceptance and one persisted browser Rename complete that integration slice.

The [test checklist](../qc/PROVIDER_RENAME_TEST_CASES.md) distinguishes current
isolated acceptance from these subsequent delivery steps.
