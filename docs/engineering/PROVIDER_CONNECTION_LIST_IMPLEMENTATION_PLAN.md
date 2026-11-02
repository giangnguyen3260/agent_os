# Provider connection list implementation plan

Date: 2 November 2026. Base: c48414ae6b592eec93fb541a825e7b3fe537f321 (PR46). This is implementation preparation, not a delivered public endpoint or a native runtime pass.

## Verified source gaps

V022 retains scoped connections, immutable credential-reference/HMAC versions and configuration events. ProviderConnectionService.list already uses RR and fixed20 pagination but returns an internal JsonIgnoreType projection carrying Metadata. It must never be exposed directly or serialized as the public Connection DTO. Its credential mapper can reject malformed selected credential metadata; the public list must distinguish invalid connection configuration (safe503) from unavailable credential storage (UNAVAILABLE/nullmask), preserving readable safe connection metadata.

V024 supplies durable deployment policy and the shared actor/org read-admission row. There is no durable key-health observation or sticky credential-integrity evidence store. No production secret-manager/key-health observer is configured. Therefore PRESENT cannot be inferred from existence of an external locator, HMAC verifier, enabled flag or successful options request. No fixed successful credential health or mocked production adapter is acceptable.

## Ordered implementation work

1. Freeze the list/observation contract and independent QC review against the storage amendment. No new Figma design is needed for this backend-only phase; existing list frames remain the later FE reference.
2. Add empty observation storage with scoped immutable-version FKs, exact constraints, bounded database-clock validity and sticky failure evidence. Trusted publication accepts safe typed evidence; it never obtains network evidence under locks. Missing observation means unavailable. Do not introduce a fake observer or a successful startup seed.
3. Implement pure, allowlisted public projection in one RR/read-only snapshot. Capture one statement-level database clock sample for all row expiry checks. Count/page rows and current ADMIN/credential-version checks belong to that snapshot. Invalid selected metadata is never repaired by GET.
4. Extend narrowly scoped provider read transport to the exact collection path, reusing PR46 admission namespace and existing human session repository. Preserve options behavior and all unrelated global security/CSRF routes. Authentication precedes method, then admission, strict wire shape and projection. No controller transaction may combine the admission commit with the RR business read.
5. GET collection supports only absent query or canonical page integer0..100000, fixed20 pages; stable createdAt DESC,id DESC. Explicitly test duplicate/unknown/encoded/empty query forms. Reuse strict body/Accept/UUID/no-store/error rules; do not accidentally route detail or commands into this slice.
6. Execute core H2 and mandatory native PostgreSQL tests, actual-cookie HTTP tests, policy/provider-disabled readability, observed expiry/rotation/revocation races, full stored-row no-business-write assertions and combined options/list120-slot quota. Add a disposable Docker list probe before retained regressions. Only completed logs establish acceptance.

## Ownership and release boundary

BA/SA owns frozen requirements; BE owns migration, trusted evidence writer and list projection/transport; QC reviews schema and safe DTO/error/type assertions independently. FE follows only after the public API and actual persisted observations are accepted. No connection creation/rotation HTTP, provider checks/model discovery, runtime grant, secret-manager deployment or UI completion is claimed here. No merge/deploy. Sonar remains paused. Existing session/CSRF one-way-storage audit remains open; this slice does not certify every framework token.

## Requirements checkpoint

The [read contract](PROVIDER_CONNECTION_LIST_READ_CONTRACT.md) now freezes the public
surface and evidence rules. Independent [QC review](../qc/PROVIDER_CONNECTION_LIST_REQUIREMENTS_REVIEW.md)
closes B1/B2/B3 as requirements issues. This acceptance is documentation-only.
Next implementation prerequisite is the concrete forward SQL/privilege layout,
trusted publisher/provenance mechanism and reviewed read-service boundary. This
is engineering work to complete, not a request for the user to approve a fake
health default. No endpoint, migration or runtime pass is delivered by this branch.
