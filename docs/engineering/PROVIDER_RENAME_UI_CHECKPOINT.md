# Rename UI checkpoint

11 November 2026. The backend PR76 head `edd21994` passed all seven workflows.
Rename UI and its capability header are implemented on `feature/provider-rename-ui`.

## Bounded product flow

Use the actual list connection's current revision and normalized name. The action
is gated independently by an explicit Rename capability header, not Create
availability or adapter/connection enabled state. The backend exposes this header on the authorized options response, with native
assertions for its default-off and enabled states.

The user edits a selected name, reviews current and proposed names with the
configuration revision, and explicitly confirms one POST. Unchanged/invalid names
cannot submit. Stale/NO_CHANGE requires current list refresh before another
review; known bounded rate rejection is advisory, with no automatic retry.
Authority/scope loss clears private review/recovery state and ignores stale replies.
A verified committed receipt triggers current list readback; historical receipt
confirmation does not assert present configuration or runtime eligibility.

UNKNOWN offers manual own-receipt GET only. Null remains unresolved; malformed,
foreign or mismatched receipt never confirms success. This UI slice offers no
resend, even though the broader metadata contract permits an explicitly designed
exact resend. No automatic POST/GET retry, polling or new-key recreation belongs
to this flow. Recovery locators/review state stay scoped in memory.

## Design and acceptance boundaries

The existing [Figma handoff](PROVIDER_CONNECTION_FIGMA_HANDOFF.md) records Rename
frames at 1440/768/390 and shared review/UNKNOWN states. This candidate follows
those existing Forest patterns; no new Figma edit, pixel-parity or accessibility
acceptance is inferred from reading the handoff.

Local verification passed 12 wire-validator groups and 42 route-mocked Rename
browser cases at desktop, tablet and mobile widths. The prior browser run also
passed all 45 retained Create/list cases. Angular build, lint and formatting pass.
Independent source review checked scope cancellation and fail-closed receipt handling.

The managed browser test executes one confirmed Rename and a second committed
Rename with a deliberately malformed response. It checks manual receipt recovery,
current list readback, unchanged stored credentials and exactly two submitted commands.
This test and the native capability checks await execution in GitHub CI.

[PM checklist](../qc/PROVIDER_RENAME_UI_CHECKLIST.md)
