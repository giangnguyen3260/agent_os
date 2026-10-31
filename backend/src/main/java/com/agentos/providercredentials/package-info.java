/**
 * Secure credential boundary only. Storage allowlist: typed scope, HMAC version and verifier,
 * opaque external reference and immutable external key version. Never persist provider plaintext,
 * ciphertext, wrapped DEK, nonce, tag, or any other reversible provider-key payload in the DB.
 * Metadata is internal persistence data, not a browser/API response or log object.
 *
 * <p>Caller authorization supplies the expected organization/connection/provider/revision. Every
 * MAC field has a four-byte big-endian byte length prefix. Canonical field order is domain
 * agentos/provider-credential/verifier/v2, HMAC key version, organization UUID, connection UUID,
 * provider enum name, credential revision, opaque reference locator, immutable external key
 * version, then visible-ASCII credential bytes; UUIDs use canonical text and revision uses positive
 * decimal text. HMAC-SHA256 comparison uses MessageDigest.isEqual on fixed 32-byte values. MAC keys
 * are managed independently from provider secrets. Historical versions are required; no lookup
 * failure permits fallback.
 *
 * <p>Prepare outside DB transactions: validate the credential, resolve the managed HMAC version/key
 * and initialize its MAC handle before external storage. Validate the returned scoped reference,
 * then finish the MAC with that same handle, including both reference fields, without another key
 * lookup. Persist returned allowlisted metadata in a later transaction. A failed/uncertain external
 * write may orphan a secret and is reported for adapter reconciliation. A later failed/uncertain DB
 * commit also requires caller reconciliation with the external manager: never automatically delete
 * a secret that may already be committed. No cross-system atomicity is promised. Production
 * adapters must enforce opaque, non-secret locators, exact scope/version access, server-only
 * authorization and durable reconciliation. No adapter or default wiring is supplied.
 *
 * <p>Buffers passed to ports and consumers are synchronously borrowed and must never be retained or
 * logged. Owned caller/read/store working buffers are erased in finally blocks; managed key handles
 * remain owned by the key provider. Java/JCA internal copies are outside this buffer ownership
 * guarantee. No runtime transport, API, schema, migration or live-provider integration is included.
 */
package com.agentos.providercredentials;
