# Team Settings S06 QC cases

Normative source: `docs/engineering/TEAM_SETTINGS_CONTRACT.md`, read fully. This is an authored test inventory, not a pass report. Main owns CI and the final report. Existing 271 backend / 522 browser baseline gates must remain intact; those numbers are historical gates, not observed successes here.

| Area | Authored executable coverage | Execution |
| --- | --- | --- |
| Fixture isolation | Native org 1; desktop 2; tablet 3; mobile 4; foreign 5. Distinct accounts, teams, agents. Password hash copied from qc@example.test; E2E_PASSWORD read only from environment. No mailer. | Not applied |
| Context/candidates | Exact context keys, initial revision zero/no GET ledger, two real candidate pages, no duplicate IDs | Not run |
| Wire | Numeric strings, fractional integers, scale, unknown/missing/duplicate fields, raw limit, invalid key, required acknowledgement, CSRF, query duplicates/unknowns | Not run |
| Scope | MEMBER denial, scoped foreign team read/write 404, other actor receipt null | Not run |
| Persistence/replay | Real201 with exact saved-state/receipt keys and safe audit payload/timestamp; exact200 replay, immutable original receipt after subsequent edit/archival/MAX, body conflict, no-op | Not run |
| Races | Python barriers for same key, distinct commands on same revision and two-settings-team claim; integrated real NewTeamDraft/settings same-Agent claim behind observed org waits, exactly one201/one409, winner-only assignments and loser atomicity | Not run |
| Post-wait authority | Persistent psql org lock and observed wait before membership revoke, account disable and credential-version change; exact403, unchanged business/history/session identity; fresh other-ADMIN session per account vector and finally restoration | Not run |
| External fingerprint | Assigned Agent configuration_revision/updated_at and real Studio HTTP PUT before POST; integrated actual AgentDraft HTTP attachment queued ahead of settings org wait; unchanged settings revision, changed fingerprint, stale command409 and unchanged settings journal | Not run |
| Removal | Terminal mission, terminal task, expired requester, AGENT audit, global inbound/outbound reporting; blocked DTO and blocked POST; unchanged full business snapshot | Not run |
| History preservation | Legal detach despite Studio version history; original config/version/create receipts and grants compared as full rows; only identified external Team/Agent-create additions and exact Studio config/history delta admitted at checked boundaries | Not run |
| Grandfathering | ACTIVE null manager; DRAFT null manager; suspended unchanged manager metadata edits; no auto-reactivation/drop; ACTIVE roster delta denied | Not run |
| Atomic rollback | Scoped audit trigger raises; full teams/agents/ledger/events/receipts/audit snapshot unchanged; trigger cleaned in finally | Not run |
| Bounds | Real200→disjoint200 replacement (400 union), real >201 roster, bounded first201, exact total, all writes blocked; INT_MAX revision rejection | Not run |
| Responsive real save | 1440/768/390 actual info edit, member add/remove, manager replacement retaining old manager, limits review/ack/save/reload; no early POST | Collected only |
| Unknown/recovery | Actual route.fetch 201 precedes abort/malformed2xx; malformed actual receipt GET remains UNKNOWN; manual identical body/key replay returns actual 200 | Collected only |
| UI stale/privacy | External Team source change prevents preflight POST, focus revocation clears draft, delayed actual candidates fenced after role loss, ACTIVE/ARCHIVED/foreign privacy | Collected only |
| UI candidates | Real page0/page1 distinct IDs and literal %_ search with zero writes | Collected only |
| UI validation | No-op and invalid text/numeric limits cannot review; dirty reload dismiss/accept | Collected only |

Each browser project owns its own mutable org but uses one fixture through serial tests. Start with freshly applied fixtures. The spec is deliberately not fully parallel. Real commit faults do not replace backend responses with invented success. Screenshots use only disposable fixture data, no login/session screenshots and no forced screenshots. The success and unknown tests request fourteen meaningful named states per project: information, dirty information, members, changed membership/manager, limits, acknowledged review, historical receipt, persisted reload, plus unknown/manual-malformed/recovered for each of two reply faults. Names are `frontend/qa-visuals/team-settings-{project}-{state}.png`; none have been captured or visually reviewed here.

## Remaining acceptance gaps

These must not be reported as tested: DB trace proving sorted acquisition order for the authored 200-current/200-desired replacement, legacy unknown member labels, Unicode strip boundary vectors, ACTIVE ineligible manager, manual null/unavailable receipt recovery. No universal claim about arbitrary SQL or noncooperative writers follows from these probes.

CI partition: grep exactly `team settings independent QC` first, then inverse grep for the complete unchanged baseline, workers1/retries0. Native fixture/probe runs before browser at E2E_BASE_URL port18080 with a five-minute job bound. Artifact glob: frontend/qa-visuals/team-settings-*.png.

Four additional browser cases (12×3 =36 authored): actual Workspace Settings RouterLink plus real dirty Back/reload and separate synthetic reused-identity navigation through existing pushSpaHistory; sibling shared-session actor swap while actual committed reply is held (privacy checked before release); UNKNOWN role loss clears private state then restored ADMIN recovers via opaque GET with one total POST; actual fetched initial503/read-malformed responses clear DOM/ack without false permission denial and GET retry recovers. All are collection-only.


Native continuation coverage above is authored and wired into main(), not executed. NewTeamDraft claims existing Agents; AgentDraft creates and attaches a new Agent, so these are distinct vectors. HTTP body/path and SQL receipt compatibility were checked against the actual backend DTO/controller/service classes. Full history snapshots are checked before admitting each external writer and after subsequent settings-only work; no unrelated settings write is exempted. The global299-second bound remains; native execution/time-budget confirmation belongs to main CI. This continuation changes only the native probe and these two documents; browser collection evidence is inherited.
