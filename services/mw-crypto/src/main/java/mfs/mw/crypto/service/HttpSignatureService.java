package mfs.mw.crypto.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.impl.ECDSA;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.client.SignatureAlgorithm;
import mfs.mw.crypto.dto.Formats;
import mfs.mw.crypto.dto.HttpComponent;
import mfs.mw.crypto.dto.HttpSignatureSignRequest;
import mfs.mw.crypto.dto.HttpSignatureSignResponse;
import mfs.mw.crypto.dto.HttpSignatureVerifyRequest;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;
import mfs.mw.crypto.util.PemKeys;
import mfs.mw.crypto.util.StructuredFields;
import mfs.mw.crypto.util.StructuredFields.InnerList;
import mfs.mw.crypto.util.StructuredFields.Item;
import org.springframework.stereotype.Service;

/**
 * RFC 9421 HTTP message signatures (ticket 65, spec 18.8) for partners that sign their HTTP calls:
 * the caller (an adapter) has the message and passes the covered components with their values;
 * mw-crypto builds the signature base (RFC 9421 2.5), signs it in the HSM ({@code HTTP_SIGN_EC}:
 * ecdsa-p256-sha256, {@code HTTP_SIGN_RSA}: rsa-pss-sha512 or rsa-v1_5-sha256, {@code HMAC_<use>}:
 * hmac-sha256) and answers the {@code Signature-Input} and {@code Signature} members. Verify parses
 * the received fields (RFC 8941, {@link StructuredFields}), rebuilds the base from the caller's
 * values and checks the required components, {@code keyid}, {@code alg}, {@code created} / {@code
 * expires} and the signature, under a key here (its live versions) or a partner PEM.
 */
@Service
public class HttpSignatureService {

  static final String DEFAULT_LABEL = "sig1";
  /** Allowed clock skew for {@code created} in the future, seconds. */
  static final long SKEW_SECONDS = 60;
  private static final Predicate<KeyPurpose> HTTP =
      p -> p.name().equals("HTTP_SIGN_RSA") || p.name().equals("HTTP_SIGN_EC") || p.capability() == Capability.HMAC;
  private static final Set<String> FLAG_PARAMS = Set.of("sf", "bs", "req", "tr");
  private static final Set<String> STRING_PARAMS = Set.of("key", "name");

  private final HsmKeys keys;
  private final CallerPolicy callers;
  private final Clock clock;

  public HttpSignatureService(HsmKeys keys, CallerPolicy callers, Clock clock) {
    this.keys = keys;
    this.callers = callers;
    this.clock = clock;
  }

  public HttpSignatureSignResponse sign(HttpSignatureSignRequest r) {
    Resolved key = keys.resolve(r.keyId(), Use.CREATE, HTTP, "http-signature-sign");
    callers.requirePurpose(key.purpose().name(), "http-signature-sign");
    HttpSignatureAlgorithm alg = requested(r.alg(), key.purpose().algorithm());
    if (alg == null) {
      alg = HttpSignatureAlgorithm.defaultFor(key.purpose().algorithm());
    }
    List<Item> covered = new ArrayList<>();
    Map<String, String> values = values(r.components(), covered);
    long created = r.created() != null ? r.created() : clock.instant().getEpochSecond();
    if (r.expires() != null && r.expires() <= created) {
      throw CryptoErrors.invalidField("expires", "after created");
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("created", created);
    if (r.expires() != null) {
      params.put("expires", r.expires());
    }
    if (r.nonce() != null) {
      params.put("nonce", r.nonce());
    }
    params.put("keyid", key.kid());
    params.put("alg", alg.id);
    if (r.tag() != null) {
      params.put("tag", r.tag());
    }
    InnerList list = new InnerList(covered, params);
    byte[] base = base(list, values);
    HttpSignatureAlgorithm a = alg;
    byte[] signature =
        a == HttpSignatureAlgorithm.HMAC_SHA256
            ? keys.run("http-signature-sign", key, p -> p.hmac(key.label(), List.of(base)).getFirst())
            : keys.run("http-signature-sign", key, p -> p.sign(key.label(), a.signature, base));
    if (a == HttpSignatureAlgorithm.ECDSA_P256_SHA256) {
      try {
        signature = ECDSA.transcodeSignatureToConcat(signature, 64);
      } catch (JOSEException e) {
        throw new MwException(ErrorCodes.SYS_INTERNAL, "ECDSA signature not DER", e);
      }
    }
    String label = r.label() != null ? r.label() : DEFAULT_LABEL;
    return new HttpSignatureSignResponse(label, key.kid(), alg.id,
        label + "=" + StructuredFields.serialize(list),
        label + "=" + StructuredFields.bare(signature));
  }

  public SignatureCheck verify(HttpSignatureVerifyRequest r) {
    if ((r.keyId() == null) == (r.certificatePem() == null)) {
      throw CryptoErrors.invalidField("keyId", "exactly one of keyId and certificatePem");
    }
    List<Resolved> candidates = List.of();
    PemKeys partner = null;
    KeyAlgorithm keyAlg;
    if (r.keyId() != null) {
      candidates = VerifyKeys.candidates(keys, r.keyId(), HTTP, "http-signature-verify");
      keyAlg = candidates.getFirst().purpose().algorithm();
      callers.requirePurpose(candidates.getFirst().purpose().name(), "http-signature-verify");
    } else {
      partner = PemKeys.parse("certificatePem", r.certificatePem(), clock.instant());
      keyAlg = partner.key() instanceof ECPublicKey ? KeyAlgorithm.EC_P256 : KeyAlgorithm.RSA_3072;
      callers.requirePurpose(keyAlg == KeyAlgorithm.EC_P256 ? "HTTP_SIGN_EC" : "HTTP_SIGN_RSA", "http-signature-verify");
    }
    HttpSignatureAlgorithm requested = requested(r.alg(), keyAlg);
    Map<String, String> given = values(r.components(), null);
    Set<String> required = required(r.requiredComponents());

    Map<String, Object> inputs;
    Map<String, Object> signatures;
    try {
      inputs = StructuredFields.parseDictionary(r.signatureInput());
      signatures = StructuredFields.parseDictionary(r.signature());
    } catch (IllegalArgumentException e) {
      return SignatureCheck.invalid("MALFORMED_SIGNATURE");
    }
    String label = r.label() != null ? r.label() : inputs.size() == 1 ? inputs.keySet().iterator().next() : null;
    if (label == null || !(inputs.get(label) instanceof InnerList list)
        || !(signatures.get(label) instanceof Item sigItem) || !(sigItem.value() instanceof byte[] signature)) {
      return SignatureCheck.invalid("MALFORMED_SIGNATURE");
    }
    Map<String, String> values = new HashMap<>();
    for (Item item : list.items()) {
      if (!(item.value() instanceof String name) || !validParams(item.params())) {
        return SignatureCheck.invalid("MALFORMED_SIGNATURE");
      }
      String id = identity(name, item.params());
      if (values.containsKey(id)) {
        return SignatureCheck.invalid("MALFORMED_SIGNATURE");
      }
      values.put(id, given.get(id));
    }
    if (!values.keySet().containsAll(required)) {
      return SignatureCheck.invalid("REQUIRED_COMPONENT_NOT_COVERED");
    }
    if (values.containsValue(null)) {
      return SignatureCheck.invalid("COMPONENT_MISSING");
    }
    Map<String, Object> p = list.params();
    Object keyid = p.get("keyid");
    Object algParam = p.get("alg");
    Object created = p.get("created");
    Object expires = p.get("expires");
    if (keyid != null && !(keyid instanceof String) || algParam != null && !(algParam instanceof String)
        || created != null && !(created instanceof Long) || expires != null && !(expires instanceof Long)) {
      return SignatureCheck.invalid("MALFORMED_SIGNATURE");
    }
    if (r.expectedKeyid() != null && !r.expectedKeyid().equals(keyid)) {
      return SignatureCheck.invalid("KEY_MISMATCH");
    }
    HttpSignatureAlgorithm alg = requested;
    if (algParam != null) {
      HttpSignatureAlgorithm signed = HttpSignatureAlgorithm.of((String) algParam).orElse(null);
      if (signed == null || (requested != null && requested != signed)) {
        return SignatureCheck.invalid("ALGORITHM_NOT_ALLOWED");
      }
      alg = signed;
    }
    if (alg == null) {
      alg = HttpSignatureAlgorithm.defaultFor(keyAlg);
    }
    if (alg.keyAlgorithm != keyAlg) {
      return SignatureCheck.invalid("ALGORITHM_NOT_ALLOWED");
    }
    long now = clock.instant().getEpochSecond();
    if (expires != null && now > (Long) expires) {
      return SignatureCheck.invalid("EXPIRED");
    }
    if (created != null && (Long) created > now + SKEW_SECONDS) {
      return SignatureCheck.invalid("CREATED_IN_FUTURE");
    }
    if (r.maxAgeSeconds() != null) {
      if (created == null) {
        return SignatureCheck.invalid("CREATED_MISSING");
      }
      if (now - (Long) created > r.maxAgeSeconds()) {
        return SignatureCheck.invalid("TOO_OLD");
      }
    }
    byte[] base = base(list, values);
    if (partner != null) {
      if (!partner.validNow()) {
        return SignatureCheck.invalid("CERTIFICATE_NOT_VALID");
      }
      return check(alg, partner.key(), base, signature) ? SignatureCheck.valid(null) : SignatureCheck.invalid("SIGNATURE_MISMATCH");
    }
    for (Resolved key : pick(candidates, keyid)) {
      if (matches(alg, key, base, signature)) {
        return SignatureCheck.valid(key.kid());
      }
    }
    return SignatureCheck.invalid("SIGNATURE_MISMATCH");
  }

  /** A kid of one of the candidates in {@code keyid} narrows the try to that version. */
  private static List<Resolved> pick(List<Resolved> candidates, Object keyid) {
    List<Resolved> named = candidates.stream().filter(k -> k.kid().equals(keyid)).toList();
    return named.isEmpty() ? candidates : named;
  }

  private boolean matches(HttpSignatureAlgorithm alg, Resolved key, byte[] base, byte[] signature) {
    if (alg == HttpSignatureAlgorithm.HMAC_SHA256) {
      byte[] mac = keys.run("http-signature-verify", key, p -> p.hmac(key.label(), List.of(base)).getFirst());
      return MessageDigest.isEqual(mac, signature);
    }
    PublicKey pub = keys.run("public-key", key, p -> p.publicKey(key.label()));
    return check(alg, pub, base, signature);
  }

  /** The signature check in the JVM with a public key (RFC 9421 3.3). */
  private static boolean check(HttpSignatureAlgorithm alg, PublicKey key, byte[] base, byte[] signature) {
    try {
      Signature s =
          switch (alg) {
            case ECDSA_P256_SHA256 -> Signature.getInstance("SHA256withECDSAinP1363Format");
            case RSA_V1_5_SHA256 -> Signature.getInstance("SHA256withRSA");
            case RSA_PSS_SHA512 -> {
              Signature pss = Signature.getInstance("RSASSA-PSS");
              pss.setParameter(SignatureAlgorithm.PS512.pssParameters());
              yield pss;
            }
            case HMAC_SHA256 -> throw new IllegalStateException("HMAC has no public key");
          };
      if (alg == HttpSignatureAlgorithm.ECDSA_P256_SHA256 && signature.length != 64) {
        return false;
      }
      s.initVerify(key);
      s.update(base);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /** The requested algorithm, or null for the key's default; 400 when it does not fit the key. */
  private static HttpSignatureAlgorithm requested(String name, KeyAlgorithm keyAlg) {
    if (name == null) {
      return null;
    }
    HttpSignatureAlgorithm alg = HttpSignatureAlgorithm.of(name)
        .orElseThrow(() -> CryptoErrors.invalidField("alg", "not a supported RFC 9421 algorithm"));
    if (alg.keyAlgorithm != keyAlg) {
      throw CryptoErrors.invalidField("alg", alg.id + " does not fit the key");
    }
    return alg;
  }

  /**
   * The values by component identity; with {@code covered} also the identifiers in order (sign).
   * Bad names, parameters or values and duplicates are 400 {@code MW-VAL-001}, never echoed.
   */
  private static Map<String, String> values(List<HttpComponent> components, List<Item> covered) {
    if (components == null || (covered != null && components.isEmpty())) {
      throw CryptoErrors.invalidField("components", "at least one component");
    }
    Map<String, String> out = new HashMap<>();
    for (int k = 0; k < components.size(); k++) {
      HttpComponent c = components.get(k);
      String field = "components[" + k + "]";
      if (c.name() == null || !c.name().matches(Formats.HTTP_COMPONENT)
          || c.name().equals("@signature-params")) {
        throw CryptoErrors.invalidField(field + ".name", "an RFC 9421 component name");
      }
      Map<String, Object> params = c.params() == null ? Map.of() : c.params();
      if (!validParams(params)) {
        throw CryptoErrors.invalidField(field + ".params", "sf, bs, req, tr (true) or key, name (string)");
      }
      if (c.value() == null || !printable(c.value())) {
        throw CryptoErrors.invalidField(field + ".value", "printable ASCII without line breaks");
      }
      String id = identity(c.name(), params);
      if (out.put(id, c.value()) != null) {
        throw CryptoErrors.invalidField(field, "the same component twice");
      }
      if (covered != null) {
        covered.add(new Item(c.name(), new LinkedHashMap<>(params)));
      }
    }
    return out;
  }

  /**
   * The identities the signature must cover (security review of ticket 65): a bare name means that
   * component without parameters only; a serialised identifier ({@code "content-digest";sf}) means
   * exactly that component with exactly those parameters. Compared as {@link #identity}, the same
   * string the signature base uses, so {@code "content-digest";req} never satisfies {@code
   * content-digest}. A malformed entry is 400 {@code MW-VAL-001}.
   */
  private static Set<String> required(List<String> entries) {
    Set<String> out = new HashSet<>();
    if (entries == null) {
      return out;
    }
    for (int k = 0; k < entries.size(); k++) {
      String e = entries.get(k);
      String field = "requiredComponents[" + k + "]";
      if (e != null && !e.startsWith("\"") && e.matches(Formats.HTTP_COMPONENT)) {
        out.add(identity(e, Map.of()));
        continue;
      }
      Item item;
      try {
        item = e == null ? null : StructuredFields.parseItem(e);
      } catch (IllegalArgumentException ex) {
        item = null;
      }
      if (item == null || !(item.value() instanceof String name) || !name.matches(Formats.HTTP_COMPONENT)
          || name.equals("@signature-params") || !validParams(item.params())) {
        throw CryptoErrors.invalidField(field, "a component name or an RFC 9421 component identifier");
      }
      out.add(identity(name, item.params()));
    }
    return out;
  }

  private static boolean validParams(Map<String, Object> params) {
    for (Map.Entry<String, Object> e : params.entrySet()) {
      boolean flag = FLAG_PARAMS.contains(e.getKey()) && Boolean.TRUE.equals(e.getValue());
      boolean string = STRING_PARAMS.contains(e.getKey()) && e.getValue() instanceof String s
          && s.matches(Formats.SF_STRING);
      if (!flag && !string) {
        return false;
      }
    }
    return true;
  }

  private static boolean printable(String v) {
    for (int k = 0; k < v.length(); k++) {
      char ch = v.charAt(k);
      if ((ch < 0x20 && ch != '\t') || ch > 0x7e) {
        return false;
      }
    }
    return true;
  }

  /** A component's identity whatever the order of its parameters. */
  private static String identity(String name, Map<String, Object> params) {
    return StructuredFields.serialize(new Item(name, new TreeMap<>(params)));
  }

  /** The signature base (RFC 9421 2.5): one line per covered component, then {@code @signature-params}. */
  private static byte[] base(InnerList list, Map<String, String> values) {
    StringBuilder b = new StringBuilder();
    for (Item item : list.items()) {
      b.append(StructuredFields.serialize(item)).append(": ")
          .append(values.get(identity((String) item.value(), item.params()))).append('\n');
    }
    b.append("\"@signature-params\": ").append(StructuredFields.serialize(list));
    return b.toString().getBytes(StandardCharsets.US_ASCII);
  }
}
