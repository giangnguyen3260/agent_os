# Token storage inventory — source inspection

31 October2026. Latest user requirement: token material must be one-way before application DB persistence. This inventory is bounded source review, not an all-row DB/runtime audit. It prevents the provider foundation from being misreported as complete compliance for every credential type.

| Credential/capability | Inspected source | Bounded observation / required next step |
| --- | --- | --- |
| Human password | SecurityConfig, V002 | BCrypt cost12 encoder and password_hash column; preserve slow password hashing. No password DB inspection performed here. |
| Password recovery | RecoveryService, V003 | Random32-byte capability, SHA256 digest lookup/storage; raw reset link generated transiently for mail. Existing expiry/one-time/revocation behavior stays unchanged. |
| Invitations | V015 and invitation slice contract | token_digest column, cleared after acceptance/revocation; no raw capability column. Actual all-table/runtime audit remains separate. |
| Provider API keys | CredentialBoundary and storage amendment | New primitive returns scoped one-way HMAC+opaque external reference metadata only; usable key belongs to external secret manager. No DB writer exists yet, so future persistence must prove all-row absence of plaintext/reversible envelopes. |
| Human session bearer identifiers | V002 spring_session.session_id and current Spring Session JDBC configuration | Existing schema stores session_id directly, not a digest-specific column. No custom hashed-ID repository was found in the inspected source. Treat whole-session one-way storage as an OPEN compatibility/hardening task; do not claim it implemented. Trace actual serialized values and cookie/repository lifecycle before changing behavior. |
| CSRF session attributes | SecurityConfig default CSRF repository behavior and V002 spring_session_attributes | No explicit digest-only CSRF persistence adapter is configured in inspected SecurityConfig. attribute_bytes is generic serialization, not evidence of hashing. Inspect actual repository/serialization behavior and freeze a separate compatible design; do not infer protection from an opaque bytea column. |

Session/CSRF hardening must preserve session fixation protection, credential-version invalidation, concurrent login/reset barriers, expiration, logout replay rejection and exact-session restart behavior. Do not hash passwords with fast HMAC or patch session IDs by SQL-only replacement: browser cookie issuance, lookup, rotation and serialized attributes need one consistent protocol. Test raw-token sentinels against every DB field/serialized attribute plus real PostgreSQL/restart/logout after implementation. Existing accepted authentication behavior is not silently changed in this foundation PR.

Next API/secret-manager slice must enforce database allowlists; a separate session/CSRF compatibility slice is necessary before a system-wide one-way-token-storage acceptance claim. Source observations are concrete follow-up requirements, not a claim of an exploited vulnerability or a completed runtime penetration test.
