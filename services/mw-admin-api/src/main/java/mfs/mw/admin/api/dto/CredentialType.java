package mfs.mw.admin.api.dto;

/**
 * What a partner credential is (ticket 131): an API key or an HMAC secret (one Vault field named
 * after the credential), or a client certificate with its key (the PKCS#12 key store and its
 * password, the fields {@code <name>-keystore} / {@code <name>-keystore-password} the adapters'
 * mTLS contexts read).
 */
public enum CredentialType {
  API_KEY,
  HMAC_SECRET,
  CLIENT_CERT
}
