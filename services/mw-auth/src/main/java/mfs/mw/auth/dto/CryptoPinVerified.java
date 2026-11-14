package mfs.mw.auth.dto;

/**
 * mw-crypto {@code /pin/verify} answer: match or not; on a match under a VERIFY_ONLY key version,
 * {@code rehash} is the value under the ACTIVE version to store instead.
 */
public record CryptoPinVerified(boolean match, PinHmac rehash) {}
