package mfs.mw.core.mock.security;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.util.Optional;
import mfs.mw.core.mock.client.AssertionJwksClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The public keys mw-auth signs transaction assertions with (ASSERTION_SIGN, EC P-256; ticket 71),
 * held in memory. {@link #refresh()} runs at start and on a timer ({@code
 * scheduler/AssertionKeyRefreshJob}); a failed fetch keeps the last set (WARN {@code
 * core.assertion.jwks_failed}). The command path only reads it ({@link #key}), so an mw-auth
 * outage never slows a command: an unknown kid is refused until the next refresh.
 */
@Component
public class AssertionKeys {

  private static final Logger log = LoggerFactory.getLogger(AssertionKeys.class);

  private final AssertionJwksClient client;
  private volatile JWKSet keys = new JWKSet();

  public AssertionKeys(AssertionJwksClient client) {
    this.client = client;
  }

  /** The EC public key of {@code kid}, if mw-auth's set has it. */
  public Optional<ECKey> key(String kid) {
    JWK k = kid == null ? null : keys.getKeyByKeyId(kid);
    return k instanceof ECKey ec && !ec.isPrivate() ? Optional.of(ec) : Optional.empty();
  }

  /** Fetches the set again; true when it was read. */
  public boolean refresh() {
    try {
      JWKSet fetched = JWKSet.parse(client.fetch()).toPublicJWKSet();
      keys = fetched;
      log.atDebug().addKeyValue("event", "core.assertion.jwks_refreshed").addKeyValue("keys", fetched.size())
          .log("assertion key set refreshed");
      return true;
    } catch (RuntimeException | java.text.ParseException e) {
      log.atWarn()
          .addKeyValue("event", "core.assertion.jwks_failed")
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("assertion key set not fetched; keeping the last one");
      return false;
    }
  }
}
