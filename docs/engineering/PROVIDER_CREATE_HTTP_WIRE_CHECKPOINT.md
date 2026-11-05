# Create header validation candidate

ProviderCreateRequest validates only the already authenticated/authorized and
session-CSRF-verified command request's header/wire structure. It returns the
canonical Idempotency-Key UUID without reading the body, obtaining a session,
writing admission or accessing keys. It is not a controller or security filter.

POST/no-query, canonical org/key, one JSON Content-Type with optional UTF-8,
one configured CSRF header and one Idempotency-Key are required. Nonidentity
content encodings, duplicate headers, control-line injection and ambiguous
length/transfer framing fail. Chunked input is permitted only without declared
length; actual bytes still require ProviderCreateBody's independent8192-byte cap.
A declared length is never trusted as actual size. Accept uses the extracted
unchanged provider GET negotiation implementation, including q specificity.

The shared ProviderAccept extraction preserves existing GET behavior. Existing
GET security chains/routes remain unchanged; in particular their disabled CSRF
configuration must never be repurposed to accept commands. Header shape is not
CSRF verification: future POST security must validate the actual session token
and current ADMIN authority before invoking this helper or consuming the body.
The CSRF header name must come from trusted session configuration.

Authored tests cover accepted JSON/UTF-8/chunked/max-length forms,22 malformed
request variations, invalid scope/configuration and zero body consumption.
The focused completion workflow explicitly selects the new test class; native
transport/security and full runtime evidence remain separate. Google formatting,
shell/YAML checks do not establish test execution.

Public command security/controller wiring, error/receipt DTOs,429 Retry-After,
token provisioning/reconciliation lifecycle and responsive create form remain
before activation. No POST route, automatic production bean, merge or deployment
is added by this candidate.
