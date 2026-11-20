package mfs.mw.auth.dto;

/** mw-crypto {@code POST /internal/v1/hmac/verify}: {@code keyId} a keyId or a kid (that version). */
public record CryptoHmacVerifyRequest(String keyId, String data, String mac) {

  @Override
  public String toString() {
    return "CryptoHmacVerifyRequest[keyId=" + keyId + ", data=****, mac=****]";
  }
}
