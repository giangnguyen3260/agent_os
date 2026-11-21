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
