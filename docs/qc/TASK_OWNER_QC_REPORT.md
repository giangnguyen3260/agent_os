# Task owner configuration QC

Status: implementation and source corrections in progress on `feature/task-draft-owner`, based on PR33 closure `ef94202a4d98b16c0804f0f32a69c8fce7d89d85`. No runtime acceptance, merge or deployment is claimed.

The [frozen contract](../engineering/TASK_OWNER_CONTRACT.md) defines explicit assignment/replacement/clear of a DRAFT task's configured owner. Real same-organization, same-Team DRAFT/ACTIVE candidates are required. Unavailable foreign owner projection is safe and explicitly clearable. This slice adds no runtime, grants, status or dependency writer.

Required acceptance: Google Java style and actual backend compilation/tests; V019→20 retention and native PostgreSQL lock/revocation/races/rollback; strict HTTP and historical exact receipt recovery; frontend lint/format/build and validator checks; new real-backend browser cases plus all 780 retained instances; 30 actual responsive captures with hashes and independent review; Docker session restart/logout. Counts and links will be recorded from completed runs, not inferred from authored tests.

Local environment has Java17, without Maven/Java21 or Docker. Backend runtime must be verified by the configured CI runners. SonarQube remains paused and is not a passing gate. The independent source [review](TASK_OWNER_REVIEW.md) records concrete corrections and evidence limits; [cases](TASK_OWNER_TEST_CASES.md) are authored acceptance requirements, not pass results.
