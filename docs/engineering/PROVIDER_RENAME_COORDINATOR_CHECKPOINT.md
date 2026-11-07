# Rename coordinator checkpoint

7 November 2026. PR75 implementation
`0596d7868002492a16f1b73237b287b8b5ec41b8` on
`feature/provider-rename-coordinator`: targeted internal orchestration, shared
quota and command-HMAC-only acceptance PASS; matching Quality/Docker pending. Baseline PR74 head
`0cf54164f2719e41eb2e4b181d21ccdc7b4a346a` passed all seven workflows, as recorded
with exact run links in [the delivery tracker](PROJECT_DELIVERY_TRACKER.md).
Baseline green does not establish this candidate's acceptance.

## Bounded implementation

`ProviderWriteAdmission` shares the existing durable 30/minute/account/organization
namespace across Create and Rename. `ProviderCreateAdmission` retains its public
API as a facade. Rename has an explicit coordinator and governed receipt/completion
entry points. Existing Create provider-enabled and connection-capacity checks
continue to apply to Create and do not apply to Rename.

Fresh authority and scoped own-receipt probing precede HMAC access and admission.
Historical replay uses the stored command-key version, compares the command
fingerprint and bypasses fresh quota and mutable target/revision/no-change gates.
Current authority remains required; changed fingerprints conflict. A concurrent
receipt selecting another HMAC version permits one bounded historical re-probe,
without a second fresh admission.

Fresh Rename commits admission before remote command HMAC and final locked
business validation. Stale, normalized no-change and revision-zero commands may
therefore consume quota, as may later HMAC/unavailability failures. A full bucket
returns 429 before those final business checks. Rejected 429 leaves the admission
row unchanged. Confirmed admission is not rolled back with a later business failure.
Final business validation retains scoped target lookup, positive expected revision,
stale revision, normalized no-change, then increment overflow ordering. At MAX,
the same normalized name returns NO_CHANGE; a changed name returns
REVISION_EXHAUSTED. No rejected business operation writes a configuration event or
receipt. A successful mutation, pinned historical events and receipt are atomic.
Commit uncertainty releases no provisional receipt.

## Independent managed wiring

`ProviderRenameVaultConfiguration` is enabled independently by
`agentos.provider-rename.enabled=true`. Its configuration prefix is
`agentos.provider-rename.` and its required fields are exactly `url`, `mount`,
`ca-file`, `command-token-file`, `command-key-version`, `writer-jdbc-url`,
`writer-username` and `writer-password-file`. Files must be readable absolute
paths; the writer URL uses PostgreSQL. Default feature activation remains off.

The command-only token policy reads only the command-receipts HMAC key path.
It grants no credential or credential-integrity key access. The private writer
runtime owns its transaction manager and actual receipt/connection proxies and
never registers or overrides the application's reader datasource. The Rename bean
requires no credential token, credential preparation, credential health service
or provider client. Remote command HMAC access remains outside database
transactions; command key availability is still required for exact comparison.

## Acceptance scope and test inventory

- Wrapper/fixture tests: fresh and historical command-key behavior, current
  authority, error outcomes and no credential collaborator; exact admission
  deltas for later business rejection and safe uncertain completion.
- Native `ProviderRenamePostgresIT`: mixed Create/Rename last-slot contention
  across instances, event/receipt insertion rollback while durable admission
  remains, competing revisions and same-key replay.
- `ProviderRenameVaultConfigurationTest`: independent activation and required
  command-only configuration boundaries.
- `ProviderRenameVaultIT.managedRenameUsesOnlyCommandHmacAndItsDedicatedMetadataWriter`:
  actual managed command-only Vault/PostgreSQL assembly with isolated principals.

The production subset and all five new Rename test files compile with the
available Java17 compiler module and pinned dependency jars. Google formatting,
imports, shell syntax and diff checks pass; these checks are not H2/PostgreSQL or
Vault execution. There are eight shared coordinator cases (H2 and PostgreSQL),
three PostgreSQL-only cases, three config-runner cases and one managed-Vault case.
The executed corrected-head evidence below supersedes the initial pending test
inventory. Earlier local compile checks are not attributed as runtime evidence.

## Subsequent deliverables

This slice supplies internal orchestration only. Public Rename ingress, explicit
Create-or-Rename own lookup and reviewed Rename UI remain concrete subsequent
work under [the frozen transport contract](PROVIDER_RENAME_TRANSPORT_CONTRACT.md).
No external provider call, merge, deployment or overall project completion is
established by this checkpoint.

## Initial publication and CI follow-up

Draft [PR75](https://github.com/giangnguyen3260/agent_os/pull/75) published initial
implementation `d36b1d0`. [Vault run 42387591045](https://github.com/giangnguyen3260/agent_os/actions/runs/42387591045)
passed both direct phases and the three managed tests: one command-only Rename
case and two retained Create browser/upload cases, with no failures/errors/skips.
[Focused run 42387591066](https://github.com/giangnguyen3260/agent_os/actions/runs/42387591066)
ran 264 cases with one failure: the new rollback test expected the wrong existing
internal failure code. Receipt persistence faults normalize to RECEIPT_UNAVAILABLE
following confirmed rollback; the public projection is fixed 503
PROVIDER_CONNECTION_UNAVAILABLE. The assertion now checks that precise internal
code and additionally executes the public Rename mapper to require no-store,
empty details, no success data and no constraint/name/internal-code disclosure.
All database rollback and retained-admission comparisons remain unchanged.
The corrected assertion passed the matching focused run below; the earlier
failed run remains historical evidence. No production behavior changed in this follow-up.

## Corrected implementation executed acceptance

Evidence belongs to implementation `0596d7868002492a16f1b73237b287b8b5ec41b8`,
not an upcoming documentation-only commit. The coordinating agent verified
[focused Java21 run 42388033761/job128449491753](https://github.com/giangnguyen3260/agent_os/actions/runs/42388033761/job/128449491753):
264 tests, zero failures/errors and one intentional H2-only compatibility skip.
New Rename coordinator H2 eight cases, PostgreSQL eleven cases and configuration
three cases all passed without skips. Retained native PostgreSQL completion 73
and actual Create HTTP two passed. This includes the precise rollback failure-code
assertion and real public response projection: internal RECEIPT_UNAVAILABLE,
public 503 PROVIDER_CONNECTION_UNAVAILABLE, empty details and no provisional data
or diagnostic/name disclosure. The correction changed test expectations only;
all rollback and durable-admission assertions remain enforced.

[Vault run 42388033759/job128449494068](https://github.com/giangnguyen3260/agent_os/actions/runs/42388033759/job/128449494068)
passed both direct TLS/restart phases and three managed cases: one command-only
Rename case and two retained Create browser/upload cases, with zero failures,
errors or skips. The coordinating agent read the matching job log. The Rename
case verifies separate reader/writer privileges, no Create configuration, no
credential-version mutation and command-token outage/recovery.
Command receipts, options policy, SQL foundation and guarded publication are also
green. Internal orchestration/shared quota/HMAC-only targeted acceptance is PASS.

[Quality 42388033757](https://github.com/giangnguyen3260/agent_os/actions/runs/42388033757)
and [Docker 42388033756](https://github.com/giangnguyen3260/agent_os/actions/runs/42388033756)
remain pending. Their completion is the remaining broad PR75 regression
checkpoint. Public Rename HTTP, expanded own lookup and UI have
no acceptance claim in this slice. No merge or deployment has occurred.
