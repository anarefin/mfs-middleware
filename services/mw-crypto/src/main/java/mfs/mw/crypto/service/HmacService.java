package mfs.mw.crypto.service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.dto.HmacSignRequest;
import mfs.mw.crypto.dto.HmacVerifyRequest;
import mfs.mw.crypto.dto.HmacView;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;
import mfs.mw.crypto.util.Bytes;
import org.springframework.stereotype.Service;

/**
 * HMAC-SHA256 sign and verify (spec 18.8: partner signatures, webhooks) with {@code HMAC_<use>} keys
 * only: the PIN HMAC and tokenisation keys never answer here, since an HMAC oracle under them would
 * let a caller brute-force PINs or reverse tokens. Verify compares in constant time and accepts the
 * ACTIVE and the VERIFY_ONLY version.
 */
@Service
public class HmacService {

  private static final Predicate<KeyPurpose> HMAC = p -> p.capability() == Capability.HMAC;

  private final HsmKeys keys;
  private final CallerPolicy callers;

  public HmacService(HsmKeys keys, CallerPolicy callers) {
    this.callers = callers;
    this.keys = keys;
  }

  public HmacView sign(HmacSignRequest r) {
    Resolved key = keys.resolve(r.keyId(), Use.CREATE, HMAC, "hmac-sign");
    callers.requirePurpose(key.purpose().name(), "hmac-sign");
    byte[] data = Bytes.decode("data", r.data());
    byte[] mac = keys.run("hmac", key, p -> p.hmac(key.label(), List.of(data)).getFirst());
    return HmacView.signed(Bytes.encode(mac), key.kid());
  }

  public HmacView verify(HmacVerifyRequest r) {
    Resolved named = keys.resolve(r.keyId(), Use.CHECK, HMAC, "hmac-verify");
    callers.requirePurpose(named.purpose().name(), "hmac-verify");
    byte[] data = Bytes.decode("data", r.data());
    byte[] expected = Bytes.decode("mac", r.mac());
    List<Resolved> candidates = r.keyId().equals(named.key().keyId()) ? live(named) : List.of(named);
    for (Resolved key : candidates) {
      byte[] mac = keys.run("hmac", key, p -> p.hmac(key.label(), List.of(data)).getFirst());
      if (MessageDigest.isEqual(mac, expected)) {
        return HmacView.verified(true, key.kid());
      }
    }
    return HmacView.verified(false, null);
  }

  /** The ACTIVE version first, then the VERIFY_ONLY one, of the key {@code active} is a version of. */
  private List<Resolved> live(Resolved active) {
    List<Resolved> out = new ArrayList<>(List.of(active));
    for (CryptoKey v : keys.liveVersions(active.purpose())) {
      if (v.keyId().equals(active.key().keyId()) && v.state() == KeyState.VERIFY_ONLY) {
        out.add(keys.resolve(v.kid(), Use.CHECK, HMAC, "hmac-verify"));
      }
    }
    return out;
  }
}
