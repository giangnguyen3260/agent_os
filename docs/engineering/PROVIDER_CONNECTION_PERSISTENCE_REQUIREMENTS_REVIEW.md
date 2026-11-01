# Provider connection persistence requirements review

Date: 1 November 2026. Independent BA/SA review of `docs/engineering/PROVIDER_CONNECTION_PERSISTENCE_CONTRACT.md` only. Contract line references below refer to the reviewed text. No implementation, V022, PR41 source, or test execution was reviewed; code-source review is deferred until freeze. The stated PR41 closure is a compatibility baseline supplied by the contract, not independently reverified here.

**Disposition: scope accepted; requirements changes needed before acceptance of the database writer.** These findings concern enforceable behavior and evidence, not a demand for a larger feature. HTTP/API, public receipts, vault adapters, provider calls, and dispatch are deliberately outside this primitive. Their absence is not a stub finding. The latest database-one-way constraint governs this slice; no request to restore reversible storage or the superseded full PR40 boundary is implied.

## Blocking requirements gaps

### R1 — Make PR41 compatibility and the storage allowlist executable

References: lines 7, 9, 13, 15.

The contract requires exact typed PR41 values and explicit HMAC domain v2, but does not enumerate the accepted metadata fields, representations, bounds, or their correspondence to database columns. `mode`, `options`, and the strict workspace grammar are also unspecified. In particular, unconstrained options or an arbitrary locator string can defeat the stated one-way allowlist even though they are named allowed fields. “Typed” alone does not establish safe content; the contract correctly acknowledges that provenance cannot be cryptographically established here.

Needed change: specify a field-to-column mapping against the existing PR41 metadata contract, including locator/version grammar and limits, supported modes, workspace grammar, canonical digest decoding/length/re-encoding rules, HMAC key-version and domain-version validation, and scope/provider/revision equality rules. Reuse the verified PR41 representation without silently introducing a new verifier or canonicalization scheme. Define options as an explicit bounded typed allowlist (or disallow them for this slice), including create/default behavior. Specify name normalization precisely so NO_CHANGE has a deterministic meaning. Reject unsupported fields and representations before any mutation. Define safe event field values as well as field names. State that diagnostics and persistence errors must not echo rejected payloads or metadata verifiers.

Acceptance evidence: valid PR41 prepared metadata persists and reloads unchanged for all providers; unsupported modes/options, malformed references/digests/versions/workspaces, and mismatched scope/provider/revision are rejected without table changes. No network or key-service validation is required or permitted here.

### R2 — Close the authorization, lock-order, and transaction-joining contract

References: lines 11, 21.

“Organization first” and “consistent ordering” do not specify the complete lock sequence or show compatibility with account disable, credential rotation, membership removal/demotion, and future outer transactions. REQUIRED propagation alone does not ensure one transaction manager/connection, suitable isolation, or safe ordering if the caller already holds locks. “After waits” also needs an observable rule for a caller that waited while its authority changed. Read snapshot consistency must include authority, count, and returned rows, rather than separate snapshots that can disagree.

Needed change: name the exact authoritative identity/credential and membership checks, ordered lock targets, and any multiple-row ordering. Define the lock protocol that all competing authority writers must share, or explicitly identify compatibility verification as a freeze gate. State the allowed outer transaction preconditions, transaction manager/data source and isolation requirements, and prohibition on independently committed inner mutations. Clarify whether the method returns provisional success until the caller commits. Define how rollback propagates for domain errors, SQL/event failures, and caller failure. Specify authorization-before-resource-disclosure and the precedence of 401/403/404 checks, including foreign IDs. Define read snapshot/isolation behavior and the authority linearization point; do not promise that a snapshot can predict a later revocation.

Acceptance evidence: native PostgreSQL contention tests for disable/credential rotation/demotion/removal against a waiting operation, demonstrating the declared serialization outcome; no stale authority admitted after the required recheck. A caller-owned transaction must roll back both this mutation and a test caller marker, including when the caller fails after the method returns. No production receipt wrapper is needed to prove joining. Verify the actual authority writers' lock compatibility only at source freeze.

### R3 — Specify a database-enforced current-version relation and retention lifecycle

References: lines 7, 13, 15, 17.

“Every active connection” leaves disabled connections ambiguous, although enable does not prepare a credential and retained current metadata must remain valid. The text does not explicitly require the composite foreign keys/unique keys that prevent a current pointer from selecting another tenant, connection, provider, or credential revision. Application validation alone is insufficient for the stated database invariant. Parent and version references can also form an insertion cycle that requires an intentional migration/transaction strategy.

Needed change: require an exact current credential relation for every persisted connection, including DISABLED, and define matching composite candidate keys and foreign keys in both directions where needed for version ownership. Define nullability, positive revision constraints, delete/update behavior, and how immutable retained versions and events are protected within the supported writer privileges. Specify a create/replace insertion strategy that leaves no invalid committed parent and works with the declared PostgreSQL and H2 test coverage; document any constraint-timing differences. Retention must survive rename, disable/enable, replacement, and failures without cascading historical deletion. Define whether historical access is a separate internal operation and give it the same explicit authorization and stored-row validation rules; otherwise do not imply an unspecified read method exists.

Acceptance evidence: native PostgreSQL rejects cross-tenant/provider/connection/current-revision pointers, dangling current references, and invalid disabled rows. Replacement retains exact old version rows, advances only the current pointer, and failed create/replace commits neither partial parent nor version nor event. Test an orphan/missing version with an outer join or equivalent selection strategy: an inner join must not silently hide corruption from list validation.

### R4 — Define CAS outcomes and atomic failure behavior precisely

References: lines 13, 15, 21, 23.

The contract names revision errors and safe duplicate create but leaves outcome precedence, duplicate-ID error semantics, create-event behavior, and the full rollback surface open. “State-changing success” can be read as excluding rename or credential replacement, despite the apparent requirement for one event per mutation.

Needed change: specify expected configuration revision for every update; define stale-revision versus NO_CHANGE precedence and overflow checks for each independently bounded revision. Define the duplicate-ID domain result without leaking foreign resource existence. Explicitly require one event for create, rename, replace, disable, and enable; no event for any rejected operation. State that failed updates, insert/constraint errors, event failure, and outer rollback preserve every preexisting value, timestamp, version, and event, with no committed new rows. Distinguish definite rejection from uncertain commit without promising receipt recovery here. All mutation and failure paths must remain local: no remote compensation, cleanup, verifier lookup, or secret preparation while joining or owning a transaction.

Acceptance evidence: same-revision competing PostgreSQL updates yield exactly one committed success and one declared conflict, with exact revisions and event counts. Exercise stale plus no-op, each revision limit, duplicate create, and event failure after credential insertion. Demonstrate both create and replacement rollback and caller-owned rollback. Inspect all affected rows, not only the returned object.

## Acceptance evidence must match the claims

Reference: lines 27–29. These are future acceptance requirements, not evidence of passing tests. No claim of actual H2/PostgreSQL coverage, unchanged 31 tests, or absence of stored secrets is verified by this document.

At freeze, provide a compact requirement-to-test inventory with executed command, database/engine, result, and the rows/tables asserted. Minimum evidence:

| Claim | Required observable check |
| --- | --- |
| All providers and exact metadata | Persist, reload through a fresh transaction/connection, and compare all allowed columns, explicit domain version, and immutable fields for each provider and supported mode. |
| All-row one-way storage | Assert schema allowlist and inspect every row/column of all three new tables after success, replacement, rejection, and rollback; compare against the complete expected row sets. Search for test sentinel plaintext and reversible test payloads as a supplementary check, not the sole proof. Include options, events, and historical versions. |
| No collateral writes | Capture and compare contents of existing application tables, including authority/session/history tables, rather than checking only new-table counts. Use controlled fixtures so unrelated concurrent activity cannot invalidate the comparison. |
| Current and retained relations | Check old rows unchanged, exact new row and current relation, disabled relation, direct FK rejection, and missing/corrupt selected-row failure. |
| Scoped list | Test mixed tenants, more than 20 matching rows, equal timestamps with ID tie-breaks, count/content snapshot behavior, invalid pages, out-of-range totals, and corruption in a selected non-first row. Clarify that unselected rows are outside the selected-row validation promise. |
| Authority and CAS | Exercise actual independent PostgreSQL transactions with coordinated contention and the declared authority/lock outcomes, rather than sequential mocks or H2-only concurrency. |
| Atomicity and joining | Verify exact before/after row sets after forced event failure, constraint failure, losing CAS, and outer caller rollback; a successful inner return must not establish durability. |
| No network in persistence | Make boundary/key-service/vault/provider access fail if invoked by persistence operations, including reads and failure paths. Prepared fixtures may be made before transactions. Combine this with source inspection at freeze; a test double alone cannot establish absence of all network paths. |
| Migration and regression | Execute actual Flyway migration history against native PostgreSQL, document H2 coverage and differences, and report the existing 31 credential-boundary tests without replacing or weakening them. |

“No sentinel” cannot by itself prove absence of arbitrary secrets, and a handful of field assertions cannot support an all-row claim. Equally, test fixture secrets are not live provider success evidence. Keep Sonar paused and retain the existing no-merge/no-deploy and no-full-system-acceptance boundaries.

## Freeze handoff

Resolve R1–R4 in the contract or an explicitly linked normative specification before declaring requirements complete. At source freeze, separately inspect the writer, V022 constraints, actual authority lock interactions, transaction configuration, and executed tests against these requirements. This review makes no finding about unfinished code and authorizes no code edits, additional agents, merge, or deployment.


## Requirements retest — normative R1–R4 clarifications

Date: 1 November 2026. Re-reviewed only the contract above, including its appended “Normative clarifications after independent R1–R4 review.” The original findings and disposition remain unchanged above as review history. This disposition supersedes the original requirements-blocked status for the clarified contract; the normative appendix controls the earlier shorthand where it is more specific.

**Updated disposition: R1–R4 resolved at requirements level; bounded persistence contract accepted for implementation and freeze verification. No remaining requirements blocker identified in this document-only retest.** This is not implementation, migration, test, or full-system acceptance.

| Finding | Retest disposition and basis |
| --- | --- |
| R1 — Metadata compatibility and allowlist | Resolved. The appendix names PR41 Scope/Reference/Metadata mappings, literal domain v2, reference/version grammars, canonical 32-byte digest encoding, provider/mode pairs, workspace grammar, exact name handling, and safe diagnostics. Arbitrary options JSON is excluded. Trusted preparation remains an explicit prerequisite; grammar validation is not claimed to prove provenance or semantic absence of secrets. Actual compatibility with the verified PR41 representation remains a source-freeze check. |
| R2 — Authority, locks, joining | Resolved as requirements. Account enabled/version and same-org ADMIN checks, authorization before resource disclosure, ordered write locks and rechecks, actual isolation validation, existing manager/data-source joining, provisional return, and rollback-only failures are explicit. Readers require one compatible REPEATABLE_READ snapshot with a stated authority linearization point. Compatibility with existing authority writers is explicitly a freeze gate, not an assertion already verified. A future receipt transaction must satisfy these same write-transaction and lock preconditions. |
| R3 — Current relation and retention | Resolved with the deliberate immediate-FK design. Both enabled and disabled parents require the exact composite current-version FK; version-first atomic creation/replacement avoids a cyclic reverse FK. Unreferenced-version parent existence is deliberately a service invariant, not a database guarantee. Parent-first authorized version access, retained immutable rows, no supported deletion/cascade, and complete selected-parent validation define the supported boundary. The earlier request for reverse ownership FKs is satisfied by this explicitly narrower ownership guarantee; a reverse FK is not a condition of acceptance. |
| R4 — CAS, events, rollback | Resolved. Expected configuration revision precedes no-change and overflow checks; rotation checks the next credential revision and both ceilings. Duplicate-ID conflict, one event per successful named operation, zero events on rejection, safe after-snapshots, exact rollback preservation, and uncertain-commit behavior are explicit. No retry/compensation or remote work is introduced into the primitive. |

The all-row evidence requirement is also addressed normatively: the appendix requires complete new-table schema/row comparisons, preservation of existing application tables under controlled fixtures, actual PostgreSQL contention and observed-wait authority-loss tests, FK rejection, retained versions, both counter ceilings, create/rotate event-failure rollback, and joined outer rollback. These are required tests, not reported passing results. The original evidence inventory remains the freeze acceptance checklist, interpreted using the clarified guarantees: options are excluded; historical access is not a new API; unreferenced-version ownership is service-enforced; selected corruption checks do not claim a scan of unselected rows.

At freeze, verify the actual transaction manager/data source/isolation checks, authority-writer lock compatibility, immediate composite FK and insertion order, supported writer behavior, exact PR41 mapping, complete row assertions, native PostgreSQL/Flyway execution, and preservation of the existing 31 credential-boundary tests. A failing or absent implementation check can reopen the corresponding finding; none was executed or inferred here. No unfinished source files were reviewed. HTTP/API/receipt wrappers, vault adapters, provider calls, and broader token/session storage remain outside this accepted bounded primitive. No merge or deployment acceptance is granted.
