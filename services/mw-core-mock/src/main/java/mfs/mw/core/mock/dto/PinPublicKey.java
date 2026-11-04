package mfs.mw.core.mock.dto;

/** The core's PIN public key as exported to channels and tests (public data, not sensitive). */
public record PinPublicKey(String keyId, String algorithm, String publicKeyPem) {}
