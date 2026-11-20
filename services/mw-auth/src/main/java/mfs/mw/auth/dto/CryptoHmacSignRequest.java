package mfs.mw.auth.dto;

/** mw-crypto {@code POST /internal/v1/hmac/sign}: HMAC-SHA256 of {@code data} (base64) under an HMAC_<use> key. */
public record CryptoHmacSignRequest(String keyId, String data) {

  @Override
  public String toString() {
    return "CryptoHmacSignRequest[keyId=" + keyId + ", data=****]";
  }
}
