package mfs.mw.crypto.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.config.PayShieldProperties;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dev bootstrap (ticket 62): makes sure every purpose of {@code mw.crypto.bootstrap-purposes} has a
 * key, generating the missing ones with their default keyId, so the dev stack comes up usable.
 * Idempotent: a purpose with any key is skipped, and a key a concurrent run created first counts as
 * done. An HSM that is not up yet stops the run; the next run picks up where it stopped. Production
 * keys come only from the key ceremony (ticket 75), so {@code scheduler/KeyBootstrap} runs only with
 * {@code mw.crypto.bootstrap-keys=true}. While the payShield provider is on, its {@code
 * mw.crypto.payshield.bootstrap-purposes} come after the others.
 */
@Service
public class KeyBootstrapService {

  private static final Logger log = LoggerFactory.getLogger(KeyBootstrapService.class);

  private final KeyService keys;
  private final List<String> purposes;

  public KeyBootstrapService(KeyService keys, CryptoProperties props, PayShieldProperties payshield) {
    this.keys = keys;
    List<String> all = new ArrayList<>(props.bootstrapPurposes());
    // last, so a payShield HSM that is not up yet defers only its own keys (ticket 64)
    if (payshield.enabled()) {
      payshield.bootstrapPurposes().stream().filter(p -> !all.contains(p)).forEach(all::add);
    }
    this.purposes = List.copyOf(all);
  }

  /** Generates the missing keys; returns the purposes it generated a key for. */
  public List<String> ensureKeys() {
    Set<String> present = new HashSet<>(keys.purposesWithKeys());
    List<String> created = new ArrayList<>();
    for (String purpose : purposes) {
      if (present.contains(purpose)) {
        continue;
      }
      try {
        keys.generate(purpose, null);
        created.add(purpose);
      } catch (MwException e) {
        if (ErrorCodes.VAL_KEY_EXISTS.equals(e.code())) {
          continue;
        }
        if (CryptoErrors.isHsmFailure(e)) {
          log.atWarn()
              .addKeyValue("event", "crypto.key.bootstrap_deferred")
              .addKeyValue("purpose", purpose)
              .log("HSM not available: key bootstrap retries on its next run");
          break;
        }
        throw e;
      }
    }
    if (!created.isEmpty()) {
      log.atInfo()
          .addKeyValue("event", "crypto.key.bootstrapped")
          .addKeyValue("purposes", String.join(",", created))
          .log("Dev key bootstrap generated missing keys");
    }
    return created;
  }
}
