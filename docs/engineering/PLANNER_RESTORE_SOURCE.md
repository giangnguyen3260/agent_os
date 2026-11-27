# Restore accepted planner source while correcting a proposal

The import editor offers explicit restoration of the already loaded accepted source. Disable it when source is absent/unchanged, an operation is busy or an apply command remains uncertain. Confirmation describes replacement and loss of current preview/owner selections; dismissal leaves correction and selections untouched. Acceptance uses the existing edit transition, preserving source attribution while clearing owners and preview and requiring a fresh review.

No source reread, preview, apply, new command or provider request occurs. Restoring malformed accepted output is permitted as an editing action; local feedback and authoritative backend checks still reject it for import. The control does not apply sample JSON or authorize execution. Browser coverage verifies dismissal, restoration, cleared owner selection and no automatic POST; retained correction/replay/authority tests remain.
