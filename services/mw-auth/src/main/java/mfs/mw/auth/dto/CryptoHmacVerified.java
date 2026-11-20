package mfs.mw.auth.dto;

/** mw-crypto {@code /hmac/verify} answer: valid or not, and the version that matched. */
public record CryptoHmacVerified(boolean valid, String kid) {}
