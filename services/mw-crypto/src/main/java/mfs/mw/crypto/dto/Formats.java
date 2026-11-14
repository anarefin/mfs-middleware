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

  private Formats() {}
}
