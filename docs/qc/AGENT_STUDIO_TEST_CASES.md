# Agent Studio acceptance cases

Disposable Studio accounts and agents are provisioned by `scripts/agent-studio-ci-fixture.sql`, isolated from existing browser fixtures. Passwords come from the CI random bootstrap credential. Browser tests read the current revision and reset configuration via authorized API, rather than assuming a fixed revision across cases. Serial execution is required while the role-revocation fixture is shared.

| ID | Action | Expected result | Evidence path |
|---|---|---|---|
| ST-01 | Current member opens Studio for a same-organization Agent | Real identity/configuration/revision displayed; ACTIVE remains read-only; no fabricated grants/checklist | Browser + backend |
| ST-02 | ADMIN edits contract/requested capabilities and structured nullable limits, reviews diff and confirms | No PUT before confirmation; before/after values match; revision increments once; exact multiline text persists; identity/team/status unchanged | Browser + backend |
| ST-03 | Submit no changes or invalid required/typed numeric values; Back and Cancel | No save; first invalid field focused; errors shown; values retained until confirmed discard | Browser + backend |
| ST-04 | Another writer changes the revision before confirmation | 409 CONFIGURATION_CONFLICT; local edits retained; saving blocked; Reload latest requires explicit discard confirmation | Browser + backend |
| ST-05 | Server commits but the response is lost; retry | Frozen immutable body, expected revision and key reused; original response returned; no second revision | Browser + native HTTP probe |
| ST-06 | Confirm save then fail GET from Back to studio; retry | Only GET retried; one original PUT; current configuration restored | Browser |
| ST-07 | MEMBER writes, missing CSRF, foreign target or revoked admin | Write denied; foreign reads scoped404; access loss clears private content | Browser + backend |
| ST-08 | Same key with changed canonical request | 409 IDEMPOTENCY_CONFLICT; no replacement or extra version | Backend + native HTTP probe |
| ST-09 | Replay original request after a later edit or status change | Fresh authorization first; original saved response returned without another write | Backend + native HTTP probe |
| ST-10 | Two keys race with the same expected revision | Exactly one update and one stale409; no lost update | Backend/H2 + native HTTP |
| ST-11 | Force receipt insertion to fail | Current configuration, revision and history all rolled back | Backend/H2 |
| ST-12 | Read and complete a legacy DRAFT without config | Revision0 and null optional settings initially; full validated contract creates first version; no automatic activation | Backend/H2 |
| ST-13 | Disable account, change credential version or revoke membership before replay | Replay/read denied using fresh identity and membership checks | Backend |
| ST-14 | Send strings/booleans/fractional integers, out-of-range values, excess budget precision or security fields | Strict validation rejects invalid types/bounds; immutable identity/team/status remain unchanged | Backend |
| ST-15 | Inspect at1440/768/390 and exercise compact menu/unsaved navigation | No horizontal overflow; usable labels, focus and controls; Escape dismisses menu; discard confirmation works | Browser + local rendering |

Native Studio probe uses simultaneous HTTP requests, not a forced database barrier or a production load test. It checks same-key replay, different-key stale revision, persisted current configuration and original-response durability after later changes. Authentication's separate PostgreSQL barrier is not Agent configuration race evidence. Local mock HTTP visual checks do not establish backend persistence. Final execution results and exact run/revision references are recorded in `AGENT_STUDIO_QC_REPORT.md`.
