# Provider create retry delay

Continue from PR63 (6ad7a8d); all six workflows on that commit completed successfully.

Fresh-create admission now computes seconds until the oldest retained request
expires, rounded up and bounded to 1..60. The typed receipt failure carries this
integer through the coordinator into the internal completion failure. Other
failures carry zero; invalid ranges and non-rate failures carrying a delay are
rejected. Existing one-argument completion failure construction remains valid.
Internal completion types remain excluded from JSON serialization and retain
redacted diagnostic output. No timestamps or credential data are added.

Shared H2/PostgreSQL fixture assertions cover bounded rate rejection, unchanged
admission rows, coordinator propagation without command-key or credential calls,
and invalid metadata. The focused workflow now also triggers on receipt sources
and transport tests. Google formatting, import rules and diff checks passed
locally. This environment has Java17 and no Maven; Java21 runtime tests require
CI and are not claimed as locally executed.

This supplies internal retry metadata only. An eventual public command response
must map it to Retry-After; no public POST, security-chain activation, merge or
deployment is included. Safe response DTOs, isolated authenticated command
routing, credential lifecycle resolution and the create form remain outstanding.
