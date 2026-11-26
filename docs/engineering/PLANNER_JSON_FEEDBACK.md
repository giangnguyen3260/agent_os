# Inline feedback while correcting a planner proposal

The reviewed import editor reports local syntax errors, the 64KiB UTF8 bound, invalid top-level JSON/schema version, Task count and the existing key/title row projection errors. Feedback is advisory and blocks preview while these local checks fail. Empty input remains the initial state, not an error banner. Corrections remove the banner when these checks pass; no sample or source text is rewritten and no preview/apply is sent by editing.

This is not a replacement for authoritative backend validation: required text, exact schema/unknown fields, duplicate JSON fields, dependencies/cycles, current owner/authority/intake/source provenance and final transactional eligibility remain server checks. A missing local banner does not establish a valid or authorized plan. Existing human-correction attribution and explicit owner/review/apply workflow remain.

Browser regression includes invalid syntax, invalid schema version, duplicate keys, corrected JSON and no automatic mutations across three viewports, plus retained proposal review/replay/authority scenarios. Native/real-provider acceptance is separate. No backend/schema/permissions changes, merge or deployment.
