#!/usr/bin/env bash
# Read-only probe for the disposable restart fixture; never retries a mutation.
await_vault_restart_fixture() {
  local status attempt
  for attempt in {1..15}; do
    status=$(curl "${curl_args[@]}" -X GET -o "$scratch/response" -w '%{http_code}' \
      -H "@$scratch/app.headers" "$base/v1/agentos/data/provider-credentials/fixture?version=1" || true)
    case "$status" in
      200) return 0 ;;
      000|500|502|503|504)
        echo "Vault restart read not ready: HTTP $status (attempt $attempt/15)" >&2 ;;
      *) echo "Vault restart read rejected: HTTP $status" >&2; return 1 ;;
    esac
    if (( attempt < 15 )); then sleep 1; fi
  done
  echo "Vault restart fixture did not become readable" >&2
  return 1
}
