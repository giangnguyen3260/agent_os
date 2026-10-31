# Provider credential boundary independent review

31 October 2026. Bounded disposition: **one reference-integrity finding requires resolution or explicit narrowing of the guarantee**. The latest storage amendment correctly supersedes PR40 DB ciphertext: application DB metadata contains scoped one-way keyed verification data and an opaque reference; usable credentials belong in external secret management. This is a foundation review, not feature acceptance or security certification. Only this review document was authored; no other agents were spawned.

## Frozen source and independent checks

Main reported the three Java files frozen before verification. SHA-256 identities observed before/after checks:

| File | SHA-256 |
| --- | --- |
| `docs/engineering/PROVIDER_CREDENTIAL_STORAGE_AMENDMENT.md` | `243123f2fa4312250b5f460be339223d6a635aaa7109becae270bbee2e2464e2` |
| `backend/src/main/java/com/agentos/providercredentials/CredentialBoundary.java` | `de0ac00137829de63a38aa88403cab9e25e01c17f2b2f5b228787149d0d1a9a4` |
| `backend/src/main/java/com/agentos/providercredentials/package-info.java` | `50145067705088c11e6b547d5de90723ba871a94613339e08345c1b5148e81cf` |
| `backend/src/test/java/com/agentos/providercredentials/CredentialBoundaryTest.java` | `257d938d5d20194a82864dcd27a30d37ae770977854ddb71939340e64c1cdf6a` |

Independent local compilation using `java -m jdk.compiler/com.sun.tools.javac.Main` and the supplied JUnit jar succeeded. JUnit selected only `CredentialBoundaryTest`: **26 found, 26 started, 26 successful, zero skipped/failed**. Supplied formatter dry-run with `--set-exit-if-changed` succeeded on all three frozen Java files. An independent Python `hmac`/`hashlib` oracle reconstructed the length-prefixed message and confirmed `0J1ORyiRL72YwJbDGh2jY9CAV7Sxk5CBVNcdltJkahQ=` for the fixed test vector. These are local unit/format checks; no application runtime, live provider, DB, deployed vault or API behavior is claimed.

## F1 — medium: reference identity is not authenticated by the verifier

`CredentialBoundary.java` lines 145–149 compute the digest before receiving the reference. Lines 191–200 authenticate domain, HMAC-key version, organization, connection, provider, credential revision and credential bytes, but omit `Reference.locator` and `Reference.keyVersion`. `use` checks scope equality and credential MAC only (lines 172–181).

Concrete consequence: replace a valid metadata reference with a different locator or immutable secret version under the same scope that yields identical credential bytes. The original digest remains valid and the callback runs. This does not permit an arbitrary different credential or cross-scope credential to pass. It does mean the primitive cannot prove the exact prepared external version/reference was retained, even with a correctly scope-enforcing adapter; identical bytes can exist at two versions. Reference/version-specific lifecycle or retention decisions must not infer that proof from the MAC.

The required reference-substitution coverage is absent. The fake `read(Reference)` ignores the supplied reference entirely, so tests exercise scope and changed bytes, but cannot establish locator/version integrity. A unit scenario using the current fake and substituted same-scope locator/version would follow the successful callback path; this consequence is from source inspection, not an additional executed test.

Recommended resolution: bind the returned immutable locator and external version in the canonical MAC after store, preserving reconciliation on any later MAC/metadata failure. Add separate locator-only and external-version-only substitution tests with unchanged credential bytes; assert no callback and read-buffer erasure. Update the independent canonical vector accordingly. Alternatively, explicitly narrow the contract to credential-content integrity and specify an enforceable adapter uniqueness rule that rejects alternate references for the same scope/revision; current scope/version lookup wording alone does not establish that rule. Do not claim reference-substitution resistance from the existing suite.

## Other reviewed properties and limits

- Scope and historical HMAC-key version binding are present. Every scope field is length-prefixed, as are domain/version/credential; fixed-size canonical digests use `MessageDigest.isEqual`. Missing historical keys fail without current-key fallback. Key entropy and immutable managed-version behavior are explicit port obligations, not locally proven provisioning properties.
- Caller characters and locally owned credential/read buffers are erased in `finally`, including late validation, key/store/callback failures. Digest output bytes and decoded comparison buffers are cleared. A read adapter that throws before returning owns its intermediate cleanup. No plaintext credential String conversion appears in production boundary code; test-only String conversions are not production leakage. JCA/VM copies remain outside the stated erasure guarantee.
- Ordinary dependency/callback exceptions become fixed-message `Failure` without causes/suppressed data; `toString` redacts metadata/reference. Java `Error` deliberately propagates (the suite asserts this for `AssertionError`) while buffers are still cleared. Thus exception sanitization is not a guarantee over arbitrary `Throwable` messages.
- Record components remain accessible to serializers: redacted `toString` does not redact Jackson record properties. There is no Jackson-output test or public DTO in this slice. Internal persistence serialization may need those properties; this is not an observed API leak. Future HTTP output must use an explicit allowlist/mask and test that locator, digest and both versions never appear. The current metadata-shape test proves neither HTTP safety nor absence of recoverable material across DB tables.
- Dependencies are constructor-required; no default vault, credential, production fake or plaintext fallback was found in the reviewed package. Fake ports exist under test sources. This primitive takes trusted expected scope; it does not establish human/worker authority, current committed revision, policy, lease, deadline or receipt validity.
- Ambiguous external store failure invokes `preparationFailed`, including with null reference; this is reconciliation notification, not deletion. A notification failure is swallowed and returns the same safe failure. Durable discovery of uncertain writes must therefore be guaranteed by the future adapter/preparation identity protocol, not by successful delivery of this callback. A later unknown DB commit is outside the primitive; never automatically delete its possibly committed external reference. No cross-system rollback guarantee is established.

## Acceptance boundary

No DB writer, migration, API, secret-manager adapter or service was requested for this slice; their absence is not an implementation defect. Retain future gates for all-row DB inspection, opaque nonsecret references, immutable scoped vault lookup, independently provisioned managed keys, public serializer allowlists, authority/current-revision enforcement, durable preparation identity, orphan reconciliation, UNKNOWN commit recovery, concurrent replay/rotation and restore behavior. No claim of complete one-way-before-DB enforcement is possible until actual persistence paths are reviewed and tested. Findings above apply only to the recorded frozen source; main owns final integration disposition.

## F1 correction retest — final v2 freeze, 31 October 2026

**Current bounded disposition: F1 resolved; no remaining blocking source finding in this reviewed foundation slice.** The earlier finding and original hashes above remain historical evidence, superseded for current disposition by this retest. The final three Java files and updated amendment were read directly following main's renewed freeze notification; their hashes were identical before and after independent checks:

| File | SHA-256 |
| --- | --- |
| `docs/engineering/PROVIDER_CREDENTIAL_STORAGE_AMENDMENT.md` | `5455bf87fa81eaffa4479c9f3719ccaa50f79c34eb350ddfe402ae923b30332b` |
| `backend/src/main/java/com/agentos/providercredentials/CredentialBoundary.java` | `eb01481bbd6b0b62c6b7aed49ac4f63d01db0007c757a0c8d219f659acafdeeb` |
| `backend/src/main/java/com/agentos/providercredentials/package-info.java` | `934a01f80e03431952dfe3dd303427ac0c8c14926f78db485a3ac7a62c00deb6` |
| `backend/src/test/java/com/agentos/providercredentials/CredentialBoundaryTest.java` | `5c755bfdcdb2cd78d300f1b345407f60dc4bbfe431fe57e4906340b38e546b27` |

The v2 MAC now includes exact locator and immutable external-secret version after the scoped fields and before credential bytes, using the same length-prefix encoding on prepare/use. Preparation resolves the historical managed key and initializes the MAC before attempting external storage, validates the returned scoped reference, then finalizes the same handle without a second managed-key lookup. Ordinary exceptions after the store attempt remain inside the preparation-reconciliation catch; owned characters/bytes remain covered by finally cleanup. The updated amendment and package documentation describe this ordering and reference binding consistently.

Independent fresh-directory compilation using OpenJDK **17.0.20** and `java -m jdk.compiler/com.sun.tools.javac.Main` succeeded. Focused JUnit execution independently observed **31 found, 31 started, 31 successful; zero failed, skipped or aborted**. Formatter dry-run with `--set-exit-if-changed` passed on all three frozen Java files.

The five added tests were inspected: separate same-scope/same-content locator and external-version substitutions both reject before callback and erase the fetched buffer; missing key and unusable key/MAC initialization fail before any store; a post-store key outage/current-version change does not trigger a second lookup and the resulting metadata verifies with its recorded historical version. The fake deliberately returns identical credential bytes for alternate references, so substitution failures establish MAC reference binding independently of vault-side rejection. Existing malformed-reference/reconciliation and cleanup tests remain present and pass.

Independent Python `hmac`/`hashlib` reconstruction used a 32-byte key of value `0x01` and four-byte big-endian byte lengths for these ordered UTF-8 fields: `agentos/provider-credential/verifier/v2`, `v1`, `00000000-0000-0000-0000-000000000001`, `00000000-0000-0000-0000-000000000002`, `OPENAI`, `1`, `opaque`, `secret-v1`, `sentinel`. Result **`aL1EZ6NLMerhRLp2lqSL/RaqMxsiCYQGVRAZKFdtFQI=`** exactly matches the new Java known-answer assertion.

All acceptance limits above remain: this closes F1 against these exact source identities, not external adapter enforcement, public Jackson-output safety, service authority, durable reconciliation/UNKNOWN behavior or the all-row DB storage property. No application runtime, live-provider, DB or full security certification claim is made. Only this review document was changed by the reviewer.
