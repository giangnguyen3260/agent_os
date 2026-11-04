# Single-node local development only; retain init/unseal material outside this repository.
ui = false
disable_mlock = true
api_addr = "https://127.0.0.1:8200"
cluster_addr = "https://vault:8201"

storage "raft" {
  path    = "/vault/data"
  node_id = "agentos-local"
}

listener "tcp" {
  address         = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_cert_file   = "/vault/tls/server.crt"
  tls_key_file    = "/vault/tls/server.key"
  tls_min_version = "tls12"
}
