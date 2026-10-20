# Agent activation review QC — execution pending

Scope: real configuration/Team preflight and a fail-closed server activation boundary. This does not deliver positive activation, providers, grants or workers. Branch feature/agent-activation-review stacks on draft/unmerged Studio PR8. SonarQube is temporarily paused by user instruction.

Local FE lint/format/build, Google Java formatter/import rules and mock HTTP review passed. Main inspected21 rendered states across1440/768/390: loading, incomplete/current configuration, configured-but-unavailable, transport error, null envelope, wrong scope and member denial. Refresh, stale-check clearing, dialog Escape/focus and zero UI writes were checked. Mock checks are not database evidence.

QC source review identified a null-200-envelope callback failure. Safe envelope access and a real-browser regression are included before CI publication. No fabricated positive activation adapter is used. Native PostgreSQL probe is prepared to configure real limits, verify six configuration checks PASS while required integrations remain UNAVAILABLE, then compare exact business rows/history/receipt/task counts across concurrent blocked, stale and CSRF-denied requests.

Actual backend, browser and native database execution remain pending. Final gate must record exact revision/run IDs and actual counts; discovery135 (117 baseline+18 new) is not execution. No migration or production seed data is introduced.
