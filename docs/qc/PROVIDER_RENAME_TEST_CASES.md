# Provider Rename test checklist

Requirement source is the bounded
[Rename contract](../engineering/PROVIDER_RENAME_TRANSPORT_CONTRACT.md).
This checklist records required assertions. Execution results belong to matching
source/CI evidence and must be recorded separately.

## Current isolated parser, projector and response slice

| ID | Required assertion |
| --- | --- |
| R01 | Parse exact two-field JSON in either order and construct the existing Rename command with canonical trusted target and normalized name. |
| R02 | Accept revision strings 0, 1 and MAX. Reject negatives, signs, leading zeros, whitespace, decimals/exponents, MAX+1, number/null/boolean values. Zero parses but fresh mutation eligibility remains separate. |
| R03 | Validate empty/all-whitespace/101-codepoint names, ISO controls, U+2028/U+2029 and unpaired surrogates. Verify supplementary codepoints and surrounding Unicode whitespace normalization. |
| R04 | Reject missing/unknown/duplicate fields, wrong root/types, malformed JSON/UTF-8 and trailing document. Accept exactly one leading UTF-8 BOM, counted within 8192 bytes. Reject repeated BOMs and UTF-16/UTF-32 LE/BE with or without BOM. Verify 8192-byte acceptance where otherwise valid and first-byte overflow rejection without draining a large stream. |
| R05 | Clear owned input/scratch buffers on valid input, parse failure and partial I/O failure. Exceptions and diagnostics contain fixed safe text without submitted content/cause. |
| R06 | Project all three provider/mode pairs, Anthropic nullable/non-null workspace and both enabled/disabled states. Assert exact envelope, meta, receipt, before/after and option field sets, explicit checkId null, canonical UUIDs/revision strings and UTC6 timestamps. |
| R07 | Reject null before/after, wrong operation/identity/scope/profile/options, invalid normalized/equal names, invalid state or mismatched credential revisions. Verify every required unchanged snapshot field. |
| R08 | Enforce adjacent revisions, positive revisions, overflow prevention and createdAt/before.updatedAt/after.updatedAt/recordedAt ordering. Accept equal allowed timestamps and the highest valid adjacent pair. |
| R09 | First committed Rename emits 201, replay emits 200 with identical data. Meta may differ. Assert JSON/no-store and no health/mask, key/reference/verifier/digest/event identifiers in serialized payload. |
| R10 | Malformed committed receipt fails safely before partial success. UNKNOWN/unavailable/rejected outcomes use fixed allowlisted errors without provisional receipt. Rate metadata is bounded and emitted only for valid 429. |
| R11 | Create projector continues to refuse Rename and Rename projector refuses Create/Rotate/Enable/Disable. Existing Create/parser/response regression checks remain green. |

## Subsequent coordinator and native backend integration

| ID | Required assertion |
| --- | --- |
| N01 | Actual PostgreSQL fresh Rename atomically changes normalized name/config revision and appends event+receipt. Provider/mode/options/state/credential revision and credential-version rows remain unchanged. Zero revision rejects, stale revision conflicts and MAX cannot wrap. |
| N02 | Same normalized name returns NO_CHANGE. Disabled connection/provider can still be renamed. Missing/foreign target and revoked authority fail under current scoped rules without mutation. |
| N03 | Matching replay precedes mutable target/revision/provider gates and consumes no fresh quota. Changed name/target/expectedRevision under the same namespace returns idempotency conflict. A later Rename/rotation cannot change historical receipt data. |
| N04 | Mixed Create/Rename fresh requests share the 30-slot admission namespace. Native competition cannot exceed quota. Retry-After is bounded and denial leaves business rows unchanged. |
| N05 | Competing revision writers, forced transaction rollback and uncertain completion retain consistent metadata/event/receipt state. Explicit own lookup returns exact historical Create or Rename data, explicit absence or safe unavailable error under current actor/org authority. |
| N06 | Command-HMAC outage fails safely. Credential-store outage with required command-HMAC material available permits Rename/replay. Verify no credential preparation/Vault credential write or provider call. |
| N07 | Real authenticated HTTP verifies current ADMIN before body, actual session/header CSRF, canonical target/key/revision, strict request shape, body bounds, denied no-write behavior and safe status/headers. |

## Subsequent UI integration

| ID | Required assertion |
| --- | --- |
| U01 | Responsive name edit and before/after review use actual selected configuration revision and display success from persisted readback. |
| U02 | NO_CHANGE, revision conflict, rate delay and authority loss have explicit safe states and stale requests cannot overwrite a newer selection. |
| U03 | UNKNOWN supports explicit secret-free own-receipt recovery. Any permitted exact resend preserves reviewed body/key. Changed name/revision requires fresh review/key and creates no automatic retry. |
| U04 | Browser with real HTTP/PostgreSQL confirms one fresh mutation, immutable historical receipt and subsequent readback. Keep mocked UI assertions distinct from executed native evidence. |

Current delivery acceptance is R01–R11 only. Native and UI rows become acceptance
criteria when their corresponding integration slices are implemented.

## Execution checkpoint

The Create/Rename body suites passed 16 standalone Java17 JUnit tests. The first
focused Java21 run also passed both parser suites but found the missing empty
`error.details` field under `NON_EMPTY` in three Rename response assertions.
The common DTO now pins that property to include empty maps. Both Create/Rename
response suites subsequently passed 14 standalone Java17 tests, including the
all-operation rejection allowlist. No tests or error-shape assertions were relaxed.
Corrected-head Java21 focused/native CI remains required before accepting R01–R11.
