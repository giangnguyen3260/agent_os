# Provider command HMAC QC chronology

Status: implementation candidate in progress, 1 November 2026. Based on PR42 closure d322e34ae94cd782c123acb061392ec1a082d238. This slice is an internal equality-verification primitive, not an HTTP route, receipt writer, external key/vault adapter or deployed feature.

The [contract](../engineering/PROVIDER_COMMAND_HMAC_CONTRACT.md) freezes byte grammar, seven exact operation shapes, command-specific historical key identities, borrowed secret ownership and cause-free error categories. [Independent BA/SA review](../engineering/PROVIDER_COMMAND_HMAC_REQUIREMENTS_REVIEW.md) accepted the bounded requirements; that does not establish runtime/source acceptance.

Independent Python hmac/hashlib oracle scripts/provider-command-vectors.py produces ten synthetic fixtures in backend/src/test/resources/providercommands/v1-vectors.json, covering all seven operations and four CREATE provider/options profiles. --check matched the committed fixture. These are known-answer vectors, not ten executed Java tests. No real credential/key or production default is supplied by this fixture.

Local runtime is Java17 with no Maven/Docker command. Published-checksum-verified GoogleJavaFormat1.22 and JUnit console1.10.5 support isolated compilation/testing; Java21 CI remains authoritative for project integration. Actual execution results and source findings will be appended when available. Prior PR42 counts do not transfer to this new candidate. Sonar paused; no merge/deploy.

## Local candidate execution and K1 correction

Production/test files freeze an internal JDK-only CommandBoundary and14 test methods, yielding23 invocations (13 ordinary+10 parameterized oracle vectors). The coordinator independently compiled those sources plus unchanged CredentialBoundary/CredentialBoundaryTest on Java17 using explicit JAR classpaths and executed54 tests:54 successful,0 failures/skips/aborts. GoogleJavaFormat1.22, import rules, oracle --check and diff checks passed. This is selected unit execution, not Java21/project/HTTP acceptance. The initial local compiler-launcher wildcard classpath was corrected to explicit paths before the successful run; no production fix was needed for that harness error.

QC K1 identified an unspecified ownership assumption for Key.getEncoded() cleanup. The normative contract and Keys port now require fresh exclusively owned disposable exports, immutable provider-owned handles and no shared backing arrays. The test uses conforming export copies: repeated preparation and historical verification preserve backing material; boundary-owned exports are erased on success, weak-key rejection and initialization failure. Provider/JCA-owned copies retain their own lifecycle; no universal key-erasure claim is made. The complete54 selected tests passed again after this change. Final independent K1 source/test disposition and Java21 CI are still separate gates.

Initial BA/SA requirements freeze accepted the six original encoding/ownership gaps. An optional later implementation-coverage pass did not complete; it is not claimed. Independent source QC, including the later K1 clarification, is tracked in [review](PROVIDER_COMMAND_HMAC_REVIEW.md).

Independent frozen-source review has now completed: all14 methods/23 command invocations were inspected, selected54 Java17 tests and a separate K1 repeated/historical/success/failure export-copy retest passed independently. K1 is resolved under the documented port precondition; no remaining source/test blocker was identified. This is not a Java21 CI result or SA implementation approval. See the appended independent review for exact source fingerprints and execution limits.
