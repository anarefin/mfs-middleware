package mfs.mw.crypto.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import mfs.mw.crypto.client.CryptoProvider;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.dto.PublicKeyView;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.mapper.KeyMapper;
import mfs.mw.crypto.repository.KeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Key management (spec 18.8, D23): list, generate, rotate and public keys, by keyId. Metadata in
 * {@code crypto.key}, material in the provider the key names. The HSM call runs inside the metadata
 * transaction, after the row is written: an HSM failure rolls the row back and fails the request
 * closed ({@code MW-SYS-010}). Rotation adds a version: the ACTIVE one becomes VERIFY_ONLY, an older
 * VERIFY_ONLY one RETIRED (verification accepts the current and the previous version only). Every
 * provider call is timed as {@code crypto.op.duration{op,provider}}.
 */
@Service
public class KeyService {

  private static final Logger log = LoggerFactory.getLogger(KeyService.class);

  private final KeyRepository keys;
  private final Map<String, CryptoProvider> providers;
  private final String defaultProvider;
  private final KeyMapper mapper;
  private final Clock clock;
  private final MeterRegistry meters;

  public KeyService(
      KeyRepository keys,
      List<CryptoProvider> providers,
      CryptoProperties props,
      KeyMapper mapper,
      Clock clock,
      MeterRegistry meters) {
    this.keys = keys;
    this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(CryptoProvider::name, Function.identity()));
    this.defaultProvider = props.defaultProvider();
    this.mapper = mapper;
    this.clock = clock;
    this.meters = meters;
  }

  @Transactional(readOnly = true)
  public KeyView.Page list() {
    return mapper.toPage(keys.findAll());
  }

  /** The purposes that have a key already (the bootstrap skips them). */
  @Transactional(readOnly = true)
  public List<String> purposesWithKeys() {
    return keys.purposes();
  }

  /** A new key, version 1, ACTIVE, in the default provider, or the provider that serves the purpose. */
  @Transactional
  public KeyView generate(String purposeName, String requestedKeyId) {
    KeyPurpose purpose = KeyPurpose.parse(purposeName);
    CryptoProvider provider = providerFor(purpose);
    String keyId = requestedKeyId == null || requestedKeyId.isBlank() ? purpose.defaultKeyId() : requestedKeyId;
    if (!keys.findVersions(keyId).isEmpty()) {
      throw CryptoErrors.keyExists(keyId);
    }
    CryptoKey key = version(keyId, 1, purpose, provider.name());
    try {
      keys.insert(key);
    } catch (DuplicateKeyException e) {
      throw CryptoErrors.keyExists(keyId);
    }
    create(provider, key, purpose);
    log.atInfo()
        .addKeyValue("event", "crypto.key.generated")
        .addKeyValue("keyId", keyId)
        .addKeyValue("purpose", purpose.name())
        .addKeyValue("kid", key.kid())
        .log("Key generated");
    return mapper.toView(List.of(key));
  }

  /** A new ACTIVE version; the previous ACTIVE one becomes VERIFY_ONLY, an older VERIFY_ONLY one RETIRED. */
  @Transactional
  public KeyView rotate(String keyId) {
    List<CryptoKey> versions = keys.lockVersions(keyId);
    if (versions.isEmpty()) {
      throw CryptoErrors.unknownKey(keyId);
    }
    CryptoKey newest = versions.getLast();
    KeyPurpose purpose = KeyPurpose.parse(newest.purpose());
    CryptoProvider provider = provider(newest.provider());
    Instant now = clock.instant();
    List<CryptoKey> after = new ArrayList<>();
    for (CryptoKey v : versions) {
      KeyState next =
          switch (v.state()) {
            case ACTIVE -> KeyState.VERIFY_ONLY;
            case VERIFY_ONLY -> KeyState.RETIRED;
            case RETIRED -> KeyState.RETIRED;
          };
      if (next != v.state()) {
        keys.updateState(v.keyId(), v.version(), next, now);
        after.add(v.withState(next, now));
      } else {
        after.add(v);
      }
    }
    CryptoKey added = version(keyId, newest.version() + 1, purpose, provider.name());
    keys.insert(added);
    create(provider, added, purpose);
    after.add(added);
    log.atInfo()
        .addKeyValue("event", "crypto.key.rotated")
        .addKeyValue("keyId", keyId)
        .addKeyValue("kid", added.kid())
        .log("Key rotated");
    return mapper.toView(after);
  }

  /** The public keys of the versions still in use (ACTIVE, VERIFY_ONLY). */
  @Transactional(readOnly = true)
  public PublicKeyView publicKeys(String keyId) {
    List<CryptoKey> versions = keys.findVersions(keyId);
    if (versions.isEmpty()) {
      throw CryptoErrors.unknownKey(keyId);
    }
    KeyPurpose purpose = KeyPurpose.parse(versions.getLast().purpose());
    if (!purpose.algorithm().asymmetric()) {
      throw CryptoErrors.noPublicKey(keyId);
    }
    List<CryptoKey> live = versions.stream().filter(v -> v.state() != KeyState.RETIRED).toList();
    List<PublicKey> pubs = new ArrayList<>();
    for (CryptoKey v : live) {
      CryptoProvider provider = provider(v.provider());
      pubs.add(timed("public-key", provider, () -> provider.publicKey(v.hsmLabel())));
    }
    return mapper.toPublic(
        purpose.name(), purpose.algorithm().name(), purpose.jwkAlgorithm(), purpose.jwkUse(), live, pubs);
  }

  private CryptoKey version(String keyId, int version, KeyPurpose purpose, String provider) {
    String kid = CryptoKey.kid(keyId, version);
    return new CryptoKey(
        keyId, version, purpose.name(), purpose.algorithm().name(), provider, kid, KeyState.ACTIVE, clock.instant(), null);
  }

  /**
   * Generates the key of {@code key} in {@code provider}; a provider that returns another reference
   * than the label (payShield: the key blob under the LMK, ticket 64) has it stored as the HSM label.
   */
  private void create(CryptoProvider provider, CryptoKey key, KeyPurpose purpose) {
    String ref = timed("generate", provider,
        () -> provider.createKey(key.hsmLabel(), purpose.algorithm(), purpose.name()));
    if (ref != null && !ref.equals(key.hsmLabel())) {
      keys.updateLabel(key.keyId(), key.version(), ref);
    }
  }

  /** The default provider when it serves the purpose, else the first provider that does. */
  private CryptoProvider providerFor(KeyPurpose purpose) {
    CryptoProvider preferred = provider(defaultProvider);
    if (preferred.capabilities().contains(purpose.capability())) {
      return preferred;
    }
    return providers.values().stream()
        .filter(p -> p.capabilities().contains(purpose.capability()))
        .min(Comparator.comparing(CryptoProvider::name))
        .orElseThrow(() -> CryptoErrors.unknownPurpose(purpose.name() + " (no provider can serve it)"));
  }

  private CryptoProvider provider(String name) {
    CryptoProvider p = providers.get(name);
    if (p == null) {
      throw new MwException(ErrorCodes.SYS_INTERNAL, "No crypto provider " + name);
    }
    return p;
  }

  private <T> T timed(String op, CryptoProvider provider, Supplier<T> call) {
    Timer.Sample sample = Timer.start(meters);
    String outcome = "success";
    try {
      return call.get();
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
}
