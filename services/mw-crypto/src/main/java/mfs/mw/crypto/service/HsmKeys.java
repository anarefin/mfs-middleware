package mfs.mw.crypto.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import mfs.mw.crypto.client.CryptoProvider;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.repository.KeyRepository;
import org.springframework.stereotype.Service;

/**
 * The key versions the operations run with (ticket 63, spec 18.8) and the timed provider call
 * around each ({@code crypto.op.duration{op,provider,outcome}}, as {@link KeyService}). A key
 * reference is a keyId ({@code pin-hmac}: its ACTIVE version) or a kid ({@code pin-hmac.v1}: that
 * version). New values (sign, derive, encrypt, tokenise) use the ACTIVE version only; checks of
 * existing values (verify, decrypt) also accept the VERIFY_ONLY one, never a RETIRED one.
 */
@Service
public class HsmKeys {

  /** keyId with an optional {@code .v<version>}. */
  static final Pattern REF = Pattern.compile("([a-z0-9][a-z0-9-]{0,62})(?:\\.v([1-9][0-9]{0,5}))?");

  /** What a key is looked up for. */
  public enum Use {
    /** Makes a new value: the ACTIVE version only. */
    CREATE,
    /** Checks or opens an existing value: ACTIVE or VERIFY_ONLY. */
    CHECK
  }

  /** One key version with its purpose and the provider that holds it. */
  public record Resolved(CryptoKey key, KeyPurpose purpose, CryptoProvider provider) {
    public String label() {
      return key.hsmLabel();
    }

    public String kid() {
      return key.kid();
    }
  }

  private final KeyRepository keys;
  private final Map<String, CryptoProvider> providers;
  private final MeterRegistry meters;

  public HsmKeys(KeyRepository keys, List<CryptoProvider> providers, MeterRegistry meters) {
    this.keys = keys;
    this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(CryptoProvider::name, Function.identity()));
    this.meters = meters;
  }

  /**
   * The version {@code ref} names, for {@code use}, of a purpose {@code fits} accepts; else {@code
   * MW-VAL-010} (no such key or version) or {@code MW-VAL-015} (other purpose, or a state {@code use}
   * does not accept).
   */
  public Resolved resolve(String ref, Use use, Predicate<KeyPurpose> fits, String operation) {
    Matcher m = ref == null ? null : REF.matcher(ref);
    if (m == null || !m.matches()) {
      throw CryptoErrors.unknownKey(String.valueOf(ref));
    }
    List<CryptoKey> versions = keys.findVersions(m.group(1));
    if (versions.isEmpty()) {
      throw CryptoErrors.unknownKey(ref);
    }
    Optional<CryptoKey> picked =
        m.group(2) == null
            ? versions.stream().filter(v -> v.state() == KeyState.ACTIVE).findFirst()
            : versions.stream().filter(v -> v.version() == Integer.parseInt(m.group(2))).findFirst();
    CryptoKey key = picked.orElseThrow(() -> CryptoErrors.unknownKey(ref));
    KeyPurpose purpose = KeyPurpose.parse(key.purpose());
    boolean stateOk =
        key.state() == KeyState.ACTIVE || (use == Use.CHECK && key.state() == KeyState.VERIFY_ONLY);
    if (!fits.test(purpose) || !stateOk) {
      throw CryptoErrors.keyNotUsable(ref, operation);
    }
    return new Resolved(key, purpose, provider(key.provider()));
  }

  /** The ACTIVE version of {@code keyId} (the re-hash target of a verify under a VERIFY_ONLY version). */
  public Resolved active(String keyId) {
    return keys.findVersions(keyId).stream()
        .filter(v -> v.state() == KeyState.ACTIVE)
        .findFirst()
        .map(k -> new Resolved(k, KeyPurpose.parse(k.purpose()), provider(k.provider())))
        .orElseThrow(() -> CryptoErrors.unknownKey(keyId));
  }

  /**
   * The ACTIVE version of the key of {@code purposeName}: the one under the purpose's default keyId
   * when it exists, else the first by keyId. No key: {@code MW-VAL-010}.
   */
  public Resolved activeByPurpose(String purposeName, Predicate<KeyPurpose> fits, String operation) {
    KeyPurpose purpose = KeyPurpose.parse(purposeName);
    if (!fits.test(purpose)) {
      throw CryptoErrors.keyNotUsable(purposeName, operation);
    }
    List<CryptoKey> active =
        keys.findByPurpose(purpose.name()).stream().filter(v -> v.state() == KeyState.ACTIVE).toList();
    CryptoKey key =
        active.stream()
            .filter(v -> v.keyId().equals(purpose.defaultKeyId()))
            .findFirst()
            .or(() -> active.stream().findFirst())
            .orElseThrow(() -> CryptoErrors.unknownKey("(purpose " + purpose.name() + ")"));
    return new Resolved(key, purpose, provider(key.provider()));
  }

  /** The versions still in use (ACTIVE, VERIFY_ONLY) of every key of {@code purpose}. */
  public List<CryptoKey> liveVersions(KeyPurpose purpose) {
    return keys.findByPurpose(purpose.name()).stream().filter(v -> v.state() != KeyState.RETIRED).toList();
  }

  /** {@code call} against the key's provider, timed as {@code crypto.op.duration{op}}. */
  public <T> T run(String op, Resolved key, Function<CryptoProvider, T> call) {
    CryptoProvider provider = key.provider();
    Timer.Sample sample = Timer.start(meters);
    String outcome = "success";
    try {
      return call.apply(provider);
    } catch (RuntimeException e) {
      outcome = CryptoErrors.isHsmFailure(e) ? "hsm_unavailable" : "error";
      throw e;
    } finally {
      sample.stop(
          Timer.builder("crypto.op.duration")
              .description("Duration of one crypto provider operation")
              .tag("op", op)
              .tag("provider", provider.name())
              .tag("outcome", outcome)
              .register(meters));
    }
  }

  private CryptoProvider provider(String name) {
    CryptoProvider p = providers.get(name);
    if (p == null) {
      throw new MwException(ErrorCodes.SYS_INTERNAL, "No crypto provider " + name);
    }
    return p;
  }
}
