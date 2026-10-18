# Delivery coordination — 2026-10-18

## Decision for this review

The user requests the full designed experience, no hardcoded business data, serial screen delivery and isolated branches. This supersedes an earlier minimal-preview delivery scope. Do not silently defer a designed screen because an old MVP document did so. Implementation of every unique current design state remains open until independently verified.

The audit branch is `review/screen-delivery-audit`; the first implementation branch is `feature/auth-login`. Both were created on GitHub from main `207fced92adb328bf1039970bae6b216f8458fbd`. Audit documents introduce no runtime implementation. No screen is marked Accepted in this review.

First delivery is login with its security/persistence prerequisites, following `AUTH_LOGIN_DELIVERY_CONTRACT.md`. Mission intake is a later vertical slice, not the selected first screen. Continue one screen/flow at a time in BA's ordered queue. A wizard is delivered step by step but cannot be accepted with a dead final submit. Shared dialogs must be verified within their consuming flows; they are not a blanket completed screen.

## Requirement and design baseline

Current Library UI_FLOW_SPEC.md was read in full, version1 (356 lines), identifying v0.3. A local v0.4 draft exists, but its individual policy details are not automatically approved merely because the user previously agreed with BA recommendations. Preserve the user's approved BA direction; reconcile exact acceptance rules against actual approved decisions and current Figma before coding each flow.

Figma page6:2 was inspected live. Metadata contains604 top-level frames including originals and historical revisions. `FIGMA_SCREEN_INVENTORY.md` enumerates467 current Forest/Mobile/Tablet target/reference nodes after excluding two desktop design-direction/component references; these are not467 independent routes. Designer's cross-surface mapping groups them into156 named entries; some are shared references or competing Delivery revisions. AUTH has31 states at each of three breakpoints. Inventory metadata is verified; comprehensive visual acceptance is not. Desktop/mobile login design contexts and screenshots were inspected; all remaining contexts require per-screen inspection.

BA owns exact frame-to-requirement traceability. Designer chooses canonical design revisions consistently across breakpoints and records retained/superseded node IDs. A missing design or unresolved behavior is a recorded dependency, never permission to fabricate working behavior.

## Team handoff and acceptance

| Owner | Required output before screen acceptance |
|---|---|
| BA | User outcome, field rules, state transitions, entry/exit, dependencies and acceptance criteria; full inventory coverage |
| SA | API/error/authorization contract, consistency/concurrency rules, migration and scale implications |
| BE | Real persisted behavior, validation, trusted actor/scope, meaningful unit/integration and migration tests |
| FE | Angular implementation using actual APIs; all actions wired; loading/empty/error/conflict, safe session/context and responsive behavior |
| Designer | Exact Figma node mapping plus rendered desktop/tablet/mobile review, accessibility and visual defects |
| QC | Independent positive/negative browser/API/DB evidence on the exact commit; defect/retest links and regression |
| Coordinator | Review PR, evidence completeness and accurate status; no merge based solely on build success |

Production business values must originate in persisted records/real measured execution. Static labels, design tokens, enum definitions and test fixtures are legitimate; demo organization IDs, credentials, agents, counts, approvals, progress and spend must not stand in for runtime data. An unavailable metric must remain explicitly unavailable until its producer exists, and its designed feature stays incomplete.

Status vocabulary: Planned → Ready → Implementing → In QC → Fix/retest → Accepted. Blocked and Awaiting integration retain the underlying incomplete status. No numeric percent without a fixed accepted-requirement denominator. Build, Docker smoke, functional acceptance and production readiness are distinct.

The current review executes no new functional tests. Previously observed CI successes are historical and do not establish full product acceptance. Sonar is temporarily paused by the user's instruction; preserve all other checks and never label Sonar passed.
