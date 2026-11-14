package mfs.mw.ussd.service;

import java.time.Clock;
import java.time.Instant;
import mfs.mw.ussd.client.AuthClient;
import mfs.mw.ussd.config.AuthClientProperties;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.PinKey;
import mfs.mw.ussd.util.PinCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The PIN transport key (ticket 72, spec 18.7): the mw-crypto {@code PIN_TRANSPORT} public key from
 * mw-auth {@code GET /auth/v1/pin-key}, fetched once the service is up (a failure there is only
 * logged: mw-auth may start later) and again when it is older than {@code mw.ussd.auth.pin-key-ttl}
 * or {@link #refresh()} was called (mw-auth refused a PIN block: the key was rotated). The PIN is
 * encrypted on receipt with it; when the key cannot be had, {@link #encrypt} throws and the step
 * ends with "unavailable".
 */
@Service
public class PinTransport {

  private static final Logger log = LoggerFactory.getLogger(PinTransport.class);

  private final AuthClient auth;
  private final AuthClientProperties props;
  private final Clock clock;

  private PinCipher cipher;
  private Instant fetchedAt = Instant.MIN;

  public PinTransport(AuthClient auth, AuthClientProperties props, Clock clock) {
    this.auth = auth;
    this.props = props;
    this.clock = clock;
  }

  /** Tries the first fetch at start; never fails the start. */
  @EventListener(ApplicationReadyEvent.class)
  public void warmUp() {
    try {
      current();
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "ussd.pin_key.unavailable")
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("PIN transport key not fetched at start; asked again on the first PIN");
    }
  }

  /** The PIN under the current transport key. */
  public EncryptedPin encrypt(String pin) {
    return current().encrypt(pin);
  }

  /** Forgets the key: the next PIN fetches it again. */
  public synchronized void refresh() {
    cipher = null;
    fetchedAt = Instant.MIN;
  }

  private synchronized PinCipher current() {
    Instant now = clock.instant();
    if (cipher == null || !now.isBefore(fetchedAt.plus(props.pinKeyTtl()))) {
      PinKey key = auth.pinKey();
      PinCipher next = new PinCipher(key.keyId(), key.publicKeyPem());
      if (cipher == null || !cipher.keyId().equals(next.keyId())) {
        log.atInfo().addKeyValue("event", "ussd.pin_key.loaded").addKeyValue("kid", next.keyId())
            .log("PIN transport key loaded");
      }
      cipher = next;
      fetchedAt = now;
    }
    return cipher;
  }
}
