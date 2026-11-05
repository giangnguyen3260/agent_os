# Provider Create error response candidate

5 November 2026. Built on local retry-delay candidate `c4622de`.

`ProviderCreateResponses` is an explicit, error-only public projection helper;
it is not a bean, route, security-chain change or deployed command. It maps every
internal completion failure to a fixed allowlisted status/code/message and the
existing exact `ApiErrorResponse` envelope. Internal preparation, connection-ID
collision, storage and unknown-completion codes become safe 503
`PROVIDER_CONNECTION_UNAVAILABLE`. Invalid wire/key shape becomes `BAD_REQUEST`;
this is not a claim of remote provider credential validity. Other exposed
conflicts use the public contract's exact code names.

All responses are JSON and `Cache-Control: no-store`. Only rate failures with
bounded 1..60 second metadata emit 429 and `Retry-After`. The legacy zero-delay
rate failure cannot supply a truthful wait and fails closed as safe 503 instead
of fabricating one. Non-rate responses clear any previously staged Retry-After.
UNKNOWN carries no provisional result, receipt, internal state or automatic
retry instruction. The helper does not poll receipts or perform any mutation.

COMMITTED is rejected before touching the response. Successful public receipt
projection remains blocked: internal persisted Configuration does not retain
the frozen credential health/mask required by the comprehensive proposed
public Receipt schema. Recomputing current health or inventing historical
PRESENT/UNAVAILABLE would change that contract. Resolve the historical snapshot
contract/persistence explicitly before enabling success or public Create.

Six authored test methods cover all internal codes, exact error/meta field sets,
empty details, UUID request correlation, rate bounds and absent metadata,
UNKNOWN/UNAVAILABLE privacy, REJECTED mapping, COMMITTED no-write rejection and
missing inputs. The focused Java21/PostgreSQL workflow selects the new class.
Google Java Format, import rules and `git diff --check` passed locally. Local
Java17 has no Maven: these JUnit assertions have not been executed here and
matching Java21 CI remains required. No public HTTP, real-login or full runtime
acceptance is claimed. No merge, deployment or provider call is included.
