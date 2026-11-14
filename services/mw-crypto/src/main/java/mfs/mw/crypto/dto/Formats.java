package mfs.mw.crypto.dto;

/** The field formats of the operation requests (crypto-internal.v1), shared by their Bean Validation. */
public final class Formats {

  /** A keyId ({@code pin-hmac}: its ACTIVE version) or a kid ({@code pin-hmac.v2}: that version). */
  public static final String KEY_REF = "[a-z0-9][a-z0-9-]{0,62}(\\.v[1-9][0-9]{0,5})?";
  /** Standard base64 with padding. */
  public static final String BASE64 = "[A-Za-z0-9+/]*={0,2}";
  /** An HMAC-SHA256 value: 32 bytes as standard base64. */
  public static final String HMAC = "[A-Za-z0-9+/]{43}=";
  /** A wallet id (the MSISDN or another id): no NUL, so {@code walletId ‖ 0x00 ‖ PIN} stays unambiguous. */
  public static final String WALLET_ID = "[A-Za-z0-9._:+-]{1,64}";
  /** Base64 of at most 64 KiB (HMAC data, JWS payload is checked by its JSON size). */
  public static final int MAX_DATA = 87_384;
  /** Base64 of at most 1 MiB (envelope plaintext). */
  public static final int MAX_PLAINTEXT = 1_398_104;
  /** A PEM certificate or public key (ticket 65: partner keys given in the request). */
  public static final int MAX_PEM = 16_384;
  /** An RFC 9421 component name: a derived component {@code @x} or a lower-case field name (ticket 65). */
  public static final String HTTP_COMPONENT = "@?[a-z0-9!#$%&'*+.^_`|~-]{1,128}";
  /** The RFC 9421 algorithms mw-crypto signs and verifies with. */
  public static final String HTTP_SIGNATURE_ALG = "ecdsa-p256-sha256|rsa-pss-sha512|rsa-v1_5-sha256|hmac-sha256";
  /** An RFC 8941 dictionary key (a signature label). */
  public static final String SF_KEY = "[a-z*][a-z0-9_.*-]{0,63}";
  /** The content of an RFC 8941 string: printable ASCII, no {@code "} or {@code \}. */
  public static final String SF_STRING = "[ !#-\\[\\]-~]*";
  /** One component value (a header field value or a derived component). */
  public static final int MAX_COMPONENT_VALUE = 8_192;
  /** A received {@code Signature-Input} or {@code Signature} field value. */
  public static final int MAX_SIGNATURE_FIELD = 16_384;

  private Formats() {}
}
