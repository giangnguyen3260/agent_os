# Trace Explorer gap review after PR28

## Frozen contract acknowledgment

**Scope acknowledged:** [TRACE_EXPLORER_CONTRACT.md](TRACE_EXPLORER_CONTRACT.md) is the authority for this slice. Trace Explorer is a read-only **Mission/Task-linked recorded audit activity** screen, not an organization-only audit inventory or an execution trace. Main owns the frozen contract, Figma and implementation. This review owns only this file; no app edits or commit.

Reviewed the frozen contract on 2026-10-26 against the PR28 baseline sources at `b56b4f9`. Main reports full Figma contexts loaded for desktop `47:2372`, mobile `60:7026` and tablet `60:13642`: filters, timeline and selected-detail cards; sample runtime metrics replaced with unavailable messaging. This review accepts that handoff without claiming independent Figma inspection or visual QC. `IMPLEMENTATION_STATUS.md` describes PR28 as development-verified but draft/unmerged; this review does not establish merge or production readiness. No live database probe or test suite was run for this documentation review.

The previous proposed creation-only enum, `/activity` route, additional Team/time/actor filters, 404-on-filter-miss and URL persistence requirement are **superseded**, not acceptance conditions. Opaque event/actor labels and a separately authorized safe detail fetch are accepted. No further approval, runtime grant or organizational audit policy decision is needed to implement this frozen scope.

## Accepted surface and semantics

| Area | Frozen scope acknowledged |
|---|---|
| Audience | Enabled credential-version-matching HUMAN with current organization ADMIN/MEMBER membership, checked through `MissionCreateService.authorize(org,human,false,false)` on every read. |
| Source | Only `audit_events` with at least one structured Mission/Task reference. Every non-null reference resolves in the same organization; paired Task/Mission references agree. No payload parsing or union of restricted history tables. |
| List | GET `/api/v1/organizations/{organizationId}/trace-events`; query allowlist `q,missionId,taskId,page`, each once. |
| Filters | q is stripped literal case-insensitive event-type substring, max100 Unicode code points with LIKE metacharacters escaped. Mission/Task IDs are canonical lowercase RFC4122 UUIDv1–5. Filters intersect visible projected rows; foreign/nonexistent filter IDs return empty results without metadata. |
| Paging | Fixed20, canonical decimal page0..100000, order `created_at DESC,id ASC`, same read-only REPEATABLE_READ transaction for total and rows; beyond-last page returns empty items and actual filtered total. |
| Envelope | List data exactly `{organizationId,generatedAt,runtimeStatus,items,page,size,total}`; runtimeStatus is `UNAVAILABLE`. It states absent runtime authority, not an observed execution result. |
| Item | Exactly `{id,eventType,actorType,createdAt,team,mission,task}` with explicit nulls. Team null or `{id,name}`; Mission required `{id,title}`; Task null or `{id,title}`. Event/actor strings are escaped opaque recorded labels, not inferred identity/outcome. |
| Detail | GET `/trace-events/{eventId}` with no query parameters; exact data `{organizationId,generatedAt,runtimeStatus,event}`. Fresh authorization and the same source predicate; missing/foreign/excluded event404. |
| Context | Current safe titles and IDs, not historical snapshots. Mission-only/null-Team events are valid but have no Team-dependent destination link. Task/Mission links require the actual validated parent chain. |
| UI | `/workspace/:id/trace`; filters/timeline/detail cards, no automatically selected event, real loading/empty/error/denied states, clear unavailable-runtime banner. URL persistence is not required. |
| Privacy refresh | Refresh/filter/page/focus/visible-return and selection revalidate live authority and clear private content. Account/route generation fences, cancellation and late-response rejection apply to success, failure and pending authorization. User-entered filter drafts may remain. |

Raw payload, actor identity, human grants, credentials, prompts, input/output, exceptions, correlation/config-version metrics, cost/tokens and runtime execution are outside this response. No export, mutation, new schema, runtime provider calls or organization-only audit inventory is required.

## Persisted source evidence

Paths below are relative to `backend/src/main/java/com/agentos/` unless a migration/document path is given.

| Source | Actual authority and limitation |
|---|---|
| `backend/src/main/resources/db/migration/V001__create_core_tables.sql` | `audit_events` stores id, org, nullable Mission/Task references, actor type/id, event type, payload text and creation timestamp. No span/run/duration/cost/usage fields. `mission_id` has a single-column Mission FK; `task_id` has no FK. Neither establishes composite tenant/reference consistency. |
| `mission/application/MissionCreateService.java`, `TaskCreateService.java` | Current writers atomically persist HUMAN `MISSION_CREATED` and `TASK_CREATED` events with structured resource references. They establish creation activity, not execution. The frozen screen may also display other stored linked labels without claiming a corresponding runtime exists. |
| `observability/application/AuditService.java`, `observability/domain/AuditEventType.java` | Generic recorder constructs SYSTEM events; constants include creation/planning labels. No generic-recorder caller was found in baseline main Java sources. Names/constants are not evidence that planning or runtime event coverage exists. |
| `mission/application/TaskDetailService.java` | Existing safe `{id,eventType,actorType,createdAt}` activity projection checks exact org/Team/Mission/Task, exact Task event reference and null-or-exact Mission. |
| `mission/application/MissionWorkspaceService.java` | Same safe metadata; requires a Mission/Task reference, null-or-exact Mission and any referenced Task inside exact org/Team/Mission. |
| `organization/application/TeamWorkspaceService.java` | Same safe metadata; each non-null reference resolves inside org+Team. Two individually valid references can still identify different Missions in the same Team. The frozen Trace contract correctly requires paired agreement. |
| V011/V012 and `governance/application/ApprovalDetailService.java`, `ApprovalInformationService.java` | Separate decisions/conversation persist actual governance history. Mirrored APPROVAL_DECIDED / INFORMATION_REQUESTED / INFORMATION_REPLIED audit rows lack structured Mission/Task references and are therefore excluded here. A decision is not runtime resume. |
| V013/V014/V015/V016; grant, member-role, invitation and Team-settings writers | Separate authoritative restricted histories include reasons, actor names, email or before/after state. Their mirrored org-only audit rows are excluded by the frozen linked-reference predicate. Do not parse payload identifiers to pull them into this screen. |
| V002/V003 and auth/recovery/logout writers | `authentication_audit` is a separate account history, not this organization's Mission/Task activity source. Excluded. |
| `model/infrastructure/StubModelGateway.java`, `tool/infrastructure/StubToolGateway.java`, migrations V001–V016 | Model stub returns placeholder text/zero usage; Tool stub returns STUBBED. No persisted execution-span/run/attempt/model-call/tool-call/billing ledger found. Stub zeroes are not measured zero cost/tokens. |

Existing activity services already use fixed20/page0..100000, read-only REPEATABLE_READ and timestamp-descending/UUID-ascending order. They are implementation precedents; their historical tests do not validate the new Trace endpoint.

`API_BOUNDARY.md` §14 describes target trace/call/usage architecture, including execution runs, artifacts and cost/token summaries. It does not establish delivered sources. Current `IMPLEMENTATION_STATUS.md` and PR28 explicitly leave runtime and effective authorization unavailable. Audit timestamps are record times, not span starts/durations; total is the filtered visible event count, not execution throughput or total organization activity.

## Constraint-realistic fixtures

V004 adds composite Mission→Team and Task→Mission→Team foreign keys. Under the normal schema a Mission cannot point at a foreign-organization Team, and a Task cannot have an invalid org/Team/Mission chain. These are enforced invariants, **not ordinary insertable hostile fixture cases**. Do not drop constraints, disable triggers or manufacture impossible resource rows to satisfy this review.

Adversarial fixtures belong at the noncomposite audit references:

- An org A audit row may reference an existing org B Mission because its Mission FK checks ID alone. A nonexistent Mission ID cannot be inserted under that FK.
- An audit Task ID may be dangling or identify another organization's valid Task because audit task_id has no FK.
- Both references may exist yet identify different Missions, including two Missions in the same Team; the audit row has no composite pair constraint.
- Null Mission plus valid Task must derive Mission/Team from the valid Task chain. Mission-only with genuine null Team remains valid. Both references null is excluded.

Use these schema-legal cases for list/count/detail exclusions, alongside foreign organization, opaque labels, >20 same-time rows, payload sentinel and current authority tests. Defensive join checks remain useful but do not imply V004 allows broken resource chains.

## Concrete gaps and review disposition

**No blocking scope gap identified in the frozen contract.** It supplies the source predicate, safe projection, filter rules, list/detail authority, pagination and private-state lifecycle necessary for Main to proceed. Implementation and QC remain in progress; this acknowledgment is not a code or release pass.

| Finding | Required treatment / disposition |
|---|---|
| Legacy observability routes return payloads | At baseline, `observability/api/ObservabilityController.java` maps `/audit-events` and `/missions/{mission}/trace`; `TraceService` returns unbounded `AuditEventResponse` including payload. `SecurityConfig` permits enabled same-org member reads and checks credential freshness via its session filter. Thus these are not anonymous endpoints and are not transport-denied, but do expose a broader unsafe legacy response. The new screen must never call them. Record as a separate existing security gap for a narrow deny/replace follow-up; it is not a requirement to add an organization audit inventory or broaden this frozen slice. This review does not claim the concurrent code has fixed it. |
| Offset paging consistency | Same-transaction count/page consistency is correctly specified. Across separate requests, newly inserted events can shift offset pages. “Stable order” means deterministic ties, not a durable multi-page snapshot. No cursor/schema expansion required. |
| Current context versus history | Safe labels come from current rows. Do not describe renamed Team/Mission/Task labels or current hierarchy as an immutable historical snapshot. No historical reconstruction required. |
| Unknown label rendering | Accepted as opaque text under the linked-reference policy, without enum rejection, outcome inference, identity lookup or runtime promises. Existing column bounds and escaped/wrapped rendering should be respected; test opaque actor text as well as event text. No new event-type approval gate. Future producers remain responsible for not embedding secrets in metadata labels. |
| Exact Unicode filter behavior | q is bounded by Unicode code points, not Java UTF-16 length. Preserve literal `%`, `_` and SQL escape behavior and compare the same canonical q in count/page predicates. Verify supplementary Unicode and limit boundaries without changing the frozen filter. |
| List/detail lifecycle | Contract requires independent authorization for detail and fences for pending live checks and all late responses. Verify revoked membership/account change/route change between selection and detail response; never restore stale list/private detail on failure. |
| Scale unknown | Fixed page size does not bound total-count scan cost. V001 indexes org/Mission/time separately; V009 adds org/Task/time/id. No new index or production scale claim is required by this slice; measured query-plan work can follow actual workload. |

The frozen verification section already covers the material delivery evidence: schema-legal PostgreSQL fixtures, exact fields/count/order/filter escaping, authority freshness, no-write snapshots, newly persisted activity refresh, delayed browser replies and three responsive widths. Distinguish collection from passed execution and keep the existing baseline gates. No extra tests for impossible V004 resource chains or a broader administrative audit screen are requested.

## Resolved scope acknowledgment

Accepted against the current frozen contract and Main's follow-up. Source inspection now confirms `ObservabilityController` retires both legacy mappings (`/audit-events` and `/missions/{missionId}/trace`) by immediately throwing EntityNotFoundException, with no injected TraceService, query or service call. This supersedes the baseline legacy-route disposition above: the concrete payload-exposure blocker is resolved in controller source. Authorized legacy requests are intended to return404; existing outer authentication/organization denial still applies first. BE/QC are adding regression evidence; this review does not claim those checks have passed.

The approved screen remains Mission/Task-linked audit metadata only. Organization-only rows are excluded from list/count/detail; every non-null reference is validated and paired references must agree. Unknown recorded `eventType` and `actorType` remain opaque escaped labels, consistent with existing Task/Mission activity projections. **No event-type allowlist or additional audience approval is required for this current metadata scope.** Future event writers must review metadata-label privacy and audience compatibility before producing new activity; that is a future producer constraint, not a present delivery blocker.

V004's normal composite resource constraints remain intact. Invalid fixtures target only schema-legal noncomposite audit references, not impossible foreign-Team or broken Task resource chains. The accepted three-width Figma handoff remains filters/timeline/detail cards with runtime sample metrics replaced by unavailable messaging. No remaining BA/SA scope blocker identified; implementation and executed QC gates remain Main/BE/QC work. Only this review document was changed by this reviewer, with no commit.
