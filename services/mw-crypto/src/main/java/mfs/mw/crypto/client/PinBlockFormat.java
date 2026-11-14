package mfs.mw.crypto.client;

/** ISO 9564-1 PIN block formats and their payShield format codes (ticket 64, spec 18.8). */
public enum PinBlockFormat {
  ISO0("01"),
  ISO1("05"),
  ISO3("47"),
  /** AES (format 4): a 16-byte block under an AES-128 key; the others are 8 bytes under triple DES. */
  ISO4("48");

  private final String code;

  PinBlockFormat(String code) {
    this.code = code;
  }

  /** The payShield format code. */
  public String code() {
    return code;
  }

  /** Hex digits of a block of this format. */
  public int hexLength() {
    return this == ISO4 ? 32 : 16;
  }
}
