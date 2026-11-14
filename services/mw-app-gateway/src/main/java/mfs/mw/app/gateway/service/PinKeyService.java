package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AuthClientProperties;
import mfs.mw.app.gateway.dto.PinKey;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.stereotype.Service;

/**
 * The PIN transport key for the apps ({@code GET /app/v1/pin-key}, ticket 72): mw-auth's {@code GET
 * /auth/v1/pin-key} (itself the newest mw-crypto {@code PIN_TRANSPORT} key), reused for {@code
 * mw.app-gateway.auth.pin-key-ttl}. When mw-auth cannot be asked the last key is served while one is
 * held (a rotated key stays usable for its overlap at mw-crypto); with none yet, 503 MW-SYS-012.
 */
@Service
public class PinKeyService {

  private record Cached(PinKey key, Instant until) {}

  private final AuthClient auth;
  private final AuthClientProperties props;
  private final Clock clock;
  private final AtomicReference<Cached> cached = new AtomicReference<>();

  public PinKeyService(AuthClient auth, AuthClientProperties props, Clock clock) {
    this.auth = auth;
    this.props = props;
    this.clock = clock;
  }

  public PinKey current() {
    Instant now = clock.instant();
    Cached c = cached.get();
    if (c != null && now.isBefore(c.until())) {
      return c.key();
    }
    try {
      PinKey fresh = auth.pinKey();
      cached.set(new Cached(fresh, now.plus(props.pinKeyTtl())));
      return fresh;
    } catch (MwException e) {
      if (c != null) {
        return c.key();
      }
      throw e;
    }
  }
}
