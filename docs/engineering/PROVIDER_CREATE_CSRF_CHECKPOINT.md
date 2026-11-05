# Header-only provider CSRF handler candidate

ProviderCsrfHandler is an explicit CsrfTokenRequestAttributeHandler specialization
for a future isolated provider POST security chain. It resolves only one valid
configured token header and never invokes form-parameter fallback. Spring
CsrfFilter and the session token repository still perform actual token matching;
this handler alone is not an authorization or CSRF verification service.

Tests execute the real CsrfFilter/HttpSessionCsrfTokenRepository with owned mock
sessions: correct session token passes; missing/wrong/duplicate/folded/control/
whitespace token forms and a token belonging to another session yield403 without
reaching the command. Request input-stream and form-parameter access throw during
these tests to detect credential-body parsing before CSRF verification. This is
filter-level verification, not real-login HTTP acceptance.

No current SecurityFilterChain is modified. The provider GET chain's disabled
CSRF setting must never be reused for writes. Future command routing needs an
isolated chain with current human/ADMIN checks before CSRF, fixed no-store JSON
failures, header-only actual session verification, method/header/body gates and
configured command dependencies. Unknown callers must not receive fresh sessions
or token metadata as an authorization workaround.

The selected focused workflow includes these three methods alongside header,
credential, command and native completion tests. Authored assertions/formatting
are not passes until matching CI logs complete. Public POST routing, safe receipt
DTO/Retry-After, lifecycle resolution and UI remain before activation. This does
not close the historical session/CSRF one-way-storage compatibility inventory.
No automatic bean, endpoint, merge or deployment is delivered.
