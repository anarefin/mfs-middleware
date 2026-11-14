package mfs.mw.crypto.security;

import java.util.Set;
import mfs.mw.crypto.config.OperationAuthzProperties;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Per-operation authorisation of the internal API (ticket 63, security review), the one place it is
 * decided: the caller is the service JWT's {@code azp} (ticket 66 ties it to the client certificate's
 * CN), the rules are {@code mw.crypto.authz} ({@link OperationAuthzProperties}). Deny by default; a
 * refusal is 403 {@code MW-AUTH-002} with the WARN event {@code security.internal_call_denied}
 * (reason {@code OPERATION_NOT_ALLOWED}). The operation services call it before any HSM work: PIN
 * operations with {@link #requirePin}, everything with a key purpose with {@link #requirePurpose}.
 * Key management (generate, rotate) is ticket 66's key-admin rule; public keys and JWKS are open to
 * every allowed caller.
 */
@Component
public class CallerPolicy {

  private static final Logger log = LoggerFactory.getLogger(CallerPolicy.class);

  private final OperationAuthzProperties props;

  public CallerPolicy(OperationAuthzProperties props) {
    this.props = props;
  }

  /** {@code /internal/v1/pin/**}: only the PIN clients ({@code mw-auth}). */
  public void requirePin(String operation) {
    require(props.pinClients(), operation, "PIN");
  }

  /** An operation with a key of {@code purpose}: only the clients bound to that purpose. */
  public void requirePurpose(String purpose, String operation) {
    require(props.purposes().getOrDefault(purpose, Set.of()), operation, purpose);
  }

  private static void require(Set<String> allowed, String operation, String purpose) {
    String client = caller();
    if (client == null || !allowed.contains(client)) {
      log.atWarn()
          .addKeyValue("event", "security.internal_call_denied")
          .addKeyValue("clientId", client == null ? "none" : client)
          .addKeyValue("reason", "OPERATION_NOT_ALLOWED")
          .addKeyValue("op", operation)
          .addKeyValue("purpose", purpose)
          .log("Internal operation refused for this caller");
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN);
    }
  }

  /** The {@code azp} of the request's service JWT, null without one. */
  static String caller() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("azp") : null;
  }
}
