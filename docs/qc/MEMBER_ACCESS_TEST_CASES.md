# Member access inventory — prospective QC cases

2026-10-23. Contract: `docs/engineering/MEMBER_ACCESS_CONTRACT.md`. Baseline HEAD `6b639c7c80086df79c3d1f6f3c81d1b7f4e0a625`, branch `feature/member-access`. Every case below is **NOT RUN** by this reviewer. Source review is not execution evidence. Bind future results to an exact application candidate and evidence artifact; aggregate regression totals do not establish individual case coverage.

## Bounded setup and oracles

Use a disposable PostgreSQL fixture: two organizations; current ADMIN and MEMBER actors; disabled and credential-version-stale actors; disabled target with current membership; foreign-only and missing targets; at least 21 current members and 21 grants with ordering ties. Include one account shared across both organizations and grants with distinct same-org/foreign Teams. Include revoked past/future grants, unrevoked past/future grants, exact expiry boundary, unsupported action text and archived parents. No production seed, schema migration, role/invite/grant write UI or execution feature is authorized.

| ID | Layer | Scenario and expected oracle |
| --- | --- | --- |
| MA01 | API/DB | Fresh enabled/version-matching HUMAN ADMIN reads both endpoints; response context matches requested org and member. |
| MA02 | API/DB | Current MEMBER, including one with an approval grant, receives403 on both endpoints. Organization role never substitutes for decision grant authority. |
| MA03 | API/DB | Missing session/non-HUMAN cannot read; disabled actor, stale credential version, removed membership and demotion lose access on the next GET. Record actual transport status; no private response data. |
| MA04 | API/DB | Absent/foreign org returns403 before member lookup; authorized org plus missing or foreign-only member returns404. Compare existing and nonexistent foreign IDs to exclude enumeration. |
| MA05 | API/DB | Disabled target remains listed and inspectable with current membership; removed target membership yields404 even if historical grants exist. |
| MA06 | API/DB | Counts and grant items scope by org AND account; Team name join scopes by org AND Team ID. Shared account never leaks foreign grant totals or Team names. |
| MA07 | API/DB | Blank query/role means all; ADMIN/MEMBER filters and display-name/email contains filters return exact persisted matches and filtered total. |
| MA08 | API/DB | Literal percent, underscore and backslash searches are literal; quote/SQL-like payload stays bound text. Case-insensitive matching and Unicode edge stripping work; >200 normalized query rejected400. |
| MA09 | API/DB | Invalid role/status and negative, fractional, nonnumeric or >100000 page rejected400; pages0/100000 accepted with fixed size20. Grant status default ALL. |
| MA10 | API/DB | Member ordering lower(display_name),account_id and grant ordering expires_at DESC,id are stable across two pages with ties; no duplicates/omissions in unchanged data. Beyond-end page returns empty items and correct total. |
| MA11 | API/DB | Revoked takes precedence regardless of expiry; unrevoked expiresAt <= generatedAt is EXPIRED and > is UNEXPIRED. Assert exact equality using a controlled clock/precise boundary, not timing sleeps. |
| MA12 | API/DB | Each status-filter total and item status agree with the same returned generatedAt; sum of member lifecycle counts matches scoped ledger. Concurrent changes cannot mix authorization/count/item snapshots. |
| MA13 | Source/DB | Both reads use read-only REPEATABLE_READ; authorization and all SQL reads share transaction. No per-row query pattern, write locks or business writes. Observe query shape and compare relevant business rows before/after; distinguish framework session maintenance from business effects. |
| MA14 | API | Strict DTO allowlist; no password hash, credential version, session IDs or audit internals, including error bodies. revokedAt key explicitly present and nullable. |
| MA15 | API/FE | Unknown stored action is displayed as plain text; disabled target/archived parent/unexpired grant never produces an eligibility guarantee or execution affordance. |
| MA16 | FE/HTTP | Authenticated guard plus fresh component ADMIN context before inventory fetch/render covers direct links and reused-route org/member transitions; MEMBER cannot gain inventory through cached context, query-only navigation or browser back. Server independently enforces. |
| MA17 | FE/browser | Search/role/page and status/page are URL-backed; reload/back restore state and matching HTTP params. Filter changes reset page consistently; Previous/Next respect total and page bounds. |
| MA18 | FE/browser | Each card renders persisted name/email/enabled/org-role/self marker and three counts; detail uses real scoped member/Team/action/risk/expiry/revoked time/status. isSelf must equal authenticated actor ID. |
| MA19 | FE/browser | Initial loading, no members, no search matches, no grants and empty status filter have distinct usable states, navigation and retry where appropriate. |
| MA20 | FE/browser | Refresh/filter/page begins by clearing private inventory. Failed, malformed, timed-out or denied read leaves no prior member/email/grant details in DOM or component inventory state. Recovery issues GET only. |
| MA21 | FE/browser | Delay an old success then change account/org/member/filter/page or deny access; old result cannot repaint. Same-account relogin/session change and component destruction invalidate old work. |
| MA22 | FE/browser | Mutate runtime envelope/context IDs/enums/counts/pages/size/self identity/duplicate IDs/lifecycle-as-of. All invalid shapes fail closed before rendering; separately test grant count and filtered-total consistency. |
| MA23 | FE/browser | All preflight and inventory GETs have finite timeout; retry rechecks current ADMIN context. Logout/demotion/credential reset while pending clears private state and cannot be reversed by late callbacks. |
| MA24 | FE/browser | Persisted hostile name/email/Team/action text renders through Angular escaping; no innerHTML, evidence hyperlinks or inventory session/local storage. Check network for absence of mutation requests. |
| MA25 | FE/visual | At1440/768/390 with actual project viewport: readable wrapping of long text, no horizontal overflow, labelled native filters, keyboard focus/order, Previous/Next, Try again and scoped back links. Save candidate-bound synthetic screenshots. |
| MA26 | FE/scope | No inferred Owner/team roles, invitations/edit/grant-success/functional fake controls. Explain unavailable team roles and separate grant writes; ADMIN inventory does not claim approval authority. |
| MA27 | Regression/evidence | Real PostgreSQL HTTP probe proves freshness, revocation, real rows and no business effects. Full Quality/Docker/browser regressions reference exact candidate and completed job logs. Keep Sonar paused and deployment/load acceptance outside scope. |

## Evidence discipline

Backend worker owns fixture/probe and backend execution; main owns FE/workflow and browser execution. Independent reviewer inspects source and supplied/retrieved evidence without converting planned assertions into passes. Record case ID, candidate, command/job, result and material limitations for any future execution attribution. Current execution count by this reviewer: zero.

## Later evidence checkpoint

Independent reviewer executed only `node scripts/test-member-access-validation.cjs` successfully on2026-10-23 against mutable pre-CI source. This is partial synthetic validator evidence for MA11/MA22 and query normalization, not execution of those full API/browser cases. All27 full cases retain NOT RUN status by reviewer; see companion review for exact scope and pre-CI source disposition.
