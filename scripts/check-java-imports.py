"""Reject wildcard imports, which Google Java Style forbids."""

from pathlib import Path
import re
import sys

source_root = Path(__file__).resolve().parents[1] / "backend" / "src"
violations = []
for source in sorted(source_root.rglob("*.java")):
    for line_number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
        if re.match(r"^\s*import\s+.*\.\*\s*;", line):
            violations.append(f"{source.relative_to(source_root)}:{line_number}: wildcard import")
if violations:
    print("\n".join(violations), file=sys.stderr)
    sys.exit(1)
print("Google Java import check passed")
