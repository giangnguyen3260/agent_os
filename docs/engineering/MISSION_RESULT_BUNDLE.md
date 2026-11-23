# Completed Mission result download

An ADMIN can download the complete verified results from the full-graph completion page after the explicit REVIEW → COMPLETED transition. The link pins the displayed manifest SHA; rendering the link performs no lifecycle command or provider call.

`GET /api/v1/organizations/{org}/teams/{team}/missions/{mission}/graph-completion/bundle?manifestSha256={sha}` uses the existing opt-in graph-completion route. Each request rechecks current authority, exact scope, completion receipt, current accepted outputs, settlement and provenance in a repeatable-read transaction. A stale manifest or invalid output rejects the entire request.

The ZIP contains `manifest.json` with exactly the canonical reviewed manifest bytes and `outputs/{runUUID}-{attemptUUID}.txt` for every output in that manifest. SHA-256 of each UTF-8 output must match its manifest entry. Titles and output text never form archive paths. Metadata retains the existing manifest contract; the archive does not grant public access or link sharing.

The manifest is limited to 1 MiB, each output to 256 KiB, and total uncompressed content to 8 MiB. Validation completes before the attachment response is built; an oversized or inconsistent set is not partially exported. The archive is buffered, so this is a bounded text-result feature rather than a large artifact streaming service. Responses use `application/zip`, attachment disposition, no-store and nosniff; `X-AgentOS-Manifest-SHA256` pins the manifest and `X-AgentOS-Bundle-SHA256` hashes the returned archive bytes.

Validation covers full diamond output membership and hashes, response hash/header integrity, rejection before completion, stale manifest, tampered output and revoked membership. Browser coverage checks completed/unblocked visibility, exact scoped manifest URL, no implicit lifecycle command and removal after denial. Database cases are inherited by the required native PostgreSQL wrapper. Boundary/overflow fixture coverage remains a follow-up; limits are enforced in the service. Native CI and actual paid-provider evidence are recorded separately.
