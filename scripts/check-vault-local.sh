#!/usr/bin/env bash
# CI-only disposable Vault. Never point this at an existing project or volume.
set -euo pipefail
umask 077
scratch=$(mktemp -d)
project="agentos-vault-ci-${RANDOM}-$$"
compose=(docker compose -p "$project" -f compose.vault.yaml -f compose.vault-ci.yaml)
cleanup() {
  "${compose[@]}" down -v >/dev/null 2>&1 || true
  rm -rf "$scratch"
}
trap cleanup EXIT
export AGENTOS_VAULT_TLS_DIR="$scratch/tls"
export AGENTOS_TEST_PG_PASSWORD_FILE="$scratch/pg-password"
openssl rand -hex 32 > "$AGENTOS_TEST_PG_PASSWORD_FILE"
# Official PostgreSQL entrypoint reads this file before dropping privileges.
chmod 600 "$AGENTOS_TEST_PG_PASSWORD_FILE"
bash docker/vault/prepare-local-tls.sh "$AGENTOS_VAULT_TLS_DIR"
gid=$(docker run --rm --entrypoint id "$AGENTOS_VAULT_IMAGE" -g vault)
sudo chgrp "$gid" "$scratch" "$AGENTOS_VAULT_TLS_DIR" "$AGENTOS_VAULT_TLS_DIR/server.key"
chmod 750 "$scratch"
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait journal-db
pg_address=$("${compose[@]}" port journal-db 5432)
export AGENTOS_TEST_JOURNAL_URL="jdbc:postgresql://127.0.0.1:${pg_address##*:}/vault_journal_test"
"${compose[@]}" up -d vault

base=https://127.0.0.1:8200
curl_args=(--noproxy '*' --silent --show-error --connect-timeout 2 --max-time 10
  --cacert "$AGENTOS_VAULT_TLS_DIR/server.crt")
for attempt in {1..30}; do
  code=$(curl "${curl_args[@]}" -o "$scratch/status" -w '%{http_code}' "$base/v1/sys/health" || true)
  if [[ "$code" == 501 ]]; then break; fi
  sleep 1
done
[[ "$code" == 501 ]] || {
  # Safe only before initialization: no root/app token or credential exists yet.
  "${compose[@]}" logs --tail 30 vault
  echo "Vault did not reach uninitialized state" >&2
  exit 1
}
request() {
  local expected=$1 method=$2 path=$3
  shift 3
  local status
  status=$(curl "${curl_args[@]}" -X "$method" -o "$scratch/response" -w '%{http_code}' "$@" "$base/v1/$path")
  [[ "$status" == "$expected" ]] || {
    echo "Vault assertion failed: $method $path status $status, expected $expected" >&2
    exit 1
  }
}
# One-share initialization is confined to this throwaway CI instance.
request 200 PUT sys/init --data '{"secret_shares":1,"secret_threshold":1}'
jq -e '.root_token and .keys_base64[0]' "$scratch/response" >/dev/null
jq -r '"X-Vault-Token: "+.root_token' "$scratch/response" > "$scratch/root.headers"
jq '{key:.keys_base64[0]}' "$scratch/response" > "$scratch/unseal.json"
request 200 PUT sys/unseal --data-binary "@$scratch/unseal.json"
jq -e '.sealed==false' "$scratch/response" >/dev/null
request 204 POST sys/mounts/agentos -H "@$scratch/root.headers" --data '{"type":"kv","options":{"version":"2"}}'
jq -Rs '{policy:.}' docker/vault/provider-app.hcl > "$scratch/policy.json"
request 204 PUT sys/policies/acl/agentos-provider-app -H "@$scratch/root.headers" --data-binary "@$scratch/policy.json"
request 200 POST auth/token/create -H "@$scratch/root.headers" --data '{"policies":["agentos-provider-app"],"no_default_policy":true,"ttl":"10m"}'
jq -er '"X-Vault-Token: "+.auth.client_token' "$scratch/response" > "$scratch/app.headers"
jq -er '.auth.client_token' "$scratch/response" > "$scratch/app.token"
export AGENTOS_TEST_VAULT_URL="$base"
export AGENTOS_TEST_VAULT_CA="$AGENTOS_VAULT_TLS_DIR/server.crt"
export AGENTOS_TEST_VAULT_TOKEN_FILE="$scratch/app.token"
export AGENTOS_TEST_VAULT_REFERENCE_FILE="$scratch/java-reference.json"
java_probe() {
  AGENTOS_TEST_VAULT_PHASE="$1" mvn -B -ntp -f backend/pom.xml \
    -Dtest=VaultCredentialSecretsVaultIT test
}
# Provision independent random HMAC keys only in the disposable Vault.
jq -Rs '{policy:.}' docker/vault/provider-hmac-reader.hcl > "$scratch/hmac-policy.json"
request 204 PUT sys/policies/acl/agentos-hmac-reader -H "@$scratch/root.headers" --data-binary "@$scratch/hmac-policy.json"
hmac_fixture() {
  local purpose=$1 cas=$2 name=$3
  openssl rand -base64 32 > "$scratch/$name"
  jq -n --rawfile material "$scratch/$name" --arg purpose "$purpose" --argjson cas "$cas" \
    '{options:{cas:$cas},data:{purpose:$purpose,algorithm:"HmacSHA256",key:($material|rtrimstr("\n"))}}' > "$scratch/hmac-write.json"
  request 200 POST "agentos/data/hmac-keys/$purpose" -H "@$scratch/root.headers" --data-binary "@$scratch/hmac-write.json"
}
hmac_fixture credential-integrity 0 credential-v1
hmac_fixture credential-integrity 1 credential-v2
hmac_fixture command-receipts 0 command-v1
request 200 POST auth/token/create -H "@$scratch/root.headers" --data '{"policies":["agentos-hmac-reader"],"no_default_policy":true,"ttl":"10m"}'
jq -er '.auth.client_token' "$scratch/response" > "$scratch/hmac.token"
jq -er '"X-Vault-Token: "+.auth.client_token' "$scratch/response" > "$scratch/hmac.headers"
request 403 GET agentos/data/hmac-keys/credential-integrity -H "@$scratch/app.headers"
request 403 POST agentos/data/hmac-keys/credential-integrity -H "@$scratch/hmac.headers" --data-binary "@$scratch/hmac-write.json"
request 403 GET agentos/data/provider-credentials/fixture -H "@$scratch/hmac.headers"
export AGENTOS_TEST_HMAC_TOKEN_FILE="$scratch/hmac.token"
export AGENTOS_TEST_HMAC_FIXTURE_DIR="$scratch"
java_probe prepare
# The only credential value here is an explicitly synthetic CI sentinel.
request 200 POST agentos/data/provider-credentials/fixture -H "@$scratch/app.headers" --data '{"options":{"cas":0},"data":{"credential":"synthetic-vault-ci-only"}}'
jq -e '.data.version==1' "$scratch/response" >/dev/null
request 200 GET 'agentos/data/provider-credentials/fixture?version=1' -H "@$scratch/app.headers"
jq -e '.data.data.credential=="synthetic-vault-ci-only" and .data.metadata.version==1' "$scratch/response" >/dev/null
request 403 POST agentos/data/provider-credentials/fixture -H "@$scratch/app.headers" --data '{"options":{"cas":1},"data":{"credential":"rejected"}}'
request 403 DELETE agentos/data/provider-credentials/fixture -H "@$scratch/app.headers"
request 403 LIST agentos/metadata/provider-credentials -H "@$scratch/app.headers"
request 403 GET agentos/data/foreign-scope/fixture -H "@$scratch/app.headers"
request 403 GET agentos/data/provider-credentials/fixture
# CA trust alone must not bypass hostname validation.
if curl "${curl_args[@]}" --resolve wrong.invalid:8200:127.0.0.1 https://wrong.invalid:8200/v1/sys/health > /dev/null 2>&1; then
  echo "Vault accepted a mismatched TLS hostname" >&2
  exit 1
fi
"${compose[@]}" restart vault journal-db >/dev/null
"${compose[@]}" up -d --wait journal-db
# Docker may reassign an ephemeral published port on restart.
pg_address=$("${compose[@]}" port journal-db 5432)
export AGENTOS_TEST_JOURNAL_URL="jdbc:postgresql://127.0.0.1:${pg_address##*:}/vault_journal_test"
for attempt in {1..30}; do
  code=$(curl "${curl_args[@]}" -o "$scratch/status" -w '%{http_code}' "$base/v1/sys/health" || true)
  if [[ "$code" == 503 ]]; then break; fi
  sleep 1
done
[[ "$code" == 503 ]]
request 200 PUT sys/unseal --data-binary "@$scratch/unseal.json"
request 200 GET 'agentos/data/provider-credentials/fixture?version=1' -H "@$scratch/app.headers"
jq -e '.data.data.credential=="synthetic-vault-ci-only" and .data.metadata.version==1' "$scratch/response" >/dev/null
java_probe restart
echo "Disposable Vault TLS, KV policy, Java adapter and restart checks passed"
