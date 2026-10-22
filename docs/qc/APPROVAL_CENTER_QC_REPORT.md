# Approval Center QC — 22 October 2026

Status: bounded read implementation and Code Quality accepted; Docker/browser/visual acceptance pending. Branch feature/approval-center based on3cfaa56 (draft/unmerged tooling PR18).

## Scope

Forest S14 desktop/mobile/tablet and information/closed group references inspected. Real GET metadata list, three actual approval groups/counts, fixed20 pagination, stable sort, URL/reload, inline safe metadata inspection and existing scoped Mission/Task links. V010 adds metadata ledger with composite scope constraints and no production seed. New installs have an honestly empty ledger until a future authorized Governance producer exists. Pending tasks are never approval requests. Decision rights, payload snapshots, runtime resume and S15 decision workflow remain incomplete. Org ADMIN/MEMBER read access is not an approval grant. No business write endpoint or provider call.

## Evidence

- Frontend lint/build passed; final formatting check and visual QA tracked separately.
- Google Java formatter applied; import and whitespace checks passed. Local Maven verify blocked before compilation because environment proxy/registry connection failed; no local backend pass claimed.
- Added6 meaningful backend integration cases,18 real-backend browser instances (6cases ×3viewports), disposable isolated SQL fixture and native PostgreSQL exact projection/order/paging/moved-requester/scope/no-write probe. Merely collecting test names does not count as execution.
- Independent QC agent hit usage limit before producing a review. Coordinator-authored tests and final source review are not independent QC acceptance.

## Remaining gates and limits

Record exact candidate/merge/run/job identifiers and completed Code Quality/Docker logs before acceptance. Expected baseline121backend/300browser plus this slice; actual completed logs determine counts. Verify fresh authorization/credential version/revocation, SQL scope constraints, malformed/failure clearing, menu Escape/focus, overflow and actual task hrefs. Snapshot comparison must confirm no business writes. Sonar paused, not passed. No Mac deployment or production/penetration/load acceptance claim.

Initial candidate dd9b1d5 Quality run39818790331/job119964193319 compiled and ran127tests:126passed, one new assertion expected405 for MEMBER POST but security correctly denied403. Follow-up tests assert MEMBER403 plus ADMIN405; no application authorization change. Local Chromium launch was blocked by host socket permission, so local render checks did not execute. Dedicated CI screenshots contain only this isolated synthetic Approval QC fixture; no session storage, trace, credential or real user data is captured.

## Current revision and remote Quality

Candidate `600cadf398d4e715b9244d51a7e2c09537a5e9a8`, checked merge `100ec1a6624f5b4948844b196dc2efd24e7185dd` onto `3cfaa56bd1ec65d6ab81fb0bb190932061ed6c93`. [Quality run39819318947](https://github.com/giangnguyen3260/agent_os/actions/runs/39819318947), job119966082110: success. Completed log shows6new governance cases and127total backend tests, zero failures/errors/skips at17:05:02 UTC, Maven BUILD SUCCESS17:05:06, including-dev audit zero vulnerabilities, FE style/build and actual CLI/dev-server tooling checks. Docker run39819318976/job119966085250 pending.

Docker candidate600cadf run39819318976/job119966085250 applied V010 and passed prior native probes, then stopped before new probe/browser execution: isolated membership fixture selected ambiguous `id` from account/org join. Follow-up qualifies `a.id` and `a.email`; application and migration unchanged. This failed run is not acceptance.
