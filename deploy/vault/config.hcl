# HashiCorp Vault server config for the single-host stack (spec 18.4, D11: one instance; D30).
# Raft integrated storage on the vault-data volume (snapshots for backup, 20.5).
storage "raft" {
  path    = "/vault/file"
  node_id = "vault-1"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  # TLS between services is a later ticket (mTLS with Vault-issued certs, 18.2).
  tls_disable = true
}

api_addr      = "http://vault:8200"
cluster_addr  = "http://vault:8201"
disable_mlock = true
ui            = true
