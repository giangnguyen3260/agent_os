#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
docker compose --env-file "$repo_dir/.env" -f "$repo_dir/docker/docker-compose.yml" up -d
