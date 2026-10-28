# Mission intake history QC evidence

Implementation candidate on feature/mission-intake-history, base188098c65cb68232022486826a7f6bcb5053ee55 (draft/unmerged PR32). No merge/deploy. Bounded read-only actual provenance, not dispatch/execution history. V019 is an additive scoped chronology index with no business-row backfill or production seeds.

Local frontend lint/format/build, production history schema/int64/time-order/authority/privacy/state harness, retained intake harness, Java imports, native/browser syntax and diff checks passed. Backend worker applied Google Java Format1.22. Browser27case discovery is not execution. Host lacks Java21/Maven/Docker; backend/PostgreSQL/browser/visual acceptance pending CI. No prior slice's pass counts are reused.

Main native probe creates26 actual accepted flips across two actors, actual Task Create gap and controlled legacy high counter; tie ordering deliberately adjusts test timestamps consistently on existing accepted receipts then restores them. Read/rejection snapshots cover all persisted business tables, excluding authentication bookkeeping. Required gate covers safe actor projection/null, current authority, exact receipt projection, paging/ties/int64, lifecycle, corruption and no writes. Browser uses separate persisted prefix540 fixtures and creates21 actual accepted changes per viewport; no fabricated DTO success. Five capture states × three widths require actual runtime artifacts and coordinator review.

Dedicated new browser slice precedes full753retained baseline and uploads its visual artifact immediately. One worker, zero retries/skips, existing45minute job timeout unchanged. Sonar remains paused. Frozen contract and independent source/case reviews identify exact evidence boundaries; final acceptance pending.


Independent source/probe review before first publication identified QC-H1 (C1 control rejection mismatch in history names), H2 (Python microsecond truncation in ordering oracle) and H3 (loose native UUID/name validation). Coordinator strengthens only the new history name decoder (including visible workspace name), preserves all9 timestamp fraction digits and cross-page order checks, enforces canonical UUID/safe nonblank255 names, and keeps a foreign membership while testing same-org author removal. These are source corrections; runtime acceptance still pending.
