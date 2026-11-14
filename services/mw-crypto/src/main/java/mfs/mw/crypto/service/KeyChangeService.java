package mfs.mw.crypto.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.crypto.dto.KeyChangeRequest;
import mfs.mw.crypto.dto.KeyChangeState;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.repository.KeyChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Key changes approved under dual control in mw-admin-api (ticket 75, OWN-08): generate, rotate and
 * retire, applied once per change request id ({@code crypto.key_change}); a repeat with the same
 * action and key answers the key as it is now, so mw-admin-api may retry an approval safely. This is
 * the production route to a new key: unlike {@code POST /internal/v1/keys} it does not need {@code
 * mw.crypto.bootstrap-keys}, because a maker and another checker approved it.
 */
@Service
public class KeyChangeService {

  private static final Logger log = LoggerFactory.getLogger(KeyChangeService.class);

  private final KeyChangeRepository changes;
  private final KeyService keys;
  private final Clock clock;

  public KeyChangeService(KeyChangeRepository changes, KeyService keys, Clock clock) {
    this.changes = changes;
    this.keys = keys;
    this.clock = clock;
  }

  /** The state of approval attempt {@code changeId}: applied, cancelled, or neither yet. */
  @Transactional(readOnly = true)
  public KeyChangeState state(UUID changeId) {
    return changes.find(changeId).map(c -> view(c)).orElse(new KeyChangeState(changeId, false, false, null, null, null));
  }

  /**
   * Makes "not applied" binding (security review): a terminal CANCELLED tombstone for {@code
   * changeId} unless it was applied; atomic with {@link #apply} (both insert the id's row, the
   * second waits for the first). Idempotent; answers applied or cancelled.
   */
  @Transactional
  public KeyChangeState cancel(UUID changeId) {
    if (changes.insertCancelled(changeId, clock.instant())) {
      log.atInfo()
          .addKeyValue("event", "crypto.key_change.cancelled")
          .addKeyValue("changeId", changeId.toString())
          .log("Key change attempt cancelled");
    }
    return view(changes.find(changeId).orElseThrow());
  }

  /**
   * Applies attempt {@code changeId} once: the id's APPLIED row is inserted first (refused when the
   * id was cancelled), then the change runs in the same transaction. A repeat with the same action
   * and key answers the key as it is now.
   */
  @Transactional
  public KeyView apply(UUID changeId, KeyChangeRequest r) {
    if (r.makerId().equals(r.checkerId())) {
      throw CryptoErrors.keyChangeConflict("Maker and checker must differ");
    }
    if (!changes.insertApplied(changeId, r.action().name(), r.keyId(), r.version(), r.makerId(), r.checkerId(),
        clock.instant())) {
      KeyChangeRepository.Change c = changes.find(changeId).orElseThrow();
      if (!c.applied()) {
        throw CryptoErrors.keyChangeConflict("This key change was cancelled");
      }
      if (!c.action().equals(r.action().name()) || !c.keyId().equals(r.keyId())) {
        throw CryptoErrors.keyChangeConflict("This change request was applied with other values");
      }
      return keys.find(r.keyId());
    }
    KeyView view =
        switch (r.action()) {
          case GENERATE -> {
            if (r.purpose() == null) {
              throw CryptoErrors.invalidField("purpose", "required for GENERATE");
            }
            yield keys.generate(r.purpose(), r.keyId());
          }
          case ROTATE -> keys.rotate(r.keyId());
          case RETIRE -> {
            if (r.version() == null) {
              throw CryptoErrors.invalidField("version", "required for RETIRE");
            }
            yield keys.retire(r.keyId(), r.version());
          }
        };
    log.atInfo()
        .addKeyValue("event", "crypto.key_change.applied")
        .addKeyValue("changeId", changeId.toString())
        .addKeyValue("action", r.action().name())
        .addKeyValue("keyId", r.keyId())
        .addKeyValue("makerId", r.makerId())
        .addKeyValue("checkerId", r.checkerId())
        .log("Approved key change applied");
    return view;
  }

  private KeyChangeState view(KeyChangeRepository.Change c) {
    if (!c.applied()) {
      return new KeyChangeState(c.changeId(), false, true, null, null, null);
    }
    KeyView k = keys.find(c.keyId());
    String kid = k.versions().stream().filter(v -> "ACTIVE".equals(v.state())).map(KeyView.Version::kid)
        .findFirst().orElse(k.versions().getLast().kid());
    return new KeyChangeState(c.changeId(), true, false, c.action(), c.keyId(), kid);
  }
}
