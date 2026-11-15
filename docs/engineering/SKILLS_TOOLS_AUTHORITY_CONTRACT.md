# Skills and Tools authority

Agent Studio stores requested Skills and Tools as text. These fields provide description only. Agent Permissions reports effective authority as unavailable because the backend has no authoritative Skill or Tool catalog and grant source. Activation Review keeps its existing `TOOL_AUTHORIZATION` check at `UNAVAILABLE`. The Tool Gateway rejects all calls at its application boundary, including requests with populated scope, action and idempotency fields. AgentOS has no registered Skill execution path.

## Authority required before enabling execution

- A Skill assignment must pin a catalog entry and its version to an Agent in the same organization. Before loading it for execution, resolve the assignment and confirm that the version is still eligible. Skill content and its requested capabilities cannot confer Tool access.
- A Tool grant must bind the organization, Agent, catalog/version and exact capability and action, with the permitted resource scope. An authorized administrator issues or revokes the grant. Each new call resolves the current grant and checks the payload against the supported operation, its schema and the granted scope. An unsupported action, unavailable resource, missing or revoked grant, and unknown scope all deny execution.
- The gateway must verify the Agent and Team, accepted plan, current Run/Attempt claim and resource reservation under the [runtime authority contract](RUNTIME_LIFECYCLE_AUTHORITY_CONTRACT.md) before dispatch. An idempotency key identifies a request for replay handling. It provides no authority. Revocation blocks new calls, while an already dispatched action follows the runtime reconciliation rules.
- Verify the first supported action with scoped fixtures covering an exact grant, sibling-scope denial, revoked grant, stale claim and replay. Keep raw payloads and credentials out of denial messages and audit metadata.

The first supported Skill version, harmless Tool action and resource scope are still product decisions. The current backend exposes no grant mutation or positive execution path for either capability.
