# Recorded execution Run and review flow

ADMIN opens Task → View run → reads scoped run status and the latest 100 attempt summaries → opens review for IN_REVIEW → reads the exact output → confirms ACCEPT or reasoned REWORK → opens Run again to verify current state → returns to Task.

Read endpoints are organization/run and organization/team/mission/task scoped. They require current ADMIN and omit input snapshots, credentials, provider payloads and output bodies. Revisions are decimal strings. Attempt summaries are ordered newest first and bounded at 100. Missing run is distinct from a failed read; no runtime records are synthesized.

ACCEPT leaves Run COMPLETED; REWORK returns Run to CREATED and Task to READY under the existing transactional decision store. Rework does not mean a new model call has occurred. Run reads remain available after review metadata is no longer pending. Decision POST still validates current authority, CSRF, attempt, revision and output hash on the server. Browser authority checks are additional guards only.

This is the full read/review loop for an existing recorded run. It does not implement mission planning, a start command, provider execution, automatic retry, artifact download or mission completion. Those remain distinct delivery work.

Validation: backend integration tests exercise scoped reads, anonymous/member denial, ACCEPT/REWORK and exact replay followed by persisted Run reads. Browser tests use mocked API responses across desktop/tablet/mobile and exercise both complete navigation paths; they are not live provider or live-backend browser acceptance. Production frontend build and lint are checked locally. CI results belong to the published head; inherited picomatch audit failure remains unresolved.
