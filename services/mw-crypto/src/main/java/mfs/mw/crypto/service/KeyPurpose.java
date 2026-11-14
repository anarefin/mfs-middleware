package mfs.mw.crypto.service;

import java.util.Locale;
import java.util.regex.Pattern;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.exception.CryptoErrors;

/**
 * What a key is for (spec 18.8 key hierarchy; JWT_SIGN is RSA, RS256, by the 2026-11-14 amendment
 * of D21), which fixes its algorithm, the provider capability it needs and, for a key pair, how its
 * public key is published as a JWK. {@code TOKENISE_<domain>} and {@code KEK_<purpose>} name one key
 * per domain or purpose ({@code [A-Z][A-Z0-9]{0,23}}).
 *
 * @param name the purpose as stored ({@code crypto.key.purpose})
 * @param jwkAlgorithm the JWK {@code alg} of the public key, null for a secret key
 * @param jwkUse the JWK {@code use} ({@code sig} or {@code enc}), null for a secret key
 */
public record KeyPurpose(
    String name, KeyAlgorithm algorithm, Capability capability, String jwkAlgorithm, String jwkUse) {

  private static final Pattern SUFFIX = Pattern.compile("[A-Z][A-Z0-9]{0,23}");

  /** The purpose named {@code name}, or {@code MW-VAL-011}. */
  public static KeyPurpose parse(String name) {
    if (name == null) {
      throw CryptoErrors.unknownPurpose("(none)");
    }
    return switch (name) {
      case "PIN_TRANSPORT" -> new KeyPurpose(name, KeyAlgorithm.RSA_3072, Capability.PIN_HMAC, "RSA-OAEP-256", "enc");
      case "PIN_HMAC" -> new KeyPurpose(name, KeyAlgorithm.HMAC_SHA256, Capability.PIN_HMAC, null, null);
      case "JWT_SIGN" -> new KeyPurpose(name, KeyAlgorithm.RSA_3072, Capability.JWS, "RS256", "sig");
      case "ASSERTION_SIGN", "AUDIT_ANCHOR_SIGN" ->
          new KeyPurpose(name, KeyAlgorithm.EC_P256, Capability.JWS, "ES256", "sig");
      case "XMLDSIG" -> new KeyPurpose(name, KeyAlgorithm.RSA_3072, Capability.XMLDSIG, "RS256", "sig");
      default -> {
        if (suffixed(name, "TOKENISE_")) {
          yield new KeyPurpose(name, KeyAlgorithm.HMAC_SHA256, Capability.TOKENISE, null, null);
        }
        if (suffixed(name, "KEK_")) {
          yield new KeyPurpose(name, KeyAlgorithm.AES_256, Capability.ENVELOPE, null, null);
        }
        throw CryptoErrors.unknownPurpose(name);
      }
    };
  }

  private static boolean suffixed(String name, String prefix) {
    return name.startsWith(prefix) && SUFFIX.matcher(name.substring(prefix.length())).matches();
  }

  /** The keyId a key of this purpose gets unless the caller names one: {@code JWT_SIGN -> jwt-sign}. */
  public String defaultKeyId() {
    return name.toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
