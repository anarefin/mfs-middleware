package mfs.mw.crypto.dto;

/**
 * Answer of the dev-only {@code GET /test/v1/payshield/keys/{purpose}} (ticket 76): the ACTIVE
 * version of the purpose's key and its blob under the simulator's LMK, which the simulator's {@code
 * /sim/payshield/pinblock} needs to make the PIN block a terminal sharing that key would send. Not a
 * clear key: only an HSM holding the LMK can use it. Never served outside dev.
 *
 * @param keyId the key ({@code tpk})
 * @param kid the version, {@code <keyId>.v<n>}
 * @param purpose TPK, ZPK, TPK_AES or ZPK_AES
 * @param keyBlock the key under the LMK ({@code U...} triple DES, {@code S...} AES)
 */
public record PayShieldTestKey(String keyId, String kid, String purpose, String keyBlock) {}
