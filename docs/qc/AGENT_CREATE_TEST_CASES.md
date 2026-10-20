# Agent Draft acceptance cases

Use disposable accounts from `scripts/team-create-ci-fixture.sql`; never seed production with QC accounts. Browser cases run serially at 1440×1000, 768×1024 and 390×844. The workflow provisions a random password rather than a default credential.

| ID | Preconditions and action | Expected result | Automation |
|---|---|---|---|
| AG-01 | ADMIN opens new Agent, submits empty Identity/Contract, enters valid values, navigates Back, then confirms Cancel | First invalid control focused; values retained; zero creation requests; returns to Overview | Browser |
| AG-02 | ADMIN completes four steps without a team | One persisted DRAFT, MID/SUGGEST; exact description/contract/requested capability text survives detail reload | Browser + backend |
| AG-03 | ADMIN searches and selects an eligible ACTIVE team before creation | Agent belongs to the selected team in the same organization; team manager/status unchanged | Browser + backend |
| AG-04 | Server commits, but browser loses the response; retry Create draft | Inputs remain frozen; identical key/body; response replay returns same ID; one stored agent | Browser; concurrent HTTP probe |
| AG-05 | MEMBER posts with CSRF; ADMIN posts without CSRF | 403; no Draft created; UI hides creation for MEMBER | Browser + backend |
| AG-06 | Confirm creation then fail detail GET; select Thử lại | Only GET is retried; no second POST | Browser |
| AG-07 | Revoke ADMIN role while wizard is on Review, then save | Write denied, private form cleared, role restored only by fixture cleanup | Browser + backend |
| AG-08 | Same key but changed canonical body | 409 IDEMPOTENCY_CONFLICT; original record/response retained | Backend + native HTTP probe |
| AG-09 | Team is foreign, missing or ARCHIVED | 409 AGENT_SETUP_CONFLICT; no partial agent/configuration/receipt | Backend |
| AG-10 | Force receipt insertion failure | Transaction rolls back agent and configuration | Backend/H2 |
| AG-11 | Disable account, change credential version or revoke membership before reads/replay | Fresh authorization denies access, including replay | Backend |
| AG-12 | Read legacy agent, foreign agent; query literal wildcard characters, bounds and later pages | Legacy config null; scoped404; literal escaped search; stable bounded page20; invalid query/page rejected | Backend |
| AG-13 | Send non-text required/optional fields or exceed lengths | 400 without coercion or persisted partial state | Backend |
| AG-14 | Two authenticated simultaneous requests with identical key/body | One201 and one200, same ID; subsequent changed body409 | Backend/H2 and separate PostgreSQL HTTP probe |
| AG-15 | Review Capabilities and optional test action | Tools explicitly not granted, limits not enforced; optional test disabled and not reported as run | Browser + visual inspection |
| AG-16 | Open a team-prefilled link, advance to Review before the candidate response arrives, then deliver it | Late response cannot change the reviewed team selection; no premature write | Browser regression |

Execution results and exact revision/run references live in `AGENT_CREATE_QC_REPORT.md`. Discovery alone is not execution. Native HTTP simultaneity is not a forced database lock barrier or a load test. Rollback proof remains H2 evidence unless separately tested on PostgreSQL.
