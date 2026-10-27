# Main integration

Import `TaskDraftEditComponent` from `./features/task-edit/task-draft-edit.component`.
Import `taskEditNavigationGuard` and `taskEditLeaveGuard` from `./features/task-edit/task-edit-navigation.guard`.

Register the editor route with the existing authenticated-session `authGuard`, `canActivate: [authGuard, taskEditNavigationGuard]`, `canDeactivate: [taskEditLeaveGuard]`, and `runGuardsAndResolvers: 'always'`. The identity guard checks all four route IDs against `TaskEditNavigationState.active`; this catches reused component parameter navigation as well as leave/back navigation. The component registers/clears that active instance and deduplicates prompts by Router navigation ID. Native beforeunload covers reload/close.

The component checks a fresh session and raw workspace ADMIN role before loading private context or rendering the editor and before saving/recovery. Direct MEMBER entry renders denied; the new API separately enforces current ADMIN authority. No invitation guard is imported.

Actual service export: `TaskEditService` in `task-edit.service.ts`. It uses a raw workspace request to retain 403/404 denial semantics, bounded requests and cancellation fences. Existing TaskDetail DTO is untouched.

Design: full Figma context/screenshots fetched for desktop 47:2950, mobile 60:7808, tablet 60:14424, session a826dd3c-03a0-4715-abd6-8ec0358eea33. Reuses native Forest shell and Studio styling, adapts S17 form spacing to four persisted DRAFT settings; no missing-input execution workflow, assets, React or Tailwind.

Validation harness: `node scripts/test-task-edit-validation.cjs` executes transpiled production validators and component methods with controlled dependencies. Browser integration/QC remains coordinator-owned.
