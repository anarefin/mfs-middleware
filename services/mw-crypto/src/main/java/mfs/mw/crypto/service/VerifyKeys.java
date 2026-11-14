package mfs.mw.crypto.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;

/**
 * The key versions a verify tries (ticket 65, as {@code HmacService}): a kid names that version only;
 * a keyId its ACTIVE version first, then its VERIFY_ONLY one. Metadata only, no HSM call.
 */
final class VerifyKeys {

  private VerifyKeys() {}

  static List<Resolved> candidates(HsmKeys keys, String ref, Predicate<KeyPurpose> fits, String operation) {
    Resolved named = keys.resolve(ref, Use.CHECK, fits, operation);
    if (!ref.equals(named.key().keyId())) {
      return List.of(named);
    }
    List<Resolved> out = new ArrayList<>(List.of(named));
    for (CryptoKey v : keys.liveVersions(named.purpose())) {
      if (v.keyId().equals(named.key().keyId()) && v.state() == KeyState.VERIFY_ONLY) {
        out.add(keys.resolve(v.kid(), Use.CHECK, fits, operation));
      }
    }
    return out;
  }
}
