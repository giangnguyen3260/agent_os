# Separate KV-v2 mount. CAS=0 creates immutable per-preparation paths.
# No update, patch, list, delete, destroy, metadata or administrative access.
path "agentos/data/provider-credentials/*" {
  capabilities = ["create", "read"]
}
