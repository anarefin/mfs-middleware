package mfs.mw.crypto.client;

/**
 * The payment-HSM operations of a provider with {@link Capability#PIN_BLOCK} and {@link
 * Capability#PVV} (ticket 64, spec 18.8): ISO 9564 PIN block translation and Visa PVV, with the clear
 * PIN only ever inside the HSM. Keys are named by the reference stored with their version (for
 * payShield the key blob under the LMK). PIN blocks are upper-case hex. Failures as {@link
 * CryptoProvider}: {@code MW-VAL-014} a PIN block the HSM refuses, {@code MW-VAL-015} a key the HSM
 * refuses (parity: corrupt or of another type), {@code MW-SYS-010} the HSM down or refusing the command.
 */
public interface PinBlockProvider {

  /**
   * A PIN key and whether it is a terminal key (TPK: ATM, POS) or a zone key (ZPK: switch, scheme).
   *
   * @param ref the key version's HSM reference
   */
  record PinKey(String ref, boolean terminal) {}

  /** The PIN of {@code block} (under {@code source}) as a {@code to} block under the ZPK {@code dest}. */
  String translate(PinKey source, String block, PinBlockFormat from, PinKey dest, PinBlockFormat to, String account);

  /** The Visa PVV (4 digits) of the PIN in {@code block} under the PVK pair {@code pvkRef} and {@code pvki}. */
  String generatePvv(String pvkRef, PinKey key, String block, PinBlockFormat format, String account, int pvki);

  /** Whether the PIN in {@code block} matches {@code pvv}; a wrong PIN is false, not an error. */
  boolean verifyPvv(String pvkRef, PinKey key, String block, PinBlockFormat format, String account, int pvki,
      String pvv);
}
