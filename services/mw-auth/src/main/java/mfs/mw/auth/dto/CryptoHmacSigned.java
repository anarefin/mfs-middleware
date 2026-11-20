package mfs.mw.auth.dto;

/** mw-crypto {@code /hmac/sign} answer: the HMAC (base64) and the key version {@code kid}. */
public record CryptoHmacSigned(String mac, String kid) {

  @Override
  public String toString() {
    return "CryptoHmacSigned[mac=****, kid=" + kid + "]";
  }
}
