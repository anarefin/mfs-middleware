package mfs.mw.crypto.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.crypto.dto.KeyChangeRequest;
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

  @Transactional
  public KeyView apply(UUID changeRequestId, KeyChangeRequest r) {
    Optional<KeyChangeRepository.Applied> done = changes.find(changeRequestId);
    if (done.isPresent()) {
      KeyChangeRepository.Applied a = done.get();
      if (!a.action().equals(r.action().name()) || !a.keyId().equals(r.keyId())) {
        throw CryptoErrors.keyChangeConflict("This change request was applied with other values");
      }
      return keys.find(r.keyId());
    }
    if (r.makerId().equals(r.checkerId())) {
      throw CryptoErrors.keyChangeConflict("Maker and checker must differ");
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
    changes.insert(changeRequestId, r.action().name(), r.keyId(), r.version(), r.makerId(), r.checkerId(), clock.instant());
    log.atInfo()
        .addKeyValue("event", "crypto.key_change.applied")
        .addKeyValue("changeRequestId", changeRequestId.toString())
        .addKeyValue("action", r.action().name())
        .addKeyValue("keyId", r.keyId())
        .addKeyValue("makerId", r.makerId())
        .addKeyValue("checkerId", r.checkerId())
        .log("Approved key change applied");
    return view;
  }
}
