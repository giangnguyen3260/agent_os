# Independent QC — provider command HMAC V1

## Checkpoint 1 — contract and independent oracle

Date: 1 November 2026. Branch observed: `feature/provider-command-hmac`. Reviewed actual contents of `docs/engineering/PROVIDER_COMMAND_HMAC_CONTRACT.md`, `scripts/provider-command-vectors.py` and `backend/src/test/resources/providercommands/v1-vectors.json` while BE implementation was in progress. Those files were untracked at observation, so branch/HEAD alone cannot identify this review. Only this new report was authored; the prior PR42 report is frozen and untouched by this reviewer. Production/test implementation review is deliberately pending main's BE-ready notification; untracked implementation/workflow presence is not evidence of readiness or correctness.

**Disposition: contract and positive known-answer oracle acceptable for implementation review; no blocking encoding mistake identified. Implementation and Java runtime acceptance remain OPEN.** This checkpoint establishes neither production conformance nor API/receipt/database atomicity.

### Findings chronology

**C1 — Low, wording ambiguity, nonblocking:** Canonical values says “Other operations require absent secret (null)” immediately after describing secret syntax, without explicitly saying “other than CREATE/ROTATE.” Read literally in isolation it could exclude ROTATE. Purpose/ownership, operation table and the ROTATE vector consistently require ROTATE's borrowed secret, so the normative behavior is clear when read together. Suggested wording: “Operations other than CREATE and ROTATE require absent secret (null).” No contract edit was made. Recheck the eventual operation-shape validator against that interpretation.

**O1 — Evidence limitation, not an oracle defect:** Python supplies normalizedName as a declared independent expected value; it does not implement Java Character trimming or validate general input shapes. Its dict encoder preserves insertion order and is exercised with only the declared zero-/one-field options objects. The final `else` in vector construction handles the two current check/discovery cases without rejecting arbitrary unknown future operation labels. These are acceptable properties of this fixed synthetic-vector generator, not a reusable command validator. Do not present the ten positive vectors as invalid-input, normalization-boundary, exhaustive field-mutation or generic object-order testing. Production tests must supply those separate checks.

### Contract assessment

| Area | Reviewed contract and remaining source/test gate |
| --- | --- |
| Borrowed secret ownership | Synchronous borrowed char[] is neither mutated nor retained on success/failure; orchestration erases its original/copies and gives CredentialBoundary a separate disposable owned copy. Primitive-owned byte/digest buffers require finally cleanup. Inspect all return/throw paths, any copies/encoders and object fields; test borrowed-buffer preservation on dependency failures too. No immutable secret String or accumulated complete canonical secret stream is allowed. JCA/VM erasure is explicitly bounded. |
| Historical keys | Command-specific port, immutable identity, separate credential-v2 keys, at least 256-bit provisioning entropy; inspect encoded-key minimum where available and opaque-key handling. Historical verification must request stored version without currentVersion, survive current-version outage and never fall back after missing history. Key identity is signed, including when distinct identities alias material. |
| Strict seven-operation shape | CREATE has null target, exact provider/mode/options/name and borrowed secret; RENAME has target/revision/name; ROTATE target/revision/secret; ENABLE/DISABLE target/revision; ACCESS_CHECK/MODEL_DISCOVERY target/both revisions. Irrelevant fields must be absent/unrepresentable or rejected; even an empty extra secret is invalid. Invalid shape must precede key lookup. |
| Typed grammar | Explicit null/string/object tags, unsigned big-endian byte lengths/counts, schema field order, common domain/key/org/actor/op/target prefix and no outer wrapper are unambiguous. UUID variant/version and canonical decimal long bounds are explicit; zero is equality-valid, not authorization to mutate persisted state. Names preserve case/composition with strict Unicode and specified trimming. Inspect actual streaming bytes and operation switches. |
| Canonical metadata | Exact supported digestVersion, bounded key identity, canonical padded standard Base64/32 decoded bytes/44 characters; malformed metadata must fail INVALID_METADATA before key access. Inspect rejection of noncanonical padding bits, wrong alphabets/lengths and unknown versions. Internal metadata is not a public DTO or bearer credential. |
| Constant-time comparison | MATCH/MISMATCH follows successful fixed-length digest computation/comparison; missing/unusable historical key is KEY_UNAVAILABLE. Inspect actual comparison call and all early returns. Optional no-key comparison must reject incompatible digest/key versions. Functional tests alone cannot prove implementation timing behavior. |
| Redaction/error boundary | Fixed cause-free errors, redacted metadata/command toString, no secret-bearing command record, logging/network/database dependency or output callback. Inspect constructors, exception translation and dependency failures, not just happy-path counts. No universal external framework/log/VM privacy claim follows. |

No receipt namespace lookup/reread, authorization, preparation deadline/bounds, HTTP parsing, secret-manager adapter, transaction joining or database receipt writer is delivered by this primitive. The contract explicitly assigns those obligations to orchestration. Historical key retention and immutability remain provisioning obligations; a 32-byte encoding check does not prove entropy. These exclusions are not missing primitive features and must remain visible in final disposition.

### Independently executed oracle verification

QC ran `python3 scripts/provider-command-vectors.py --check`: **10 vectors matched**, without rewriting the fixture. QC additionally ran a separate inline decoder, without importing the oracle or production Java, that parsed every canonicalHex by tag/length, consumed the full byte stream, compared every decoded prefix/payload field to the fixture/schema, and recomputed each digest using Python standard-library hmac on the literal fixture bytes/key. All ten passed. This independently checks the generator's output framing/field layout, while sharing the standard-library HMAC primitive; it is not a second cryptographic implementation or a Java execution.

| Vector | Shape | Encoded bytes |
| --- | --- | ---: |
| v1-01 | CREATE OPENAI, empty options | 209 |
| v1-02 | CREATE ANTHROPIC, explicit null workspace | 232 |
| v1-03 | CREATE ANTHROPIC, non-null workspace | 246 |
| v1-04 | CREATE GOOGLE_GEMINI, empty options | 216 |
| v1-05 | RENAME, revision zero, decomposed/supplementary name | 201 |
| v1-06 | ROTATE, Long.MAX_VALUE revision | 227 |
| v1-07 | DISABLE | 190 |
| v1-08 | ENABLE | 189 |
| v1-09 | ACCESS_CHECK | 201 |
| v1-10 | MODEL_DISCOVERY | 204 |

CREATE name `Café 🌱` has 10 UTF-8 bytes; RENAME name `Á 🌱` has 8. The fixture distinguishes empty object versus Anthropic's one-field object/null and exact non-null string, operation identities, target null versus UUID, and fixed prefix/key identity. All seven operations and all three providers are present, with both Anthropic workspace profiles. Synthetic key/secret values and complete synthetic canonical bytes are appropriate in test-only oracle material; they are not deployment defaults or permission to expose runtime canonical secrets.

Reviewed SHA-256 identities:

| File | SHA-256 |
| --- | --- |
| `docs/engineering/PROVIDER_COMMAND_HMAC_CONTRACT.md` | `9bbe2b8a6f0229098daa52f5cbc10a86aef643b860346b27ccfe656fbb789a60` |
| `scripts/provider-command-vectors.py` | `7bd0d262f259e44937cda74a9499f0cec45ef3e12ab03e87377e558261e5cd14` |
| `backend/src/test/resources/providercommands/v1-vectors.json` | `4180fc6c6a4f9ffb5c20054a0f0e3b19104e561c1231e335bed77731202a9206` |

### Pending BE-ready review and evidence

Review actual production source and test assertions against the gates above, including every included-field mutation, operation-pair separation, normalization equivalence versus composition/case differences, invalid UUID/revision/Unicode/workspace/secret/metadata shapes, canonical framing, historical outage semantics, weak/null/unusable keys, constant-time call and cleanup/redaction. Confirm the existing CredentialBoundaryTest remains unchanged. Record findings and retests as new chronological checkpoints rather than overwriting this one.

Local `java -version` reports OpenJDK **17.0.20**. QC ran no Java compilation or tests at this checkpoint; no authored or passed Java test count is asserted. Any later selected Java17 compilation/test evidence must identify its exact source/classes/command and cannot establish Java21/full-application acceptance. Final Java21 CI requires independently fetched completed logs, exact source/tested-merge identity and actual suite counts, combined with actual source review. No API, receipt/DB atomicity, live-provider, vault, deployment or whole-system acceptance can follow from this primitive's vectors/test totals. No implementation edits or CI/remote writes were performed.

## Checkpoint 2 — provisional production source review before BE freeze

Date: 1 November 2026. Main explicitly authorized beginning read-only review of the now-present production source while tests remain in progress. Reviewed the complete `backend/src/main/java/com/agentos/providercommands/CommandBoundary.java`, SHA-256 `7c69cf4c2fd0aed5c06b1d9908614bf258f21baec7da52d783f81560c8cb238d`, against the checkpoint-1 contract. This is an in-progress source identity, not BE freeze. No test-source or final CI acceptance is asserted. Prior findings remain chronological above.

**Provisional disposition: one medium key-ownership issue requires resolution before acceptance; final disposition held until BE freeze and evidence review.**

### K1 — Medium: encoded-key cleanup assumes an unspecified ownership transfer

Actual source: `Keys` (lines 16–20) returns a general `java.security.Key` with no ownership documentation. `prepareVersion` obtains `encoded = key.getEncoded()`, checks its size, initializes the Mac from the same key, then unconditionally zeroes `encoded` in finally (lines 229–239). The contract specifies clearing *primitive-owned* buffers but does not declare that bytes returned from the port's key are fresh, exclusively owned disposable copies. Thus the implementation mutates dependency-returned key bytes without an explicit ownership guarantee.

Concrete source-derived failure scenario: a custom exportable Key returns its backing mutable 32-byte array from getEncoded and the port reuses that key/version. The finally block zeros that backing material after the first initialization (also after a rejected short key or initialization failure). A subsequent historical preparation under the same version can use changed/all-zero material, producing a different digest or failing, despite the contract's immutable-version obligation. With a provider that retains the same array rather than eagerly copying, impact could reach the first computation too; that provider behavior was not tested and is not required to establish the shared-array mutation. This is a correctness/ownership contract issue, not an observed secret disclosure or an assertion that standard key implementations return shared storage. No runtime reproducer has been executed by QC at this checkpoint.

Required resolution: explicitly require in the command-specific Keys port and normative ownership contract that each non-null getEncoded result is a fresh exclusively owned disposable copy, never shared with key/provider/other invocations, and may be erased by the boundary immediately after initialization or on failure. Null remains allowed for usable opaque managed keys. The key handle itself remains provider-owned; all JCA interactions must honor the immutable handle/material lifetime. Test the conforming adapter behavior, repeated preparations and historical verification, success/failure cleanup and preservation of provider-owned backing material. If arbitrary shared-buffer Key implementations are intended to be supported, change the implementation ownership strategy instead and document exactly whose returned buffer is borrowed versus erasable; simply cloning and then still erasing the borrowed original would not resolve the issue. A port precondition is a legitimate bounded resolution, but makes shared-buffer implementations explicitly unsupported rather than claiming to detect them dynamically. Main should route this to BE/contract ownership; QC edits no implementation.

### Source observations pending final test verification

| Area | Actual source assessment and limit |
| --- | --- |
| Borrowed command secret | validate reads only; secret streams visible ASCII characters directly as single bytes into Mac after tag/length. No secret String, retained char[] field, whole canonical byte stream or secret callback was found. Failure paths do not explicitly mutate the borrowed array. Thread-concurrent caller mutation is not prevented by this synchronous borrow contract. Runtime preservation/cleanup assertions remain to review. |
| Operation shape | Sealed Command plus final records make irrelevant fields unrepresentable. Constructors constrain provider/mode, workspace, target UUIDs, nonnegative revisions and the exact operation pairs. CREATE target is always null. validate requires secret only for CREATE/ROTATE and null otherwise, before key lookup. This implements the intended C1 interpretation; the contract wording note remains open until clarified. |
| Typed encoding | string/null/object tags, big-endian four-byte lengths, common prefix and operation-specific field order match the reviewed oracle by inspection. Anthropic emits exactly workspaceId, including explicit null; others emit empty object. ASCII secret length equals byte length. Name validation rejects retained unpaired surrogates before UTF-8 encoding; remaining strings originate from ASCII grammars, enum/UUID/long formatting or literals. Although getBytes itself is not a rejecting encoder, no accepted malformed-surrogate string route was found through these sealed validated inputs. Production-vector execution is still pending. |
| Historical key behavior | verify calls prepareVersion with stored.keyVersion and does not call currentVersion; no fallback path found. Current-version/key-resolution/initialization exceptions become fixed KEY_UNAVAILABLE. K1 must be resolved before claiming version stability across repeated calls. |
| Canonical metadata/comparison | Metadata checks domain/key grammar, 44-character Base64, 32 decoded bytes and exact re-encoding, clearing decoded bytes in finally. Final records prevent later mutation through public setters. compare rejects null/incompatible version identities, decodes both valid digests and invokes MessageDigest.isEqual on fixed 32-byte arrays, then clears both. No digest content equality shortcut found. This is source evidence for using the designated constant-time comparison primitive, not a timing measurement or proof of all JVM behavior. |
| Redaction and dependencies | Scope/command/metadata override toString with constants. Failure uses fixed code name, null cause, disabled suppression/stack trace. No logger, Spring bean, database/network collaborator, current configuration read, public DTO or default key was found. Dependency Errors rather than Exceptions are not swallowed; fatal JVM/error sanitization is not established or demanded by this assessment. Key handles and JCA state are not explicitly erased; their ownership/provider lifecycle must remain distinguished from owned buffers. |

Only this QC report was appended. No production/test/contract/oracle/workflow edits, Java executions, remote writes or CI retries were performed. Java17 local evidence and Java21 CI remain unexecuted by QC for this checkpoint. Final review must inspect frozen source plus actual test assertions, not infer correctness from the ten positive vectors or later aggregate counts. No API, receipt/DB atomicity, provider, vault, deployment or system acceptance is granted.

## Checkpoint 3 — C1 wording retest and test identity clarification

Date: 1 November 2026. Independently reread the contract's secret-shape sentence: “Operations other than CREATE and ROTATE require absent secret (null)”. **C1 resolved**: the wording now explicitly matches the operation table and the production validator reviewed in checkpoint 2. Updated contract SHA-256 is `f566cbbdbb2291300975820b8ee0430b85502d34ba874a24586621528b67502c`. Main reports no other semantic change; this bounded retest verifies the corrected sentence and does not certify an unseen full-file diff against the earlier untracked snapshot.

Main clarified the exact test class as `CommandBoundaryTest`; local path enumeration confirms `backend/src/test/java/com/agentos/providercommands/CommandBoundaryTest.java`. Its assertions/results have not yet been reviewed by QC. Production remains BE-owned and tests are still finalizing. **K1 remains open; final implementation/runtime disposition remains held until BE freeze.** Only this report was appended; no implementation or frozen PR42 report edits.


## Checkpoint 4 — final independent source/test review and K1 runtime retest

Date: 1 November 2026. Reviewed the complete final CommandBoundary.java and CommandBoundaryTest.java, normative contract, Python oracle, ten literal Java vectors/JSON fixture, requirements freeze and main-owned QC chronology. Baseline HEAD remains `d322e34ae94cd782c123acb061392ec1a082d238`; candidate files are untracked, so the hashes below identify this review rather than HEAD alone. Only this report was edited. No source, tests, contract, workflow or main-owned QC report was changed.

**Disposition: K1 resolved by explicit port precondition, conforming adapter tests and independent Java17 execution. No remaining blocking source/test defect identified in the bounded primitive. Final acceptance remains conditional on completed Java21 CI for the published candidate, with exact tested source/merge identity and independently inspected results.** No optional SA implementation approval was obtained or inferred; its usage-limit interruption is not an approval. The existing accepted requirements freeze is distinct from implementation acceptance.

### Final source findings and K1 resolution

The normative contract and Keys Javadoc now require each non-null getEncoded result to be a fresh disposable exclusively owned copy. Handles remain provider-owned and immutable per version; provider/JCA use must not rely on that copy's lifetime. Shared-backing implementations are explicitly unsupported, with no claim of automatic detection. This is the legitimate bounded contract resolution requested in checkpoint 2; the primitive's erasure strategy is unchanged.

ExportingKey clones its backing on every export; ExportingSecretKey implements SecretKey for successful JDK HMAC initialization. The test captures the boundary's first export separately from any provider-created exports, asserts its erasure, and asserts nonzero provider backing preservation after initial preparation, repeated explicit preparation, historical verification during currentVersion outage, rejection of a 31-byte key, and initialization failure with a non-SecretKey. Repeated digest metadata is equal and historical verification MATCHes. Borrowed secret contents remain intact. This test intentionally does not assert erasure of every JCA-internal export: those copies are outside the boundary's ownership guarantee. K1 is closed under this documented adapter precondition, not for arbitrary shared-array Key implementations.

Independent final source inspection confirms: sealed records exclude unrelated fields and contain no secret; constructors enforce UUID variant/version, revisions, provider/mode and workspace shapes. Validation rejects extra secrets before key lookup. Names trim the specified codepoints and reject retained controls, separators and unpaired surrogates; accepted names therefore cannot reach getBytes with malformed UTF-16. Common prefix and operation switches stream the exact tags, unsigned big-endian lengths, schema order, explicit null and options objects described in the contract. Visible ASCII secrets stream byte-by-byte without a secret String or complete canonical buffer. All boundary-owned encoded-key, UTF-8 and digest/comparison byte arrays have finally cleanup. No borrowed char[] mutation, retained secret, logger, network/DB collaborator, default key or production wiring was found.

Historical verify resolves only stored.keyVersion, never currentVersion or fallback. Metadata constructors enforce supported domain, key token and exact canonical padded 32-byte Base64; final records prevent subsequent mutation. Compatible comparisons decode two fixed 32-byte arrays and invoke MessageDigest.isEqual, including same-object inputs, without digest-content equality shortcuts. This is source confirmation of the designated comparison primitive, not a timing proof. Failures use fixed code names, null causes and disabled stack/suppression; key failures and computation failures remain distinct from successfully computed MISMATCH. Fatal Errors, caller-concurrent mutation and inaccessible VM/provider copies remain outside these bounded guarantees.

### Actual assertions reviewed — all 14 methods, 23 new invocations

The 13 ordinary methods plus one parameterized method with ten vectors yield 23 command invocations; loops below contain additional assertions, not additional JUnit invocations. Assessment is based on method bodies rather than that count.

| Method | Assertions inspected |
| --- | --- |
| allOperationsRoundTripAndDoNotMutateBorrowedSecret | All seven operations, MATCH, unchanged borrowed arrays, domain/44-character output; revision zero and Long.MAX_VALUE. |
| historicalVerificationNeverConsultsCurrentVersionOrFallsBack | Rotation/current outage preserves historical MATCH and requested version; historical resolution exception is KEY_UNAVAILABLE with no current lookup; secret preserved. |
| everyIncludedFieldChangesDigest | Organization, actor, CREATE name/secret, valid provider/mode changes, workspace null/string, aliased-material key identity; target/revision and paired operation changes for both revision and credential commands; credential revision; RENAME target/revision/name; ROTATE target/revision/secret. |
| namesNormalizeOnlyEdgesAndPreserveCaseCompositionAndSupplementaryCharacters | Specified trimming equivalence; composition/case inequality; 100/101 supplementary-codepoint boundary; empty/null/control/separator/unpaired-surrogate rejection. |
| invalidShapesFailBeforeKeyLookup | Missing/empty/invalid/oversized secret, null scope/command and extra secrets rejected with zero key calls; 4096 visible ASCII accepted. |
| constructorsRejectInvalidUuidRevisionProviderAndWorkspaceShapes | UUID variant/version/null, negative revisions, restricted operations, all provider/mode combinations, null provider/mode, forbidden workspace and workspace syntax/128-character bound. |
| metadataIsStrictAndComparisonRequiresMatchingVersions | Domain/key null/unknown/invalid bounds; malformed, unpadded, wrong-length and noncanonical-padding-bit Base64; compatible MATCH, null/incompatible comparator inputs, null stored metadata without key access. |
| unavailableInvalidAndWeakKeysProduceCauseFreeFailuresAndLeaveSecretUnchanged | Current lookup exception/invalid version, key exception/null/weak/unusable handle, invalid explicit version; fixed redacted failures and borrowed preservation. |
| commandScopeAndMetadataRenderingIsRedacted | Constant scope rendering and redacted rendering for every record type; no private name/target in output. |
| independentKnownAnswersAndCanonicalByteGrammar (10) | Literal Python-derived digest and canonical hex for all seven operations/four CREATE profiles; explicit independent test encoder and JCA recomputation over literal bytes; historical MATCH, org/actor/alias mutations and borrowed preservation on success/mismatch/null metadata. |
| framedBoundariesCannotShiftBetweenNameWorkspaceOrSecret | Name/secret and workspace/secret boundary shifts produce MISMATCH. |
| allNonsecretOperationsRejectBothEmptyAndNonemptyExtraSecrets | Every nonsecret operation rejects both extra-array shapes before either key-port call and preserves arrays. |
| opaqueKeysCanWorkAndUnexpectedComputationFailuresAreInternalAndRedacted | Test-only provider accepts null-encoding opaque key; update failure maps INTERNAL_FAILURE; getEncoded exception maps KEY_UNAVAILABLE; borrowed preservation and provider removal in finally. This does not establish a real vault adapter. |
| versionIdentityAndUuidBoundariesAndOwnedKeyCopyErasure | Accepted key-token limits and UUID versions 1–5; fresh-copy success/repeat/history erasure and backing preservation; weak-key and init-failure cleanup; borrowed preservation. |

Mode cannot change independently of provider in a valid CREATE, so valid pair mutations plus invalid cross-pair rejection are the appropriate tests. Literal UTF-8 hex includes multibyte/supplementary names, null versus object/string and byte lengths. Source review independently checked those production writes; the test-only encoder is not instrumentation of production output. Historical outage uses a throwing key port and proves no current fallback; it is not an external key-retention integration test. Invalid Metadata objects cannot survive their constructor, so malformed-metadata construction itself cannot perform key access. No exhaustive property testing or timing measurement is claimed.

### Independently executed evidence

OpenJDK 17.0.20. Initial `javac` invocation failed because no launcher was on PATH; the following compiler entry point succeeded with exit 0, compiling the actual reviewed source/tests into a separate temporary classes directory:

```sh
java -cp /tmp/agentos-command-tools/junit.jar com.sun.tools.javac.Main -d /tmp/agentos-qc-independent-classes backend/src/main/java/com/agentos/providercommands/CommandBoundary.java backend/src/main/java/com/agentos/providercredentials/CredentialBoundary.java backend/src/test/java/com/agentos/providercommands/CommandBoundaryTest.java backend/src/test/java/com/agentos/providercredentials/CredentialBoundaryTest.java
java -cp /tmp/agentos-command-tools/junit.jar:/tmp/agentos-qc-independent-classes org.junit.platform.console.ConsoleLauncher execute --select-class com.agentos.providercommands.CommandBoundaryTest --select-class com.agentos.providercredentials.CredentialBoundaryTest --details summary
java -cp /tmp/agentos-command-tools/junit.jar:/tmp/agentos-qc-independent-classes org.junit.platform.console.ConsoleLauncher execute --select-method com.agentos.providercommands.CommandBoundaryTest#versionIdentityAndUuidBoundariesAndOwnedKeyCopyErasure --details summary
```

Combined run: **54 found/started/successful, zero failed/skipped/aborted** (23 command plus 31 retained credential invocations). Separate K1 selection: **1 found/started/successful, zero failed/skipped/aborted**; this repeats an included invocation, not a 55th distinct test. An earlier console attempt after the missing-javac failure could not discover uncompiled classes and supplies no passing evidence. The successful runs above used independently compiled classes. `git diff --exit-code HEAD -- backend/src/test/java/com/agentos/providercredentials/CredentialBoundaryTest.java` returned 0: retained test unchanged. `python3 scripts/provider-command-vectors.py --check` again passed all ten fixture comparisons without rewriting them.

| Reviewed file | SHA-256 |
| --- | --- |
| CommandBoundary.java | `e271d42f7f489087847697a4821f59a5487869b716fdd57317d698683169893d` |
| CommandBoundaryTest.java | `0aaf0be0883c3e5e2809640557199fb2321cb7f6671fd482ba756a40683959fd` |
| PROVIDER_COMMAND_HMAC_CONTRACT.md | `be2e0b971d1214dd56ecd1648a85802e328e7447c621070248c55cf20d314f29` |
| provider-command-vectors.py | `7bd0d262f259e44937cda74a9499f0cec45ef3e12ab03e87377e558261e5cd14` |
| v1-vectors.json | `4180fc6c6a4f9ffb5c20054a0f0e3b19104e561c1231e335bed77731202a9206` |

Remaining acceptance gate: main must publish the reviewed candidate and obtain successful Java21 CI, then verify actual completed logs, exact tested source/merge identity, selected-suite counts and required Maven checks. The present Java21 workflow definition is intent, not execution evidence. Local isolated Java17 compilation/tests establish neither Java21/Maven application integration nor coverage-tool acceptance. No source fix is currently requested. No HTTP/receipt/database/vault/live-provider/UI, deployment, authorization or whole-system acceptance follows. Main retains ownership of fixes, CI and PROVIDER_COMMAND_HMAC_QC_REPORT.md.


## Checkpoint 5 — published PR43 and independently fetched focused Java21 logs

Date: 1 November 2026. Independently queried PR43 metadata, commit-associated workflow runs, focused job logs and source-to-tested-merge comparison through the GitHub connector. PR remains open/draft/unmerged. Source is `08c405cd1103e45674582eb50791f62866838f03`, base `d322e34ae94cd782c123acb061392ec1a082d238`, tested PR merge `f2def30629da8f012ca5d6ef7dd509ac2a6b7b91`. Checkout logs explicitly identify that merge and its source/base. GitHub comparison from source to tested merge reports one merge commit ahead and **zero changed files**, corroborating identical file contents. Local HEAD is the published source and production/test SHA-256 values still match checkpoint 4. Main reports tree `c5219d4c6fd092c7181fb4028aabbd25f1fab85d`; that tree identifier is not independently established by this checkpoint's comparison response.

[Focused run 41295516530](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516530), job [124815441098](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516530/job/124815441098): workflow independently observed **completed/success**. Actual decoded logs show Temurin **21.0.13+9**, production/test compilation at release 21, ten oracle vectors matching, Google import check passing, Spotless 254 files clean, and Maven `-Dtest=CommandBoundaryTest,CredentialBoundaryTest verify`. Surefire reports **31 CredentialBoundaryTest + 23 CommandBoundaryTest = 54 tests, failures 0, errors 0, skipped 0**. Maven reports **BUILD SUCCESS at 10:00:51 UTC**; JaCoCo report generation analyzed 374 classes (no coverage percentage or threshold acceptance inferred). Evidence artifact upload completed, artifact ID `13078168971`. This independently corroborates the reported Java21 focused result; counts alone do not replace checkpoint 4's actual assertion/source review.

At this observation, [full Code quality 41295516469](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516469), job `124815440949`, remains in progress. Its decoded-log download returned transient BlobNotFound while running; no full-suite count or acceptance is inferred. [Docker 41295516495](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516495), compose-smoke job `124815441303`, also remains in progress; formatting job `124815441080` succeeded, which does not establish compose-smoke acceptance. **Docker remains explicitly pending until completed evidence is inspected.** Full Quality evidence will be appended separately when available; there is no source change requested and no merge/deploy or expanded primitive/system acceptance.


## Checkpoint 6 — independently fetched completed full Quality evidence

Date: 1 November 2026. Independently observed [Code quality run 41295516469](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516469) **completed/success**, then fetched the actual decoded logs for [verify job 124815440949](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516469/job/124815440949). Checkout explicitly records tested merge `f2def30629da8f012ca5d6ef7dd509ac2a6b7b91`, merging source `08c405cd1103e45674582eb50791f62866838f03` into base `d322e34ae94cd782c123acb061392ec1a082d238`. Local `git rev-parse HEAD^{tree}` independently confirms source tree `c5219d4c6fd092c7181fb4028aabbd25f1fab85d`, resolving checkpoint 5's tree-ID evidence limitation. Source-to-merge zero-file-diff evidence remains as recorded there.

Actual logs show Temurin **21.0.13+9**, Google import check passed, Spotless **254 clean files**, and the full workflow Maven verify selector (`*Test` plus the nine explicitly named PostgreSQL IT suites, including ProviderConnectionPostgresIT). Independently summed **57 per-suite result rows: 1,183 tests, failures 0, errors 0, skipped 0**, exactly matching the aggregate Surefire result. Included rows explicitly report **CommandBoundaryTest 23/0/0/0**, **CredentialBoundaryTest 31/0/0/0**, and **ProviderConnectionPostgresIT 69/0/0/0**. These included results are not additional distinct tests to add to 1,183. Maven reports **BUILD SUCCESS at 10:02:39 UTC**, with JaCoCo report generation; no uninspected coverage percentage/threshold is claimed.

Frontend logs show npm ci completed, dependency audit **zero vulnerabilities**, ESLint with max-warnings 0, Prettier success, tooling/auth/validation harness success, and Angular application bundle generation completed at **10:03:12 UTC**. The chained workflow step succeeded through build; these script assertions are not recounted as JUnit cases. Quality evidence artifact upload finalized, ID `13079146696`. PostgreSQL cleanup logs contain constraint/injected-failure diagnostics; these are not Surefire failures and do not alter the observed 1,183/0/0/0 result. This checkpoint reports executed regression evidence, not a fresh source acceptance of every unrelated feature.

**Updated disposition: independent source/test review, K1 retest, local Java17, focused Java21 and full Java21 Quality gates are satisfied for the identified source/tested merge. No remaining blocker identified within the command primitive's reviewed scope. Docker compose-smoke run 41295516495/job 124815441303 remains explicitly PENDING; no completed Docker acceptance is asserted and this review does not wait for that longer run.** No source edits, remote comments/approvals, CI retries, merge or deployment were performed. Only this review was appended by QC; concurrent main-owned documentation changes are outside this review's edits. SA implementation approval remains unclaimed; HTTP/receipt/DB/vault/provider/UI/system exclusions remain unchanged.

## Checkpoint 7 — independent PR43 follow-up on documentation source and outstanding Docker runs

Observation: 1 November 2026, approximately **10:39 UTC**. Read-only GitHub connector queries independently inspected the four specified workflow runs and their latest-attempt jobs, plus decoded logs for the two completed latest-source jobs. This checkpoint appends evidence only; prior source/assertion review and K1 closure remain as recorded above.

### Source and tested-merge identity

All three latest-source runs identify source `e40618560d29aea8337e83a9b72aec5f77e883b8`. GitHub comparison from prior C1 source `08c405cd1103e45674582eb50791f62866838f03` to this source reports one commit ahead and exactly four changed documentation files: `IMPLEMENTATION_STATUS.md`, `README.md`, `docs/qc/PROVIDER_COMMAND_HMAC_QC_REPORT.md`, and this review. No production source, tests, fixtures or workflows changed in that comparison. Both completed jobs' checkout logs identify tested merge `5a41e2ccdaa1b8c918d1a922cb96224b3aa49c2e`, merging the latest source into base `d322e34ae94cd782c123acb061392ec1a082d238`. Independent source-to-tested-merge comparison reports one merge commit ahead and **zero changed files**, with tree `d7c9e3080b571842b5ec394aad2994a71573f3f5` for both source and tested merge. The completed evidence therefore carries forward the bounded reviewed primitive disposition without a new source-review claim.

### Completed latest-source evidence

| Gate | Independently observed result and actual decoded-log evidence |
| --- | --- |
| [Focused 41295873869](https://github.com/giangnguyen3260/agent_os/actions/runs/41295873869), [job 124816504920](https://github.com/giangnguyen3260/agent_os/actions/runs/41295873869/job/124816504920) | **Completed/success**. Temurin **21.0.13+9**, ten independent oracle vectors match, Google import check passed, Spotless **254 clean files**. Maven selected `CommandBoundaryTest,CredentialBoundaryTest` verify; two suite rows independently sum to **54 tests: 23 command + 31 credential, failures 0, errors 0, skipped 0**, matching the aggregate. **BUILD SUCCESS**, finished **10:05:41 UTC**. Evidence artifact finalized, ID `13078874871`. |
| [Quality 41295873883](https://github.com/giangnguyen3260/agent_os/actions/runs/41295873883), [job 124816504200](https://github.com/giangnguyen3260/agent_os/actions/runs/41295873883/job/124816504200) | **Completed/success**. Temurin **21.0.13+9**, Google import check passed, Spotless **254 clean files**. Independently summed **57 suite rows: 1,183 tests, failures 0, errors 0, skipped 0**, matching the aggregate; included rows explicitly show command **23**, credential **31**, ProviderConnectionPostgresIT **69**, each with zero failures/errors/skips. **BUILD SUCCESS**, finished **10:07:35 UTC**. Frontend dependency audit reports zero vulnerabilities, Prettier passed, production harness pass messages are present, and Angular bundle generation completed **10:08:11 UTC**; the frontend style/type/build step completed successfully. Quality evidence artifact finalized, ID `13079118994`. |

The focused cases are included in full Quality, not additional distinct cases to add to 1,183. No new coverage percentage, threshold or timing guarantee is inferred.

### Docker remains pending — no completed acceptance

| Source / run | Latest observed job state |
| --- | --- |
| Prior C1 `08c405cd1103e45674582eb50791f62866838f03`, [Docker 41295516495](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516495), [compose-smoke job 124815441303](https://github.com/giangnguyen3260/agent_os/actions/runs/41295516495/job/124815441303) | Run and compose-smoke job **in_progress**, conclusion **null**. Step 78, `Retained real backend browser regression`, is in progress; later steps remain pending. Formatting job `124815441080` completed successfully, which does not establish compose-smoke success. **PENDING**. |
| Latest source `e40618560d29aea8337e83a9b72aec5f77e883b8`, [Docker 41295873892](https://github.com/giangnguyen3260/agent_os/actions/runs/41295873892), [compose-smoke job 124816504604](https://github.com/giangnguyen3260/agent_os/actions/runs/41295873892/job/124816504604) | Run and compose-smoke job **in_progress**, conclusion **null**. Step 78, `Retained real backend browser regression`, is in progress; later steps remain pending. Formatting job `124816504311` completed successfully, which does not establish compose-smoke success. **PENDING**. |

No polling wait, rerun, cancellation or Docker pass claim was made. Step summaries and uploaded visual-evidence labels do not constitute independent visual inspection; **no new visual review was performed**. K1 remains closed under the previously reviewed ownership precondition and retest. Prior C1 focused/full Quality acceptance remains intact; latest-source focused/full Quality are independently corroborated above, while both Docker gates remain pending. This evidence does not extend acceptance to the next receipt contract, HTTP/receipt atomicity, database/vault/live-provider integration, deployment or whole-system behavior. Only this review file was edited; no source edits, remote comments/approvals, merge or deployment were performed.
