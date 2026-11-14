package mfs.mw.crypto.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.client.SignatureAlgorithm;
import mfs.mw.crypto.dto.JwsSignRequest;
import mfs.mw.crypto.dto.JwsSignResponse;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;
import org.springframework.stereotype.Service;

/**
 * JWS sign and JWKS (spec 18.8): RS256 (the mw-auth access tokens, D21 amendment) or PS256 with an RSA
 * key, ES256 with an EC key (assertions, audit anchor). Nimbus JOSE builds the JWS; the signature is
 * made in the HSM ({@link HsmJwsSigner}). The header {@code kid} is the key version, {@code
 * <keyId>.v<n>}, as in the JWKS.
 */
@Service
public class JwsService {

  static final int MAX_PAYLOAD_BYTES = 65_536;
  private static final Predicate<KeyPurpose> JWS = p -> p.capability() == Capability.JWS;
  /** Header members the service sets itself or that would point a verifier at another key. */
  private static final List<String> RESERVED =
      List.of("alg", "kid", "jku", "jwk", "x5u", "x5c", "x5t", "x5t#S256", "crit", "b64", "enc", "zip");

  private final HsmKeys keys;
  private final CallerPolicy callers;
  private final KeyService keyService;

  public JwsService(HsmKeys keys, KeyService keyService, CallerPolicy callers) {
    this.callers = callers;
    this.keys = keys;
    this.keyService = keyService;
  }

  public JwsSignResponse sign(JwsSignRequest r) {
    if ((r.keyId() == null) == (r.purpose() == null)) {
      throw CryptoErrors.invalidField("keyId", "exactly one of keyId and purpose");
    }
    if (r.purpose() != null) {
      callers.requirePurpose(r.purpose(), "jws-sign");
    }
    Resolved key =
        r.keyId() != null
            ? keys.resolve(r.keyId(), Use.CREATE, JWS, "jws-sign")
            : keys.activeByPurpose(r.purpose(), JWS, "jws-sign");
    // by keyId: the key's purpose decides (metadata only; no HSM call before this)
    callers.requirePurpose(key.purpose().name(), "jws-sign");
    SignatureAlgorithm alg = algorithm(key, r.alg());
    Payload payload = new Payload(r.payload());
    if (payload.toBytes().length > MAX_PAYLOAD_BYTES) {
      throw CryptoErrors.invalidField("payload", "larger than " + MAX_PAYLOAD_BYTES + " bytes");
    }
    JWSObject jws = new JWSObject(header(alg, key.kid(), r.header()), payload);
    try {
      jws.sign(new HsmJwsSigner(keys, key, alg));
    } catch (JOSEException e) {
      throw new MwException(ErrorCodes.SYS_INTERNAL, "JWS signing failed", e);
    }
    return new JwsSignResponse(jws.serialize(), key.kid(), alg.name());
  }

  /** The JWK Set of every version in use (ACTIVE, VERIFY_ONLY) of every key of {@code purposeName}. */
  public Map<String, Object> jwks(String purposeName) {
    KeyPurpose purpose = KeyPurpose.parse(purposeName);
    if (!purpose.algorithm().asymmetric()) {
      throw CryptoErrors.noPublicKey(purposeName);
    }
    List<Object> out = new ArrayList<>();
    keys.liveVersions(purpose).stream().map(CryptoKey::keyId).distinct()
        .forEach(id -> keyService.publicKeys(id).versions().forEach(v -> out.add(v.jwk())));
    return Map.of("keys", out);
  }

  private static SignatureAlgorithm algorithm(Resolved key, String requested) {
    KeyAlgorithm keyAlg = key.purpose().algorithm();
    SignatureAlgorithm alg =
        requested != null
            ? SignatureAlgorithm.valueOf(requested)
            : keyAlg == KeyAlgorithm.EC_P256 ? SignatureAlgorithm.ES256 : SignatureAlgorithm.RS256;
    if (alg.keyAlgorithm() != keyAlg) {
      throw CryptoErrors.invalidField("alg", alg + " does not fit a " + keyAlg + " key");
    }
    return alg;
  }

  private static JWSHeader header(SignatureAlgorithm alg, String kid, Map<String, Object> extra) {
    JWSHeader.Builder b = new JWSHeader.Builder(JWSAlgorithm.parse(alg.name())).keyID(kid);
    if (extra != null) {
      for (Map.Entry<String, Object> e : extra.entrySet()) {
        String name = e.getKey();
        if (RESERVED.contains(name)) {
          throw CryptoErrors.invalidField("header." + name, "set by the service");
        }
        switch (name) {
          case "typ" -> b.type(new JOSEObjectType(String.valueOf(e.getValue())));
          case "cty" -> b.contentType(String.valueOf(e.getValue()));
          default -> b.customParam(name, e.getValue());
        }
      }
    }
    return b.build();
  }
}
