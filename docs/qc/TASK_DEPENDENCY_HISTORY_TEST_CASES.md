# Task dependency history acceptance cases

Authored cases, not execution results. See [QC status](TASK_DEPENDENCY_HISTORY_QC_REPORT.md).

| Area | Expected result | Evidence implementation |
| --- | --- | --- |
| Empty legacy configuration | Existing edges with no event produce genuine total0; no inferred author/baseline | Backend empty case, browser01 |
| Actual provenance |21 accepted dependency commands produce descending21 events; first20 and final1 match DB arrays | Backend paging, native probe, browser02 |
| Independent snapshots | Target metadata/current graph/lifecycle changes do not rewrite or hide recorded arrays | Backend lifecycle/cycle case, browser03 |
| Member privacy | Current author rename follows membership; removed membership explicit null, no foreign lookup/raw actor ID | Backend actor case, native probe, browser09 |
| Current authorization | MEMBER/foreign/unauthenticated denied before malformed query; exact structural scope enforced | Backend authority, native probe, browser04/06 |
| Strict history | Exact keys/types/safe text/sorted known-state arrays, no self/duplicate/same-set history; malformed selected data safe500 | Backend corruption parameters, production validator harness |
| Page isolation | Off-page corruption does not hide valid page; actual total retained for out-of-range pages | Backend off-page, native probe, browser08 |
| Transport/decoder errors | No empty-success fallback; rows cleared; explicit GET retry only | Browser05, state harness |
| Late replies | Role/account/scope/page/epoch/destruction fences prevent stale success/error publication | State harness, browser06/07 |
| Read-only | Successful GET and denials preserve committed values/tuple identity; no browser business POST | Native snapshot oracle, browser snapshots |
| Responsive | Full before/after lists, dark text, wrapping, no horizontal overflow, native menu/Back | All30 cases,15 planned captures |

Negative/setup SQL is restricted to disposable prefix580 fixtures and is named/restored where shared later. Production migrations do not seed history. Corrupt response delivery is a negative transport test; successful business state is always obtained from real APIs.
