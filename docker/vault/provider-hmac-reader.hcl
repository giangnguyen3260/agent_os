# Separate from the provider credential application identity. Read historical keys only.
path "agentos/data/hmac-keys/credential-integrity" {
  capabilities = ["read"]
}
path "agentos/data/hmac-keys/command-receipts" {
  capabilities = ["read"]
}
