#!/usr/bin/env python3
"""Explicit operator-only policy administration through Docker PostgreSQL.

No provider credentials are accepted. Success is reported only after psql commits.
"""

import argparse
import json
import re
import subprocess
import sys

PROVIDERS = ("OPENAI", "ANTHROPIC", "GOOGLE_GEMINI")
MAX_REVISION = 9223372036854775807


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("show", "provision", "replace"))
    parser.add_argument("--providers")
    parser.add_argument("--expected-revision")
    parser.add_argument("--sql-only", action="store_true")
    args = parser.parse_args()
    if args.operation == "show":
        if args.providers is not None or args.expected_revision is not None:
            parser.error("show accepts no provider selection or revision")
        return args
    selected = args.providers.split(",") if args.providers else []
    if not selected or len(set(selected)) != len(selected) or any(
        value not in PROVIDERS for value in selected
    ):
        parser.error("--providers requires an explicit nonempty distinct provider subset")
    args.selected = set(selected)
    if args.operation == "replace":
        value = args.expected_revision or ""
        if not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > MAX_REVISION:
            parser.error("replace requires a canonical positive BIGINT --expected-revision")
    elif args.expected_revision is not None:
        parser.error("provision accepts no expected revision")
    return args


def policy_json(alias="p"):
    return (
        "json_build_object('status','CONFIRMED','revision',"
        f"{alias}.revision::text,'providers',json_build_object("
        f"'OPENAI',{alias}.openai_enabled,'ANTHROPIC',{alias}.anthropic_enabled,"
        f"'GOOGLE_GEMINI',{alias}.gemini_enabled))"
    )


def sql_for(args):
    if args.operation == "show":
        return (
            "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
            "SELECT CASE WHEN p.revision<=0 OR p.revision IS NULL "
            "OR p.openai_enabled IS NULL OR p.anthropic_enabled IS NULL "
            "OR p.gemini_enabled IS NULL OR NOT (p.openai_enabled OR p.anthropic_enabled OR p.gemini_enabled) "
            "OR p.created_at IS NULL OR p.updated_at IS NULL OR p.updated_at<p.created_at "
            "OR NOT isfinite(p.created_at) OR NOT isfinite(p.updated_at) "
            f"THEN json_build_object('status','UNAVAILABLE') ELSE {policy_json()} END "
            "FROM provider_deployment_policy p WHERE id=1\n"
            "UNION ALL SELECT json_build_object('status','UNPROVISIONED') "
            "WHERE NOT EXISTS (SELECT 1 FROM provider_deployment_policy WHERE id=1);\n"
            "COMMIT;\n"
        )
    flags = ["true" if item in args.selected else "false" for item in PROVIDERS]
    values = ",".join(flags)
    if args.operation == "provision":
        return (
            "BEGIN ISOLATION LEVEL READ COMMITTED;\n"
            "WITH t AS MATERIALIZED (SELECT clock_timestamp() AS now), "
            "p AS (INSERT INTO provider_deployment_policy "
            "(id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at) "
            f"SELECT 1,1,{values},now,now FROM t RETURNING *) "
            f"SELECT {policy_json()} FROM p;\nCOMMIT;\n"
        )
    expected = args.expected_revision
    return (
        "BEGIN ISOLATION LEVEL READ COMMITTED;\n"
        "WITH locked AS MATERIALIZED (SELECT id FROM provider_deployment_policy WHERE id=1 FOR UPDATE) "
        "SELECT EXISTS (SELECT 1 FROM locked) AS policy_locked \\gset\n"
        "\\if :policy_locked\n"
        "WITH t AS MATERIALIZED (SELECT clock_timestamp() AS now), "
        "decision AS MATERIALIZED (SELECT p.*,t.now,CASE "
        "WHEN p.revision IS NULL OR p.revision<=0 OR p.created_at IS NULL "
        "OR p.updated_at IS NULL OR NOT isfinite(p.created_at) OR NOT isfinite(p.updated_at) "
        "OR p.updated_at<p.created_at OR p.openai_enabled IS NULL "
        "OR p.anthropic_enabled IS NULL OR p.gemini_enabled IS NULL "
        "OR NOT (p.openai_enabled OR p.anthropic_enabled OR p.gemini_enabled) "
        "THEN 'UNAVAILABLE' "
        f"WHEN p.revision<>{expected} THEN 'REVISION_CONFLICT' "
        f"WHEN (p.openai_enabled,p.anthropic_enabled,p.gemini_enabled)=({values}) "
        "THEN 'NO_CHANGE' "
        f"WHEN p.revision={MAX_REVISION} THEN 'REVISION_EXHAUSTED' "
        "WHEN t.now<p.updated_at THEN 'UNAVAILABLE' ELSE 'UPDATE' END AS outcome "
        "FROM t LEFT JOIN provider_deployment_policy p ON p.id=1), "
        "changed AS (UPDATE provider_deployment_policy p SET revision=p.revision+1,"
        f"openai_enabled={flags[0]},anthropic_enabled={flags[1]},gemini_enabled={flags[2]},"
        "updated_at=d.now FROM decision d WHERE p.id=1 AND d.outcome='UPDATE' RETURNING p.*) "
        f"SELECT {policy_json('c')} FROM changed c UNION ALL "
        "SELECT json_build_object('status',outcome) FROM decision WHERE outcome<>'UPDATE';\n"
        "\\else\nSELECT json_build_object('status','UNAVAILABLE');\n\\endif\nCOMMIT;\n"
    )


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def validate_response(args, stdout):
    lines = [line.strip() for line in stdout.splitlines() if line.strip()]
    if len(lines) != 1:
        raise ValueError("unexpected output")
    response = json.loads(lines[0], object_pairs_hook=unique_object)
    if not isinstance(response, dict) or not isinstance(response.get("status"), str):
        raise ValueError("invalid status")
    status = response["status"]
    if status != "CONFIRMED":
        allowed = {"show": {"UNPROVISIONED", "UNAVAILABLE"},
                   "provision": set(),
                   "replace": {"UNAVAILABLE", "REVISION_CONFLICT", "NO_CHANGE", "REVISION_EXHAUSTED"}}
        if set(response) != {"status"} or status not in allowed[args.operation]:
            raise ValueError("unexpected rejection")
        return {"status": status}
    if set(response) != {"status", "revision", "providers"}:
        raise ValueError("invalid fields")
    revision = response["revision"]
    flags = response["providers"]
    if not isinstance(revision, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", revision) or int(revision) > MAX_REVISION:
        raise ValueError("invalid revision")
    if not isinstance(flags, dict) or set(flags) != set(PROVIDERS) or any(type(v) is not bool for v in flags.values()) or not any(flags.values()):
        raise ValueError("invalid providers")
    if args.operation != "show":
        if {key for key, enabled in flags.items() if enabled} != args.selected:
            raise ValueError("unexpected selection")
        expected = 1 if args.operation == "provision" else int(args.expected_revision) + 1
        if int(revision) != expected:
            raise ValueError("unexpected revision")
    return {"status": status, "revision": revision,
            "providers": {key: flags[key] for key in PROVIDERS}}


def main():
    args = arguments()
    sql = sql_for(args)
    if args.sql_only:
        print(sql, end="")
        return 0
    command = [
        "docker", "compose", "exec", "-T", "postgres", "sh", "-c",
        'exec psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ]
    try:
        result = subprocess.run(
            command, input=sql, text=True, capture_output=True, timeout=45, check=False
        )
        if result.returncode != 0:
            raise ValueError("unconfirmed")
        response = validate_response(args, result.stdout)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        print(json.dumps({"status": "UNCONFIRMED", "action": "Run show before deciding; do not retry automatically"}))
        return 2
    print(json.dumps(response, separators=(",", ":")))
    return 0 if response["status"] == "CONFIRMED" else 1


if __name__ == "__main__":
    sys.exit(main())
