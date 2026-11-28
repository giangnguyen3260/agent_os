# Review an Agent plan before creating Tasks

1. In a planning Mission, give an ordinary text Task the goal, required output and the JSON format below. Start, review and ACCEPT its output through the existing execution flow. Complete that source Mission using graph review/completion. This is a normal governed Task; importing does not call a model.
2. Create a separate empty Mission in the same Team. Its goal and required output should describe the work you intend to import. Keep intake open and status CREATED.
3. Open **Nhập đề xuất từ Agent** and enter the source Run ID. Read the exact accepted source. If it is malformed or needs changes, edit the proposal JSON; the import will be recorded as HUMAN_CORRECTED.
4. Choose an active Agent for every proposed Task. Agent output cannot select owners, permissions, status or execution behavior.
5. Preview every description, acceptance criterion, priority and dependency. Applying creates all Task configuration together or none. Existing manual Tasks cause a conflict; they are never replaced.
6. Continue to **Xem và duyệt kế hoạch từ Task**, then explicitly accept, admit and Start using the existing flows. An import receipt does not authorize execution. If a response is uncertain, retry the exact retained command instead of creating another import.

Example planner output (plain JSON, without Markdown fences or surrounding commentary):

```json
{"schemaVersion":1,"tasks":[
  {"key":"research","title":"Collect references","description":"Collect relevant evidence for the supplied goal.","acceptanceCriteria":"A concise set of relevant references with stated limitations.","priority":"NORMAL","dependencies":[]},
  {"key":"write","title":"Write the deliverable","description":"Use the accepted research to produce the required output.","acceptanceCriteria":"The output meets the Mission goal and states remaining gaps.","priority":"NORMAL","dependencies":["research"]}
]}
```

Keys use a lowercase letter followed by lowercase letters, digits or underscores (up to 40 characters). A proposal contains 1–20 Tasks and at most 100 dependency edges, all within the proposal, with no cycles. Text limits follow manual Tasks: title 255, description 8000, acceptance criteria 4000 Java characters; required text is nonblank and already trimmed. Priority is LOW, NORMAL, HIGH or URGENT. Each source/correction is bounded at 64 KiB and its expanded snapshot at 128 KiB; oversized data is rejected without truncation.

This opt-in flow requires `agentos.mission-plan-proposal.enabled=true` and the existing graph review/acceptance/admission/completion capabilities. It supports completed graph Missions, including one-Task graphs, rather than legacy single-Task closure. Receipt reread is scoped to the importing human and current ADMIN authority at `/api/v1/organizations/{org}/teams/{team}/missions/{mission}/plan-proposal/receipt?idempotencyKey={key}`; it returns the original reviewed snapshot and Task mapping after later lifecycle changes.

## Assign one Agent to all proposed Tasks

Choose an available Agent under **Agent dùng cho toàn bộ Task**, then explicitly press **Gán Agent đã chọn cho toàn bộ Task**. Selecting the dropdown alone does not change assignments. The button replaces all current row choices and clears any previous preview; individual row overrides remain available afterward. Editing JSON, changing/restoring source or resetting private state clears the bulk selection. The action is unavailable while busy or an apply command is uncertain, and only uses an Agent present in the current target context. This is local form editing: no preview/apply/provider request or execution authority is created. You must explicitly preview again and review every row before applying. Backend still revalidates current owners and scope.

Controlled Vault browser extension exercises the bulk selector after source restoration: choosing alone keeps all four rows unassigned; explicit bulk assignment sends no preview/apply POST. Real backend preview must show AGENT_OUTPUT; another bulk assignment invalidates preview/apply without a network mutation. A second explicit preview then allows exactly one atomic import. Existing native owner/provenance/provider-count assertions remain. Matching-head Vault CI is required before claiming this new native acceptance.

## Leaving while editing or awaiting an apply result

In-app navigation asks confirmation when a correction or owner assignment has not been applied, and uses a distinct warning when the exact apply command remains uncertain. Canceling navigation preserves text, owners and retained retry data. Accepting explicitly abandons in-memory work; the warning does not cancel a committed backend operation. Browser unload/reload requests the native browser warning for the same states (browser support and interaction requirements apply). Unchanged source and successful receipt do not warn. No draft or command is persisted to browser storage. Role/account privacy clearing still takes precedence.

After an ambiguous apply response (including a malformed success receipt), later retry conflicts/bad requests/not-found responses do not prove the original command failed. The editor retains the exact command and fingerprint and continues to require explicit exact retry rather than enabling a fresh import. A first definitive rejection may still return to editing. Current401/403 or account changes clear private retained data as before; no background retry is introduced.

Controlled Vault recovery acceptance extension: the browser forwards the first apply to the real backend, requires200 and then drops its committed reply. Canceled departure must preserve the retained command. The second attempt receives an explicitly synthetic409 without backend forwarding, testing client uncertainty handling rather than claiming an actual backend conflict. The third identical command is forwarded and must recover a real historical receipt. Native assertions retain exactly one import receipt and four Tasks. All three command bodies must match; no automatic apply is permitted. Matching-head CI is required before claiming acceptance.

While an apply command is retained, **Tra cứu kết quả lệnh đang giữ** performs a fresh ADMIN check followed by the existing receipt GET using that exact key. It creates no apply POST. The returned receipt must match current org/team/Mission, retained source/key/fingerprint, reviewed origin/intake revision and unique Task mapping before resolving uncertainty. Missing/malformed/conflicting reads retain the original command for explicit retry;401/403 still clear private data. Receipt display records an earlier import and is not execution authority. The key is shown in memory but is not saved to browser storage. New UI mocked acceptance needs its own matching-head CI and native GET-recovery browser gate.

Native GET-recovery acceptance extension uses the new lookup button after the real committed apply response is dropped and the explicit synthetic409 retry fault. Await a real scoped GET with the original key; require200/no-store, original fingerprint and four Task mappings, then the displayed snapshot. No third apply POST is permitted. Native one-receipt/four-Task/provenance assertions remain. PR158 separately established real same-command POST replay; this extension verifies GET recovery instead. Requires its own matching-head Vault CI.
