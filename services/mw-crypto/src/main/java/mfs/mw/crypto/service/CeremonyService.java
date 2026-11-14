package mfs.mw.crypto.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.CryptoProvider;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.dto.CeremonyComponentRequest;
import mfs.mw.crypto.dto.CeremonyView;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.entity.Ceremony;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.repository.CeremonyRepository;
import mfs.mw.crypto.repository.KeyRepository;
import mfs.mw.crypto.util.CeremonyComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dual-control key ceremony (ticket 75, spec 18.8, D23, OWN-08): two custodians each enter a
 * component in their own session (through mw-admin-api, which holds the maker-checker request);
 * the first is stored in the HSM as a derive-only object, the second is combined with it inside the
 * HSM ({@link CryptoProvider#combineComponents}) into the staged key version, and only its KCV comes
 * back. Nothing here stores or logs a component: {@code custodian_share_log} holds who and when.
 * The staged version is not usable until {@link #commit} (the approved change request) makes it the
 * ACTIVE version; {@link #abort} (rejected, expired) destroys the staged objects.
 *
 * <p>Purposes: the HMAC-SHA256 keys ({@code PIN_HMAC}, {@code TOKENISE_*}, {@code HMAC_*}); the
 * combined key is an ECDH secret, which the dev HSM can derive only as a generic secret. KEKs (AES)
 * and the payShield LMK are a follow-up (CLAUDE.md "Key ceremony").
 */
@Service
public class CeremonyService {

  private static final Logger log = LoggerFactory.getLogger(CeremonyService.class);

  private final CeremonyRepository ceremonies;
  private final KeyRepository keys;
  private final KeyService keyService;
  private final List<CryptoProvider> providers;
  private final String defaultProvider;
  private final Clock clock;

  public CeremonyService(
      CeremonyRepository ceremonies,
      KeyRepository keys,
      KeyService keyService,
      List<CryptoProvider> providers,
      CryptoProperties props,
      Clock clock) {
    this.ceremonies = ceremonies;
    this.keys = keys;
    this.keyService = keyService;
    this.providers = List.copyOf(providers);
    this.defaultProvider = props.defaultProvider();
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CeremonyView get(UUID id) {
    return view(ceremonies.find(id).orElseThrow(() -> notFound(id)));
  }

  /** One custodian's component; the first opens the ceremony, the second combines. */
  @Transactional
  public CeremonyView component(UUID id, CeremonyComponentRequest r) {
    byte[] component = CeremonyComponents.parse(r.component());
    try {
      if (!CeremonyComponents.valid(component)) {
        throw CryptoErrors.badComponent("component");
      }
      if (!CeremonyComponents.sameCheckValue(CeremonyComponents.checkValue(component), r.checkValue())) {
        throw CryptoErrors.badComponent("checkValue");
      }
      KeyPurpose purpose = KeyPurpose.parse(r.purpose());
      if (purpose.algorithm() != KeyAlgorithm.HMAC_SHA256) {
        throw CryptoErrors.keyNotUsable(r.keyId(), "a key ceremony (" + purpose.name() + ")");
      }
      Instant now = clock.instant();
      Ceremony c = ceremonies.lock(id).orElseGet(() -> open(id, r.keyId(), purpose, now));
      if (!c.keyId().equals(r.keyId()) || !c.purpose().equals(purpose.name())) {
        throw CryptoErrors.keyChangeConflict("The ceremony is for another key");
      }
      if (c.state() != Ceremony.State.OPEN) {
        throw CryptoErrors.keyChangeConflict("The ceremony takes no more components");
      }
      if (c.shares().stream().anyMatch(s -> s.custodian().equals(r.custodian()))) {
        throw CryptoErrors.keyChangeConflict("The two components must come from two custodians");
      }
      CryptoProvider provider = provider();
      int share = c.shares().size() + 1;
      if (share == 1) {
        String label = "ceremony-" + id + ".c1";
        provider.destroy(label);
        provider.storeComponent(label, component);
        ceremonies.logShare(id, 1, r.custodian(), now);
        ceremonies.update(id, Ceremony.State.OPEN, label, null, null, now);
      } else {
        String staged = CryptoKey.kid(c.keyId(), c.version());
        provider.destroy(staged);
        byte[] mac = provider.combineComponents(c.componentLabel(), component, staged);
        String kcv = CeremonyComponents.keyCheckValue(mac);
        provider.destroy(c.componentLabel());
        ceremonies.logShare(id, 2, r.custodian(), now);
        ceremonies.update(id, Ceremony.State.COMBINED, null, staged, kcv, now);
      }
      log.atInfo()
          .addKeyValue("event", "crypto.ceremony.component_entered")
          .addKeyValue("ceremonyId", id.toString())
          .addKeyValue("keyId", c.keyId())
          .addKeyValue("share", share)
          .addKeyValue("custodian", r.custodian())
          .log("Ceremony component entered");
      return view(ceremonies.find(id).orElseThrow());
    } finally {
      CeremonyComponents.zero(component);
    }
  }

  /** The approved ceremony's key version becomes ACTIVE; a repeat answers the key as it is. */
  @Transactional
  public KeyView commit(UUID id, String makerId, String checkerId) {
    Ceremony c = ceremonies.lock(id).orElseThrow(() -> notFound(id));
    if (c.state() == Ceremony.State.COMMITTED) {
      return keyService.find(c.keyId());
    }
    if (c.state() != Ceremony.State.COMBINED) {
      throw CryptoErrors.keyChangeConflict("Both components are needed before the approval");
    }
    if (makerId.equals(checkerId)) {
      throw CryptoErrors.keyChangeConflict("Maker and checker must differ");
    }
    KeyView key = keyService.activate(c.keyId(), c.purpose(), provider().name(), c.version(), c.stagedLabel());
    ceremonies.update(id, Ceremony.State.COMMITTED, null, c.stagedLabel(), c.checkValue(), clock.instant());
    log.atInfo()
        .addKeyValue("event", "crypto.ceremony.committed")
        .addKeyValue("ceremonyId", id.toString())
        .addKeyValue("kid", c.stagedLabel())
        .addKeyValue("makerId", makerId)
        .addKeyValue("checkerId", checkerId)
        .log("Ceremony committed");
    return key;
  }

  /** Destroys the staged objects; nothing to do for an unknown or aborted one; a committed one is 409. */
  @Transactional
  public void abort(UUID id) {
    Optional<Ceremony> found = ceremonies.lock(id);
    if (found.isEmpty() || found.get().state() == Ceremony.State.ABORTED) {
      return;
    }
    Ceremony c = found.get();
    if (c.state() == Ceremony.State.COMMITTED) {
      throw CryptoErrors.keyChangeConflict("The ceremony was committed");
    }
    CryptoProvider provider = provider();
    if (c.componentLabel() != null) {
      provider.destroy(c.componentLabel());
    }
    if (c.stagedLabel() != null) {
      provider.destroy(c.stagedLabel());
    }
    ceremonies.update(id, Ceremony.State.ABORTED, null, null, c.checkValue(), clock.instant());
    log.atInfo()
        .addKeyValue("event", "crypto.ceremony.aborted")
        .addKeyValue("ceremonyId", id.toString())
        .addKeyValue("keyId", c.keyId())
        .log("Ceremony aborted");
  }

  private Ceremony open(UUID id, String keyId, KeyPurpose purpose, Instant now) {
    List<CryptoKey> versions = keys.findVersions(keyId);
    if (!versions.isEmpty() && !versions.getLast().purpose().equals(purpose.name())) {
      throw CryptoErrors.keyNotUsable(keyId, purpose.name());
    }
    int version = versions.isEmpty() ? 1 : versions.getLast().version() + 1;
    try {
      ceremonies.insert(id, keyId, purpose.name(), version, now);
    } catch (DuplicateKeyException e) {
      throw CryptoErrors.keyChangeConflict("Another ceremony for " + keyId + " is open");
    }
    log.atInfo()
        .addKeyValue("event", "crypto.ceremony.opened")
        .addKeyValue("ceremonyId", id.toString())
        .addKeyValue("keyId", keyId)
        .addKeyValue("kid", CryptoKey.kid(keyId, version))
        .log("Ceremony opened");
    return ceremonies.lock(id).orElseThrow();
  }

  private CryptoProvider provider() {
    return providers.stream()
        .filter(p -> p.name().equals(defaultProvider) && p.capabilities().contains(Capability.CEREMONY))
        .findFirst()
        .orElseThrow(() -> new MwException(ErrorCodes.SYS_INTERNAL, "No provider for the key ceremony"));
  }

  private static CeremonyView view(Ceremony c) {
    return new CeremonyView(
        c.ceremonyId(), c.keyId(), c.purpose(), c.version(), CryptoKey.kid(c.keyId(), c.version()), c.state().name(),
        c.shares().stream().map(s -> new CeremonyView.Share(s.share(), s.custodian(), s.enteredAt())).toList(),
        c.checkValue());
  }

  private static MwException notFound(UUID id) {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No ceremony " + id).withStatus(HttpStatus.NOT_FOUND);
  }
}
